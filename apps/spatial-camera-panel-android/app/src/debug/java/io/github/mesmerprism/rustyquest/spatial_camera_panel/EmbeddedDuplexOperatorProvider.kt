package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentReview
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentService
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeService
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeStatus
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPairStatus
import java.util.concurrent.TimeUnit
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPeerAction

/** Explicit shell operator calls share the panel's app-owned status/review/confirm service. */
class EmbeddedDuplexOperatorProvider : ContentProvider() {
  private val gate = Any()
  private val pending = EmbeddedDuplexOperatorReviewSlot<EmbeddedDuplexEnrollmentReview>()

  override fun onCreate(): Boolean = context != null

  override fun call(method: String, argument: String?, extras: Bundle?): Bundle {
    if (!BuildConfig.DEBUG || !EmbeddedDuplexOperatorContract.callerIsShell(Binder.getCallingUid())) {
      closed()
    }
    val request = try {
      EmbeddedDuplexOperatorContract.parseCall(method, argument, extras)
    } catch (_: Exception) { closed() }
    val app = requireNotNull(context).applicationContext
    return try {
      when (request.route) {
        EmbeddedDuplexOperatorContract.Route.START, EmbeddedDuplexOperatorContract.Route.RENEW_AUTHORITY,
        EmbeddedDuplexOperatorContract.Route.PEER_STOP, EmbeddedDuplexOperatorContract.Route.PEER_REVOKE,
        EmbeddedDuplexOperatorContract.Route.PEER_STATUS, EmbeddedDuplexOperatorContract.Route.WHOLE_APP_CLOSE -> {
          val action = when (request.route) {
            EmbeddedDuplexOperatorContract.Route.START -> EmbeddedDuplexPeerAction.START
            EmbeddedDuplexOperatorContract.Route.RENEW_AUTHORITY -> EmbeddedDuplexPeerAction.RENEW_AUTHORITY
            EmbeddedDuplexOperatorContract.Route.PEER_STOP -> EmbeddedDuplexPeerAction.STOP
            EmbeddedDuplexOperatorContract.Route.PEER_REVOKE -> EmbeddedDuplexPeerAction.REVOKE
            EmbeddedDuplexOperatorContract.Route.PEER_STATUS -> EmbeddedDuplexPeerAction.STATUS
            else -> EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
          }
          val receipt = EmbeddedDuplexRuntimeService.peerLifecycle(app, action, request.challenge).get(120, TimeUnit.SECONDS)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.POLICY_READ, EmbeddedDuplexOperatorContract.Route.POLICY_UPDATE -> {
          val receipt = EmbeddedDuplexRuntimeService.concurrentPolicy(app, request.challenge, request.policy).get(30, TimeUnit.SECONDS)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.CONCURRENT_ARM, EmbeddedDuplexOperatorContract.Route.CONCURRENT_STATUS -> {
          val receipt = if (request.route == EmbeddedDuplexOperatorContract.Route.CONCURRENT_ARM)
            EmbeddedDuplexRuntimeService.armConcurrentQualification(app, request.challenge).get(30, TimeUnit.SECONDS)
          else EmbeddedDuplexRuntimeService.concurrentQualificationStatus(app, request.challenge).get(30, TimeUnit.SECONDS)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.STATUS -> {
          val status = EmbeddedDuplexEnrollmentService.status(app, requireNotNull(request.roleId)).get()
          Bundle().apply {
            header("verified")
            putString("challenge", request.challenge)
            putString("role_id", status.installed.roleId)
            putString("package_id", status.installed.packageId)
            putString("signer_sha256", status.installed.signerSha256)
            putString("manifest_sha256", status.installed.manifestSha256)
            putString("route_sha256", status.installed.routeSha256)
            putString("local_key_id", status.installed.localKeyId)
            putString("local_public_key_hex", status.installed.localPublicKeyHex)
            putString("local_device_id", status.installed.localDeviceId)
            putString("local_peer_id", status.installed.localPeerId)
            putString("remote_device_id", status.installed.remoteDeviceId)
            putString("remote_peer_id", status.installed.remotePeerId)
            putString("record_state", status.state)
            putLong("revision", status.revision)
            status.recordSha256?.let { putString("record_sha256", it) }
          }
        }
        EmbeddedDuplexOperatorContract.Route.REVIEW -> synchronized(gate) {
          pending.clear()
          val review = EmbeddedDuplexEnrollmentService.review(app, requireNotNull(request.draft)).get()
          pending.install(request.challenge, review.reviewSha256, review)
          Bundle().apply {
            header("reviewed")
            putString("challenge", request.challenge)
            putString("review_sha256", review.reviewSha256)
            putString("remote_key_id", review.remoteKeyId)
            putString("review_details", review.details)
          }
        }
        EmbeddedDuplexOperatorContract.Route.CONFIRM -> {
          val reviewed = synchronized(gate) {
            pending.consume(request.challenge, requireNotNull(request.reviewSha256))
          }
          val saved = EmbeddedDuplexEnrollmentService.confirm(app, reviewed).get()
          Bundle().apply {
            header("committed")
            putString("challenge", request.challenge)
            putString("review_sha256", reviewed.reviewSha256)
            putString("role_id", saved.installed.roleId)
            putLong("revision", saved.revision)
            putString("record_sha256", saved.recordSha256)
            putBoolean("local_fixture", saved.localFixture)
          }
        }
        EmbeddedDuplexOperatorContract.Route.RUNTIME_STATUS ->
          runtimeBundle(request.challenge, EmbeddedDuplexRuntimeService.status(app).get(10, TimeUnit.SECONDS))
        EmbeddedDuplexOperatorContract.Route.BOOTSTRAP_REAL_PEER ->
          runtimeBundle(request.challenge,
              EmbeddedDuplexDiagnosticActivityGate.requestRealPeerBootstrap().get(120, TimeUnit.SECONDS))
        EmbeddedDuplexOperatorContract.Route.CLOSE_NO_MEDIA -> {
          val closed = EmbeddedDuplexDiagnosticActivityGate.requestNoMediaClose().get(120, TimeUnit.SECONDS)
          require(closed == "no-media-closed" || closed == "uninitialized-display-detached" ||
              closed == "peer-no-media-closed-own-capture-scope")
          runtimeBundle(request.challenge,
              EmbeddedDuplexRuntimeService.status(app).get(10, TimeUnit.SECONDS)).apply {
            putString("close_disposition", closed)
          }
        }
        EmbeddedDuplexOperatorContract.Route.PAIR_STATUS ->
          pairBundle(request.challenge, EmbeddedDuplexRuntimeService.pairStatus(app).get(10, TimeUnit.SECONDS))
        EmbeddedDuplexOperatorContract.Route.PAIR_SESSION ->
          pairBundle(request.challenge,
              EmbeddedDuplexDiagnosticActivityGate.requestPairSession().get(120, TimeUnit.SECONDS))
        EmbeddedDuplexOperatorContract.Route.START_PREFLIGHT -> {
          val decision = EmbeddedDuplexDiagnosticActivityGate.requestStartPreflight()
              .get(10, TimeUnit.SECONDS)
          Bundle().apply {
            header("observed")
            putString("challenge", request.challenge)
            putString("preflight_state", decision.state)
            putString("session_id", decision.sessionId)
            putLong("session_expires_at_ms", decision.sessionExpiresAtMs)
            putString("runtime_config_sha256", decision.runtimeConfigSha256)
            putString("enrollment_record_sha256", decision.enrollmentRecordSha256)
            putLong("display_generation", decision.displayGeneration)
            putBoolean("peer_route_proven", decision.peerRouteProven)
            putBoolean("media_effect_proven", decision.mediaEffectProven)
          }
        }
      }
    } catch (_: Exception) { closed() }
  }

  private fun Bundle.header(status: String) {
    putString("schema", EmbeddedDuplexOperatorContract.SCHEMA)
    putString("status", status)
  }

  private fun runtimeBundle(challenge: String, state: EmbeddedDuplexRuntimeStatus): Bundle =
      Bundle().apply {
        header("observed")
        putString("challenge", challenge)
        putString("runtime_state", state.state)
        putBoolean("display_attached", state.displayAttached)
        if (state.cleanupScope == "peer_subscription_only") {
          putString("cleanup_scope", state.cleanupScope)
          putString("own_app_capture_state", state.ownAppCaptureState)
        }
        state.runtimeConfigSha256?.let { putString("runtime_config_sha256", it) }
        state.enrollmentRecordSha256?.let { putString("enrollment_record_sha256", it) }
        putBoolean("peer_route_proven", false)
        putBoolean("media_effect_proven", false)
      }

  private fun pairBundle(challenge: String, state: EmbeddedDuplexPairStatus): Bundle =
      Bundle().apply {
        header("observed")
        putString("challenge", challenge)
        putString("pair_state", state.state)
        state.sessionId?.let { putString("session_id", it) }
        putBoolean("local_session_current", state.localSessionCurrent)
        putBoolean("remote_session_current", state.remoteSessionCurrent)
        putLong("local_session_expires_at_ms", state.localSessionExpiresAtMs)
        state.lastStep?.let { putString("last_step", it) }
        state.lastFailureCode?.let { putString("last_failure_code", it) }
        putBoolean("peer_route_proven", state.routeCurrent)
        putBoolean("media_effect_proven", state.mediaEffectProven)
      }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
      selectionArgs: Array<out String>?, sortOrder: String?): Cursor = closed()
  override fun getType(uri: Uri): String = closed()
  override fun insert(uri: Uri, values: ContentValues?): Uri? = closed()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = closed()
  override fun update(uri: Uri, values: ContentValues?, selection: String?,
      selectionArgs: Array<out String>?): Int = closed()
  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = closed()
  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor = closed()
  override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String,
      opts: Bundle?): AssetFileDescriptor = closed()

  private fun closed(): Nothing = throw SecurityException("embedded_duplex_operator_request_rejected")
}
