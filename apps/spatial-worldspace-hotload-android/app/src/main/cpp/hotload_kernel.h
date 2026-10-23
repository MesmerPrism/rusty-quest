#pragma once
#include "workload_backend.h"
#include <string>
#include <vector>
namespace rq::workload {
class HotloadKernel {
public:
 HotloadKernel(const GpuContext&,AAssetManager*);
 ~HotloadKernel();
 ParticleOutput output() const;
 std::string request(const std::string& action,const std::string& requestId,uint64_t expectedGeneration);
 std::string stage(std::vector<uint32_t> code,const std::string& digest,const std::string& abi,
                   uint64_t expectedGeneration,const std::string& expectedDigest,const std::string& requestId);
 std::string externalRequest(const std::string& action,const std::string& candidate,
                   uint64_t expectedGeneration,const std::string& expectedDigest,const std::string& requestId);
 std::string rejectRequest(const std::string& action,const std::string& candidate,const std::string& reason,
                   uint64_t expectedGeneration,const std::string& expectedDigest,const std::string& requestId);
 std::string statusJson() const;
 uint64_t generation() const;
 uint32_t tag() const;
 // Only after successful fence retirement AND vkResetCommandBuffer.
 void afterRetirement(uint64_t completedSerial);
 void record(VkCommandBuffer,const FrameContext&);
 void confirmSubmitted(uint64_t serial);
 void confirmCompleted(uint64_t serial);
 void stop();
 // Caller proves every GPU use retired and recorded command buffers reset/destroyed.
 void destroyAfterRetirement();
private:
 struct Impl; std::unique_ptr<Impl> impl_;
};
}
