// Real production wrappers, fake external Vulkan leaf functions only.
#define RQ_VULKAN_PROVENANCE_APP_ID "org.example.rqvp.fixture"
#include "vulkan_provenance_layer.cpp"
#include <cassert>
#include <iostream>
#include <string>

namespace fake {
struct Handle { void* dispatch; int id; };
int instanceKeys[2]{}, deviceKeys[2]{};
Handle instances[2]{{&instanceKeys[0],0},{&instanceKeys[1],1}};
Handle physicals[2]{{&instanceKeys[0],0},{&instanceKeys[1],1}};
Handle devices[2]{{&deviceKeys[0],0},{&deviceKeys[1],1}};
Handle commands[2]{{&deviceKeys[0],0},{&deviceKeys[1],1}};
int nextInstance = 0, imagesA = 0, imagesB = 0, forwarded = 0;
VkResult createResult = VK_SUCCESS;
const void* expectedInfo = nullptr;
const VkAllocationCallbacks* expectedAllocator = nullptr;
const void* expectedPNext = nullptr;
const VkWriteDescriptorSet* expectedWrites = nullptr;
const VkCopyDescriptorSet* expectedCopies = nullptr;
const VkBindImageMemoryInfo* expectedBinds = nullptr;
const void* expectedData = nullptr;
std::string trace;
bool sink(const char* bytes, size_t count, void*) { trace.append(bytes,count); return true; }
template<class T, class U> T h(U value) { return reinterpret_cast<T>(static_cast<uintptr_t>(value)); }
int id(VkDevice device) { return reinterpret_cast<Handle*>(device)->id; }
void unknownA() {} void unknownB() {}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance, const char*);
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice, const char*);
VKAPI_ATTR VkResult VKAPI_CALL createInstance(const VkInstanceCreateInfo* info, const VkAllocationCallbacks* allocator, VkInstance* output) {
  assert(info == expectedInfo && info->pNext == expectedPNext && allocator == expectedAllocator);
  assert(reinterpret_cast<const VkLayerInstanceCreateInfo*>(info->pNext)->u.pLayerInfo == nullptr);
  // Reentry proves dispatchMutex is not held over the downstream constructor.
  assert(vkGetInstanceProcAddr(VK_NULL_HANDLE,"vkUnknownGlobal") == reinterpret_cast<PFN_vkVoidFunction>(unknownA));
  if (createResult == VK_SUCCESS) *output = reinterpret_cast<VkInstance>(&instances[nextInstance++]);
  return createResult;
}
VKAPI_ATTR void VKAPI_CALL destroyInstance(VkInstance instance, const VkAllocationCallbacks* allocator) {
  assert(allocator == expectedAllocator);
  assert(vkGetInstanceProcAddr(instance,"vkUnknown") != nullptr);
  reinterpret_cast<Handle*>(instance)->dispatch = nullptr; // downstream frees/invalidates dispatch storage
  ++forwarded;
}
VKAPI_ATTR VkResult VKAPI_CALL createDevice(VkPhysicalDevice physical, const VkDeviceCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkDevice* output) {
  assert(info == expectedInfo && info->pNext == expectedPNext && allocator == expectedAllocator);
  assert(reinterpret_cast<const VkLayerDeviceCreateInfo*>(info->pNext)->u.pLayerInfo == nullptr);
  *output = reinterpret_cast<VkDevice>(&devices[reinterpret_cast<Handle*>(physical)->id]);
  return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroyDevice(VkDevice device, const VkAllocationCallbacks* allocator) {
  assert(allocator == expectedAllocator);
  assert(vkGetDeviceProcAddr(device,"vkUnknown") != nullptr);
  reinterpret_cast<Handle*>(device)->dispatch = nullptr;
  ++forwarded;
}
VKAPI_ATTR VkResult VKAPI_CALL createImage(VkDevice device, const VkImageCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkImage* output) {
  assert(info == expectedInfo && info->pNext == expectedPNext && allocator == expectedAllocator);
  assert(vkGetDeviceProcAddr(device,"vkUnknown"));
  ++forwarded;
  if (createResult == VK_SUCCESS) *output = h<VkImage>(0x110 + id(device));
  return createResult;
}
VKAPI_ATTR VkResult VKAPI_CALL createImageA(VkDevice d,const VkImageCreateInfo* i,const VkAllocationCallbacks* a,VkImage* o) {
  ++imagesA; return createImage(d,i,a,o);
}
VKAPI_ATTR VkResult VKAPI_CALL createImageB(VkDevice d,const VkImageCreateInfo* i,const VkAllocationCallbacks* a,VkImage* o) {
  ++imagesB; return createImage(d,i,a,o);
}
VKAPI_ATTR VkResult VKAPI_CALL createView(VkDevice, const VkImageViewCreateInfo* info,
    const VkAllocationCallbacks* allocator, VkImageView* output) {
  assert(info == expectedInfo && info->pNext == expectedPNext && allocator == expectedAllocator);
  *output = h<VkImageView>(0x220); ++forwarded; return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL allocate(VkDevice, const VkMemoryAllocateInfo* info,
    const VkAllocationCallbacks* allocator, VkDeviceMemory* output) {
  assert(info == expectedInfo && info->pNext == expectedPNext && allocator == expectedAllocator);
  *output = h<VkDeviceMemory>(0x330); ++forwarded; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL freeMemory(VkDevice device, VkDeviceMemory memory, const VkAllocationCallbacks* allocator) {
  assert(memory == h<VkDeviceMemory>(0x330) && allocator == expectedAllocator);
  assert(vkGetDeviceProcAddr(device,"vkUnknown")); ++forwarded;
}
VKAPI_ATTR void VKAPI_CALL destroyImage(VkDevice, VkImage image, const VkAllocationCallbacks* allocator) {
  assert(image == h<VkImage>(0x110) && allocator == expectedAllocator); ++forwarded;
}
VKAPI_ATTR void VKAPI_CALL destroyView(VkDevice, VkImageView view, const VkAllocationCallbacks* allocator) {
  assert(view == h<VkImageView>(0x220) && allocator == expectedAllocator); ++forwarded;
}
VKAPI_ATTR VkResult VKAPI_CALL bind(VkDevice, VkImage image, VkDeviceMemory memory, VkDeviceSize offset) {
  assert(image == h<VkImage>(0x110) && memory == h<VkDeviceMemory>(0x330) && offset == 96); ++forwarded;
  return VK_ERROR_OUT_OF_DEVICE_MEMORY;
}
VKAPI_ATTR VkResult VKAPI_CALL bind2(VkDevice, uint32_t count, const VkBindImageMemoryInfo* infos) {
  assert(count == 1 && infos == expectedBinds && infos[0].memoryOffset == 64); ++forwarded; return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL update(VkDevice device, uint32_t wc, const VkWriteDescriptorSet* writes,
    uint32_t cc, const VkCopyDescriptorSet* copies) {
  assert(wc == 4 && cc == 1 && writes == expectedWrites && copies == expectedCopies);
  assert(writes[3].dstBinding == 19 && writes[3].dstArrayElement == 5);
  assert(vkGetDeviceProcAddr(device,"vkUnknown")); ++forwarded;
}
VKAPI_ATTR void VKAPI_CALL updateTemplate(VkDevice, VkDescriptorSet set, VkDescriptorUpdateTemplate t, const void* data) {
  assert(set == h<VkDescriptorSet>(0x440) && t == h<VkDescriptorUpdateTemplate>(0x550) && data == expectedData); ++forwarded;
}
VKAPI_ATTR void VKAPI_CALL push(VkCommandBuffer command, VkPipelineBindPoint point, VkPipelineLayout layout,
    uint32_t set,uint32_t count,const VkWriteDescriptorSet* writes) {
  assert(command == reinterpret_cast<VkCommandBuffer>(&commands[0]) && point == VK_PIPELINE_BIND_POINT_GRAPHICS);
  assert(layout == h<VkPipelineLayout>(0x660) && set == 2 && count == 4 && writes == expectedWrites); ++forwarded;
}
VKAPI_ATTR VkResult VKAPI_CALL createSwap(VkDevice,const VkSwapchainCreateInfoKHR* info,const VkAllocationCallbacks* a,VkSwapchainKHR* output) {
  assert(info == expectedInfo && a == expectedAllocator); *output = h<VkSwapchainKHR>(0x770); ++forwarded; return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL swapImages(VkDevice,VkSwapchainKHR swap,uint32_t* count,VkImage* images) {
  assert(swap == h<VkSwapchainKHR>(0x770));
  if (!images) { *count = 2; ++forwarded; return VK_SUCCESS; }
  assert(*count == 2); images[0] = h<VkImage>(0x771); images[1] = h<VkImage>(0x772); *count = 2; ++forwarded; return VK_INCOMPLETE;
}
VKAPI_ATTR void VKAPI_CALL destroySwap(VkDevice,VkSwapchainKHR swap,const VkAllocationCallbacks* a) {
  assert(swap == h<VkSwapchainKHR>(0x770) && a == expectedAllocator); ++forwarded;
}
VKAPI_ATTR VkResult VKAPI_CALL debugName(VkDevice,const VkDebugUtilsObjectNameInfoEXT* info) {
  assert(info == expectedInfo && info->objectHandle == 0x220 && std::strcmp(info->pObjectName,"actual-view-name") == 0);
  ++forwarded; return VK_ERROR_UNKNOWN;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL physicalProc(VkInstance instance,const char* name) {
  if(std::strcmp(name,"vkCreateImage")==0) return reinterpret_cast<PFN_vkVoidFunction>(unknownA);
  return std::strcmp(name,"vkPhysicalUnknown") == 0 ? reinterpret_cast<PFN_vkVoidFunction>(
      reinterpret_cast<Handle*>(instance)->id == 0 ? unknownA : unknownB) : nullptr;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gdpa(VkDevice device,const char* name) {
  if (!name) return nullptr;
#define LEAF(N,F) if(std::strcmp(name,#N)==0) return reinterpret_cast<PFN_vkVoidFunction>(F)
  LEAF(vkGetDeviceProcAddr,gdpa); LEAF(vkDestroyDevice,destroyDevice);
  LEAF(vkCreateImage,id(device)==0?createImageA:createImageB);
  LEAF(vkCreateImageView,createView); LEAF(vkDestroyImage,destroyImage); LEAF(vkDestroyImageView,destroyView);
  LEAF(vkAllocateMemory,allocate); LEAF(vkFreeMemory,freeMemory); LEAF(vkBindImageMemory,bind);
  if(std::strcmp(name,"vkBindImageMemory2")==0) return id(device)==0 ? reinterpret_cast<PFN_vkVoidFunction>(bind2) : nullptr;
  if(std::strcmp(name,"vkBindImageMemory2KHR")==0) return id(device)==1 ? reinterpret_cast<PFN_vkVoidFunction>(bind2) : nullptr;
  LEAF(vkUpdateDescriptorSets,update); LEAF(vkUpdateDescriptorSetWithTemplate,updateTemplate);
  LEAF(vkCmdPushDescriptorSetKHR,push);
  LEAF(vkCreateSwapchainKHR,createSwap); LEAF(vkGetSwapchainImagesKHR,swapImages); LEAF(vkDestroySwapchainKHR,destroySwap);
  LEAF(vkSetDebugUtilsObjectNameEXT,debugName);
#undef LEAF
  if(std::strcmp(name,"vkUnknown")==0) return reinterpret_cast<PFN_vkVoidFunction>(id(device)==0?unknownA:unknownB);
  return nullptr;
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL gipa(VkInstance instance,const char* name) {
  if(!name) return nullptr;
  if(std::strcmp(name,"vkCreateInstance")==0) return reinterpret_cast<PFN_vkVoidFunction>(createInstance);
  if(std::strcmp(name,"vkCreateDevice")==0) return reinterpret_cast<PFN_vkVoidFunction>(createDevice);
  if(std::strcmp(name,"vkDestroyInstance")==0) return reinterpret_cast<PFN_vkVoidFunction>(destroyInstance);
  if(!instance) return std::strcmp(name,"vkUnknownGlobal")==0?reinterpret_cast<PFN_vkVoidFunction>(unknownA):nullptr;
  return gdpa(reinterpret_cast<VkDevice>(&devices[reinterpret_cast<Handle*>(instance)->id]),name);
}
VkInstance makeInstance(const VkAllocationCallbacks* allocator) {
  VkLayerInstanceLink link{}; link.pfnNextGetInstanceProcAddr=gipa; link.pfnNextGetPhysicalDeviceProcAddr=physicalProc;
  VkLayerInstanceCreateInfo chainInfo{}; chainInfo.sType=VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
  chainInfo.function=VK_LAYER_LINK_INFO; chainInfo.u.pLayerInfo=&link;
  VkInstanceCreateInfo info{}; info.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO; info.pNext=&chainInfo;
  expectedInfo=&info; expectedPNext=info.pNext; expectedAllocator=allocator;
  VkInstance result{}; assert(vkCreateInstance(&info,allocator,&result)==VK_SUCCESS); return result;
}
VkDevice makeDevice(int which,const VkAllocationCallbacks* allocator) {
  VkLayerDeviceLink link{}; link.pfnNextGetInstanceProcAddr=gipa; link.pfnNextGetDeviceProcAddr=gdpa;
  VkLayerDeviceCreateInfo chainInfo{}; chainInfo.sType=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
  chainInfo.function=VK_LAYER_LINK_INFO; chainInfo.u.pLayerInfo=&link;
  VkDeviceCreateInfo info{}; info.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO; info.pNext=&chainInfo;
  expectedInfo=&info; expectedPNext=info.pNext; expectedAllocator=allocator;
  VkDevice result{}; assert(vkCreateDevice(reinterpret_cast<VkPhysicalDevice>(&physicals[which]),&info,allocator,&result)==VK_SUCCESS); return result;
}
}

int main() {
  assert(rqvp_layer::runtimeSelected("1", RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected("01", RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected("true", RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected("0", RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected("", RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected(nullptr, RQ_VULKAN_PROVENANCE_APP_ID));
  assert(!rqvp_layer::runtimeSelected("1", RQ_VULKAN_PROVENANCE_APP_ID ":worker"));
  VkNegotiateLayerInterface negotiation{};
  negotiation.sType = LAYER_NEGOTIATE_INTERFACE_STRUCT;
  negotiation.loaderLayerInterfaceVersion = 99;
  assert(vkNegotiateLoaderLayerInterfaceVersion(&negotiation) == VK_SUCCESS);
  assert(negotiation.loaderLayerInterfaceVersion == 2);
  assert(negotiation.pfnGetInstanceProcAddr == vkGetInstanceProcAddr);
  assert(negotiation.pfnGetDeviceProcAddr == vkGetDeviceProcAddr);
  assert(negotiation.pfnGetPhysicalDeviceProcAddr);
  negotiation.loaderLayerInterfaceVersion = 0;
  assert(vkNegotiateLoaderLayerInterfaceVersion(&negotiation) == VK_ERROR_INITIALIZATION_FAILED);
  assert(!vkGetDeviceProcAddr(VK_NULL_HANDLE, "vkCreateImage"));
  assert(!vkGetInstanceProcAddr(VK_NULL_HANDLE, nullptr));
  VkAllocationCallbacks allocator{};
  VkInstance ia=fake::makeInstance(&allocator), ib=fake::makeInstance(&allocator);
  VkDevice da=fake::makeDevice(0,&allocator), db=fake::makeDevice(1,&allocator);
  assert(!capture().recorder); // Host/default off still chains and tracks live dispatch.
  assert(vkGetDeviceProcAddr(da,"vkUnknown")==reinterpret_cast<PFN_vkVoidFunction>(fake::unknownA));
  assert(vkGetDeviceProcAddr(db,"vkUnknown")==reinterpret_cast<PFN_vkVoidFunction>(fake::unknownB));
  assert(vkGetInstanceProcAddr(ia,"vkCreateImage")==reinterpret_cast<PFN_vkVoidFunction>(vkCreateImage));
  assert(vkGetDeviceProcAddr(da,"vkBindImageMemory2")==reinterpret_cast<PFN_vkVoidFunction>(vkBindImageMemory2));
  assert(!vkGetDeviceProcAddr(da,"vkBindImageMemory2KHR"));
  assert(!vkGetInstanceProcAddr(ia,"vkBindImageMemory2KHR"));
  assert(!vkGetDeviceProcAddr(db,"vkBindImageMemory2"));
  assert(vkGetDeviceProcAddr(db,"vkBindImageMemory2KHR")==reinterpret_cast<PFN_vkVoidFunction>(vkBindImageMemory2KHR));
  assert(!vkGetDeviceProcAddr(db,"vkTotallyAbsent"));
  assert(vk_layerGetPhysicalDeviceProcAddr(ia,"vkPhysicalUnknown")==reinterpret_cast<PFN_vkVoidFunction>(fake::unknownA));
  assert(vk_layerGetPhysicalDeviceProcAddr(ib,"vkPhysicalUnknown")==reinterpret_cast<PFN_vkVoidFunction>(fake::unknownB));
  assert(vkGetInstanceProcAddr(ia,"vk_layerGetPhysicalDeviceProcAddr")==reinterpret_cast<PFN_vkVoidFunction>(vk_layerGetPhysicalDeviceProcAddr));
  assert(!vk_layerGetPhysicalDeviceProcAddr(ia,"vkCreateImage"));
  VkImageCreateInfo imageInfo{}; imageInfo.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  imageInfo.extent={13,17,1}; imageInfo.arrayLayers=2; imageInfo.mipLevels=3;
  VkBaseInStructure extension{}; extension.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO; imageInfo.pNext=&extension;
  fake::expectedInfo=&imageInfo; fake::expectedPNext=&extension; fake::expectedAllocator=&allocator;
  VkImage image{};
  assert(vkCreateImage(da,&imageInfo,&allocator,&image)==VK_SUCCESS && bits(image)==0x110);
  assert(vkCreateImage(db,&imageInfo,&allocator,&image)==VK_SUCCESS && bits(image)==0x111);
  assert(fake::imagesA==1 && fake::imagesB==1 && fake::trace.empty());

  // Test-only TU access installs a real recorder, not a production activation API.
  capture().recorder=std::make_unique<rqvp::Recorder>(fake::sink,nullptr);
  capture().recorder->start(true,now(),789);
  assert(vkCreateImage(da,&imageInfo,&allocator,&image)==VK_SUCCESS && bits(image)==0x110);
  fake::createResult=VK_ERROR_OUT_OF_DEVICE_MEMORY; image=fake::h<VkImage>(0xdead);
  assert(vkCreateImage(da,&imageInfo,&allocator,&image)==VK_ERROR_OUT_OF_DEVICE_MEMORY && bits(image)==0xdead);
  fake::createResult=VK_SUCCESS;
  VkImageViewCreateInfo viewInfo{}; viewInfo.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
  viewInfo.image=fake::h<VkImage>(0x110); viewInfo.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,1,2,3,4};
  fake::expectedInfo=&viewInfo; fake::expectedPNext=nullptr; VkImageView view{};
  assert(vkCreateImageView(da,&viewInfo,&allocator,&view)==VK_SUCCESS);
  VkMemoryAllocateInfo allocateInfo{}; allocateInfo.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  allocateInfo.allocationSize=8192; allocateInfo.memoryTypeIndex=7;
  VkMemoryDedicatedAllocateInfo dedicated{};dedicated.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
  dedicated.image=fake::h<VkImage>(0x110);
  VkImportMemoryFdInfoKHR importInfo{};importInfo.sType=VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
  importInfo.handleType=VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;importInfo.fd=42;
  dedicated.pNext=&importInfo;allocateInfo.pNext=&dedicated;
  fake::expectedInfo=&allocateInfo;fake::expectedPNext=&dedicated;VkDeviceMemory memory{};
  assert(vkAllocateMemory(da,&allocateInfo,&allocator,&memory)==VK_SUCCESS);
  assert(vkBindImageMemory(da,fake::h<VkImage>(0x110),memory,96)==VK_ERROR_OUT_OF_DEVICE_MEMORY);
  VkBindImageMemoryInfo bindInfo{}; bindInfo.sType=VK_STRUCTURE_TYPE_BIND_IMAGE_MEMORY_INFO;
  bindInfo.image=fake::h<VkImage>(0x110); bindInfo.memory=memory; bindInfo.memoryOffset=64;
  VkBindImagePlaneMemoryInfo plane{};plane.sType=VK_STRUCTURE_TYPE_BIND_IMAGE_PLANE_MEMORY_INFO;
  plane.planeAspect=VK_IMAGE_ASPECT_PLANE_1_BIT;plane.pNext=&extension;bindInfo.pNext=&plane;
  fake::expectedBinds=&bindInfo;
  assert(vkBindImageMemory2(da,1,&bindInfo)==VK_SUCCESS);
  assert(vkBindImageMemory2KHR(db,1,&bindInfo)==VK_SUCCESS);
  VkDescriptorImageInfo imageInfos[2]{}; imageInfos[0].imageView=view; imageInfos[0].imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  imageInfos[1].imageView=fake::h<VkImageView>(0x221); imageInfos[1].imageLayout=VK_IMAGE_LAYOUT_GENERAL;
  VkWriteDescriptorSet writes[4]{};
  for(auto& w:writes){w.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;w.descriptorType=VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
    w.dstSet=fake::h<VkDescriptorSet>(0x440);w.descriptorCount=2;w.pImageInfo=imageInfos;}
  writes[0].dstBinding=7; writes[1].dstBinding=8; writes[2].dstBinding=9; writes[3].dstBinding=19; writes[3].dstArrayElement=5;
  VkCopyDescriptorSet copy{}; copy.sType=VK_STRUCTURE_TYPE_COPY_DESCRIPTOR_SET;
  copy.srcSet=fake::h<VkDescriptorSet>(0x440);copy.srcBinding=19;copy.srcArrayElement=6;
  copy.dstSet=fake::h<VkDescriptorSet>(0x441);copy.dstBinding=27;copy.dstArrayElement=8;copy.descriptorCount=1;
  fake::expectedWrites=writes;fake::expectedCopies=&copy;
  vkUpdateDescriptorSets(da,4,writes,1,&copy);
  fake::expectedData=&copy;
  vkUpdateDescriptorSetWithTemplate(da,fake::h<VkDescriptorSet>(0x440),fake::h<VkDescriptorUpdateTemplate>(0x550),&copy);
  vkCmdPushDescriptorSetKHR(reinterpret_cast<VkCommandBuffer>(&fake::commands[0]),VK_PIPELINE_BIND_POINT_GRAPHICS,
      fake::h<VkPipelineLayout>(0x660),2,4,writes);
  VkSwapchainCreateInfoKHR swapInfo{};swapInfo.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
  swapInfo.imageFormat=VK_FORMAT_R8G8B8A8_UNORM;swapInfo.imageExtent={23,29};
  fake::expectedInfo=&swapInfo;VkSwapchainKHR swap{};
  assert(vkCreateSwapchainKHR(da,&swapInfo,&allocator,&swap)==VK_SUCCESS);
  uint32_t count=0;assert(vkGetSwapchainImagesKHR(da,swap,&count,nullptr)==VK_SUCCESS && count==2);
  VkImage swapOutput[2]{};assert(vkGetSwapchainImagesKHR(da,swap,&count,swapOutput)==VK_INCOMPLETE);
  vkDestroySwapchainKHR(da,swap,&allocator);
  VkDebugUtilsObjectNameInfoEXT nameInfo{};nameInfo.sType=VK_STRUCTURE_TYPE_DEBUG_UTILS_OBJECT_NAME_INFO_EXT;
  nameInfo.objectType=VK_OBJECT_TYPE_IMAGE_VIEW;nameInfo.objectHandle=bits(view);nameInfo.pObjectName="actual-view-name";
  fake::expectedInfo=&nameInfo;assert(vkSetDebugUtilsObjectNameEXT(da,&nameInfo)==VK_ERROR_UNKNOWN);
  vkDestroyImageView(da,view,&allocator);
  vkFreeMemory(da,memory,&allocator);
  vkDestroyImage(da,fake::h<VkImage>(0x110),&allocator);
  std::cout<<"TRACE\n"<<fake::trace<<"END_TRACE\n";

  // A terminal trace budget may not retire live dispatch custody.
  fake::trace.clear();rqvp::Limits limits{};limits.events=1;
  capture().recorder=std::make_unique<rqvp::Recorder>(fake::sink,nullptr,limits);
  capture().recorder->start(true,now(),789);
  fake::expectedInfo=&imageInfo;fake::expectedPNext=&extension;
  assert(vkCreateImage(db,&imageInfo,&allocator,&image)==VK_SUCCESS);
  assert(vkCreateImage(db,&imageInfo,&allocator,&image)==VK_SUCCESS);
  assert(vkGetDeviceProcAddr(db,"vkUnknown")==reinterpret_cast<PFN_vkVoidFunction>(fake::unknownB));
  assert(vkGetDeviceProcAddr(db,"vkBindImageMemory2KHR")==reinterpret_cast<PFN_vkVoidFunction>(vkBindImageMemory2KHR));
  assert(fake::imagesB==3);
  std::cout<<"CAPPED_TRACE\n"<<fake::trace<<"END_CAPPED_TRACE\n";
  capture().recorder.reset();
  vkDestroyDevice(da,&allocator);vkDestroyDevice(db,&allocator);
  vkDestroyInstance(ia,&allocator);vkDestroyInstance(ib,&allocator);
  assert(rqvp_layer::devices.empty() && rqvp_layer::instances.empty());
  fake::instances[0].dispatch=&fake::instanceKeys[0];fake::devices[0].dispatch=&fake::deviceKeys[0];fake::nextInstance=0;
  fake::trace.clear();capture().recorder=std::make_unique<rqvp::Recorder>(fake::sink,nullptr);
  capture().recorder->start(true,now(),789);
  ia=fake::makeInstance(&allocator);da=fake::makeDevice(0,&allocator);
  fake::expectedInfo=&imageInfo;fake::expectedPNext=&extension;
  assert(vkCreateImage(da,&imageInfo,&allocator,&image)==VK_SUCCESS);
  vkDestroyDevice(da,&allocator);vkDestroyInstance(ia,&allocator);
  assert(rqvp_layer::devices.empty() && rqvp_layer::instances.empty());
  std::cout<<"CHAIN_TRACE\n"<<fake::trace<<"END_CHAIN_TRACE\n";
  std::cout << "actual wrapper forwarding/query/alias/reentry/lifecycle controls passed\n";
}
