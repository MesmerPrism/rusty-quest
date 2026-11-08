// Real host dlopen/dlclose, actual production DSO exports, fake Vulkan leaves.
#define VK_NO_PROTOTYPES
#include <dlfcn.h>
#include <unistd.h>
#include <vulkan/vk_layer.h>

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <stdexcept>
#include <type_traits>

namespace {
void require(bool value, const char* message) {
  if (!value) throw std::runtime_error(message);
}

struct Handle {
  void* dispatch;
};
int instance_keys[2]{}, device_key = 0;
Handle instances[2]{{&instance_keys[0]}, {&instance_keys[1]}};
Handle physical{&instance_keys[1]}, device{&device_key};
unsigned made_instances = 0, freed_instances = 0, made_devices = 0,
         freed_devices = 0;
unsigned made_images = 0, freed_images = 0;
std::array<bool, 2> live_instances{};
bool live_device = false, live_image = false;

template <class T>
T handle(uintptr_t value) {
  if constexpr (std::is_pointer_v<T>)
    return reinterpret_cast<T>(value);
  else
    return static_cast<T>(value);
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance, const char*);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice, const char*);

VKAPI_ATTR VkResult VKAPI_CALL
create_instance(const VkInstanceCreateInfo* info,
                const VkAllocationCallbacks* allocator, VkInstance* output) {
  require(allocator == nullptr && info && output && made_instances < 2,
          "instance forwarding changed");
  const auto* chain =
      static_cast<const VkLayerInstanceCreateInfo*>(info->pNext);
  require(chain && chain->u.pLayerInfo == nullptr,
          "instance loader chain was not consumed");
  live_instances[made_instances] = true;
  *output = reinterpret_cast<VkInstance>(&instances[made_instances++]);
  return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL
destroy_instance(VkInstance value, const VkAllocationCallbacks* allocator) {
  require(allocator == nullptr, "instance allocator changed");
  unsigned index = value == reinterpret_cast<VkInstance>(&instances[0]) ? 0 : 1;
  require(value == reinterpret_cast<VkInstance>(&instances[index]) &&
              live_instances[index],
          "instance destroyed twice");
  require(!live_device && !live_image,
          "GPU-shaped fake object retained at instance destruction");
  live_instances[index] = false;
  instances[index].dispatch = nullptr;
  ++freed_instances;
}

VKAPI_ATTR VkResult VKAPI_CALL
create_device(VkPhysicalDevice value, const VkDeviceCreateInfo* info,
              const VkAllocationCallbacks* allocator, VkDevice* output) {
  require(value == reinterpret_cast<VkPhysicalDevice>(&physical) &&
              live_instances[1] && info && allocator == nullptr && output &&
              !live_device,
          "device forwarding changed");
  const auto* chain = static_cast<const VkLayerDeviceCreateInfo*>(info->pNext);
  require(chain && chain->u.pLayerInfo == nullptr,
          "device loader chain was not consumed");
  live_device = true;
  ++made_devices;
  *output = reinterpret_cast<VkDevice>(&device);
  return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL
destroy_device(VkDevice value, const VkAllocationCallbacks* allocator) {
  require(value == reinterpret_cast<VkDevice>(&device) &&
              allocator == nullptr && live_device && !live_image,
          "device lifetime changed");
  live_device = false;
  device.dispatch = nullptr;
  ++freed_devices;
}

VKAPI_ATTR VkResult VKAPI_CALL
create_image(VkDevice value, const VkImageCreateInfo* info,
             const VkAllocationCallbacks* allocator, VkImage* output) {
  require(value == reinterpret_cast<VkDevice>(&device) && live_device && info &&
              output && info->extent.width == 64 && info->extent.height == 32 &&
              allocator == nullptr && !live_image,
          "image arguments or forwarding changed");
  live_image = true;
  ++made_images;
  *output = handle<VkImage>(0x551);
  return VK_SUCCESS;
}

VKAPI_ATTR void VKAPI_CALL destroy_image(
    VkDevice value, VkImage image, const VkAllocationCallbacks* allocator) {
  require(value == reinterpret_cast<VkDevice>(&device) &&
              image == handle<VkImage>(0x551) && allocator == nullptr &&
              live_image,
          "image lifetime changed");
  live_image = false;
  ++freed_images;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice, const char* name) {
  if (!name) return nullptr;
  if (std::strcmp(name, "vkGetDeviceProcAddr") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(gdpa);
  if (std::strcmp(name, "vkDestroyDevice") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(destroy_device);
  if (std::strcmp(name, "vkCreateImage") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(create_image);
  if (std::strcmp(name, "vkDestroyImage") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(destroy_image);
  return nullptr;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance, const char* name) {
  if (!name) return nullptr;
  if (std::strcmp(name, "vkGetInstanceProcAddr") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(gipa);
  if (std::strcmp(name, "vkCreateInstance") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(create_instance);
  if (std::strcmp(name, "vkDestroyInstance") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(destroy_instance);
  if (std::strcmp(name, "vkCreateDevice") == 0)
    return reinterpret_cast<PFN_vkVoidFunction>(create_device);
  return nullptr;
}

class Layer {
 public:
  explicit Layer(const char* path)
      : module_(dlopen(path, RTLD_NOW | RTLD_LOCAL)) {
    if (!module_) throw std::runtime_error(dlerror());
  }
  template <class T>
  T symbol(const char* name) {
    void* value = dlsym(module_, name);
    require(value != nullptr, "actual layer export unavailable");
    return reinterpret_cast<T>(value);
  }
  void close() {
    require(dlclose(module_) == 0, "dlclose failed");
    module_ = nullptr;
  }

 private:
  void* module_;
};

bool resident(const char* path) {
  void* existing = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
  if (!existing) return false;
  require(dlclose(existing) == 0, "NOLOAD reference close failed");
  return true;
}

VkInstance make_instance(Layer& layer) {
  VkLayerInstanceLink link{};
  link.pfnNextGetInstanceProcAddr = gipa;
  VkLayerInstanceCreateInfo chain{};
  chain.sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
  chain.function = VK_LAYER_LINK_INFO;
  chain.u.pLayerInfo = &link;
  VkInstanceCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  info.pNext = &chain;
  VkInstance result{};
  require(layer.symbol<PFN_vkCreateInstance>("vkCreateInstance")(
              &info, nullptr, &result) == VK_SUCCESS,
          "actual instance wrapper failed");
  return result;
}

VkDevice make_device(Layer& layer) {
  VkLayerDeviceLink link{};
  link.pfnNextGetInstanceProcAddr = gipa;
  link.pfnNextGetDeviceProcAddr = gdpa;
  VkLayerDeviceCreateInfo chain{};
  chain.sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
  chain.function = VK_LAYER_LINK_INFO;
  chain.u.pLayerInfo = &link;
  VkDeviceCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  info.pNext = &chain;
  VkDevice result{};
  require(layer.symbol<PFN_vkCreateDevice>("vkCreateDevice")(
              reinterpret_cast<VkPhysicalDevice>(&physical), &info, nullptr,
              &result) == VK_SUCCESS,
          "actual device wrapper failed");
  return result;
}
}  // namespace

int main(int argc, char** argv) {
  try {
    require(argc == 6,
            "arguments: DSO cmdline sink selected expected-resident");
    const bool expected_resident = std::strcmp(argv[5], "1") == 0;
    {
      std::ofstream cmdline(argv[2], std::ios::binary);
      cmdline.write("runtime.host", sizeof("runtime.host"));
      require(cmdline.good(), "fixture cmdline could not be written");
    }
    require(setenv("RQVP_HOST_CMDLINE", argv[2], 1) == 0 &&
                setenv("RQVP_HOST_SINK", argv[3], 1) == 0 &&
                setenv("RQVP_HOST_SELECTED", argv[4], 1) == 0,
            "host leaves setup failed");
    Layer probe(argv[1]);
    const auto first = make_instance(probe);
    probe.symbol<PFN_vkDestroyInstance>("vkDestroyInstance")(first, nullptr);
    require(freed_instances == 1 && !live_instances[0],
            "probe instance retained");
    probe.close();
    const bool after_probe = resident(argv[1]);
    require(after_probe == expected_resident,
            "observed DSO residency did not match ELF variant");
    // An initially-off resident image must not reopen admission after an
    // ambient change.
    if (std::strcmp(argv[4], "0") == 0)
      require(setenv("RQVP_HOST_SELECTED", "1", 1) == 0, "toggle failed");
    Layer sdk(argv[1]);
    const auto second = make_instance(sdk);
    const auto made_device = make_device(sdk);
    VkImageCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = VK_FORMAT_R8G8B8A8_UNORM;
    info.extent = {64, 32, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    VkImage image{};
    require(sdk.symbol<PFN_vkCreateImage>("vkCreateImage")(
                made_device, &info, nullptr, &image) == VK_SUCCESS,
            "actual image wrapper failed");
    sdk.symbol<PFN_vkDestroyImage>("vkDestroyImage")(made_device, image,
                                                     nullptr);
    sdk.symbol<PFN_vkDestroyDevice>("vkDestroyDevice")(made_device, nullptr);
    sdk.symbol<PFN_vkDestroyInstance>("vkDestroyInstance")(second, nullptr);
    sdk.close();
    require(made_instances == 2 && freed_instances == 2 && made_devices == 1 &&
                freed_devices == 1 && made_images == 1 && freed_images == 1 &&
                !live_instances[0] && !live_instances[1] && !live_device &&
                !live_image,
            "diagnostic residency changed fake GPU-shaped object cleanup");
    std::printf(
        "{\"pid\":%ld,\"resident_after_probe_close\":%s,\"resident_after_sdk_"
        "close\":%s,"
        "\"instances_created\":2,\"instances_destroyed\":2,\"devices_created\":"
        "1,"
        "\"devices_destroyed\":1,\"images_created\":1,\"images_destroyed\":1,"
        "\"live_gpu_objects\":0}\n",
        static_cast<long>(getpid()), after_probe ? "true" : "false",
        resident(argv[1]) ? "true" : "false");
    return 0;
  } catch (const std::exception& failure) {
    std::fprintf(stderr, "%s\n", failure.what());
    return 2;
  }
}
