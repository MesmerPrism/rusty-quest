package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.net.Uri
import android.os.Bundle

internal data class ConnectionHubWearerControlSnapshot(
    val available: Boolean,
    val listenerEnabled: Boolean,
    val desiredConnectionState: String,
    val pairingAvailable: Boolean,
    val activeControllerSessions: Int,
    val transportClassification: String,
    val confidentiality: String,
    val productionEligible: Boolean,
    val status: String,
) {
  val summary: String
    get() =
        when {
          !available -> "Hub unavailable"
          listenerEnabled -> "Hub on · $activeControllerSessions controller(s)"
          else -> "Hub off"
        }

  companion object {
    fun unavailable(status: String = "broker-unavailable") =
        ConnectionHubWearerControlSnapshot(
            available = false,
            listenerEnabled = false,
            desiredConnectionState = "unavailable",
            pairingAvailable = false,
            activeControllerSessions = 0,
            transportClassification = "unavailable",
            confidentiality = "unavailable",
            productionEligible = false,
            status = status,
        )
  }
}

/** Bounded same-signer adapter; it never requests or receives pairing/session secrets. */
internal class ConnectionHubWearerControlClient(
    context: Context,
    private val marker: (String) -> Unit,
) {
  private val resolver = context.applicationContext.contentResolver

  fun status(): ConnectionHubWearerControlSnapshot = invoke(METHOD_STATUS)

  fun start(): ConnectionHubWearerControlSnapshot = invoke(METHOD_START)

  fun stop(): ConnectionHubWearerControlSnapshot = invoke(METHOD_STOP)

  private fun invoke(method: String): ConnectionHubWearerControlSnapshot =
      runCatching {
            val response =
                requireNotNull(resolver.call(CONTENT_URI, method, null, Bundle.EMPTY)) {
                  "empty-response"
                }
            require(response.getString("schema") == SNAPSHOT_SCHEMA) { "schema-mismatch" }
            require(!response.getBoolean("secrets_in_snapshot", true)) { "secret-boundary" }
            require(!response.getBoolean("caller_selected_authority", true)) {
              "authority-boundary"
            }
            ConnectionHubWearerControlSnapshot(
                available = response.getBoolean("status_available", false),
                listenerEnabled = response.getBoolean("listener_enabled", false),
                desiredConnectionState =
                    response.getString("desired_connection_state") ?: "unavailable",
                pairingAvailable = response.getBoolean("pairing_available", false),
                activeControllerSessions = response.getInt("active_controller_sessions", 0),
                transportClassification =
                    response.getString("transport_classification") ?: "unavailable",
                confidentiality = response.getString("confidentiality") ?: "unavailable",
                productionEligible = response.getBoolean("production_eligible", false),
                status = "ok",
            )
          }
          .onSuccess { snapshot ->
            marker(
                "status=connection-hub-wearer-control action=$method " +
                    "available=${snapshot.available} listenerEnabled=${snapshot.listenerEnabled} " +
                    "desiredState=${snapshot.desiredConnectionState} " +
                    "activeControllerSessions=${snapshot.activeControllerSessions} " +
                    "secretsInSnapshot=false controllerInputIndependent=true"
            )
          }
          .getOrElse { error ->
            val reason = error.javaClass.simpleName.take(48)
            marker(
                "status=connection-hub-wearer-control-unavailable action=$method " +
                    "reason=$reason secretsInSnapshot=false controllerInputIndependent=true"
            )
            ConnectionHubWearerControlSnapshot.unavailable(reason)
          }

  private companion object {
    const val AUTHORITY =
        "io.github.mesmerprism.rustymanifold.broker.connection-hub-wearer-control"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
    const val SNAPSHOT_SCHEMA = "rusty.quest.connection_hub.wearer_control_snapshot.v1"
    const val METHOD_START = "start"
    const val METHOD_STOP = "stop"
    const val METHOD_STATUS = "status"
  }
}
