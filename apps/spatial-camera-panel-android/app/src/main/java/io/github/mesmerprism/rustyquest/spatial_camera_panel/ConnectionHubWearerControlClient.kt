package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

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

internal fun connectionHubShouldOwnSurfaceClient(
    activityStarted: Boolean,
    snapshot: ConnectionHubWearerControlSnapshot,
): Boolean =
    activityStarted && snapshot.listenerEnabled && snapshot.status != "stop-pending"

/** Bounded same-signer adapter; it never requests or receives pairing/session secrets. */
internal class ConnectionHubWearerControlClient(
    context: Context,
    private val marker: (String) -> Unit,
    private val onEffectiveSnapshotChanged: (ConnectionHubWearerControlSnapshot) -> Unit = {},
) : Closeable {
  private val resolver = context.applicationContext.contentResolver
  private val mainHandler = Handler(Looper.getMainLooper())
  private val workerThread =
      HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
  private val workerHandler = Handler(workerThread.looper)
  private val observers =
      CopyOnWriteArraySet<(ConnectionHubWearerControlSnapshot) -> Unit>()
  private val closed = AtomicBoolean(false)
  @Volatile
  private var latestSnapshot =
      ConnectionHubWearerControlSnapshot.unavailable("status-not-requested")

  /** Returns the latest local readback only. This method never performs Binder/provider work. */
  fun status(): ConnectionHubWearerControlSnapshot = latestSnapshot

  fun refresh(): ConnectionHubWearerControlSnapshot =
      enqueue(METHOD_STATUS, pendingStatus = "status-pending")

  fun start(): ConnectionHubWearerControlSnapshot =
      enqueue(METHOD_START, pendingStatus = "start-pending")

  fun stop(): ConnectionHubWearerControlSnapshot =
      enqueue(METHOD_STOP, pendingStatus = "stop-pending")

  fun observe(observer: (ConnectionHubWearerControlSnapshot) -> Unit): Closeable {
    if (closed.get()) return Closeable {}
    observers += observer
    mainHandler.post {
      if (!closed.get() && observer in observers) observer(latestSnapshot)
    }
    return Closeable { observers -= observer }
  }

  private fun enqueue(method: String, pendingStatus: String): ConnectionHubWearerControlSnapshot {
    if (closed.get()) return latestSnapshot
    val pending = latestSnapshot.copy(status = pendingStatus)
    publish(pending)
    workerHandler.post {
      if (!closed.get()) publish(invokeBlocking(method))
    }
    return pending
  }

  private fun invokeBlocking(method: String): ConnectionHubWearerControlSnapshot =
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
                    "workerThread=${Thread.currentThread().name} " +
                    "secretsInSnapshot=false controllerInputIndependent=true"
            )
          }
          .getOrElse { error ->
            val reason = error.javaClass.simpleName.take(48)
            marker(
                "status=connection-hub-wearer-control-unavailable action=$method " +
                    "reason=$reason workerThread=${Thread.currentThread().name} " +
                    "secretsInSnapshot=false controllerInputIndependent=true"
            )
            ConnectionHubWearerControlSnapshot.unavailable(reason)
          }

  private fun publish(snapshot: ConnectionHubWearerControlSnapshot) {
    latestSnapshot = snapshot
    mainHandler.post {
      if (closed.get()) return@post
      onEffectiveSnapshotChanged(snapshot)
      observers.forEach { observer -> observer(snapshot) }
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    observers.clear()
    mainHandler.removeCallbacksAndMessages(null)
    workerThread.quitSafely()
  }

  private companion object {
    const val WORKER_THREAD_NAME = "RqConnectionHubControl"
    const val AUTHORITY =
        "io.github.mesmerprism.rustymanifold.broker.connection-hub-wearer-control"
    val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
    const val SNAPSHOT_SCHEMA = "rusty.quest.connection_hub.wearer_control_snapshot.v1"
    const val METHOD_START = "start"
    const val METHOD_STOP = "stop"
    const val METHOD_STATUS = "status"
  }
}
