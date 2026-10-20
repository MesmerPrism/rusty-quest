package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex
import android.os.Bundle
/** Same typed projection for Activity-owned and operator-owned originals; native receipt Strings remain exact. */
object EmbeddedDuplexOperatorResponses {
  private fun Bundle.header(status: String) {
    putString("schema", "rusty.quest.embedded_duplex.operator_transport.v1")
    putString("status", status)
  }

  @JvmStatic fun runtimeBundle(challenge: String, state: EmbeddedDuplexRuntimeStatus): Bundle =
      Bundle().apply {
        header("observed")
        putString("challenge", challenge)
        putString("runtime_state", state.state)
        putBoolean("display_attached", state.displayAttached)
        if (state.cleanupScope == "peer_subscription_only") {
          putString("cleanup_scope", state.cleanupScope)
          putString("own_app_capture_state", state.ownAppCaptureState)
        }
        state.ownCleanupStatus?.let {
          putBoolean("own_stop_requested", it.stopRequested)
          putBoolean("own_startup_settled", it.startupSettled)
          putBoolean("own_camera_thread_alive", it.cameraThreadAlive)
          putBoolean("own_compositor_thread_alive", it.compositorThreadAlive)
          putBoolean("own_compositor_physically_retired", it.compositorPhysicallyRetired)
          putBoolean("own_compositor_cleanup_rejected", it.compositorCleanupRejected)
          putString("own_left_callback_barrier", it.leftCallbackBarrier)
          putString("own_right_callback_barrier", it.rightCallbackBarrier)
          putString("own_compositor_barrier", it.compositorBarrier)
        }
        state.lastBootstrapFailureStage?.let { putString("last_bootstrap_failure_stage", it) }
        state.lastBootstrapFailureCode?.let { putString("last_bootstrap_failure_code", it) }
        state.setupCredentials?.let {
          putString("setup_credential_policy", it.policyId)
          putLong("setup_credential_horizon_ms", it.horizonMs)
          putLong("setup_credentials_issued_at_ms", it.issuedAtMs)
          putLong("setup_credentials_expires_at_ms", it.expiresAtMs)
        }
        state.runtimeConfigSha256?.let { putString("runtime_config_sha256", it) }
        state.enrollmentRecordSha256?.let { putString("enrollment_record_sha256", it) }
        putBoolean("peer_route_proven", false)
        putBoolean("media_effect_proven", false)
      }

  @JvmStatic fun pairBundle(challenge: String, state: EmbeddedDuplexPairStatus): Bundle =
      Bundle().apply {
        header("observed")
        putString("challenge", challenge)
        putString("pair_state", state.state)
        state.sessionId?.let { putString("session_id", it) }
        putBoolean("local_session_current", state.localSessionCurrent)
        putBoolean("remote_session_current", state.remoteSessionCurrent)
        putLong("local_session_expires_at_ms", state.localSessionExpiresAtMs)
        state.localSessionRejectionReason?.let { putString("local_session_rejection_reason", it) }
        state.remoteSessionRejectionReason?.let { putString("remote_session_rejection_reason", it) }
        putLong("local_session_validated_at_ms", state.localSessionValidatedAtMs)
        putLong("remote_session_validated_at_ms", state.remoteSessionValidatedAtMs)
        state.lastStep?.let { putString("last_step", it) }
        state.lastFailureCode?.let { putString("last_failure_code", it) }
        putBoolean("peer_route_proven", state.routeCurrent)
        putBoolean("media_effect_proven", state.mediaEffectProven)
      }

  @JvmStatic fun receipt(challenge: String,value: String): Bundle = Bundle().apply {
    header("observed");putString("challenge",challenge);putString("receipt",value)
  }
}
