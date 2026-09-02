#pragma once

// Focused OpenXR 1.1 loader/API-layer ABI declarations used by this probe.
// Names, layouts, and numeric values are from the Khronos OpenXR headers and
// openxr_loader_negotiation.h (Apache-2.0), release 1.1.60.

#include <cstddef>
#include <cstdint>
#include <vulkan/vulkan.h>

#define XRAPI_ATTR __attribute__((visibility("default")))
#define XRAPI_CALL
#define XRAPI_PTR

#define XR_MAKE_VERSION(major, minor, patch) \
  ((((uint64_t)(major)&0xffffULL) << 48U) | (((uint64_t)(minor)&0xffffULL) << 32U) | ((uint64_t)(patch)&0xffffffffULL))

using XrVersion = uint64_t;
using XrFlags64 = uint64_t;
using XrBool32 = uint32_t;
using XrTime = int64_t;
using XrDuration = int64_t;
using XrSystemId = uint64_t;
using XrResult = int32_t;
using XrStructureType = int32_t;
using XrReferenceSpaceType = int32_t;
using XrViewConfigurationType = int32_t;
using XrViewStateFlags = XrFlags64;
using XrEnvironmentBlendMode = int32_t;
using XrInstanceCreateFlags = XrFlags64;
using XrSessionCreateFlags = XrFlags64;
using XrEnvironmentDepthProviderCreateFlagsMETA = XrFlags64;
using XrEnvironmentDepthSwapchainCreateFlagsMETA = XrFlags64;
using XrVulkanInstanceCreateFlagsKHR = XrFlags64;
using XrVulkanDeviceCreateFlagsKHR = XrFlags64;

struct XrInstance_T;
struct XrSession_T;
struct XrSpace_T;
struct XrEnvironmentDepthProviderMETA_T;
struct XrEnvironmentDepthSwapchainMETA_T;
using XrInstance = XrInstance_T*;
using XrSession = XrSession_T*;
using XrSpace = XrSpace_T*;
using XrEnvironmentDepthProviderMETA = XrEnvironmentDepthProviderMETA_T*;
using XrEnvironmentDepthSwapchainMETA = XrEnvironmentDepthSwapchainMETA_T*;

constexpr XrInstance XR_NULL_HANDLE = nullptr;
constexpr XrResult XR_SUCCESS = 0;
constexpr XrResult XR_ERROR_VALIDATION_FAILURE = -1;
constexpr XrResult XR_ERROR_RUNTIME_FAILURE = -2;
constexpr XrResult XR_ERROR_INITIALIZATION_FAILED = -6;
constexpr XrResult XR_ERROR_FUNCTION_UNSUPPORTED = -7;
constexpr XrResult XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META = 1000291000;

constexpr XrStructureType XR_TYPE_INSTANCE_CREATE_INFO = 3;
constexpr XrStructureType XR_TYPE_VIEW_LOCATE_INFO = 6;
constexpr XrStructureType XR_TYPE_VIEW = 7;
constexpr XrStructureType XR_TYPE_SESSION_CREATE_INFO = 8;
constexpr XrStructureType XR_TYPE_FRAME_END_INFO = 12;
constexpr XrStructureType XR_TYPE_VIEW_STATE = 11;
constexpr XrStructureType XR_TYPE_FRAME_WAIT_INFO = 33;
constexpr XrStructureType XR_TYPE_REFERENCE_SPACE_CREATE_INFO = 37;
constexpr XrStructureType XR_TYPE_FRAME_STATE = 44;
constexpr XrStructureType XR_TYPE_FRAME_BEGIN_INFO = 46;
constexpr XrStructureType XR_TYPE_GRAPHICS_BINDING_VULKAN_KHR = 1000025000;
constexpr XrStructureType XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR = 1000025001;
constexpr XrStructureType XR_TYPE_VULKAN_INSTANCE_CREATE_INFO_KHR = 1000090000;
constexpr XrStructureType XR_TYPE_VULKAN_DEVICE_CREATE_INFO_KHR = 1000090001;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_PROVIDER_CREATE_INFO_META = 1000291000;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_CREATE_INFO_META = 1000291001;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_STATE_META = 1000291002;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_ACQUIRE_INFO_META = 1000291003;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_VIEW_META = 1000291004;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_META = 1000291005;
constexpr XrStructureType XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_TIMESTAMP_META = 1000291008;
constexpr XrReferenceSpaceType XR_REFERENCE_SPACE_TYPE_LOCAL = 2;

constexpr uint32_t XR_MAX_APPLICATION_NAME_SIZE = 128;
constexpr uint32_t XR_MAX_ENGINE_NAME_SIZE = 128;
constexpr uint32_t XR_MAX_API_LAYER_NAME_SIZE = 256;
constexpr uint32_t XR_MAX_API_LAYER_SETTINGS_PATH_SIZE = 512;
constexpr uint32_t XR_CURRENT_LOADER_API_LAYER_VERSION = 1;
constexpr uint32_t XR_API_LAYER_INFO_STRUCT_VERSION = 1;
constexpr XrVersion XR_CURRENT_API_VERSION = XR_MAKE_VERSION(1, 1, 60);

struct XrVector3f {
  float x;
  float y;
  float z;
};

struct XrBaseInStructure {
  XrStructureType type;
  const XrBaseInStructure* next;
};

struct XrBaseOutStructure {
  XrStructureType type;
  XrBaseOutStructure* next;
};

struct XrQuaternionf {
  float x;
  float y;
  float z;
  float w;
};

struct XrPosef {
  XrQuaternionf orientation;
  XrVector3f position;
};

struct XrFovf {
  float angleLeft;
  float angleRight;
  float angleUp;
  float angleDown;
};

struct XrApplicationInfo {
  char applicationName[XR_MAX_APPLICATION_NAME_SIZE];
  uint32_t applicationVersion;
  char engineName[XR_MAX_ENGINE_NAME_SIZE];
  uint32_t engineVersion;
  XrVersion apiVersion;
};

struct XrInstanceCreateInfo {
  XrStructureType type;
  const void* next;
  XrInstanceCreateFlags createFlags;
  XrApplicationInfo applicationInfo;
  uint32_t enabledApiLayerCount;
  const char* const* enabledApiLayerNames;
  uint32_t enabledExtensionCount;
  const char* const* enabledExtensionNames;
};

struct XrSessionCreateInfo {
  XrStructureType type;
  const void* next;
  XrSessionCreateFlags createFlags;
  XrSystemId systemId;
};

struct XrVulkanInstanceCreateInfoKHR {
  XrStructureType type;
  const void* next;
  XrSystemId systemId;
  XrVulkanInstanceCreateFlagsKHR createFlags;
  PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
  const VkInstanceCreateInfo* vulkanCreateInfo;
  const VkAllocationCallbacks* vulkanAllocator;
};

struct XrVulkanDeviceCreateInfoKHR {
  XrStructureType type;
  const void* next;
  XrSystemId systemId;
  XrVulkanDeviceCreateFlagsKHR createFlags;
  PFN_vkGetInstanceProcAddr pfnGetInstanceProcAddr;
  VkPhysicalDevice vulkanPhysicalDevice;
  const VkDeviceCreateInfo* vulkanCreateInfo;
  const VkAllocationCallbacks* vulkanAllocator;
};

struct XrGraphicsBindingVulkanKHR {
  XrStructureType type;
  const void* next;
  VkInstance instance;
  VkPhysicalDevice physicalDevice;
  VkDevice device;
  uint32_t queueFamilyIndex;
  uint32_t queueIndex;
};

struct XrSwapchainImageVulkanKHR {
  XrStructureType type;
  void* next;
  VkImage image;
};

struct XrReferenceSpaceCreateInfo {
  XrStructureType type;
  const void* next;
  XrReferenceSpaceType referenceSpaceType;
  XrPosef poseInReferenceSpace;
};

struct XrViewLocateInfo {
  XrStructureType type;
  const void* next;
  XrViewConfigurationType viewConfigurationType;
  XrTime displayTime;
  XrSpace space;
};

struct XrViewState {
  XrStructureType type;
  void* next;
  XrViewStateFlags viewStateFlags;
};

struct XrView {
  XrStructureType type;
  void* next;
  XrPosef pose;
  XrFovf fov;
};

struct XrFrameWaitInfo {
  XrStructureType type;
  const void* next;
};

struct XrFrameState {
  XrStructureType type;
  void* next;
  XrTime predictedDisplayTime;
  XrDuration predictedDisplayPeriod;
  XrBool32 shouldRender;
};

struct XrFrameBeginInfo {
  XrStructureType type;
  const void* next;
};

struct XrCompositionLayerBaseHeader;

struct XrFrameEndInfo {
  XrStructureType type;
  const void* next;
  XrTime displayTime;
  XrEnvironmentBlendMode environmentBlendMode;
  uint32_t layerCount;
  const XrCompositionLayerBaseHeader* const* layers;
};

struct XrEnvironmentDepthProviderCreateInfoMETA {
  XrStructureType type;
  const void* next;
  XrEnvironmentDepthProviderCreateFlagsMETA createFlags;
};

struct XrEnvironmentDepthSwapchainCreateInfoMETA {
  XrStructureType type;
  const void* next;
  XrEnvironmentDepthSwapchainCreateFlagsMETA createFlags;
};

struct XrEnvironmentDepthSwapchainStateMETA {
  XrStructureType type;
  void* next;
  uint32_t width;
  uint32_t height;
};

struct XrEnvironmentDepthImageAcquireInfoMETA {
  XrStructureType type;
  const void* next;
  XrSpace space;
  XrTime displayTime;
};

struct XrEnvironmentDepthImageViewMETA {
  XrStructureType type;
  void* next;
  XrFovf fov;
  XrPosef pose;
};

struct XrEnvironmentDepthImageMETA {
  XrStructureType type;
  void* next;
  uint32_t swapchainIndex;
  float nearZ;
  float farZ;
  XrEnvironmentDepthImageViewMETA views[2];
};

struct XrEnvironmentDepthImageTimestampMETA {
  XrStructureType type;
  void* next;
  XrTime captureTime;
};

struct XrLoaderInitInfoBaseHeaderKHR {
  XrStructureType type;
  const void* next;
};

using PFN_xrVoidFunction = void(XRAPI_PTR*)();
using PFN_xrGetInstanceProcAddr = XrResult(XRAPI_PTR*)(XrInstance, const char*, PFN_xrVoidFunction*);
using PFN_xrDestroyInstance = XrResult(XRAPI_PTR*)(XrInstance);
using PFN_xrCreateVulkanInstanceKHR = XrResult(XRAPI_PTR*)(
    XrInstance, const XrVulkanInstanceCreateInfoKHR*, VkInstance*, VkResult*);
using PFN_xrCreateVulkanDeviceKHR = XrResult(XRAPI_PTR*)(
    XrInstance, const XrVulkanDeviceCreateInfoKHR*, VkDevice*, VkResult*);
using PFN_xrCreateSession = XrResult(XRAPI_PTR*)(XrInstance, const XrSessionCreateInfo*, XrSession*);
using PFN_xrDestroySession = XrResult(XRAPI_PTR*)(XrSession);
using PFN_xrCreateReferenceSpace = XrResult(XRAPI_PTR*)(XrSession, const XrReferenceSpaceCreateInfo*, XrSpace*);
using PFN_xrDestroySpace = XrResult(XRAPI_PTR*)(XrSpace);
using PFN_xrLocateViews = XrResult(XRAPI_PTR*)(
    XrSession,
    const XrViewLocateInfo*,
    XrViewState*,
    uint32_t,
    uint32_t*,
    XrView*);
using PFN_xrWaitFrame = XrResult(XRAPI_PTR*)(XrSession, const XrFrameWaitInfo*, XrFrameState*);
using PFN_xrBeginFrame = XrResult(XRAPI_PTR*)(XrSession, const XrFrameBeginInfo*);
using PFN_xrEndFrame = XrResult(XRAPI_PTR*)(XrSession, const XrFrameEndInfo*);
using PFN_xrInitializeLoaderKHR = XrResult(XRAPI_PTR*)(const XrLoaderInitInfoBaseHeaderKHR*);
using PFN_xrCreateEnvironmentDepthProviderMETA = XrResult(XRAPI_PTR*)(
    XrSession, const XrEnvironmentDepthProviderCreateInfoMETA*, XrEnvironmentDepthProviderMETA*);
using PFN_xrDestroyEnvironmentDepthProviderMETA = XrResult(XRAPI_PTR*)(XrEnvironmentDepthProviderMETA);
using PFN_xrStartEnvironmentDepthProviderMETA = XrResult(XRAPI_PTR*)(XrEnvironmentDepthProviderMETA);
using PFN_xrStopEnvironmentDepthProviderMETA = XrResult(XRAPI_PTR*)(XrEnvironmentDepthProviderMETA);
using PFN_xrCreateEnvironmentDepthSwapchainMETA = XrResult(XRAPI_PTR*)(
    XrEnvironmentDepthProviderMETA,
    const XrEnvironmentDepthSwapchainCreateInfoMETA*,
    XrEnvironmentDepthSwapchainMETA*);
using PFN_xrDestroyEnvironmentDepthSwapchainMETA = XrResult(XRAPI_PTR*)(XrEnvironmentDepthSwapchainMETA);
using PFN_xrEnumerateEnvironmentDepthSwapchainImagesMETA = XrResult(XRAPI_PTR*)(
    XrEnvironmentDepthSwapchainMETA, uint32_t, uint32_t*, void*);
using PFN_xrGetEnvironmentDepthSwapchainStateMETA = XrResult(XRAPI_PTR*)(
    XrEnvironmentDepthSwapchainMETA, XrEnvironmentDepthSwapchainStateMETA*);
using PFN_xrAcquireEnvironmentDepthImageMETA = XrResult(XRAPI_PTR*)(
    XrEnvironmentDepthProviderMETA,
    const XrEnvironmentDepthImageAcquireInfoMETA*,
    XrEnvironmentDepthImageMETA*);

enum XrLoaderInterfaceStructs : int32_t {
  XR_LOADER_INTERFACE_STRUCT_UNINTIALIZED = 0,
  XR_LOADER_INTERFACE_STRUCT_LOADER_INFO = 1,
  XR_LOADER_INTERFACE_STRUCT_API_LAYER_REQUEST = 2,
  XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST = 3,
  XR_LOADER_INTERFACE_STRUCT_API_LAYER_CREATE_INFO = 4,
  XR_LOADER_INTERFACE_STRUCT_API_LAYER_NEXT_INFO = 5,
  XR_LOADER_INTERFACE_STRUCT_MAX_ENUM = 0x7fffffff
};

struct XrNegotiateLoaderInfo {
  XrLoaderInterfaceStructs structType;
  uint32_t structVersion;
  size_t structSize;
  uint32_t minInterfaceVersion;
  uint32_t maxInterfaceVersion;
  XrVersion minApiVersion;
  XrVersion maxApiVersion;
};

struct XrApiLayerCreateInfo;
using PFN_xrCreateApiLayerInstance = XrResult(XRAPI_PTR*)(
    const XrInstanceCreateInfo*, const XrApiLayerCreateInfo*, XrInstance*);

struct XrNegotiateApiLayerRequest {
  XrLoaderInterfaceStructs structType;
  uint32_t structVersion;
  size_t structSize;
  uint32_t layerInterfaceVersion;
  XrVersion layerApiVersion;
  PFN_xrGetInstanceProcAddr getInstanceProcAddr;
  PFN_xrCreateApiLayerInstance createApiLayerInstance;
};

struct XrApiLayerNextInfo {
  XrLoaderInterfaceStructs structType;
  uint32_t structVersion;
  size_t structSize;
  char layerName[XR_MAX_API_LAYER_NAME_SIZE];
  PFN_xrGetInstanceProcAddr nextGetInstanceProcAddr;
  PFN_xrCreateApiLayerInstance nextCreateApiLayerInstance;
  XrApiLayerNextInfo* next;
};

struct XrApiLayerCreateInfo {
  XrLoaderInterfaceStructs structType;
  uint32_t structVersion;
  size_t structSize;
  void* loaderInstance;
  char settingsFileLocation[XR_MAX_API_LAYER_SETTINGS_PATH_SIZE];
  XrApiLayerNextInfo* nextInfo;
};

static_assert(sizeof(XrPosef) == 28);
static_assert(offsetof(XrInstanceCreateInfo, applicationInfo) == 24);
static_assert(sizeof(XrEnvironmentDepthImageViewMETA) == 64);
