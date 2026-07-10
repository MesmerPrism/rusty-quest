package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialNativeInputBootstrapBindings(
    val receiptLibraryLoaded: () -> Boolean,
    val multimodalInputEnabled: () -> Boolean,
    val controllerActionsEnabled: () -> Boolean,
    val requestMultimodalInput: (Long, Long, Long) -> Long,
    val startControllerActions: (Long, Long, Long) -> Long,
    val marker: (String) -> Unit,
)

internal class SpatialNativeInputBootstrapCoordinator(
    private val bindings: SpatialNativeInputBootstrapBindings,
) {
  var controllerActionsStarted = false
    private set

  var controllerActionsStartMask = 0L
    private set

  private var multimodalInputRequested = false
  private var multimodalInputRequestMask = 0L

  fun requestMultimodalInputIfReady(probe: SpatialNativeInteropProbe, phase: String) {
    if (multimodalInputRequested || !bindings.receiptLibraryLoaded()) {
      return
    }
    if (!bindings.multimodalInputEnabled()) {
      multimodalInputRequested = true
      bindings.marker(SpatialOpenXrRouteModule.spatialMultimodalInputDisabledMarker(phase))
      return
    }
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(SpatialOpenXrRouteModule.spatialMultimodalInputDeferredMarker(phase))
      return
    }
    val requestMask =
        runCatching {
              bindings.requestMultimodalInput(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialOpenXrRouteModule.spatialMultimodalInputErrorMarker(
                      phase,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                  )
              )
              0L
            }
    multimodalInputRequested = true
    multimodalInputRequestMask = requestMask
    bindings.marker(SpatialOpenXrRouteModule.spatialMultimodalInputResultMarker(phase, requestMask))
  }

  fun startControllerActionsIfReady(probe: SpatialNativeInteropProbe, phase: String) {
    if (!bindings.controllerActionsEnabled()) {
      bindings.marker(SpatialOpenXrRouteModule.nativeControllerActionsDisabledMarker(phase))
      return
    }
    if (controllerActionsStarted || !bindings.receiptLibraryLoaded()) {
      return
    }
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      bindings.marker(SpatialOpenXrRouteModule.nativeControllerActionsStartDeferredMarker(phase))
      return
    }
    val startMask =
        runCatching {
              bindings.startControllerActions(
                  probe.openXrInstanceHandle,
                  probe.openXrSessionHandle,
                  probe.openXrGetInstanceProcAddrHandle,
              )
            }
            .getOrElse { throwable ->
              bindings.marker(
                  SpatialOpenXrRouteModule.nativeControllerActionsStartErrorMarker(
                      phase,
                      throwable.javaClass.simpleName,
                      throwable.message ?: "none",
                  )
              )
              0L
            }
    controllerActionsStartMask = startMask
    controllerActionsStarted =
        SpatialOpenXrRouteModule.nativeSpatialControllerActionSetAttached(startMask)
    bindings.marker(
        SpatialOpenXrRouteModule.nativeControllerActionsStartResultMarker(
            phase,
            startMask,
            controllerActionsStarted,
        )
    )
  }

  fun disableControllerActions() {
    controllerActionsStarted = false
  }

  companion object {
    const val MODULE_ID = "spatial-native-input-bootstrap-coordinator"
  }
}
