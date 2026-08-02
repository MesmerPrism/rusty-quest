package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.content.pm.PackageManager
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

/** One scalar JNI entry point per closed Manifold local-control operation. */
internal object NativeManifoldBridge {
  init {
    System.loadLibrary("rusty_quest_spatial_video_local_control")
  }

  external fun nativeInitialize(
      platformSubject: String,
      signingFingerprint: String,
      allowDebugShellOperator: Boolean,
  ): String

  external fun nativeOpenPairingWindow(
      requestId: String,
      requestedWindowMs: Long,
      accessMode: String,
      enableActor: String,
  ): String

  external fun nativeAdmitController(requestId: String): String

  external fun nativeAcceptCommand(
      requestId: String,
      command: String,
      videoId: String,
      expectedLocalRevision: Long,
  ): String

  external fun nativeDisable(requestId: String, cause: String): String

  external fun nativeEnforceExpiry(requestId: String): String

  external fun nativeSafeStatus(): String
}

/**
 * Transport projection around the process-local Rust Manifold authority.
 *
 * Pairing code, cookie, and remote address remain local transport facts. Every
 * admission, command, expiry, and disable decision crosses a typed JNI method
 * into the exact pinned Manifold composite.
 */
internal class NativeManifoldAuthorityPort private constructor() : ManifoldAuthorityPort {
  private val lock = Any()
  private val random = SecureRandom()
  private val pairAttempts = ArrayDeque<Instant>()
  private var displayedAddress: String? = null
  private var pairingCodeDigest: ByteArray? = null
  private var pairingCodeUsed = false
  private var sessionCookieDigest: ByteArray? = null
  private var controllerRemoteAddress: String? = null
  private var controllerLeaseId: String? = null
  private var activeAccessMode: ManifoldAuthorityPort.AccessMode? = null

  override fun beginWearerEnable(
      request: ManifoldAuthorityPort.EnableRequest
  ): ManifoldAuthorityPort.PairingOffer = synchronized(lock) {
    if (!request.foregroundOperatorAction()) {
      return@synchronized rejectedOffer(request.displayedAddress(), "foreground_operator_action_required")
    }
    if (request.requestedWindow().isZero ||
        request.requestedWindow().isNegative ||
        request.requestedWindow() > TrustedLocalControlPolicy.MAX_ENABLE_WINDOW) {
      return@synchronized rejectedOffer(request.displayedAddress(), "enable_window_out_of_bounds")
    }
    if (!isTrustedPrivateIpv4(request.displayedAddress())) {
      return@synchronized rejectedOffer(request.displayedAddress(), "private_bind_address_required")
    }
    val externalRequestId = randomRequestId("window-open")
    val root =
        strictJson(
            NativeManifoldBridge.nativeOpenPairingWindow(
                externalRequestId,
                request.requestedWindow().toMillis(),
                request.accessMode().protocolName(),
                request.enableActor().protocolName(),
            )
        )
    val receipt = root.getJSONObject("window_receipt")
    val status = receipt.getJSONObject("status")
    val revisions = receipt.getJSONObject("resulting_revisions").toRevisions()
    if (!receipt.getBoolean("opened")) {
      return@synchronized ManifoldAuthorityPort.PairingOffer(
          false,
          request.displayedAddress(),
          "",
          status.optionalInstant("window_expires_at_ms") ?: Instant.EPOCH,
          receipt.getString("window_id"),
          receipt.getString("request_id"),
          root.getString("wearer_evidence_id"),
          revisions,
          request.accessMode(),
          receipt.optionalReason(),
      )
    }
    val code =
        if (request.accessMode() == ManifoldAuthorityPort.AccessMode.PAIRED) {
          String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))
        } else {
          ""
        }
    pairingCodeDigest = code.takeIf(String::isNotEmpty)?.let(::digest)
    pairingCodeUsed = false
    displayedAddress = request.displayedAddress()
    activeAccessMode = request.accessMode()
    clearControllerTransport()
    ManifoldAuthorityPort.PairingOffer(
        true,
        request.displayedAddress(),
        code,
        requireNotNull(status.optionalInstant("window_expires_at_ms")),
        receipt.getString("window_id"),
        receipt.getString("request_id"),
        root.getString("wearer_evidence_id"),
        revisions,
        request.accessMode(),
        "wearer_enabled",
    )
  }

  override fun pair(
      attempt: ManifoldAuthorityPort.PairAttempt
  ): ManifoldAuthorityPort.PairDecision = synchronized(lock) {
    trimPairAttempts(attempt.now())
    if (pairAttempts.size >= TrustedLocalControlPolicy.MAX_PAIR_ATTEMPTS_PER_MINUTE) {
      return@synchronized pairRejected("pair_rate_limited")
    }
    pairAttempts.addLast(attempt.now())
    val expectedDigest = pairingCodeDigest
    val presentedDigest = digest(attempt.request().pairingCode())
    val codeMatched =
        !pairingCodeUsed &&
            expectedDigest != null &&
            MessageDigest.isEqual(expectedDigest, presentedDigest)
    presentedDigest.fill(0)
    if (!codeMatched) {
      return@synchronized pairRejected("pairing_code_rejected")
    }
    admitAndCreateSession(attempt.request().requestId(), attempt.remoteAddress(), paired = true)
  }

  override fun admitOpenLan(
      attempt: ManifoldAuthorityPort.OpenLanAttempt
  ): ManifoldAuthorityPort.PairDecision = synchronized(lock) {
    val authority = safeStatus()
    if (activeAccessMode != ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE ||
        authority.accessMode() != ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE) {
      return@synchronized pairRejected("open_lan_not_enabled", authority.revisions())
    }
    admitAndCreateSession(attempt.requestId(), attempt.remoteAddress(), paired = false)
  }

  private fun admitAndCreateSession(
      requestId: String,
      remoteAddress: String,
      paired: Boolean,
  ): ManifoldAuthorityPort.PairDecision {
    val receipt = strictJson(NativeManifoldBridge.nativeAdmitController(requestId))
    val revisions = receipt.getJSONObject("resulting_revisions").toRevisions()
    if (!receipt.getBoolean("admitted")) {
      return pairRejected(receipt.optionalReason(), revisions)
    }
    val authority = safeStatus()
    val sessionExpiresAt =
        authority.sessionExpiresAt()
            ?: return pairRejected("missing_session_expiry", revisions)
    val leaseId = admissionLeaseId(receipt) ?: return pairRejected("missing_controller_lease", revisions)
    val cookieBytes = ByteArray(32).also(random::nextBytes)
    val cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(cookieBytes)
    cookieBytes.fill(0)
    pairingCodeUsed = paired
    pairingCodeDigest?.fill(0)
    pairingCodeDigest = null
    sessionCookieDigest = digest(cookie)
    controllerRemoteAddress = remoteAddress
    controllerLeaseId = leaseId
    return ManifoldAuthorityPort.PairDecision(
        true,
        cookie,
        authority.controllerId(),
        sessionExpiresAt,
        receipt.getString("receipt_id"),
        leaseId,
        revisions,
        if (paired) "paired" else "open_lan_admitted",
    )
  }

  override fun inspectSession(
      sessionCookie: String,
      remoteAddress: String,
      now: Instant,
  ): ManifoldAuthorityPort.SessionDecision = synchronized(lock) {
    val authority = safeStatus()
    val presentedCookieDigest = digest(sessionCookie)
    val valid =
        authority.controllerConnected() &&
            controllerRemoteAddress == remoteAddress &&
            sessionCookieDigest?.let { MessageDigest.isEqual(it, presentedCookieDigest) } == true
    presentedCookieDigest.fill(0)
    ManifoldAuthorityPort.SessionDecision(
        valid,
        authority,
        if (valid) "active" else "session_rejected",
    )
  }

  override fun enforceExpiry(
      request: ManifoldAuthorityPort.ExpiryRequest
  ): ManifoldAuthorityPort.ExpiryDecision = synchronized(lock) {
    val root = strictJson(NativeManifoldBridge.nativeEnforceExpiry(request.requestId()))
    val authority = root.getJSONObject("status").toAuthoritySnapshot()
    val enforced = root.getBoolean("enforced")
    if (enforced) {
      clearPairingTransport()
      clearControllerTransport()
    }
    val expiryReceipt =
        root.optionalObject("expiry_receipt") ?: root.optionalObject("disable_receipt")
    ManifoldAuthorityPort.ExpiryDecision(
        root.getBoolean("due"),
        enforced,
        root.getBoolean("expired"),
        expiryReceipt?.optString("receipt_id")?.takeIf(String::isNotEmpty),
        root.optionalString("cause"),
        authority,
        root.optString("reason").ifEmpty { if (enforced) "expired" else "not_due" },
    )
  }

  override fun review(
      attempt: ManifoldAuthorityPort.CommandAttempt
  ): ManifoldAuthorityPort.CommandDecision = synchronized(lock) {
    val session = inspectSession(attempt.sessionCookie(), attempt.remoteAddress(), Instant.EPOCH)
    val envelope = attempt.envelope()
    if (!session.active()) {
      return@synchronized rejectedCommand(envelope, session.authority().revisions(), session.reason())
    }
    val receipt =
        strictJson(
            NativeManifoldBridge.nativeAcceptCommand(
                envelope.requestId(),
                envelope.command(),
                envelope.videoId() ?: "",
                envelope.expectedAuthorityRevision(),
            )
        )
    val revisions = receipt.getJSONObject("resulting_revisions").toRevisions()
    if (!receipt.getBoolean("command_accepted")) {
      return@synchronized rejectedCommand(envelope, revisions, receipt.optionalReason())
    }
    val leaseId =
        receipt.optionalString("controller_lease_id")
            ?: controllerLeaseId
            ?: return@synchronized rejectedCommand(
                envelope,
                revisions,
                "missing_controller_lease",
            )
    ManifoldAuthorityPort.CommandDecision(
        true,
        envelope.requestId(),
        envelope.command(),
        revisions,
        leaseId,
        receipt.getString("receipt_id"),
        "accepted",
    )
  }

  override fun snapshot(): ManifoldAuthorityPort.AuthoritySnapshot = synchronized(lock) {
    safeStatus()
  }

  override fun revokeByWearer(
      request: ManifoldAuthorityPort.RevokeRequest
  ): ManifoldAuthorityPort.RevokeDecision = synchronized(lock) {
    val receipt =
        strictJson(NativeManifoldBridge.nativeDisable(request.requestId(), request.cause()))
    val revisions = receipt.getJSONObject("resulting_revisions").toRevisions()
    val disabled = receipt.getBoolean("disabled")
    if (disabled) {
      clearPairingTransport()
      clearControllerTransport()
    }
    ManifoldAuthorityPort.RevokeDecision(
        disabled,
        revisions,
        receipt.getString("receipt_id"),
        request.cause(),
        if (disabled) "disabled" else receipt.optionalReason(),
    )
  }

  private fun rejectedOffer(
      address: String,
      reason: String,
  ): ManifoldAuthorityPort.PairingOffer {
    val authority = safeStatus()
    return ManifoldAuthorityPort.PairingOffer(
        false,
        address,
        "",
        authority.windowExpiresAt() ?: Instant.EPOCH,
        authority.windowId(),
        null,
        null,
        authority.revisions(),
        authority.accessMode() ?: ManifoldAuthorityPort.AccessMode.PAIRED,
        reason,
    )
  }

  private fun pairRejected(
      reason: String,
      revisions: ManifoldAuthorityPort.AuthorityRevisions = safeStatus().revisions(),
  ): ManifoldAuthorityPort.PairDecision =
      ManifoldAuthorityPort.PairDecision(
          false,
          null,
          null,
          Instant.EPOCH,
          null,
          null,
          revisions,
          reason,
      )

  private fun rejectedCommand(
      envelope: CommandEnvelope,
      revisions: ManifoldAuthorityPort.AuthorityRevisions,
      reason: String,
  ): ManifoldAuthorityPort.CommandDecision =
      ManifoldAuthorityPort.CommandDecision(
          false,
          envelope.requestId(),
          envelope.command(),
          revisions,
          null,
          null,
          reason,
      )

  private fun safeStatus(): ManifoldAuthorityPort.AuthoritySnapshot =
      strictJson(NativeManifoldBridge.nativeSafeStatus()).toAuthoritySnapshot()

  private fun clearPairingTransport() {
    pairingCodeDigest?.fill(0)
    pairingCodeDigest = null
    pairingCodeUsed = false
    displayedAddress = null
    activeAccessMode = null
  }

  private fun clearControllerTransport() {
    sessionCookieDigest?.fill(0)
    sessionCookieDigest = null
    controllerRemoteAddress = null
    controllerLeaseId = null
  }

  private fun trimPairAttempts(now: Instant) {
    val threshold = now.minusSeconds(60)
    while (pairAttempts.isNotEmpty() && pairAttempts.first.isBefore(threshold)) {
      pairAttempts.removeFirst()
    }
  }

  private fun digest(value: String): ByteArray =
      MessageDigest.getInstance("SHA-256")
          .digest(value.toByteArray(StandardCharsets.UTF_8))

  private fun randomRequestId(prefix: String): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    val suffix = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    bytes.fill(0)
    return "$prefix-$suffix"
  }

  internal companion object {
    fun createOrNull(context: Context): NativeManifoldAuthorityPort? =
        runCatching {
              val fingerprint = signingFingerprint(context)
              strictJson(
                  NativeManifoldBridge.nativeInitialize(
                      context.packageName,
                      fingerprint,
                      BuildConfig.DEBUG,
                  )
              ).toAuthoritySnapshot()
              NativeManifoldAuthorityPort()
            }
            .getOrNull()

    private fun signingFingerprint(context: Context): String {
      val packageInfo =
          context.packageManager.getPackageInfo(
              context.packageName,
              PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
          )
      val signers =
          requireNotNull(packageInfo.signingInfo).apkContentsSigners
      require(signers.size == 1) { "exactly one current APK signer is required" }
      val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
      return "sha256:" +
          digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private fun isTrustedPrivateIpv4(value: String): Boolean {
      if (!value.matches(Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$"))) {
        return false
      }
      val address = runCatching { InetAddress.getByName(value) }.getOrNull()
      if (address !is Inet4Address || address.isAnyLocalAddress || address.isLoopbackAddress) {
        return false
      }
      val bytes = address.address.map(Byte::toInt).map { it and 0xff }
      return bytes[0] == 10 ||
          (bytes[0] == 172 && bytes[1] in 16..31) ||
          (bytes[0] == 192 && bytes[1] == 168) ||
          (bytes[0] == 169 && bytes[1] == 254)
    }

    private fun strictJson(raw: String): JSONObject {
      val value = JSONObject(raw)
      val error = value.optString("bridge_error").takeIf(String::isNotEmpty)
      require(error == null) { error ?: "native bridge rejected" }
      return value
    }

    private fun JSONObject.toRevisions(): ManifoldAuthorityPort.AuthorityRevisions =
        ManifoldAuthorityPort.AuthorityRevisions(
            getLong("local_revision"),
            getLong("admission_revision"),
            getLong("lease_authority_revision"),
            getLong("host_revision"),
        )

    private fun JSONObject.toAuthoritySnapshot(): ManifoldAuthorityPort.AuthoritySnapshot =
        ManifoldAuthorityPort.AuthoritySnapshot(
            getString("state"),
            optionalString("access_mode")?.let(::accessMode),
            ManifoldAuthorityPort.AuthorityRevisions(
                getLong("local_revision"),
                getLong("admission_revision"),
                getLong("lease_authority_revision"),
                getLong("host_revision"),
            ),
            optionalString("window_id"),
            optionalInstant("window_expires_at_ms"),
            optionalString("controller_id"),
            optionalInstant("session_expires_at_ms"),
            optionalInstant("idle_expires_at_ms"),
            optionalString("last_accepted_command_receipt_id"),
        )

    private fun accessMode(value: String): ManifoldAuthorityPort.AccessMode =
        when (value) {
          "paired" -> ManifoldAuthorityPort.AccessMode.PAIRED
          "open_lan_insecure" -> ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE
          else -> error("unknown access mode")
        }

    private fun JSONObject.optionalObject(name: String): JSONObject? =
        if (has(name) && !isNull(name)) getJSONObject(name) else null

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optionalInstant(name: String): Instant? =
        if (has(name) && !isNull(name)) Instant.ofEpochMilli(getLong(name)) else null

    private fun JSONObject.optionalReason(): String =
        optionalString("rejection_reason") ?: "authority_rejected"

    private fun admissionLeaseId(receipt: JSONObject): String? {
      val application = receipt.optionalObject("lease_application") ?: return null
      val snapshot = application.optionalObject("applied_snapshot") ?: return null
      val leases = snapshot.optJSONArray("active_leases") ?: return null
      return if (leases.length() == 1) {
        leases.getJSONObject(0).getString("lease_id")
      } else {
        null
      }
    }
  }
}
