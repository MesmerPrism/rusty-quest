package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.InetAddress
import java.time.Duration
import java.time.Instant

data class HeadsetControllerState(
    val listenerEnabled: Boolean = false,
    val displayedAddress: String? = null,
    val pairingCode: String? = null,
    val enableExpiresAt: Instant? = null,
    val controllerConnected: Boolean = false,
    val controllerLabel: String? = null,
    val authorityRevision: Long = 0,
    val status: String = "disabled",
)

/**
 * Android lifecycle adapter. It has no ambient authority and cannot start
 * without an explicitly injected process-local Manifold port.
 */
class AndroidTrustedLocalControlAdapter(
    private val context: Context,
    private val authority: ManifoldAuthorityPort,
    player: PlayerPort,
    private val onState: (HeadsetControllerState) -> Unit,
) : Closeable {
  private val mainHandler = Handler(Looper.getMainLooper())
  private val coordinator =
      LocalControlCoordinator(authority, player, VideoCatalog.bundledSynthetic())
  private var foreground = false
  private var server: TrustedLocalHttpServer? = null
  private var state = HeadsetControllerState()
  private val expiryAction = Runnable { revokeFromHeadset("enable_window_expired") }

  init {
    check(!BuildConfig.TRUSTED_LOCAL_HTTP_ENABLED_DEFAULT) {
      "trusted_local_http_v1 must remain disabled by default"
    }
    publish(state)
  }

  fun setWearerForeground(visible: Boolean) {
    foreground = visible
    if (!visible && state.listenerEnabled) {
      revokeFromHeadset("activity_left_foreground")
    }
  }

  fun enableFromWearer(
      bindAddress: InetAddress,
      requestedWindow: Duration = Duration.ofMinutes(2),
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "wearer enable must run on the foreground UI thread"
    }
    if (!foreground) {
      publish(state.copy(status = "foreground_wearer_action_required"))
      return
    }
    if (requestedWindow.isZero ||
        requestedWindow.isNegative ||
        requestedWindow > TrustedLocalControlPolicy.MAX_ENABLE_WINDOW) {
      publish(state.copy(status = "enable_window_out_of_bounds"))
      return
    }
    revokeFromHeadset("replace_enable_window")
    val now = Instant.now()
    val offer =
        authority.beginWearerEnable(
            ManifoldAuthorityPort.EnableRequest(
                bindAddress.hostAddress,
                requestedWindow,
                now,
                true,
            )
        )
    if (!offer.enabled()) {
      publish(
          HeadsetControllerState(
              authorityRevision = offer.authorityRevision(),
              status = offer.reason(),
          )
      )
      return
    }
    val nextServer =
        TrustedLocalHttpServer(coordinator) { path ->
          val assetName =
              when (path) {
                "/index.html" -> "control/index.html"
                "/app.js" -> "control/app.js"
                "/styles.css" -> "control/styles.css"
                else -> null
              }
          if (assetName == null) {
            null
          } else {
            val contentType =
                when {
                  path.endsWith(".js") -> "text/javascript; charset=utf-8"
                  path.endsWith(".css") -> "text/css; charset=utf-8"
                  else -> "text/html; charset=utf-8"
                }
            TrustedLocalHttpServer.Asset(
                contentType,
                context.assets.open(assetName).use { it.readBytes() },
            )
          }
        }
    val endpoint =
        runCatching { nextServer.start(offer, bindAddress) }
            .getOrElse { error ->
              authority.revokeByWearer(Instant.now())
              publish(
                  HeadsetControllerState(
                      authorityRevision = authority.snapshot(Instant.now()).authorityRevision(),
                      status = "listener_start_failed_${error.javaClass.simpleName}",
                  )
              )
              return
            }
    server = nextServer
    publish(
        HeadsetControllerState(
            listenerEnabled = true,
            displayedAddress = endpoint.origin(),
            pairingCode = offer.singleUseCode(),
            enableExpiresAt = offer.expiresAt(),
            authorityRevision = offer.authorityRevision(),
            status = "awaiting_manual_pairing",
        )
    )
    mainHandler.removeCallbacks(expiryAction)
    mainHandler.postDelayed(expiryAction, requestedWindow.toMillis())
  }

  fun refreshVisibleState() {
    val authorityState = authority.snapshot(Instant.now())
    if (!authorityState.enabled() && state.listenerEnabled) {
      server?.close()
      server = null
    }
    val remainsEnabled = state.listenerEnabled && authorityState.enabled()
    publish(
        state.copy(
            listenerEnabled = remainsEnabled,
            displayedAddress = if (remainsEnabled) state.displayedAddress else null,
            pairingCode =
                if (remainsEnabled && !authorityState.controllerConnected()) {
                  state.pairingCode
                } else {
                  null
                },
            controllerConnected = authorityState.controllerConnected(),
            controllerLabel = authorityState.controllerLabel(),
            authorityRevision = authorityState.authorityRevision(),
            status =
                when {
                  !remainsEnabled -> "disabled_or_expired"
                  authorityState.controllerConnected() -> "controller_connected"
                  else -> "awaiting_manual_pairing"
                },
        )
    )
  }

  fun revokeFromHeadset(reason: String = "wearer_revoke") {
    mainHandler.removeCallbacks(expiryAction)
    server?.close()
    server = null
    if (state.listenerEnabled || authority.snapshot(Instant.now()).enabled()) {
      authority.revokeByWearer(Instant.now())
    }
    val revision = authority.snapshot(Instant.now()).authorityRevision()
    publish(
        HeadsetControllerState(
            authorityRevision = revision,
            status = reason,
        )
    )
  }

  override fun close() {
    revokeFromHeadset("activity_destroyed")
  }

  private fun publish(next: HeadsetControllerState) {
    state = next
    onState(next)
  }
}
