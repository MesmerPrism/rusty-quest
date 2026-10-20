package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import java.util.concurrent.CompletableFuture
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOriginals
import android.os.ParcelFileDescriptor
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentReview
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentService
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeService
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexRuntimeStatus
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexPairStatus
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
    val app = requireNotNull(context).applicationContext
    val snapshot = extras?.let { Bundle(it) } ?: Bundle()
    val challenge = argument ?: closed()
    if (!challenge.matches(Regex("[0-9a-f]{32}"))) closed()
    val originals = EmbeddedDuplexOriginals.forApplication(app)
    if(method == "operation_continuation_handoff"){
      if(snapshot.keySet()!=setOf("original_id") || snapshot.get("original_id") !is String)closed()
      return originals.queryContinuationHandoff(requireNotNull(snapshot.getString("original_id")),challenge)
    }
    if(method == "operation_continuation_status"){
      if(snapshot.keySet()!=setOf("original_id") || snapshot.get("original_id") !is String)closed()
      return originals.queryContinuationJoin(requireNotNull(snapshot.getString("original_id")),challenge)
    }
    if(method == "operation_join_status"){
      if(snapshot.keySet()!=setOf("original_id") || snapshot.get("original_id") !is String)closed()
      return originals.queryJoin(requireNotNull(snapshot.getString("original_id")),challenge)
    }
    if (method == "operation_stop") {
      val expected=setOf("original_id","request_sha256","stop_intent_sha256")
      if(snapshot.keySet()!=expected || expected.any { snapshot.get(it) !is String }) closed()
      return originals.requestStop(requireNotNull(snapshot.getString("original_id")),challenge,
          requireNotNull(snapshot.getString("request_sha256")),requireNotNull(snapshot.getString("stop_intent_sha256")))
    }
    if (method == "operation_query" || method == "operation_ack") {
      val expected = if (method == "operation_query") setOf("original_id") else
        setOf("original_id","request_sha256","outcome_sha256","consumer_receipt_sha256")
      if (snapshot.keySet() != expected || expected.any { snapshot.get(it) !is String }) closed()
      return if (method == "operation_query") originals.query(requireNotNull(snapshot.getString("original_id")), challenge)
      else originals.acknowledge(requireNotNull(snapshot.getString("original_id")), challenge,
          requireNotNull(snapshot.getString("request_sha256")), requireNotNull(snapshot.getString("outcome_sha256")),
          requireNotNull(snapshot.getString("consumer_receipt_sha256")))
    }
    if(method == "fresh_pair_continue") {
      val expected=setOf("original_id","prior_original_id","prior_outcome_sha256")
      if(snapshot.keySet()!=expected || expected.any { snapshot.get(it) !is String }) closed()
      val id=requireNotNull(snapshot.getString("original_id"))
      return originals.beginContinuation(id,challenge,EmbeddedDuplexOriginals.request(method,challenge,snapshot),
          requireNotNull(snapshot.getString("prior_original_id")),requireNotNull(snapshot.getString("prior_outcome_sha256"))) {
        EmbeddedDuplexRuntimeService.continueFreshPair(app).thenApply { pairBundle(challenge,it) }
      }
    }
    if(method=="prepare_peer_start"){
      if(snapshot.keySet()!=setOf("original_id") || snapshot.get("original_id") !is String)closed()
      val id=requireNotNull(snapshot.getString("original_id"))
      return originals.begin(id,method,challenge,EmbeddedDuplexOriginals.request(method,challenge,snapshot)){
        EmbeddedDuplexRuntimeService.preparePeerStart(app).thenApply { receipt ->
          Bundle().apply { header("observed");putString("challenge",challenge);putString("receipt",receipt) }
        }
      }
    }
    if(method=="prepared_peer_start_status"){
      if(!snapshot.isEmpty)closed()
      val receipt=EmbeddedDuplexRuntimeService.preparedPeerStartStatus(app).awaitOperatorCompletion(10)
      return Bundle().apply { header("observed");putString("challenge",challenge);putString("receipt",receipt) }
    }
    if(method=="reestablish_peer_roots"){
      if(snapshot.keySet()!=setOf("original_id") || snapshot.get("original_id") !is String)closed()
      val id=requireNotNull(snapshot.getString("original_id"))
      return originals.begin(id,method,challenge,EmbeddedDuplexOriginals.request(method,challenge,snapshot)){
        EmbeddedDuplexRuntimeService.reestablishPeerRoots(app).thenApply { receipt ->
          Bundle().apply { header("observed");putString("challenge",challenge);putString("receipt",receipt) }
        }
      }
    }
    if(method=="peer_root_status"){
      if(!snapshot.isEmpty)closed()
      val receipt=EmbeddedDuplexRuntimeService.peerRootStatus(app).awaitOperatorCompletion(10)
      return Bundle().apply { header("observed");putString("challenge",challenge);putString("receipt",receipt) }
    }
    if(method=="pair_setup_recovery_status"){
      if(!snapshot.isEmpty) closed()
      val receipt=EmbeddedDuplexRuntimeService.completedSetupRecoveryStatus(app,challenge).awaitOperatorCompletion(10)
      return Bundle().apply { header("observed");putString("challenge",challenge);putString("receipt",receipt) }
    }
    val effect = method in setOf("bootstrap_real_peer","concurrent_arm","pair_session","start","renew_authority",
        "peer_stop","peer_revoke","whole_app_close","close_no_media","policy_update","blend_update","confirm","abandon_retained_scope")
    val originalId = if (effect) snapshot.get("original_id") as? String ?: closed() else null
    if (originalId != null && !originalId.matches(Regex("[0-9a-f]{32}"))) closed()
    val parsedExtras = Bundle(snapshot).apply { if (effect) remove("original_id") }
    val request = try {
      EmbeddedDuplexOperatorContract.parseCall(method, challenge, parsedExtras)
    } catch (_: Exception) { closed() }
    if (effect) return originals.begin(requireNotNull(originalId), method, challenge,
        EmbeddedDuplexOriginals.request(method,challenge,snapshot)) { effectFuture(request) }
    return try {
      when (request.route) {
        EmbeddedDuplexOperatorContract.Route.RECOVERY_STATUS, EmbeddedDuplexOperatorContract.Route.ABANDON_RETAINED_SCOPE -> {
          val receipt = if(request.route == EmbeddedDuplexOperatorContract.Route.RECOVERY_STATUS)
            EmbeddedDuplexRuntimeService.recoveryStatus(app,request.challenge).awaitOperatorCompletion(30)
          else EmbeddedDuplexRuntimeService.abandonRetainedScope(app,request.challenge,requireNotNull(request.abandonment)).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge",request.challenge); putString("receipt",receipt) }
        }
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
          val receipt = EmbeddedDuplexRuntimeService.peerLifecycle(app, action, request.challenge).awaitOperatorCompletion(120)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.BLEND_READ, EmbeddedDuplexOperatorContract.Route.BLEND_UPDATE -> {
          val receipt = EmbeddedDuplexRuntimeService.concurrentMask(app,request.challenge,request.mask).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge",request.challenge); putString("receipt",receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.POLICY_READ, EmbeddedDuplexOperatorContract.Route.POLICY_UPDATE -> {
          val receipt = EmbeddedDuplexRuntimeService.concurrentPolicy(app, request.challenge, request.policy).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.CONCURRENT_ARM, EmbeddedDuplexOperatorContract.Route.CONCURRENT_STATUS -> {
          val receipt = if (request.route == EmbeddedDuplexOperatorContract.Route.CONCURRENT_ARM)
            EmbeddedDuplexRuntimeService.armConcurrentQualification(app, request.challenge).awaitOperatorCompletion(30)
          else EmbeddedDuplexRuntimeService.concurrentQualificationStatus(app, request.challenge).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.OWNER_FAILURE_DIAGNOSTIC -> {
          val receipt = EmbeddedDuplexRuntimeService.ownerFailureDiagnosticRead(app, request.challenge)
              .awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.OWN_CAPTURE_DIAGNOSTIC -> {
          val receipt = EmbeddedDuplexRuntimeService.ownCaptureDiagnostic(app, request.challenge)
              .awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge", request.challenge); putString("receipt", receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.STREAM_DROPOUT_DIAGNOSTIC -> {
          val receipt = EmbeddedDuplexRuntimeService.streamDropoutDiagnostic(app, request.challenge)
              .awaitOperatorCompletion(30)
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
        EmbeddedDuplexOperatorContract.Route.BLEND_CONFIG_READ -> {
          val receipt = EmbeddedDuplexRuntimeService.concurrentMaskConfiguration(app,request.challenge).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge",request.challenge); putString("receipt",receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.RENDERER_TERMINAL_STATUS -> {
          val receipt = EmbeddedDuplexRuntimeService.rendererTerminalStatus(app,request.challenge).awaitOperatorCompletion(30)
          Bundle().apply { header("observed"); putString("challenge",request.challenge); putString("receipt",receipt) }
        }
        EmbeddedDuplexOperatorContract.Route.RUNTIME_STATUS ->
          runtimeBundle(request.challenge, EmbeddedDuplexRuntimeService.status(app).awaitOperatorCompletion(10))
        EmbeddedDuplexOperatorContract.Route.BOOTSTRAP_REAL_PEER ->
          runtimeBundle(request.challenge,
              EmbeddedDuplexDiagnosticActivityGate.requestRealPeerBootstrap().awaitOperatorCompletion(120))
        EmbeddedDuplexOperatorContract.Route.CLOSE_NO_MEDIA -> {
          val closed = EmbeddedDuplexDiagnosticActivityGate.requestNoMediaClose().awaitOperatorCompletion(120)
          require(closed == "no-media-closed" || closed == "uninitialized-display-detached" ||
              closed == "peer-no-media-closed-own-capture-scope")
          runtimeBundle(request.challenge,
              EmbeddedDuplexRuntimeService.status(app).awaitOperatorCompletion(10)).apply {
            putString("close_disposition", closed)
          }
        }
        EmbeddedDuplexOperatorContract.Route.PAIR_STATUS ->
          pairBundle(request.challenge, EmbeddedDuplexRuntimeService.pairStatus(app).awaitOperatorCompletion(10))
        EmbeddedDuplexOperatorContract.Route.PAIR_SESSION ->
          pairBundle(request.challenge,
              EmbeddedDuplexDiagnosticActivityGate.requestPairSession().awaitOperatorCompletion(120))
        EmbeddedDuplexOperatorContract.Route.START_PREFLIGHT -> {
          val decision = EmbeddedDuplexDiagnosticActivityGate.requestStartPreflight()
              .awaitOperatorCompletion(10)
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

  /** Starts exactly one existing app-owned Future; no provider worker or estimate cancellation. */
  private fun effectFuture(request: EmbeddedDuplexOperatorContract.Request): CompletableFuture<Bundle> {
    val app = requireNotNull(context).applicationContext
    fun receipt(value: String): Bundle = Bundle().apply {
      header("observed"); putString("challenge",request.challenge); putString("receipt",value)
    }
    return when (request.route) {
      EmbeddedDuplexOperatorContract.Route.BOOTSTRAP_REAL_PEER ->
        EmbeddedDuplexDiagnosticActivityGate.requestRealPeerBootstrap().thenApply { runtimeBundle(request.challenge,it) }
      EmbeddedDuplexOperatorContract.Route.CLOSE_NO_MEDIA ->
        EmbeddedDuplexDiagnosticActivityGate.requestNoMediaClose().thenCompose { closed ->
          require(closed in setOf("no-media-closed","uninitialized-display-detached","peer-no-media-closed-own-capture-scope"))
          CompletableFuture.completedFuture(runtimeBundle(request.challenge,EmbeddedDuplexRuntimeService.closedNoMediaStatus(app)).apply { putString("close_disposition",closed) })
        }
      EmbeddedDuplexOperatorContract.Route.PAIR_SESSION ->
        EmbeddedDuplexDiagnosticActivityGate.requestPairSession().thenApply { pairBundle(request.challenge,it) }
      EmbeddedDuplexOperatorContract.Route.CONCURRENT_ARM ->
        EmbeddedDuplexRuntimeService.armConcurrentQualification(app,request.challenge).thenApply { receipt(it) }
      EmbeddedDuplexOperatorContract.Route.START, EmbeddedDuplexOperatorContract.Route.RENEW_AUTHORITY,
      EmbeddedDuplexOperatorContract.Route.PEER_STOP, EmbeddedDuplexOperatorContract.Route.PEER_REVOKE,
      EmbeddedDuplexOperatorContract.Route.WHOLE_APP_CLOSE -> {
        val action = when(request.route) {
          EmbeddedDuplexOperatorContract.Route.START -> EmbeddedDuplexPeerAction.START
          EmbeddedDuplexOperatorContract.Route.RENEW_AUTHORITY -> EmbeddedDuplexPeerAction.RENEW_AUTHORITY
          EmbeddedDuplexOperatorContract.Route.PEER_STOP -> EmbeddedDuplexPeerAction.STOP
          EmbeddedDuplexOperatorContract.Route.PEER_REVOKE -> EmbeddedDuplexPeerAction.REVOKE
          else -> EmbeddedDuplexPeerAction.WHOLE_APP_CLOSE
        }
        EmbeddedDuplexRuntimeService.peerLifecycle(app,action,request.challenge).thenApply { receipt(it) }
      }
      EmbeddedDuplexOperatorContract.Route.POLICY_UPDATE ->
        EmbeddedDuplexRuntimeService.concurrentPolicy(app,request.challenge,request.policy).thenApply { receipt(it) }
      EmbeddedDuplexOperatorContract.Route.BLEND_UPDATE ->
        EmbeddedDuplexRuntimeService.concurrentMask(app,request.challenge,request.mask).thenApply { receipt(it) }
      EmbeddedDuplexOperatorContract.Route.ABANDON_RETAINED_SCOPE ->
        EmbeddedDuplexRuntimeService.abandonRetainedScope(app,request.challenge,requireNotNull(request.abandonment)).thenApply { receipt(it) }
      EmbeddedDuplexOperatorContract.Route.CONFIRM -> {
        val review = synchronized(gate) { pending.consume(request.challenge,requireNotNull(request.reviewSha256)) }
        EmbeddedDuplexEnrollmentService.confirm(app,review).thenApply { saved -> Bundle().apply {
          header("committed"); putString("challenge",request.challenge); putString("review_sha256",review.reviewSha256)
          putString("role_id",saved.installed.roleId); putLong("revision",saved.revision)
          putString("record_sha256",saved.recordSha256); putBoolean("local_fixture",saved.localFixture)
        } }
      }
      else -> throw IllegalArgumentException("operator effect route")
    }
  }

  private fun Bundle.header(status: String) {
    putString("schema",EmbeddedDuplexOperatorContract.SCHEMA);putString("status",status)
  }
  private fun runtimeBundle(challenge: String,state: EmbeddedDuplexRuntimeStatus): Bundle =
    io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOperatorResponses.runtimeBundle(challenge,state)
  private fun pairBundle(challenge: String,state: EmbeddedDuplexPairStatus): Bundle =
    io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexOperatorResponses.pairBundle(challenge,state)

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
