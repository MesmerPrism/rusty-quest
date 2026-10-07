package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialPeerProjectionDecoderIdentity(
    val routeGeneration: Long,
    val decoderToken: Long,
    val readerGeneration: Long,
    val role: SpatialVideoDecoderRole = SpatialVideoDecoderRole.ProjectionPeer,
)

internal enum class SpatialPeerProjectionRuntimeState {
  Stopped,
  Staged,
  Starting,
  Active,
  Rejected,
}

internal data class SpatialPeerProjectionRuntimeSnapshot(
    val state: SpatialPeerProjectionRuntimeState = SpatialPeerProjectionRuntimeState.Stopped,
    val stagedRouteGeneration: Long = 0L,
    val identity: SpatialPeerProjectionDecoderIdentity? = null,
    val sourceDemand: Boolean = false,
)

internal data class SpatialPeerProjectionRuntimeBindings(
    val startDecoder: (
        SpatialVideoProjectionSettings,
        Long,
        SpatialVideoProjectionPlaybackCallbacks,
    ) -> SpatialPeerProjectionDecoderIdentity?,
    val stopDecoder: (SpatialPeerProjectionDecoderIdentity) -> Boolean,
    val bindNativeRole: (SpatialPeerProjectionDecoderIdentity) -> Boolean,
    val attachCommonGraph: (SpatialPeerProjectionDecoderIdentity) -> Boolean,
    val marker: (String) -> Unit,
)

/** Fixed ProjectionPeer lane. It never owns or mutates CompositorVideo state. */
internal class SpatialPeerProjectionRuntimeCoordinator(
    private val bindings: SpatialPeerProjectionRuntimeBindings,
) {
  val role = SpatialVideoDecoderRole.ProjectionPeer
  private val lock = Any()
  private var snapshot = SpatialPeerProjectionRuntimeSnapshot()
  private var stagedSettings: SpatialVideoProjectionSettings? = null
  private var firstFrameRouteGeneration = 0L
  private var attachedRouteGeneration = 0L
  private var decoderStartInFlightRouteGeneration = 0L

  fun snapshot(): SpatialPeerProjectionRuntimeSnapshot = synchronized(lock) { snapshot }

  fun stage(settings: SpatialVideoProjectionSettings, routeGeneration: Long): Boolean {
    if (routeGeneration <= 0L || !settings.active || settings.source != "peer-packed-stereo" ||
        settings.brokerPort !in 1..65535 || settings.peerSessionId.isBlank()) {
      return false
    }
    synchronized(lock) {
      if (snapshot.identity != null || decoderStartInFlightRouteGeneration != 0L) return false
      stagedSettings = settings.copy()
      firstFrameRouteGeneration = 0L
      attachedRouteGeneration = 0L
      snapshot = SpatialPeerProjectionRuntimeSnapshot(
          state = SpatialPeerProjectionRuntimeState.Staged,
          stagedRouteGeneration = routeGeneration,
          sourceDemand = snapshot.sourceDemand,
      )
    }
    return true
  }

  fun updateSourceDemand(required: Boolean, reason: String) {
    val identity = synchronized(lock) {
      snapshot = snapshot.copy(sourceDemand = required)
      snapshot.identity.takeIf { !required }
    }
    if (identity != null) stopExact(identity, "$reason-demand-withdrawn")
  }

  fun startAfterNativeAck(routeGeneration: Long, reason: String): Boolean {
    val settings = synchronized(lock) {
      if (snapshot.state != SpatialPeerProjectionRuntimeState.Staged ||
          snapshot.stagedRouteGeneration != routeGeneration || !snapshot.sourceDemand) return false
      snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Starting)
      decoderStartInFlightRouteGeneration = routeGeneration
      stagedSettings
    } ?: return false
    val callbacks = SpatialVideoProjectionPlaybackCallbacks(
        onFirstFrame = { promoteActive(routeGeneration, reason) },
        onError = { rejectCurrent(routeGeneration, "$reason-$it") },
        onStopped = { markStopped(routeGeneration) },
    )
    val identity = bindings.startDecoder(settings, routeGeneration, callbacks)
    if (identity == null) {
      synchronized(lock) {
        if (decoderStartInFlightRouteGeneration == routeGeneration) {
          decoderStartInFlightRouteGeneration = 0L
        }
      }
      return rejectCurrent(routeGeneration, "$reason-decoder-start")
    }
    if (identity.routeGeneration != routeGeneration || identity.decoderToken <= 0L ||
        identity.readerGeneration <= 0L || identity.role != SpatialVideoDecoderRole.ProjectionPeer) {
      retireUnacceptedReturnedIdentity(identity, routeGeneration, "$reason-identity")
      return rejectCurrent(routeGeneration, "$reason-identity")
    }
    val retained = synchronized(lock) {
      if (decoderStartInFlightRouteGeneration == routeGeneration) {
        decoderStartInFlightRouteGeneration = 0L
      }
      if (snapshot.state == SpatialPeerProjectionRuntimeState.Starting &&
          snapshot.stagedRouteGeneration == routeGeneration && snapshot.sourceDemand) {
        snapshot = snapshot.copy(identity = identity)
        true
      } else false
    }
    if (!retained) {
      retireUnacceptedReturnedIdentity(identity, routeGeneration, "$reason-invalidated")
      return false
    }
    if (!bindings.bindNativeRole(identity) || !bindings.attachCommonGraph(identity)) {
      val stopped = stopExact(identity, "$reason-attach-rejected")
      synchronized(lock) {
        if (snapshot.stagedRouteGeneration == routeGeneration) {
          snapshot = snapshot.copy(
              state = SpatialPeerProjectionRuntimeState.Rejected,
              identity = if (stopped) null else identity,
          )
        }
      }
      return false
    }
    synchronized(lock) {
      if (snapshot.identity == identity && snapshot.stagedRouteGeneration == routeGeneration) {
        attachedRouteGeneration = routeGeneration
        if (firstFrameRouteGeneration == routeGeneration) {
          snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Active)
        }
      }
    }
    return true
  }

  private fun retireUnacceptedReturnedIdentity(
      identity: SpatialPeerProjectionDecoderIdentity,
      routeGeneration: Long,
      reason: String,
  ): Boolean {
    val stopped = bindings.stopDecoder(identity)
    synchronized(lock) {
      if (decoderStartInFlightRouteGeneration == routeGeneration) {
        decoderStartInFlightRouteGeneration = 0L
      }
      if (!stopped && snapshot.identity == null) {
        snapshot = snapshot.copy(
            state = SpatialPeerProjectionRuntimeState.Rejected,
            stagedRouteGeneration = routeGeneration,
            identity = identity,
        )
      }
    }
    bindings.marker(
        "channel=spatial-projection-peer status=returned-identity-retirement " +
            "reason=${activityMarkerToken(reason)} routeGeneration=${identity.routeGeneration} " +
            "decoderToken=${identity.decoderToken} readerGeneration=${identity.readerGeneration} " +
            "stopped=$stopped"
    )
    return stopped
  }

  fun stopExact(identity: SpatialPeerProjectionDecoderIdentity, reason: String): Boolean {
    val current = synchronized(lock) { snapshot.identity }
    if (current != identity) return false
    val stopped = bindings.stopDecoder(identity)
    if (stopped) synchronized(lock) {
      if (snapshot.identity == identity) {
        snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Stopped, identity = null)
        firstFrameRouteGeneration = 0L
        attachedRouteGeneration = 0L
      }
    }
    bindings.marker(
        "channel=spatial-projection-peer status=stop reason=${activityMarkerToken(reason)} " +
            "routeGeneration=${identity.routeGeneration} decoderToken=${identity.decoderToken} " +
            "readerGeneration=${identity.readerGeneration} stopped=$stopped"
    )
    return stopped
  }

  fun retireForActivityDestroy(reason: String): Boolean {
    updateSourceDemand(false, reason)
    val identity = synchronized(lock) { snapshot.identity }
    if (identity == null) {
      synchronized(lock) {
        if (snapshot.identity == null) {
          snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Stopped)
        }
      }
      return true
    }
    return stopExact(identity, reason)
  }

  private fun promoteActive(routeGeneration: Long, reason: String) = synchronized(lock) {
    if (snapshot.stagedRouteGeneration == routeGeneration) {
      firstFrameRouteGeneration = routeGeneration
      if (snapshot.identity != null && attachedRouteGeneration == routeGeneration) {
        snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Active)
      }
      bindings.marker(
          "channel=spatial-projection-peer status=first-frame " +
              "reason=${activityMarkerToken(reason)} routeGeneration=$routeGeneration"
      )
    }
  }

  private fun rejectCurrent(routeGeneration: Long, reason: String): Boolean {
    synchronized(lock) {
      if (snapshot.stagedRouteGeneration == routeGeneration) {
        snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Rejected)
        attachedRouteGeneration = 0L
      }
    }
    bindings.marker(
        "channel=spatial-projection-peer status=rejected " +
            "reason=${activityMarkerToken(reason)} routeGeneration=$routeGeneration"
    )
    return false
  }

  private fun markStopped(routeGeneration: Long) = synchronized(lock) {
    if (snapshot.stagedRouteGeneration == routeGeneration) {
      snapshot = snapshot.copy(state = SpatialPeerProjectionRuntimeState.Stopped, identity = null)
      firstFrameRouteGeneration = 0L
      attachedRouteGeneration = 0L
    }
  }
}
