#pragma once

#include <android/asset_manager.h>
#include <vulkan/vulkan.h>
#include <cstdint>
#include <memory>
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
#include <string>
#include <vector>
#endif

namespace rq::workload {

// The caller owns the device, queue, submission and retirement. A backend only
// records commands and owns its explicitly allocated buffers and pipelines.
struct GpuContext {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    VkPhysicalDeviceProperties properties{};
};

struct ViewPose {
    float position[3] = {0, 1.4f, 0};
    float quaternion[4] = {0, 0, 0, 1};
};

struct WorkloadConfiguration {
    uint32_t dataset = 0;
    uint32_t drawMainCount = 2562;
    uint32_t drawTracerCount = 17934;
    bool simulate = true;
    bool reset = false;
    float visualScale = 0.70f;
    float worldCenterScale[4] = {0, 1.4f, -1.5f, 0.35f};
    float controls[8] = {0, 1, 0, 0, 0, 0, 0, 0};
};

struct FrameContext {
    uint64_t generation = 0;
    uint64_t submission = 0;
    uint64_t monotonicNs = 0;
    float deltaSeconds = 0;
    double elapsedSeconds = 0;
    ViewPose viewer{};
};

// Four std430 vec4 rows per instance, retaining all backend visual attributes.
// Positions are OpenXR right-handed, floor-relative metres. The atlas adapter
// applies the explicitly selected SDK-space conversion exactly once.
struct ParticleOutput {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceSize offset = 0;
    VkDeviceSize bytes = 0;
    uint32_t mainCount = 0;
    uint32_t tracerCapacity = 0;
    uint32_t instanceCapacity = 0;
    uint32_t rowsPerInstance = 4;
    bool initialized = false;
    // Recorded candidate identity, not completed GPU work or SDK consumption.
    // Paused state can be republished in a newer atlas packet without advancing.
    uint64_t sourceStateSequence = 0;
    uint64_t sourceStateGeneration = 0;
    bool dispatchRecorded = false;
};

class ComputeWorkload {
public:
    virtual ~ComputeWorkload() = default;
    virtual ParticleOutput output() const = 0;
    virtual void record(VkCommandBuffer command, const FrameContext& frame,
                        const WorkloadConfiguration& configuration) = 0;
    // Call only after successful vkQueueSubmit. Recording does not advance state.
    virtual void confirmSubmitted() = 0;
    virtual uint64_t simulationStepsSubmitted() const = 0;
#if defined(RQ_EXTERNAL_WORKLOAD_HOTLOAD) && RQ_EXTERNAL_WORKLOAD_HOTLOAD
    // Explicit debug-only code intake. The backend owns ABI validation, copied
    // candidate bytes and kernel transactions; none of these calls reset state.
    virtual std::string reloadStage(std::vector<uint32_t>, const std::string&,
        const std::string&, uint64_t, uint64_t, const std::string&, uint64_t) {
        return "{\"phase\":\"rejected\",\"reason\":\"backend_unsupported\"}";
    }
    virtual std::string reloadRequest(const std::string&, const std::string&,
        uint64_t, uint64_t, const std::string&, uint64_t) {
        return "{\"phase\":\"rejected\",\"reason\":\"backend_unsupported\"}";
    }
    virtual std::string reloadStatusJson() const {
        return "{\"available\":false,\"phase\":\"unsupported\"}";
    }
    // Retirement: actual fence success AND command-buffer reset, before record.
    virtual void afterReloadRetirement(uint64_t, uint64_t) {}
    // Submission/completion are separate facts from recording and activation.
    virtual void confirmReloadSubmitted(uint64_t) {}
    virtual void confirmReloadCompleted(uint64_t) {}
    virtual void stopReload() {}
#endif
    // Caller proves every using submission retired before calling this method.
    virtual void destroyAfterRetirement() = 0;
};

// Supplied by an explicitly selected external implementation. The public build
// fails with an unavailable workload when no implementation is selected.
std::unique_ptr<ComputeWorkload> createExternalWorkload(
    const GpuContext& context, AAssetManager* assets, uint32_t dataset);

} // namespace rq::workload
