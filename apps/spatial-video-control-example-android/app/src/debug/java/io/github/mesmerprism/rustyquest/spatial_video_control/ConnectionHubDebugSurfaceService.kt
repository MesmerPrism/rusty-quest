package io.github.mesmerprism.rustyquest.spatial_video_control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Debug-only foreground owner for off-head Connection Hub surface testing.
 * The DUMP permission is enforced by the debug manifest before this exported
 * service is entered. Only the two fixed actions below are accepted.
 */
class ConnectionHubDebugSurfaceService : Service() {
  private var surfaceClient: ConnectionHubSurfaceClient? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return when (intent?.action) {
      START_ACTION -> {
        startAsForeground()
        if (surfaceClient == null) {
          surfaceClient =
              ConnectionHubSurfaceClient(this, DebugConnectionHubSurfaceTarget()).also {
                it.start()
              }
          marker("started")
        } else {
          marker("already_started")
        }
        START_NOT_STICKY
      }
      STOP_ACTION -> {
        closeSurface("stopped_by_operator")
        stopSelfResult(startId)
        START_NOT_STICKY
      }
      else -> {
        marker("rejected_unknown_action")
        stopSelfResult(startId)
        START_NOT_STICKY
      }
    }
  }

  override fun onDestroy() {
    closeSurface("service_destroyed")
    super.onDestroy()
  }

  private fun startAsForeground() {
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Connection Hub debug surface",
            NotificationManager.IMPORTANCE_LOW,
        )
    )
    val notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Connection Hub debug surface")
            .setContentText("Off-head media controls are available for qualification")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    startForeground(NOTIFICATION_ID, notification)
  }

  private fun closeSurface(reason: String) {
    val prior = surfaceClient
    surfaceClient = null
    prior?.close()
    stopForeground(STOP_FOREGROUND_REMOVE)
    marker(reason)
  }

  private fun marker(status: String) {
    Log.i(TAG, "channel=rusty-connection-hub-debug-surface status=$status")
  }

  companion object {
    val START_ACTION =
        "${BuildConfig.APPLICATION_ID}.action.START_CONNECTION_HUB_DEBUG_SURFACE"
    val STOP_ACTION =
        "${BuildConfig.APPLICATION_ID}.action.STOP_CONNECTION_HUB_DEBUG_SURFACE"

    private const val TAG = "RqHubDebugSurface"
    private const val NOTIFICATION_CHANNEL_ID = "connection-hub-debug-surface"
    private const val NOTIFICATION_ID = 0x4348
  }
}
