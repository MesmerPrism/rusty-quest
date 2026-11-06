#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include "workload_backend.h"
#include "atlas_packet.h"
#include "gpu_hand_input.h"
#include "gpu_hand_workload.h"
#include "hotload_kernel.h"
#include "hotload_owner.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>
#include <sstream>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
#include "external_workload_intake.h"
#endif

namespace {
void check(VkResult result, const char* operation) {
    if (result != VK_SUCCESS)
        throw std::runtime_error(std::string(operation) + ":VkResult=" + std::to_string(result));
}
const uint32_t vertexCode[] =
#include "atlas.vert.inc"
;
const uint32_t fragmentCode[] =
#include "atlas.frag.inc"
;
using namespace rq::workload;
uint64_t nowNs() { timespec t{}; clock_gettime(CLOCK_MONOTONIC,&t); return uint64_t(t.tv_sec)*1000000000ull+uint64_t(t.tv_nsec); }
uint32_t floatBits(float v) { uint32_t u; std::memcpy(&u,&v,4); return u; }
struct Configuration { WorkloadConfiguration workload{}; bool drawHands=false,drawGrafts=false,publicationPaused=false; float graftScale=.85f; uint32_t revision=0; };
struct Producer {
    ANativeWindow* window = nullptr;
    std::atomic<bool> running{true}, closed{false};
    std::atomic<uint64_t> submitted{0}, completed{0};
    std::atomic<bool> publicationPausedRequested{false},publicationPausedEffective{false};
    std::atomic<uint64_t> heldSubmission{0},heldGeneration{0},sourceStateGeneration{0};
    std::mutex statusMutex;
    std::string phase = "starting";
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    uint32_t timestampBits=0;
    VkQueryPool timingPool=VK_NULL_HANDLE;
    uint64_t lastTimingRead=0;
    std::atomic<bool> gpuTimingAvailable{false};
    std::atomic<uint64_t> computeGpuNs{0},atlasAndColorWaitGpuNs{0},timingSubmission{0};
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkExtent2D extent{};
    std::atomic<bool> wsiSelectionAvailable{false};
    std::atomic<uint32_t> wsiFormat{0},wsiColorSpace{0},wsiCurrentTransform{0},wsiWidth{0},wsiHeight{0};
    std::atomic<uint64_t> presentCalls{0},presentAccepted{0};
    std::atomic<int32_t> lastPresentResult{VK_NOT_READY};
    VkRenderPass pass = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    GpuContext context{};
    AAssetManager* assets = nullptr;
    JavaVM* vm = nullptr;
    jobject assetOwner = nullptr;
    jobject surfaceOwner = nullptr;
    std::unique_ptr<ComputeWorkload> workload;
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
    std::mutex workloadReloadMutex;
    std::string workloadReloadIntake="{}";
    void stopWorkloadReload(){std::lock_guard<std::mutex> lock(workloadReloadMutex);if(workload)workload->stopReload();}
#endif
    std::unique_ptr<HandWorkload> hands;
    std::unique_ptr<HotloadKernel> hotload;
    std::mutex hotloadOwnerMutex;
    HandInput handInput;
    std::mutex configMutex;
    Configuration configuration;
    VkBuffer headerBuffer=VK_NULL_HANDLE;
    VkDeviceMemory headerMemory=VK_NULL_HANDLE;
    void* headerMapped=nullptr;
    uint64_t generation=0;
    std::atomic<uint64_t> simulationSteps{0}, poseRevision{0}, poseTime{0}, poseMonotonic{0};
    std::atomic<uint32_t> leftActive{0},rightActive{0},controlRevision{0},sourceReady{0};
    std::atomic<uint32_t> mainCapacity{0},tracerCapacity{0},baseHandCount{0},graftCount{0};
    std::atomic<uint64_t> recordCpuNs{0};
    std::atomic<bool> retained{false};
    VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptor = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore available = VK_NULL_HANDLE;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> frames;
    std::vector<VkSemaphore> finished;

    void setPhase(const std::string& value) {
        std::lock_guard<std::mutex> lock(statusMutex); phase = value;
        __android_log_print(ANDROID_LOG_INFO, "RQSpatialAtlas", "%s", value.c_str());
    }
    VkShaderModule shader(const uint32_t* code, size_t bytes) {
        VkShaderModuleCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = bytes; info.pCode = code;
        VkShaderModule module = VK_NULL_HANDLE;
        check(vkCreateShaderModule(device, &info, nullptr, &module), "shader");
        return module;
    }
    void initialize() {
        const char* instanceExtensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo app{}; app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "Spatial resident GPU workload"; app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo create{}; create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        create.pApplicationInfo = &app; create.enabledExtensionCount = 2;
        create.ppEnabledExtensionNames = instanceExtensions;
        check(vkCreateInstance(&create, nullptr, &instance), "instance");
        VkAndroidSurfaceCreateInfoKHR androidSurface{}; androidSurface.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        androidSurface.window = window;
        check(vkCreateAndroidSurfaceKHR(instance, &androidSurface, nullptr, &surface), "surface");
        uint32_t count = 0;
        check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "physical-count");
        std::vector<VkPhysicalDevice> physicals(count);
        check(vkEnumeratePhysicalDevices(instance, &count, physicals.data()), "physicals");
        VkPhysicalDevice selected = VK_NULL_HANDLE;
        for (auto physical : physicals) {
            uint32_t queues = 0; vkGetPhysicalDeviceQueueFamilyProperties(physical, &queues, nullptr);
            std::vector<VkQueueFamilyProperties> properties(queues);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, &queues, properties.data());
            for (uint32_t i = 0; i < queues; ++i) {
                VkBool32 present = VK_FALSE;
                check(vkGetPhysicalDeviceSurfaceSupportKHR(physical, i, surface, &present), "present-support");
                constexpr auto required = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
                if (present && (properties[i].queueFlags & required) == required) {
                    selected = physical; family = i; timestampBits=properties[i].timestampValidBits; break;
                }
            }
            if (selected != VK_NULL_HANDLE) break;
        }
        if (selected == VK_NULL_HANDLE) throw std::runtime_error("no compute/graphics/present queue");
        float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{}; queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueInfo.queueFamilyIndex = family; queueInfo.queueCount = 1; queueInfo.pQueuePriorities = &priority;
        const char* extension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo deviceInfo{}; deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        deviceInfo.queueCreateInfoCount = 1; deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1; deviceInfo.ppEnabledExtensionNames = &extension;
        check(vkCreateDevice(selected, &deviceInfo, nullptr, &device), "device");
        vkGetDeviceQueue(device, family, 0, &queue);
        context.physicalDevice=selected; context.device=device;
        vkGetPhysicalDeviceMemoryProperties(selected,&context.memoryProperties);
        vkGetPhysicalDeviceProperties(selected,&context.properties);
        if(timestampBits && context.properties.limits.timestampComputeAndGraphics){
            VkQueryPoolCreateInfo query{};query.sType=VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            query.queryType=VK_QUERY_TYPE_TIMESTAMP;query.queryCount=3;
            if(vkCreateQueryPool(device,&query,nullptr,&timingPool)!=VK_SUCCESS)timingPool=VK_NULL_HANDLE;
        }
        if(context.properties.limits.maxStorageBufferRange < 3*1024*1024)
            throw std::runtime_error("storage buffer limit rejects selected workload");
        VkSurfaceCapabilitiesKHR caps{};
        check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(selected, surface, &caps), "surface-caps");
        uint32_t formatsCount = 0;
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(selected, surface, &formatsCount, nullptr), "format-count");
        std::vector<VkSurfaceFormatKHR> formats(formatsCount);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(selected, surface, &formatsCount, formats.data()), "formats");
        VkSurfaceFormatKHR format{};
        for (const auto& candidate : formats) {
            if (candidate.format == VK_FORMAT_R8G8B8A8_UNORM || candidate.format == VK_FORMAT_B8G8R8A8_UNORM) {
                format = candidate; break;
            }
        }
        if (format.format == VK_FORMAT_UNDEFINED) throw std::runtime_error("no UNORM WSI format; no hidden sRGB producer conversion");
        extent = caps.currentExtent;
        if (extent.width == UINT32_MAX) extent = {atlasWidth, atlasHeight};
        if (extent.width < caps.minImageExtent.width || extent.height < caps.minImageExtent.height ||
            extent.width > caps.maxImageExtent.width || extent.height > caps.maxImageExtent.height)
            throw std::runtime_error("Surface capabilities reject declared 1024x1024 atlas extent");
        if (extent.width != atlasWidth || extent.height != atlasHeight)
            throw std::runtime_error("SDK Surface extent is not the declared 1024x1024 data atlas");
        if (!(caps.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT))
            throw std::runtime_error("Surface lacks color-attachment usage");
        uint32_t images = caps.minImageCount + 1;
        if (caps.maxImageCount && images > caps.maxImageCount) images = caps.maxImageCount;
        VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        for (auto choice : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                           VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR})
            if (caps.supportedCompositeAlpha & choice) { alpha = choice; break; }
        VkSwapchainCreateInfoKHR swap{}; swap.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swap.surface = surface; swap.minImageCount = images; swap.imageFormat = format.format;
        swap.imageColorSpace = format.colorSpace; swap.imageExtent = extent; swap.imageArrayLayers = 1;
        swap.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; swap.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swap.preTransform = caps.currentTransform; swap.compositeAlpha = alpha;
        swap.presentMode = VK_PRESENT_MODE_FIFO_KHR; swap.clipped = VK_TRUE;
        check(vkCreateSwapchainKHR(device, &swap, nullptr, &swapchain), "swapchain");
        wsiFormat.store(uint32_t(format.format));wsiColorSpace.store(uint32_t(format.colorSpace));
        wsiCurrentTransform.store(uint32_t(caps.currentTransform));wsiWidth.store(extent.width);wsiHeight.store(extent.height);
        wsiSelectionAvailable.store(true);
        check(vkGetSwapchainImagesKHR(device, swapchain, &images, nullptr), "image-count");
        std::vector<VkImage> imageHandles(images);
        check(vkGetSwapchainImagesKHR(device, swapchain, &images, imageHandles.data()), "images");
        VkAttachmentDescription attachment{};
        attachment.format = format.format; attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference color{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription subpass{}; subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1; subpass.pColorAttachments = &color;
        VkSubpassDependency dependency{}; dependency.srcSubpass = VK_SUBPASS_EXTERNAL; dependency.dstSubpass = 0;
        dependency.srcStageMask = dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo passInfo{}; passInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        passInfo.attachmentCount = 1; passInfo.pAttachments = &attachment;
        passInfo.subpassCount = 1; passInfo.pSubpasses = &subpass;
        passInfo.dependencyCount = 1; passInfo.pDependencies = &dependency;
        check(vkCreateRenderPass(device, &passInfo, nullptr, &pass), "render-pass");
        VkSemaphoreCreateInfo semaphoreInfo{}; semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        for (auto image : imageHandles) {
            VkImageViewCreateInfo viewInfo{}; viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = image; viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D; viewInfo.format = format.format;
            viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkImageView view = VK_NULL_HANDLE;
            check(vkCreateImageView(device, &viewInfo, nullptr, &view), "view"); views.push_back(view);
            VkFramebufferCreateInfo frameInfo{}; frameInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            frameInfo.renderPass = pass; frameInfo.attachmentCount = 1; frameInfo.pAttachments = &views.back();
            frameInfo.width = extent.width; frameInfo.height = extent.height; frameInfo.layers = 1;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            check(vkCreateFramebuffer(device, &frameInfo, nullptr, &framebuffer), "framebuffer"); frames.push_back(framebuffer);
            VkSemaphore rendered = VK_NULL_HANDLE;
            check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &rendered), "present-semaphore"); finished.push_back(rendered);
        }
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
        {auto prepared=createExternalWorkload(context,assets,0);std::lock_guard<std::mutex> lock(workloadReloadMutex);
            if(!running.load())prepared->stopReload();
            workload=std::move(prepared);}
#else
        workload=createExternalWorkload(context,assets,0);
#endif
        hands=std::make_unique<HandWorkload>(context,assets);
        auto preparedHotload=std::make_unique<HotloadKernel>(context,assets);
        {std::lock_guard<std::mutex> lock(hotloadOwnerMutex);
            if(!running.load())preparedHotload->stop();
            hotload=std::move(preparedHotload);}
        const auto particles=workload->output(); const auto hand=hands->output();
        const uint64_t records=2ull*particles.instanceCapacity+hand.baseCount+hand.graftCount+4;
        if(!particles.buffer || !hand.buffer || particles.rowsPerInstance!=4 || records>maxRecords)
            throw std::runtime_error("selected workload violates atlas capacity/ABI");
        mainCapacity.store(particles.mainCount);tracerCapacity.store(particles.tracerCapacity);
        baseHandCount.store(hand.baseCount);graftCount.store(hand.graftCount);
        VkBufferCreateInfo bufferInfo{};bufferInfo.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size=sizeof(PacketHeader);bufferInfo.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        check(vkCreateBuffer(device,&bufferInfo,nullptr,&headerBuffer),"packet-header-buffer");
        VkMemoryRequirements req{};vkGetBufferMemoryRequirements(device,headerBuffer,&req);
        uint32_t memoryType=UINT32_MAX;
        for(uint32_t i=0;i<context.memoryProperties.memoryTypeCount;++i)
            if((req.memoryTypeBits&(1u<<i)) && (context.memoryProperties.memoryTypes[i].propertyFlags&
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==
              (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)){memoryType=i;break;}
        if(memoryType==UINT32_MAX)throw std::runtime_error("no coherent packet header memory");
        VkMemoryAllocateInfo memoryInfo{};memoryInfo.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        memoryInfo.allocationSize=req.size;memoryInfo.memoryTypeIndex=memoryType;
        check(vkAllocateMemory(device,&memoryInfo,nullptr,&headerMemory),"header-memory");
        check(vkBindBufferMemory(device,headerBuffer,headerMemory,0),"header-bind");
        check(vkMapMemory(device,headerMemory,0,sizeof(PacketHeader),0,&headerMapped),"header-map");
        VkDescriptorSetLayoutBinding bindings[4]{};
        for(uint32_t i=0;i<4;++i){bindings[i].binding=i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount=1;bindings[i].stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;}
        VkDescriptorSetLayoutCreateInfo descriptorInfo{};descriptorInfo.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        descriptorInfo.bindingCount=4;descriptorInfo.pBindings=bindings;
        check(vkCreateDescriptorSetLayout(device,&descriptorInfo,nullptr,&descriptorLayout),"packet-descriptor-layout");
        VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,4};
        VkDescriptorPoolCreateInfo descriptorPoolInfo{};descriptorPoolInfo.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        descriptorPoolInfo.maxSets=1;descriptorPoolInfo.poolSizeCount=1;descriptorPoolInfo.pPoolSizes=&poolSize;
        check(vkCreateDescriptorPool(device,&descriptorPoolInfo,nullptr,&descriptorPool),"packet-descriptor-pool");
        VkDescriptorSetAllocateInfo descriptorAllocate{};descriptorAllocate.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        descriptorAllocate.descriptorPool=descriptorPool;descriptorAllocate.descriptorSetCount=1;
        descriptorAllocate.pSetLayouts=&descriptorLayout;
        check(vkAllocateDescriptorSets(device,&descriptorAllocate,&descriptor),"packet-descriptor");
        const auto diagnostic=hotload->output();
        VkDescriptorBufferInfo infos[4]={{particles.buffer,particles.offset,particles.bytes},{hand.buffer,0,hand.bytes},{headerBuffer,0,sizeof(PacketHeader)},{diagnostic.buffer,0,diagnostic.bytes}};
        VkWriteDescriptorSet writes[4]{};
        for(uint32_t i=0;i<4;++i){writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=descriptor;
            writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&infos[i];}
        vkUpdateDescriptorSets(device,4,writes,0,nullptr);
        VkPipelineLayoutCreateInfo layoutInfo{};layoutInfo.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount=1;layoutInfo.pSetLayouts=&descriptorLayout;
        check(vkCreatePipelineLayout(device,&layoutInfo,nullptr,&layout),"packet-pipeline-layout");
        auto vertex = shader(vertexCode, sizeof(vertexCode));
        VkShaderModule fragment = VK_NULL_HANDLE;
        try { fragment = shader(fragmentCode, sizeof(fragmentCode)); }
        catch (...) { vkDestroyShaderModule(device, vertex, nullptr); throw; }
        VkPipelineShaderStageCreateInfo stages[2]{};
        for (auto& stage : stages) { stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stage.pName = "main"; }
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT; stages[0].module = vertex;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module = fragment;
        VkPipelineVertexInputStateCreateInfo input{}; input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        VkPipelineInputAssemblyStateCreateInfo assembly{}; assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkViewport viewport{0, 0, float(extent.width), float(extent.height), 0, 1};
        VkRect2D scissor{{0, 0}, extent};
        VkPipelineViewportStateCreateInfo viewportInfo{}; viewportInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportInfo.viewportCount = viewportInfo.scissorCount = 1; viewportInfo.pViewports = &viewport; viewportInfo.pScissors = &scissor;
        VkPipelineRasterizationStateCreateInfo raster{}; raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        raster.polygonMode = VK_POLYGON_MODE_FILL; raster.lineWidth = 1; raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        VkPipelineMultisampleStateCreateInfo samples{}; samples.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        samples.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend{}; blend.colorWriteMask = 15;
        VkPipelineColorBlendStateCreateInfo blendInfo{}; blendInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blendInfo.attachmentCount = 1; blendInfo.pAttachments = &blend;
        VkGraphicsPipelineCreateInfo graphics{}; graphics.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        graphics.stageCount = 2; graphics.pStages = stages; graphics.pVertexInputState = &input;
        graphics.pInputAssemblyState = &assembly; graphics.pViewportState = &viewportInfo;
        graphics.pRasterizationState = &raster; graphics.pMultisampleState = &samples;
        graphics.pColorBlendState = &blendInfo; graphics.layout = layout; graphics.renderPass = pass;
        auto result = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &graphics, nullptr, &pipeline);
        vkDestroyShaderModule(device, fragment, nullptr); vkDestroyShaderModule(device, vertex, nullptr);
        check(result, "pipeline");
        VkCommandPoolCreateInfo poolInfo{}; poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.queueFamilyIndex = family; poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device, &poolInfo, nullptr, &pool), "command-pool");
        VkCommandBufferAllocateInfo allocate{}; allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate.commandPool = pool; allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; allocate.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device, &allocate, &cmd), "command-buffer");
        VkFenceCreateInfo fenceInfo{}; fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        check(vkCreateFence(device, &fenceInfo, nullptr, &fence), "fence");
        check(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &available), "acquire-semaphore");
        setPhase("ready");
    }
    void renderLoop() {
        const uint64_t start=nowNs();uint64_t previous=start;
        while(running.load()) {
            auto waited=vkWaitForFences(device,1,&fence,VK_TRUE,100000000);
            if(waited==VK_TIMEOUT)continue;
            check(waited,"frame-fence");completed.store(submitted.load());
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
            {std::lock_guard<std::mutex> lock(workloadReloadMutex);if(workload)workload->confirmReloadCompleted(completed.load());}
#endif
            {std::lock_guard<std::mutex> lock(hotloadOwnerMutex);hotload->confirmCompleted(completed.load());}
            // At most one compact diagnostic per60 submitted frames; no geometry readback.
            if(timingPool && submitted.load()>lastTimingRead && submitted.load()%60==0){
                uint64_t result[6]{};
                const auto r=vkGetQueryPoolResults(device,timingPool,0,3,sizeof(result),result,16,
                    VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if(r==VK_SUCCESS && result[1]&&result[3]&&result[5]){
                    const uint64_t mask=timestampBits>=64?UINT64_MAX:((uint64_t(1)<<timestampBits)-1);
                    const double computeNs=double((result[2]-result[0])&mask)*context.properties.limits.timestampPeriod;
                    const double atlasNs=double((result[4]-result[2])&mask)*context.properties.limits.timestampPeriod;
                    const bool valid=std::isfinite(computeNs)&&std::isfinite(atlasNs)&&computeNs>=0&&atlasNs>=0
                        &&computeNs<double(UINT64_MAX)&&atlasNs<double(UINT64_MAX);
                    {std::lock_guard<std::mutex> lock(statusMutex);
                        if(valid){computeGpuNs.store(uint64_t(computeNs));atlasAndColorWaitGpuNs.store(uint64_t(atlasNs));
                            timingSubmission.store(submitted.load());}
                        gpuTimingAvailable.store(valid);}
                }else{std::lock_guard<std::mutex> lock(statusMutex);gpuTimingAvailable.store(false);
                }
                lastTimingRead=submitted.load();
            }
            if(!running.load())break;
            // Pause only after retirement and before acquiring an image or sampling poses.
            // No acquire semaphore/image is held; stop still progresses on the next iteration.
            bool pausePublication;
            {std::lock_guard<std::mutex> lock(configMutex);pausePublication=configuration.publicationPaused;}
            if(pausePublication){
                heldSubmission.store(submitted.load());heldGeneration.store(generation);
                publicationPausedEffective.store(true);
                std::this_thread::sleep_for(std::chrono::milliseconds(10));continue;
            }
            publicationPausedEffective.store(false);
            if(submitted.load()>=UINT32_MAX)throw std::runtime_error("packet submission exhausted");
            uint32_t image=0;
            const auto acquired=vkAcquireNextImageKHR(device,swapchain,100000000,available,VK_NULL_HANDLE,&image);
            if(acquired==VK_TIMEOUT || acquired==VK_NOT_READY)continue;
            if(acquired!=VK_SUBOPTIMAL_KHR)check(acquired,"acquire");
            Configuration config;
            {std::lock_guard<std::mutex> lock(configMutex);config=configuration;configuration.workload.reset=false;}
            const uint64_t beginNs=nowNs();
            FrameContext frame;frame.generation=generation;frame.submission=submitted.load()+1;frame.monotonicNs=beginNs;
            frame.deltaSeconds=std::clamp(float(beginNs-previous)*1e-9f,.001f,.033f);
            frame.elapsedSeconds=double(beginNs-start)*1e-9;previous=beginNs;
            const auto input=handInput.sample();
            check(vkResetCommandBuffer(cmd,0),"reset-command");
            retireHotloadOwner(hotloadOwnerMutex,running,hotload,completed.load());
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
            {std::lock_guard<std::mutex> lock(workloadReloadMutex);if(workload)workload->afterReloadRetirement(generation,completed.load());}
#endif
            VkCommandBufferBeginInfo begin{};begin.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            check(vkBeginCommandBuffer(cmd,&begin),"begin-command");
            if(timingPool){vkCmdResetQueryPool(cmd,timingPool,0,3);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,timingPool,0);}
            hotload->record(cmd,frame);
            workload->record(cmd,frame,config.workload);
            hands->record(cmd,frame,input,config.drawHands,config.drawGrafts,config.graftScale);
            if(timingPool)vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,timingPool,1);
            const auto particles=workload->output();const auto hand=hands->output();
            PacketHeader h{};
            h[Magic]=packetMagic;h[Version]=packetVersion;h[Width]=atlasWidth;h[Height]=atlasHeight;
            h[Generation]=uint32_t(generation);h[Submission]=uint32_t(frame.submission);
            h[Flags]=(config.workload.simulate?1:0)|(config.drawHands?2:0)|(config.drawGrafts?4:0)|8|(particles.dispatchRecorded?16:0);
            h[MainCapacity]=particles.mainCount;h[TracerCapacity]=particles.tracerCapacity;
            h[ParticlesOffset]=0;h[ParticlesCount]=particles.instanceCapacity;
            h[AuxiliaryOffset]=particles.instanceCapacity;h[AuxiliaryCount]=particles.instanceCapacity;
            h[HandsOffset]=particles.instanceCapacity*2;h[HandsCount]=hand.baseCount;
            h[GraftsOffset]=h[HandsOffset]+hand.baseCount;h[GraftsCount]=hand.graftCount;
            if(particles.sourceStateSequence>UINT32_MAX || particles.sourceStateGeneration==0 || particles.sourceStateGeneration>UINT32_MAX)
                throw std::runtime_error("source metadata exceeds packet domain");
            h[SimulationSteps]=uint32_t(particles.sourceStateSequence);
            h[HandRecordLayout]=handRecordLayout;h[SourceStateGeneration]=uint32_t(particles.sourceStateGeneration);
            h[MonotonicLow]=uint32_t(beginNs);h[MonotonicHigh]=uint32_t(beginNs>>32);
            h[PoseTimeLow]=uint32_t(input.xrTime);h[PoseTimeHigh]=uint32_t(uint64_t(input.xrTime)>>32);
            h[PoseRevisionLow]=uint32_t(input.revision);h[PoseRevisionHigh]=uint32_t(input.revision>>32);
            h[PoseClockDomain]=input.clockDomain;
            h[DrawMain]=config.workload.drawMainCount;h[DrawTracers]=config.workload.drawTracerCount;
            h[DrawHands]=config.drawHands;h[DrawGrafts]=config.drawGrafts;
            h[CoordinateMapping]=1;h[RadiusLimitBits]=floatBits(radiusLimit);h[CoordinateLimitBits]=floatBits(coordinateLimit);
            h[ControlRevision]=config.revision;h[Dataset]=config.workload.dataset;
            h[LeftActive]=input.hands[0].active;h[RightActive]=input.hands[1].active;
            h[SourceReady]=particles.initialized&&hand.initialized&&hotload->output().initialized;h[PoseSource]=input.source;
            h[DiagnosticOffset]=h[GraftsOffset]+h[GraftsCount];h[DiagnosticCount]=2;
            h[DiagnosticAuxOffset]=h[DiagnosticOffset]+2;h[DiagnosticAuxCount]=2;
            h[NativeKernelGeneration]=uint32_t(hotload->generation());h[NativeKernelTag]=hotload->tag();
            std::memcpy(headerMapped,h.data(),sizeof(h));
            VkMemoryBarrier barrier{};barrier.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_HOST_WRITE_BIT;
            barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,1,&barrier,0,nullptr,0,nullptr);
            VkRenderPassBeginInfo render{};render.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            render.renderPass=pass;render.framebuffer=frames.at(image);render.renderArea={{0,0},extent};
            vkCmdBeginRenderPass(cmd,&render,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,1,&descriptor,0,nullptr);
            vkCmdDraw(cmd,3,1,0,0);vkCmdEndRenderPass(cmd);
            if(timingPool)vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,timingPool,2);
            check(vkEndCommandBuffer(cmd),"end-command");
            check(vkResetFences(device,1,&fence),"reset-fence");
            VkPipelineStageFlags waitStage=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            VkSubmitInfo submit{};submit.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submit.waitSemaphoreCount=1;submit.pWaitSemaphores=&available;submit.pWaitDstStageMask=&waitStage;
            submit.commandBufferCount=1;submit.pCommandBuffers=&cmd;
            submit.signalSemaphoreCount=1;submit.pSignalSemaphores=&finished.at(image);
            check(vkQueueSubmit(queue,1,&submit,fence),"submit");
            submitted.fetch_add(1);workload->confirmSubmitted();
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
            {std::lock_guard<std::mutex> lock(workloadReloadMutex);if(workload)workload->confirmReloadSubmitted(submitted.load());}
#endif
            hotload->confirmSubmitted(submitted.load());
            simulationSteps.store(workload->simulationStepsSubmitted());sourceReady.store(h[SourceReady]);
            sourceStateGeneration.store(particles.sourceStateGeneration);
            poseRevision.store(input.revision);poseTime.store(uint64_t(input.xrTime));poseMonotonic.store(input.monotonicNs);
            leftActive.store(h[LeftActive]);rightActive.store(h[RightActive]);controlRevision.store(config.revision);
            recordCpuNs.store(nowNs()-beginNs);
            VkPresentInfoKHR present{};present.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
            present.waitSemaphoreCount=1;present.pWaitSemaphores=&finished.at(image);
            present.swapchainCount=1;present.pSwapchains=&swapchain;present.pImageIndices=&image;
            presentCalls.fetch_add(1);
            const auto presented=vkQueuePresentKHR(queue,&present);
            lastPresentResult.store(int32_t(presented));
            if(presented==VK_SUCCESS||presented==VK_SUBOPTIMAL_KHR)presentAccepted.fetch_add(1);
            if(presented!=VK_SUBOPTIMAL_KHR)check(presented,"present");
        }
    }
    bool cleanup() {
        stopHotloadOwner(hotloadOwnerMutex,running,hotload);
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
        stopWorkloadReload();
#endif
        publicationPausedEffective.store(false);
        handInput.close();
        if (device) {
            // Only worker teardown waits for queue retirement; SDK/UI threads never wait here.
            auto retired = vkDeviceWaitIdle(device);
            if(retired!=VK_SUCCESS){retained=true;setPhase("retirement-failed-retained");return false;}
            completed.store(submitted.load());
            // Reset recorded command references before retiring dynamic pipelines.
            if(pool && vkResetCommandPool(device,pool,0)!=VK_SUCCESS){
                retained.store(true);setPhase("command-reset-failed-retained");return false;
            }
            {std::lock_guard<std::mutex> lock(hotloadOwnerMutex);if(hotload){hotload->destroyAfterRetirement();hotload.reset();}}
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
            {std::lock_guard<std::mutex> lock(workloadReloadMutex);if(workload){workload->destroyAfterRetirement();workload.reset();}}
#else
            if(workload){workload->destroyAfterRetirement();workload.reset();}
#endif
            if(hands){hands->destroyAfterRetirement();hands.reset();}
            for (auto framebuffer : frames) vkDestroyFramebuffer(device, framebuffer, nullptr);
            for (auto view : views) vkDestroyImageView(device, view, nullptr);
            for (auto semaphore : finished) vkDestroySemaphore(device, semaphore, nullptr);
            if (available) vkDestroySemaphore(device, available, nullptr);
            if (fence) vkDestroyFence(device, fence, nullptr);
            if(timingPool)vkDestroyQueryPool(device,timingPool,nullptr);
            if (pool) vkDestroyCommandPool(device, pool, nullptr);
            if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
            if (layout) vkDestroyPipelineLayout(device, layout, nullptr);
            if (descriptorPool) vkDestroyDescriptorPool(device, descriptorPool, nullptr);
            if (descriptorLayout) vkDestroyDescriptorSetLayout(device, descriptorLayout, nullptr);
            if(headerMapped)vkUnmapMemory(device,headerMemory);
            if(headerBuffer)vkDestroyBuffer(device,headerBuffer,nullptr);
            if(headerMemory)vkFreeMemory(device,headerMemory,nullptr);
            if (pass) vkDestroyRenderPass(device, pass, nullptr);
            if (swapchain) vkDestroySwapchainKHR(device, swapchain, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (surface) vkDestroySurfaceKHR(instance, surface, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
        if (window) ANativeWindow_release(window);
        window = nullptr; device = VK_NULL_HANDLE;
        if(vm){JNIEnv* env=nullptr;bool attached=false;
            if(vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK){
                if(vm->AttachCurrentThread(&env,nullptr)==JNI_OK)attached=true;}
            if(env){if(assetOwner)env->DeleteGlobalRef(assetOwner);if(surfaceOwner)env->DeleteGlobalRef(surfaceOwner);}
            if(attached)vm->DetachCurrentThread();}
        assetOwner=nullptr;surfaceOwner=nullptr;return true;
    }
};
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Producer>> registry;
jlong nextHandle = 1;
// Monotonic handles are retired only after cleanup; retain bounded acknowledgment history.
std::atomic<jlong> retiredThrough{0};
std::shared_ptr<Producer> find(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto entry = registry.find(handle); return entry == registry.end() ? nullptr : entry->second;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeStart(
    JNIEnv* env,jclass,jobject surface,jobject assets) {
    auto producer=std::make_shared<Producer>();env->GetJavaVM(&producer->vm);
    producer->window=ANativeWindow_fromSurface(env,surface);
    producer->assets=AAssetManager_fromJava(env,assets);
    if(!producer->window || !producer->assets){producer->cleanup();return 0;}
    producer->assetOwner=env->NewGlobalRef(assets);producer->surfaceOwner=env->NewGlobalRef(surface);
    if(!producer->assetOwner || !producer->surfaceOwner){producer->cleanup();return 0;}
    jlong handle;
    {std::lock_guard<std::mutex> lock(registryMutex);
        for(auto it=registry.begin();it!=registry.end();){
            if(!it->second->closed.load()){producer->cleanup();return 0;}
            retiredThrough.store(std::max(retiredThrough.load(),it->first));it=registry.erase(it);}
        if(nextHandle>=UINT32_MAX){producer->cleanup();return 0;}
        handle=nextHandle++;producer->generation=uint64_t(handle);registry.emplace(handle,producer);}
    try {std::thread([producer]{
        try{producer->initialize();producer->renderLoop();producer->setPhase("stopping");}
        catch(const std::exception& e){producer->setPhase(std::string("failed: ")+e.what());}
        if(producer->cleanup())producer->closed.store(true);
    }).detach();}
    catch(...){if(producer->cleanup())producer->closed.store(true);producer->setPhase("failed worker-start");return 0;}
    return handle;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeStop(
    JNIEnv*,jclass,jlong handle){if(auto p=find(handle)){
    stopHotloadOwner(p->hotloadOwnerMutex,p->running,p->hotload);
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
    p->stopWorkloadReload();
#endif
}}
namespace {
std::string quote(const std::string& s){std::string r="\"";for(unsigned char c:s){
    if(c=='"'||c=='\\'){r+='\\';r+=char(c);}else if(c>=32)r+=char(c);else r+=' ';}return r+'"';}
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeStatus(
    JNIEnv* env,jclass,jlong handle){
    auto p=find(handle);std::ostringstream s;
    if(!p){s<<"{\"phase\":\"absent\",\"teardownCompleted\":"<<((handle>0&&handle<=retiredThrough.load())?"true":"false")<<",\"sdkSampledProof\":false}";}
    else{std::lock_guard<std::mutex> lock(p->statusMutex);
        s<<"{\"phase\":"<<quote(p->phase)<<",\"generation\":"<<p->generation
         <<",\"submitted\":"<<p->submitted.load()<<",\"gpuFenceCompleted\":"<<p->completed.load()
         <<",\"wsi\":{\"selectionAvailable\":"<<(p->wsiSelectionAvailable.load()?"true":"false")
         <<",\"format\":"<<p->wsiFormat.load()<<",\"colorSpace\":"<<p->wsiColorSpace.load()
         <<",\"currentTransform\":"<<p->wsiCurrentTransform.load()<<",\"width\":"<<p->wsiWidth.load()<<",\"height\":"<<p->wsiHeight.load()
         <<",\"presentCalls\":"<<p->presentCalls.load()<<",\"presentAccepted\":"<<p->presentAccepted.load()
         <<",\"lastPresentResult\":"<<p->lastPresentResult.load()<<",\"presentationCompletionProof\":false}"
         <<",\"simulationStepsSubmitted\":"<<p->simulationSteps.load()
         <<",\"sourceStateGeneration\":"<<p->sourceStateGeneration.load()
         <<",\"sourceStateCompletionProof\":false"
         <<",\"publicationPausedRequested\":"<<(p->publicationPausedRequested.load()?"true":"false")
         <<",\"publicationPausedEffective\":"<<(p->publicationPausedEffective.load()?"true":"false")
         <<",\"heldSubmission\":"<<p->heldSubmission.load()<<",\"heldGeneration\":"<<p->heldGeneration.load()
         <<",\"computeMainCapacity\":"<<p->mainCapacity.load()<<",\"tracerCapacity\":"<<p->tracerCapacity.load()
         <<",\"baseHandCorners\":"<<p->baseHandCount.load()<<",\"graftCorners\":"<<p->graftCount.load()
         <<",\"sourceReady\":"<<p->sourceReady.load()<<",\"leftActive\":"<<p->leftActive.load()<<",\"rightActive\":"<<p->rightActive.load()
         <<",\"poseRevision\":"<<p->poseRevision.load()<<",\"poseXrTime\":"<<p->poseTime.load()<<",\"poseMonotonicNs\":"<<p->poseMonotonic.load()
         <<",\"controlRevision\":"<<p->controlRevision.load()<<",\"recordCpuNs\":"<<p->recordCpuNs.load()
         <<",\"inputStatus\":"<<quote(p->handInput.status())
         <<",\"poseTiming\":\"current-monotonic-converted-not-sdk-predicted\",\"coordinateMapping\":1"
         <<",\"colorEncoding\":\"UNORM8-clamp\",\"invalidCountAvailable\":false"
         <<",\"gpuTimingAvailable\":"<<(p->gpuTimingAvailable.load()?"true":"false")
         <<",\"computeGpuNs\":"<<p->computeGpuNs.load()<<",\"atlasAndColorWaitGpuNs\":"<<p->atlasAndColorWaitGpuNs.load()
         <<",\"gpuTimingSubmission\":"<<p->timingSubmission.load()<<",\"gpuTimingIncludesSdkRender\":false"
         <<",\"stopRequested\":"<<(p->running.load()?"false":"true")
         <<",\"teardownCompleted\":"<<(p->closed.load()?"true":"false")
         <<",\"resourcesRetained\":"<<(p->retained.load()?"true":"false")
         <<",\"sdkSampledProof\":false,\"cpuSimulation\":false,\"dynamicGeometryReadbackBytes\":0";
        {std::lock_guard<std::mutex> ownerLock(p->hotloadOwnerMutex);s<<",\"hotload\":"<<(p->hotload?p->hotload->statusJson():"null");}
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
        {std::lock_guard<std::mutex> ownerLock(p->workloadReloadMutex);s<<",\"workloadReload\":"<<(p->workload?p->workload->reloadStatusJson():"null")<<",\"workloadReloadIntake\":"<<p->workloadReloadIntake;}
#endif
        s<<"}";}
    return env->NewStringUTF(s.str().c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeAttachXr(
    JNIEnv*,jclass,jlong handle,jlong instance,jlong session,jlong proc,jlong generation,jint space,jboolean focused){
    auto p=find(handle);return p&&p->running.load()&&generation>0&&p->handInput.attach(instance,session,proc,p->generation,space,focused,uint64_t(generation));}
extern "C" JNIEXPORT void JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeSetFocused(
    JNIEnv*,jclass,jlong handle,jboolean focused){if(auto p=find(handle))p->handInput.setFocused(focused);}
extern "C" JNIEXPORT void JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeDetachXr(
    JNIEnv*,jclass,jlong handle){if(auto p=find(handle))p->handInput.detach();}
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeConfigure(
    JNIEnv* env,jclass,jlong handle,jstring json){
    auto p=find(handle);if(!p||!p->running.load()||!json)return JNI_FALSE;
    jclass cls=env->FindClass("org/json/JSONObject");if(!cls){env->ExceptionClear();return JNI_FALSE;}
    const auto ctor=env->GetMethodID(cls,"<init>","(Ljava/lang/String;)V");
    jobject obj=env->NewObject(cls,ctor,json);
    if(env->ExceptionCheck()||!obj){env->ExceptionClear();return JNI_FALSE;}
    auto keys=env->GetMethodID(cls,"keys","()Ljava/util/Iterator;");
    auto has=env->GetMethodID(cls,"has","(Ljava/lang/String;)Z");
    auto getNumber=env->GetMethodID(cls,"getDouble","(Ljava/lang/String;)D");
    auto getBool=env->GetMethodID(cls,"getBoolean","(Ljava/lang/String;)Z");
    if(env->ExceptionCheck()||!keys||!has||!getNumber||!getBool){env->ExceptionClear();return JNI_FALSE;}
    jobject it=env->CallObjectMethod(obj,keys);
    if(env->ExceptionCheck()||!it){env->ExceptionClear();return JNI_FALSE;}
    jclass iterator=env->FindClass("java/util/Iterator");
    if(env->ExceptionCheck()||!iterator){env->ExceptionClear();return JNI_FALSE;}
    auto hasNext=env->GetMethodID(iterator,"hasNext","()Z");auto next=env->GetMethodID(iterator,"next","()Ljava/lang/Object;");
    if(env->ExceptionCheck()||!hasNext||!next){env->ExceptionClear();return JNI_FALSE;}
    const std::vector<std::string> allowed={"drawMainCount","drawTracerCount","drawHands","drawGrafts","simulate","reset","publicationPaused","visualScale","graftScale","centerX","centerY","centerZ","worldScale","controlRevision"};
    bool valid=true;
    while(valid){
        const bool more=env->CallBooleanMethod(it,hasNext);
        if(env->ExceptionCheck()){env->ExceptionClear();valid=false;break;}
        if(!more)break;
        auto key=static_cast<jstring>(env->CallObjectMethod(it,next));
        if(env->ExceptionCheck()||!key){env->ExceptionClear();valid=false;break;}
        const char* chars=env->GetStringUTFChars(key,nullptr);
        if(!chars){env->ExceptionClear();valid=false;break;}
        const bool known=std::find(allowed.begin(),allowed.end(),chars)!=allowed.end();
        env->ReleaseStringUTFChars(key,chars);env->DeleteLocalRef(key);if(!known){valid=false;break;}
    }
    Configuration c;{std::lock_guard<std::mutex> lock(p->configMutex);c=p->configuration;}
    auto number=[&](const char* name,double value,double lo,double hi,bool integer=false){
        if(!valid)return lo;
        jstring key=env->NewStringUTF(name);
        if(!key||env->ExceptionCheck()){env->ExceptionClear();valid=false;return lo;}
        const bool present=env->CallBooleanMethod(obj,has,key);
        if(!env->ExceptionCheck()&&present)value=env->CallDoubleMethod(obj,getNumber,key);
        if(env->ExceptionCheck()){env->ExceptionClear();valid=false;}
        env->DeleteLocalRef(key);
        if(!valid||!std::isfinite(value)||value<lo||value>hi||(integer&&std::floor(value)!=value)){valid=false;return lo;}
        return value;
    };
    auto boolean=[&](const char* name,bool value){
        if(!valid)return value;
        jstring key=env->NewStringUTF(name);
        if(!key||env->ExceptionCheck()){env->ExceptionClear();valid=false;return value;}
        const bool present=env->CallBooleanMethod(obj,has,key);
        if(!env->ExceptionCheck()&&present)value=env->CallBooleanMethod(obj,getBool,key);
        if(env->ExceptionCheck()){env->ExceptionClear();valid=false;}
        env->DeleteLocalRef(key);return value;
    };
    c.workload.drawMainCount=uint32_t(number("drawMainCount",c.workload.drawMainCount,0,2562,true));
    c.workload.drawTracerCount=uint32_t(number("drawTracerCount",c.workload.drawTracerCount,0,17934,true));
    c.drawHands=boolean("drawHands",c.drawHands);c.drawGrafts=boolean("drawGrafts",c.drawGrafts);
    c.publicationPaused=boolean("publicationPaused",c.publicationPaused);
    c.workload.simulate=boolean("simulate",c.workload.simulate);c.workload.reset=boolean("reset",c.workload.reset);
    c.workload.visualScale=float(number("visualScale",c.workload.visualScale,.01,2));
    c.graftScale=float(number("graftScale",c.graftScale,.01,2));
    c.workload.worldCenterScale[0]=float(number("centerX",c.workload.worldCenterScale[0],-8,8));
    c.workload.worldCenterScale[1]=float(number("centerY",c.workload.worldCenterScale[1],0,8));
    c.workload.worldCenterScale[2]=float(number("centerZ",c.workload.worldCenterScale[2],-8,8));
    c.workload.worldCenterScale[3]=float(number("worldScale",c.workload.worldCenterScale[3],.01,4));
    c.revision=uint32_t(number("controlRevision",c.revision,0,UINT32_MAX,true));
    if(env->ExceptionCheck()){env->ExceptionClear();valid=false;}
    env->DeleteLocalRef(obj);env->DeleteLocalRef(cls);env->DeleteLocalRef(iterator);env->DeleteLocalRef(it);
    if(!valid)return JNI_FALSE;
    {std::lock_guard<std::mutex> lock(p->configMutex);p->configuration=c;p->publicationPausedRequested.store(c.publicationPaused);}
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeHotload(
    JNIEnv* env,jclass,jlong handle,jstring action,jlong expectedGeneration,jlong requestId){
    auto p=find(handle);std::string response="{\"phase\":\"rejected\",\"reason\":\"inactive-or-invalid-request\"}";
    if(p&&p->running.load()&&action&&expectedGeneration>=0&&requestId>0){
        const char* chars=env->GetStringUTFChars(action,nullptr);
        if(!chars){env->ExceptionClear();return env->NewStringUTF(response.c_str());}
        const std::string value(chars);env->ReleaseStringUTFChars(action,chars);
        std::lock_guard<std::mutex> lock(p->hotloadOwnerMutex);
        if(p->hotload)response=p->hotload->request(value,std::to_string(requestId),uint64_t(expectedGeneration));
    }
    return env->NewStringUTF(response.c_str());
}

namespace {
bool hotloadAscii(JNIEnv* env,jstring input,size_t limit,std::string& out){
    if(!input)return false;
    const auto length=env->GetStringLength(input);if(length<0||size_t(length)>limit)return false;
    const jchar* chars=env->GetStringChars(input,nullptr);if(!chars){env->ExceptionClear();return false;}
    bool valid=true;out.clear();out.reserve(size_t(length));
    for(jsize i=0;i<length;++i){if(chars[i]<32||chars[i]>126){valid=false;break;}out.push_back(char(chars[i]));}
    env->ReleaseStringChars(input,chars);return valid;
}
const char* inactiveExternal="{\"phase\":\"rejected\",\"reason\":\"inactive_or_invalid_external_request\"}";
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeHotloadStage(
    JNIEnv* env,jclass,jlong handle,jbyteArray bytes,jstring digest,jstring abi,jlong expectedProducer,
    jlong expectedGeneration,jstring expectedDigest,jlong requestId){
    std::string codeDigest,abiDigest,activeDigest;auto p=find(handle);
    if(!p||!bytes||requestId<=0||expectedGeneration<=0||
       !hotloadAscii(env,digest,64,codeDigest)||!hotloadAscii(env,abi,64,abiDigest)||!hotloadAscii(env,expectedDigest,64,activeDigest))return env->NewStringUTF(inactiveExternal);
    const jsize length=env->GetArrayLength(bytes);
    std::lock_guard<std::mutex> ownerLock(p->hotloadOwnerMutex);
    if(!p->hotload)return env->NewStringUTF(inactiveExternal);
    const auto reject=[&](const char* reason){return p->hotload->rejectRequest("stage",codeDigest,reason,uint64_t(expectedGeneration),activeDigest,std::to_string(requestId));};
    std::string response;
    if(!p->running.load()||expectedProducer<=0||uint64_t(expectedProducer)!=p->generation)response=reject("stale_or_stopped_producer");
    else if(length<20||length>65536||length%4)response=reject("external_length");
    else try{
        std::vector<uint32_t> code(size_t(length)/4);
        env->GetByteArrayRegion(bytes,0,length,reinterpret_cast<jbyte*>(code.data()));
        if(env->ExceptionCheck()){env->ExceptionClear();response=reject("external_byte_copy_failed");}
        else response=p->hotload->stage(std::move(code),codeDigest,abiDigest,uint64_t(expectedGeneration),activeDigest,std::to_string(requestId));
    }catch(const std::exception&){response=reject("external_intake_failed");}
    return env->NewStringUTF(response.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_GpuWorkloadProducer_nativeHotloadExternal(
    JNIEnv* env,jclass,jlong handle,jstring action,jstring candidate,jlong expectedProducer,
    jlong expectedGeneration,jstring expectedDigest,jlong requestId){
    std::string verb,id,digest;auto p=find(handle);
    if(!p||requestId<=0||expectedGeneration<=0||!hotloadAscii(env,action,16,verb)||
       !hotloadAscii(env,candidate,64,id)||!hotloadAscii(env,expectedDigest,64,digest))return env->NewStringUTF(inactiveExternal);
    std::lock_guard<std::mutex> ownerLock(p->hotloadOwnerMutex);
    if(!p->hotload)return env->NewStringUTF(inactiveExternal);
    std::string response;
    try{
        if(!p->running.load()||expectedProducer<=0||uint64_t(expectedProducer)!=p->generation)
            response=p->hotload->rejectRequest(verb,id,"stale_or_stopped_producer",uint64_t(expectedGeneration),digest,std::to_string(requestId));
        else response=p->hotload->externalRequest(verb,id,uint64_t(expectedGeneration),digest,std::to_string(requestId));
    }catch(const std::exception&){response=p->hotload->rejectRequest(verb,id,"external_request_failed",uint64_t(expectedGeneration),digest,std::to_string(requestId));}
    return env->NewStringUTF(response.c_str());
}

#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
namespace {
std::string workloadRejected(const char* reason){return std::string("{\"phase\":\"rejected\",\"reason\":\"")+reason+"\"}";}
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_ExternalWorkloadNative_stage(
    JNIEnv* env,jobject,jlong handle,jbyteArray bytes,jstring digest,jstring abi,jlong expectedProducer,
    jlong expectedGeneration,jstring expectedDigest,jlong requestId){
    auto p=find(handle);std::string codeDigest,abiDigest,activeDigest;
    if(!p)return env->NewStringUTF(workloadRejected("inactive_producer").c_str());
    std::lock_guard<std::mutex> lock(p->workloadReloadMutex);
    auto reject=[&](const char* reason){p->workloadReloadIntake=workloadRejected(reason);return env->NewStringUTF(p->workloadReloadIntake.c_str());};
    if(!bytes||expectedProducer<=0||expectedGeneration<=0||requestId<=0||
       !hotloadAscii(env,digest,64,codeDigest)||!hotloadAscii(env,abi,64,abiDigest)||!hotloadAscii(env,expectedDigest,64,activeDigest))return reject("invalid_context");
    const char* context=externalWorkloadContext(p->running.load(),p->generation,uint64_t(expectedProducer),uint64_t(expectedGeneration),activeDigest,uint64_t(requestId));
    if(*context)return reject(context);
    const jsize length=env->GetArrayLength(bytes);
    const char* input=externalWorkloadStage(size_t(length),codeDigest,abiDigest);
    if(*input)return reject(input);
    if(!p->workload)return reject("backend_unavailable");
    try{
        std::vector<uint32_t> code(size_t(length)/4);
        env->GetByteArrayRegion(bytes,0,length,reinterpret_cast<jbyte*>(code.data()));
        if(env->ExceptionCheck()){env->ExceptionClear();return reject("external_byte_copy_failed");}
        p->workloadReloadIntake=p->workload->reloadStage(std::move(code),codeDigest,abiDigest,uint64_t(expectedProducer),uint64_t(expectedGeneration),activeDigest,uint64_t(requestId));
    }catch(const std::exception&){p->workloadReloadIntake="{\"phase\":\"dispatch_result_unknown\",\"reason\":\"backend_stage_exception\",\"automaticRetry\":false}";}
    return env->NewStringUTF(p->workloadReloadIntake.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_ExternalWorkloadNative_request(
    JNIEnv* env,jobject,jlong handle,jstring action,jstring candidate,jlong expectedProducer,
    jlong expectedGeneration,jstring expectedDigest,jlong requestId){
    auto p=find(handle);std::string verb,id,digest;
    if(!p)return env->NewStringUTF(workloadRejected("inactive_producer").c_str());
    std::lock_guard<std::mutex> lock(p->workloadReloadMutex);
    auto reject=[&](const char* reason){p->workloadReloadIntake=workloadRejected(reason);return env->NewStringUTF(p->workloadReloadIntake.c_str());};
    if(expectedProducer<=0||expectedGeneration<=0||requestId<=0||!hotloadAscii(env,action,16,verb)||
       !hotloadAscii(env,candidate,64,id)||!hotloadAscii(env,expectedDigest,64,digest))return reject("invalid_context");
    const char* context=externalWorkloadContext(p->running.load(),p->generation,uint64_t(expectedProducer),uint64_t(expectedGeneration),digest,uint64_t(requestId));
    if(*context)return reject(context);
    const char* input=externalWorkloadAction(verb,id);if(*input)return reject(input);
    if(!p->workload)return reject("backend_unavailable");
    try{p->workloadReloadIntake=p->workload->reloadRequest(verb,id,uint64_t(expectedProducer),uint64_t(expectedGeneration),digest,uint64_t(requestId));}
    catch(const std::exception&){p->workloadReloadIntake="{\"phase\":\"dispatch_result_unknown\",\"reason\":\"backend_request_exception\",\"automaticRetry\":false}";}
    return env->NewStringUTF(p->workloadReloadIntake.c_str());
}
#endif
