package io.github.mesmerprism.rustyquest.spatial_video_control

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.time.Duration
import java.time.Instant
import java.security.SecureRandom

data class HeadsetControllerState(
    val listenerEnabled: Boolean = false,
    val displayedAddress: String? = null,
    val pairingCode: String? = null,
    val accessMode: ManifoldAuthorityPort.AccessMode? = null,
    val discoveryStatus: String? = null,
    val enableExpiresAt: Instant? = null,
    val controllerConnected: Boolean = false,
    val controllerLabel: String? = null,
    val authority: ManifoldAuthorityPort.AuthoritySnapshot? = null,
    val lastRevocationReceiptId: String? = null,
    val lastRevocationCause: String? = null,
    val status: String = "disabled",
) {
  val authorityRevision: Long
    get() = authority?.revisions()?.localRevision() ?: 0
}

/**
 * Android lifecycle adapter. It has no ambient authority and cannot start
 * without an explicitly injected process-local Manifold port.
 */
class AndroidTrustedLocalControlAdapter(
    private val context: Context,
    private val authority: ManifoldAuthorityPort,
    player: PlayerPort,
    catalog: VideoCatalog,
    private val onState: (HeadsetControllerState) -> Unit,
) : Closeable {
  private companion object {
    const val TRANSPORT_DIAGNOSTIC_TAG = "RqLocalControl"
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val random = SecureRandom()
  private val discovery =
      LocalControlNsdAdvertiser(context) { nextStatus ->
        mainHandler.post { publish(state.copy(discoveryStatus = nextStatus)) }
      }
  private val coordinator =
      LocalControlCoordinator(authority, player, catalog)
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
  ) = enable(
      bindAddress,
      requestedWindow,
      ManifoldAuthorityPort.AccessMode.PAIRED,
      ManifoldAuthorityPort.EnableActor.WEARER,
  )

  fun enableOpenLanFromWearer(
      bindAddress: InetAddress,
      requestedWindow: Duration = Duration.ofMinutes(2),
  ) = enable(
      bindAddress,
      requestedWindow,
      ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE,
      ManifoldAuthorityPort.EnableActor.WEARER,
  )

  fun enableFromDebugShell(
      bindAddress: InetAddress,
      accessMode: ManifoldAuthorityPort.AccessMode,
      requestedWindow: Duration = Duration.ofMinutes(2),
  ) = enable(
      bindAddress,
      requestedWindow,
      accessMode,
      ManifoldAuthorityPort.EnableActor.DEBUG_SHELL,
  )

  private fun enable(
      bindAddress: InetAddress,
      requestedWindow: Duration,
      accessMode: ManifoldAuthorityPort.AccessMode,
      enableActor: ManifoldAuthorityPort.EnableActor,
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
                accessMode,
                enableActor,
            )
        )
    if (!offer.enabled()) {
      publish(
          HeadsetControllerState(
              authority = authority.snapshot(),
              status = offer.reason(),
          )
      )
      return
    }
    val nextServer =
        TrustedLocalHttpServer(
            coordinator,
            { path ->
              val assetName =
                  when (path) {
                    "/index.html" -> "control/index.html"
                    "/app.js" -> "control/app.js"
                    "/styles.css" -> "control/styles.css"
                    "/favicon.svg" -> "control/favicon.svg"
                    else -> null
                  }
              if (assetName == null) {
                null
              } else {
                val contentType =
                    when {
                      path.endsWith(".js") -> "text/javascript; charset=utf-8"
                      path.endsWith(".css") -> "text/css; charset=utf-8"
                      path.endsWith(".svg") -> "image/svg+xml"
                      else -> "text/html; charset=utf-8"
                    }
                TrustedLocalHttpServer.Asset(
                    contentType,
                    context.assets.open(assetName).use { it.readBytes() },
                )
              }
            },
            { phase, failureKind ->
              Log.w(TRANSPORT_DIAGNOSTIC_TAG, "$phase:$failureKind")
            },
        )
    val endpoint =
        runCatching { nextServer.start(offer, bindAddress) }
            .getOrElse { error ->
              val revoked =
                  authority.revokeByWearer(
                      ManifoldAuthorityPort.RevokeRequest(
                          randomRequestId("listener-failure"),
                          "listener_start_failed",
                      )
                  )
              publish(
                  HeadsetControllerState(
                      authority = authority.snapshot(),
                      lastRevocationReceiptId = revoked.disableReceiptId(),
                      lastRevocationCause = revoked.cause(),
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
            pairingCode = offer.singleUseCode().takeIf(String::isNotEmpty),
            accessMode = accessMode,
            enableExpiresAt = offer.expiresAt(),
            authority = authority.snapshot(),
            status =
                if (accessMode == ManifoldAuthorityPort.AccessMode.PAIRED) {
                  "awaiting_manual_pairing"
                } else {
                  "open_lan_insecure_anyone_can_connect"
                },
        )
    )
    discovery.start(endpoint.port(), accessMode)
    mainHandler.removeCallbacks(expiryAction)
    mainHandler.postDelayed(expiryAction, requestedWindow.toMillis())
  }

  fun refreshVisibleState() {
    authority.enforceExpiry(
        ManifoldAuthorityPort.ExpiryRequest(
            randomRequestId("visible-expiry"),
            Instant.now(),
        )
    )
    val authorityState = authority.snapshot()
    if (!authorityState.enabled() && state.listenerEnabled) {
      discovery.close()
      server?.close()
      server = null
    }
    val remainsEnabled = state.listenerEnabled && authorityState.enabled()
    publish(
        state.copy(
            listenerEnabled = remainsEnabled,
            displayedAddress = if (remainsEnabled) state.displayedAddress else null,
            pairingCode =
                if (remainsEnabled &&
                    state.accessMode == ManifoldAuthorityPort.AccessMode.PAIRED &&
                    !authorityState.controllerConnected()) {
                  state.pairingCode
                } else {
                  null
                },
            controllerConnected = authorityState.controllerConnected(),
            controllerLabel = authorityState.controllerId(),
            authority = authorityState,
            status =
                when {
                  !remainsEnabled -> "disabled_or_expired"
                  authorityState.controllerConnected() -> "controller_connected"
                  state.accessMode == ManifoldAuthorityPort.AccessMode.OPEN_LAN_INSECURE ->
                      "open_lan_insecure_anyone_can_connect"
                  else -> "awaiting_manual_pairing"
                },
        )
    )
  }

  fun revokeFromHeadset(reason: String = "wearer_revoke") {
    mainHandler.removeCallbacks(expiryAction)
    discovery.close()
    server?.close()
    server = null
    val before = authority.snapshot()
    val revoked =
        if (before.enabled()) {
          authority.revokeByWearer(
              ManifoldAuthorityPort.RevokeRequest(
                  randomRequestId("wearer-disable"),
                  reason,
              )
          )
        } else {
          null
        }
    publish(
        HeadsetControllerState(
            authority = authority.snapshot(),
            lastRevocationReceiptId = revoked?.disableReceiptId(),
            lastRevocationCause = revoked?.cause(),
            status =
                if (revoked == null || revoked.revoked()) {
                  reason
                } else {
                  "revoke_failed_${revoked.reason()}"
                },
        )
    )
  }

  override fun close() {
    revokeFromHeadset("activity_destroyed")
    coordinator.close()
  }

  private fun publish(next: HeadsetControllerState) {
    state = next
    onState(next)
  }

  private fun randomRequestId(prefix: String): String {
    val bytes = ByteArray(12).also(random::nextBytes)
    val suffix = bytes.joinToString("") { "%02x".format(it) }
    bytes.fill(0)
    return "$prefix-$suffix"
  }
}
