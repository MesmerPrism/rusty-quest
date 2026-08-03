package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.Closeable
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin provider adapter. Android Binder supplies UID/package/signer evidence;
 * this client never supplies or overrides those fields. It retains the legacy
 * trusted_local_http_v1 route unchanged and only adds an optional Hub surface.
 */
class ConnectionHubSurfaceClient(
    context: Context,
    private val player: Media3SpatialPlayerAdapter,
) : Closeable {
  private val appContext = context.applicationContext
  private val handler = ProviderHandler(Looper.getMainLooper())
  private val callback = Messenger(handler)
  private val random = SecureRandom()
  private var broker: Messenger? = null
  private var bound = false
  private var stage = Stage.IDLE
  private var admissionRevision = 0L
  private var tokenId = ""
  private var surfaceRegistered = false
  private val statePublisher =
      object : Runnable {
        override fun run() {
          if (!surfaceRegistered) return
          sendSurfaceState()
          handler.postDelayed(this, STATE_PUBLISH_INTERVAL_MS)
        }
      }

  fun start() {
    if (bound) return
    val intent =
        Intent().setComponent(
            ComponentName(BROKER_PACKAGE, BROKER_ADMISSION_SERVICE)
        )
    bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    if (!bound) marker("bind_rejected")
  }

  override fun close() {
    handler.removeCallbacks(statePublisher)
    if (surfaceRegistered) {
      val data = Bundle()
      data.putString("surface_id", SURFACE_ID)
      send(MESSAGE_UNREGISTER_SURFACE, data)
      surfaceRegistered = false
    }
    if (bound) {
      runCatching { appContext.unbindService(connection) }
      bound = false
    }
    broker = null
    stage = Stage.IDLE
  }

  private val connection =
      object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
          broker = Messenger(binder)
          stage = Stage.AWAITING_EVIDENCE
          send(MESSAGE_RUNTIME_EVIDENCE, Bundle())
        }

        override fun onServiceDisconnected(name: ComponentName) {
          broker = null
          surfaceRegistered = false
          handler.removeCallbacks(statePublisher)
          marker("broker_disconnected")
        }
      }

  private inner class ProviderHandler(looper: Looper) : Handler(looper) {
    override fun handleMessage(message: Message) {
      if (message.what == MESSAGE_SURFACE_COMMAND) {
        handleSurfaceCommand(message)
        return
      }
      val error = message.data.getString("error", "")
      if (error.isNotEmpty()) {
        marker("operation_rejected_${sanitize(error)}")
        return
      }
      runCatching {
            val response = JSONObject(message.data.getString("response_json", "{}"))
            when (stage) {
              Stage.AWAITING_EVIDENCE -> {
                admissionRevision =
                    response
                        .getJSONObject("runtime")
                        .getJSONObject("admission_snapshot")
                        .getLong("authority_revision")
                stage = Stage.AWAITING_TOKEN
                sendIssueToken()
              }
              Stage.AWAITING_TOKEN -> {
                val receipt = response.getJSONObject("receipt")
                check(receipt.getBoolean("applied"))
                tokenId = receipt.getJSONObject("token").getString("token_id")
                admissionRevision = receipt.getLong("resulting_authority_revision")
                stage = Stage.AWAITING_USE
                sendAuthorizeUse()
              }
              Stage.AWAITING_USE -> {
                val receipt = response.getJSONObject("receipt")
                check(receipt.getBoolean("applied"))
                admissionRevision = receipt.getLong("resulting_authority_revision")
                stage = Stage.AWAITING_REGISTRATION
                sendRegisterSurface()
              }
              Stage.AWAITING_REGISTRATION -> {
                check(response.optBoolean("applied", false))
                surfaceRegistered = true
                stage = Stage.REGISTERED
                marker("surface_registered")
                handler.removeCallbacks(statePublisher)
                handler.post(statePublisher)
              }
              Stage.REGISTERED, Stage.IDLE -> Unit
            }
          }
          .onFailure { marker("operation_rejected_${it.javaClass.simpleName}") }
    }
  }

  private fun sendIssueToken() {
    val data = Bundle()
    data.putString("request_id", randomToken("hub-issue"))
    data.putLong("expected_authority_revision", admissionRevision)
    data.putString("capabilities", SURFACE_PROVIDER_CAPABILITY)
    data.putLong("token_ttl_ms", 30_000L)
    send(MESSAGE_ISSUE_TOKEN, data)
  }

  private fun sendAuthorizeUse() {
    val data = Bundle()
    data.putString("request_id", randomToken("hub-use"))
    data.putLong("expected_authority_revision", admissionRevision)
    data.putString("token_id", tokenId)
    data.putString("capability_id", SURFACE_PROVIDER_CAPABILITY)
    send(MESSAGE_AUTHORIZE_USE, data)
  }

  private fun sendRegisterSurface() {
    val registration =
        JSONObject()
            .put("\$schema", SURFACE_REGISTRATION_SCHEMA)
            .put("schema_version", 1)
            .put("surface_id", SURFACE_ID)
            .put("display_label", "Spatial Video Control")
            .put("description", "Select and control the active bundled immersive video")
            .put(
                "commands",
                JSONArray()
                    .put(command(COMMAND_PAUSE, "Pause", CAPABILITY_PAUSE))
                    .put(command(COMMAND_PLAY, "Play", CAPABILITY_PLAY))
                    .put(command(COMMAND_SELECT_NEXT, "Next video", CAPABILITY_SELECT_NEXT))
                    .put(command(COMMAND_SELECT_PREVIOUS, "Previous video", CAPABILITY_SELECT_PREVIOUS)),
            )
            .put("surface_contract_sha256", SURFACE_CONTRACT_SHA256)
            .put("state", player.hubSurfaceState())
    val data = Bundle()
    data.putString("surface_registration_json", registration.toString())
    send(MESSAGE_REGISTER_SURFACE, data)
  }

  private fun sendSurfaceState() {
    val data = Bundle()
    data.putString("surface_id", SURFACE_ID)
    data.putString("state_json", player.hubSurfaceState().toString())
    send(MESSAGE_UPDATE_SURFACE_STATE, data)
  }

  private fun handleSurfaceCommand(message: Message) {
    val response = Message.obtain(null, MESSAGE_SURFACE_COMMAND)
    val result = Bundle()
    runCatching {
          val requestId = message.data.getString("request_id", "")
          val surfaceId = message.data.getString("surface_id", "")
          val command = message.data.getString("command", "")
          val args = JSONObject(message.data.getString("args_json", "{}"))
          val receipt =
              JSONObject(message.data.getString("authority_receipt_json", "{}"))
          val status =
              player.enqueueHubAuthorizedCommand(
                  requestId,
                  surfaceId,
                  command,
                  args,
                  receipt,
              )
          result.putBoolean("provider_applied", false)
          result.putString("status", status)
          result.putString("state_json", player.hubSurfaceState().toString())
          handler.postDelayed({ if (surfaceRegistered) sendSurfaceState() }, 500L)
        }
        .onFailure { error ->
          result.putBoolean("provider_applied", false)
          result.putString("status", "provider_rejected_${error.javaClass.simpleName}")
          result.putString("state_json", player.hubSurfaceState().toString())
        }
    response.data = result
    runCatching { message.replyTo?.send(response) }
  }

  private fun send(what: Int, data: Bundle) {
    val message = Message.obtain(null, what)
    message.data = data
    message.replyTo = callback
    runCatching { checkNotNull(broker).send(message) }
        .onFailure { marker("send_failed_${it.javaClass.simpleName}") }
  }

  private fun command(id: String, label: String, capability: String) =
      JSONObject()
          .put("command", id)
          .put("display_label", label)
          .put("required_controller_capability", capability)

  private fun randomToken(prefix: String): String {
    val bytes = ByteArray(12).also(random::nextBytes)
    val suffix = bytes.joinToString("") { "%02x".format(it) }
    bytes.fill(0)
    return "$prefix.$suffix"
  }

  private fun marker(status: String) {
    Log.i(TAG, "channel=rusty-connection-hub surfaceId=$SURFACE_ID status=$status")
  }

  private fun sanitize(value: String) =
      value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(96)

  private enum class Stage {
    IDLE,
    AWAITING_EVIDENCE,
    AWAITING_TOKEN,
    AWAITING_USE,
    AWAITING_REGISTRATION,
    REGISTERED,
  }

  private companion object {
    const val TAG = "RqConnectionHub"
    const val BROKER_PACKAGE = "io.github.mesmerprism.rustymanifold.broker"
    const val BROKER_ADMISSION_SERVICE =
        "io.github.mesmerprism.rustymanifold.broker.ConnectionHubAdmissionService"
    const val SURFACE_ID = "surface.spatial_video_control.media"
    const val SURFACE_REGISTRATION_SCHEMA =
        "rusty.quest.connection_hub.surface_registration.v1"
    const val SURFACE_PROVIDER_CAPABILITY =
        "capability.connection_hub.provider.register"
    const val COMMAND_PAUSE = "command.spatial_video_control.pause"
    const val COMMAND_PLAY = "command.spatial_video_control.play"
    const val COMMAND_SELECT_NEXT = "command.spatial_video_control.select_next"
    const val COMMAND_SELECT_PREVIOUS = "command.spatial_video_control.select_previous"
    const val CAPABILITY_PAUSE = "capability.spatial_video_control.pause"
    const val CAPABILITY_PLAY = "capability.spatial_video_control.play"
    const val CAPABILITY_SELECT_NEXT = "capability.spatial_video_control.select_next"
    const val CAPABILITY_SELECT_PREVIOUS = "capability.spatial_video_control.select_previous"
    const val SURFACE_CONTRACT_SHA256 =
        "sha256:099dab2723521655df0617b22a14f3a8021ecf75fc952587d619b944e8019e60"
    const val MESSAGE_ISSUE_TOKEN = 1
    const val MESSAGE_AUTHORIZE_USE = 2
    const val MESSAGE_RUNTIME_EVIDENCE = 6
    const val MESSAGE_REGISTER_SURFACE = 20
    const val MESSAGE_UPDATE_SURFACE_STATE = 21
    const val MESSAGE_UNREGISTER_SURFACE = 22
    const val MESSAGE_SURFACE_COMMAND = 23
    const val STATE_PUBLISH_INTERVAL_MS = 1_000L
  }
}
