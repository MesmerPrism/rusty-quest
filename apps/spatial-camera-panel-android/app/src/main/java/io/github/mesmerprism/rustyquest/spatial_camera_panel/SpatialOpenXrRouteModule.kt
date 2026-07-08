package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal const val OPENXR_ERROR_HANDLE_INVALID = -12
internal const val NATIVE_RECEIPT_LIBRARY = "spatial_camera_panel_native_receipt"
internal const val SPATIAL_MULTIMODAL_INPUT_ENABLED_PROPERTY =
    "debug.rustyquest.spatial.multimodal_input.enabled"

private const val SPATIAL_MULTIMODAL_INPUT_DEFAULT_ENABLED = false
private const val XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION =
    "XR_META_simultaneous_hands_and_controllers"
private const val XR_META_DETACHED_CONTROLLERS_EXTENSION = "XR_META_detached_controllers"
private const val XR_FB_PASSTHROUGH_EXTENSION = "XR_FB_passthrough"
private const val XR_META_ENVIRONMENT_DEPTH_EXTENSION = "XR_META_environment_depth"
private val SPATIAL_PASSTHROUGH_REQUIRED_OPENXR_EXTENSIONS =
    listOf(XR_FB_PASSTHROUGH_EXTENSION, XR_META_ENVIRONMENT_DEPTH_EXTENSION)
private val SPATIAL_MULTIMODAL_REQUIRED_OPENXR_EXTENSIONS =
    listOf(
        XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION,
        XR_META_DETACHED_CONTROLLERS_EXTENSION,
    )

private const val NATIVE_SPATIAL_CONTROLLER_ACTION_SET_ATTACHED_BIT = 1L shl 8
private const val SPATIAL_MULTIMODAL_INPUT_SUPPORTED_BIT = 1L shl 8
private const val SPATIAL_MULTIMODAL_INPUT_RESUME_RESOLVED_BIT = 1L shl 9
private const val SPATIAL_MULTIMODAL_INPUT_RESUME_SUCCEEDED_BIT = 1L shl 10
private const val SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT = 1L shl 10
private const val SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT = 1L shl 22
private const val SPATIAL_ENVIRONMENT_DEPTH_ACQUIRE_THREAD_STARTED_BIT = 1L shl 23
private const val NATIVE_RECEIPT_OPENXR_INSTANCE_BIT = 1L shl 1
private const val NATIVE_RECEIPT_OPENXR_SESSION_BIT = 1L shl 2
private const val NATIVE_RECEIPT_OPENXR_GET_PROC_BIT = 1L shl 3
private const val NATIVE_RECEIPT_PANEL_SURFACE_BIT = 1L shl 4
private const val NATIVE_RECEIPT_OPENXR_GET_PROC_CALLABLE_BIT = 1L shl 5
private const val NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED_BIT = 1L shl 6
private const val NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED_BIT = 1L shl 7
private const val NATIVE_RECEIPT_XR_GET_SYSTEM_RESOLVED_BIT = 1L shl 8
private const val NATIVE_RECEIPT_XR_GET_SYSTEM_SUCCEEDED_BIT = 1L shl 9
private const val NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED_BIT = 1L shl 10
private const val NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED_BIT = 1L shl 11
private const val NATIVE_RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED_BIT = 1L shl 12
private const val NATIVE_RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED_BIT = 1L shl 13
private const val NATIVE_RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED_BIT = 1L shl 14
private const val NATIVE_RECEIPT_VK_INSTANCE_CREATED_BIT = 1L shl 15
private const val NATIVE_RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED_BIT = 1L shl 16
private const val NATIVE_RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND_BIT = 1L shl 17
private const val NATIVE_RECEIPT_VK_DEVICE_CREATED_BIT = 1L shl 18
private const val NATIVE_RECEIPT_VK_QUEUE_OBTAINED_BIT = 1L shl 19
private const val NATIVE_RECEIPT_VK_OBJECTS_DESTROYED_BIT = 1L shl 20

internal object SpatialOpenXrRouteModule {
  const val MODULE_ID = "spatial-openxr-route-policy"

  fun spatialMultimodalInputEnabled(rawValue: Boolean?): Boolean =
      rawValue ?: SPATIAL_MULTIMODAL_INPUT_DEFAULT_ENABLED

  fun spatialMultimodalRequiredOpenXrExtensions(multimodalEnabled: Boolean): List<String> =
      if (multimodalEnabled) {
        SPATIAL_MULTIMODAL_REQUIRED_OPENXR_EXTENSIONS
      } else {
        emptyList()
      }

  fun spatialRequiredOpenXrExtensions(multimodalEnabled: Boolean): List<String> =
      (SPATIAL_PASSTHROUGH_REQUIRED_OPENXR_EXTENSIONS +
              spatialMultimodalRequiredOpenXrExtensions(multimodalEnabled))
          .distinct()

  fun spatialRequiredOpenXrExtensionMarker(multimodalEnabled: Boolean): String =
      spatialRequiredOpenXrExtensions(multimodalEnabled)
          .ifEmpty { listOf("none") }
          .joinToString(";")

  fun nativeInteropReceiptUnavailable(error: String): NativeInteropReceiptResult =
      nativeInteropReceiptFailure(status = "library-unavailable", error = error)

  fun nativeInteropReceiptCallFailed(error: String): NativeInteropReceiptResult =
      nativeInteropReceiptFailure(status = "call-failed", error = error)

  fun nativeInteropReceiptReceived(mask: Long): NativeInteropReceiptResult =
      NativeInteropReceiptResult(
          status = "received",
          mask = mask,
          openXrInstanceHandleNonZero = mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_INSTANCE_BIT),
          openXrSessionHandleNonZero = mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_SESSION_BIT),
          openXrGetInstanceProcAddrHandleNonZero =
              mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_GET_PROC_BIT),
          openXrGetInstanceProcAddrCallable =
              mask.hasReceiptBit(NATIVE_RECEIPT_OPENXR_GET_PROC_CALLABLE_BIT),
          xrGetInstancePropertiesResolved =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_RESOLVED_BIT),
          xrGetInstancePropertiesSucceeded =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_INSTANCE_PROPERTIES_SUCCEEDED_BIT),
          xrGetSystemResolved = mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_SYSTEM_RESOLVED_BIT),
          xrGetSystemSucceeded = mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_SYSTEM_SUCCEEDED_BIT),
          xrVulkanGraphicsRequirements2Resolved =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_RESOLVED_BIT),
          xrVulkanGraphicsRequirements2Succeeded =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_VULKAN_REQUIREMENTS2_SUCCEEDED_BIT),
          xrCreateVulkanInstanceResolved =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_CREATE_VULKAN_INSTANCE_RESOLVED_BIT),
          xrGetVulkanGraphicsDevice2Resolved =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_GET_VULKAN_GRAPHICS_DEVICE2_RESOLVED_BIT),
          xrCreateVulkanDeviceResolved =
              mask.hasReceiptBit(NATIVE_RECEIPT_XR_CREATE_VULKAN_DEVICE_RESOLVED_BIT),
          vkInstanceCreated = mask.hasReceiptBit(NATIVE_RECEIPT_VK_INSTANCE_CREATED_BIT),
          vkGraphicsDeviceObtained =
              mask.hasReceiptBit(NATIVE_RECEIPT_VK_GRAPHICS_DEVICE_OBTAINED_BIT),
          vkGraphicsComputeQueueFound =
              mask.hasReceiptBit(NATIVE_RECEIPT_VK_GRAPHICS_COMPUTE_QUEUE_FOUND_BIT),
          vkDeviceCreated = mask.hasReceiptBit(NATIVE_RECEIPT_VK_DEVICE_CREATED_BIT),
          vkQueueObtained = mask.hasReceiptBit(NATIVE_RECEIPT_VK_QUEUE_OBTAINED_BIT),
          vkObjectsDestroyed = mask.hasReceiptBit(NATIVE_RECEIPT_VK_OBJECTS_DESTROYED_BIT),
          surfaceValid = mask.hasReceiptBit(NATIVE_RECEIPT_PANEL_SURFACE_BIT),
          error = "none",
      )

  fun nativeSpatialControllerActionSetAttached(mask: Long): Boolean =
      mask.hasReceiptBit(NATIVE_SPATIAL_CONTROLLER_ACTION_SET_ATTACHED_BIT)

  fun nativePassthroughLayerActive(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_NATIVE_PASSTHROUGH_LAYER_ACTIVE_BIT)

  fun spatialEnvironmentDepthProviderStarted(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_ENVIRONMENT_DEPTH_PROVIDER_STARTED_BIT)

  fun spatialEnvironmentDepthAcquireThreadStarted(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_ENVIRONMENT_DEPTH_ACQUIRE_THREAD_STARTED_BIT)

  fun spatialMultimodalInputSupported(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_SUPPORTED_BIT)

  fun spatialMultimodalInputResumeResolved(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_RESUME_RESOLVED_BIT)

  fun spatialMultimodalInputResumeSucceeded(mask: Long): Boolean =
      mask.hasReceiptBit(SPATIAL_MULTIMODAL_INPUT_RESUME_SUCCEEDED_BIT)

  private fun nativeInteropReceiptFailure(
      status: String,
      error: String,
  ): NativeInteropReceiptResult =
      NativeInteropReceiptResult(
          status = status,
          mask = 0L,
          openXrInstanceHandleNonZero = false,
          openXrSessionHandleNonZero = false,
          openXrGetInstanceProcAddrHandleNonZero = false,
          openXrGetInstanceProcAddrCallable = false,
          xrGetInstancePropertiesResolved = false,
          xrGetInstancePropertiesSucceeded = false,
          xrGetSystemResolved = false,
          xrGetSystemSucceeded = false,
          xrVulkanGraphicsRequirements2Resolved = false,
          xrVulkanGraphicsRequirements2Succeeded = false,
          xrCreateVulkanInstanceResolved = false,
          xrGetVulkanGraphicsDevice2Resolved = false,
          xrCreateVulkanDeviceResolved = false,
          vkInstanceCreated = false,
          vkGraphicsDeviceObtained = false,
          vkGraphicsComputeQueueFound = false,
          vkDeviceCreated = false,
          vkQueueObtained = false,
          vkObjectsDestroyed = false,
          surfaceValid = false,
          error = error,
      )
}
