// Link the production implementation to test-only Vulkan/Android effects.
// This tests real intake/transaction paths, not GPU execution or shader semantics.
#define main runSpirvInterfaceFixtures
#include "hotload_spirv_contract_test.cpp"
#undef main
#include "../../main/cpp/hotload_kernel.cpp"
#include "../../main/cpp/hotload_owner.h"
#include <algorithm>
#include <condition_variable>
#include <set>
#include <thread>

struct AAssetManager {};
struct AAsset {std::vector<uint8_t> bytes;size_t offset=0;};
static unsigned moduleCalls=0,pipelineCalls=0;
static bool failPipeline=false;
static uintptr_t nextHandle=1;
static std::set<uintptr_t> pipelines;
static std::vector<uintptr_t> createdPipelines;
static uintptr_t lastBoundPipeline=0;
template<class T> static T handle(){return (T)(nextHandle++);}
extern "C" AAsset* AAssetManager_open(AAssetManager*,const char*,int){
    auto words=fixture();auto* result=new AAsset;const auto* p=reinterpret_cast<const uint8_t*>(words.data());
    result->bytes.assign(p,p+words.size()*4);return result;
}
extern "C" int64_t AAsset_getLength64(AAsset* asset){return int64_t(asset->bytes.size());}
extern "C" int AAsset_read(AAsset* asset,void* buffer,size_t bytes){
    bytes=std::min(bytes,asset->bytes.size()-asset->offset);std::memcpy(buffer,asset->bytes.data()+asset->offset,bytes);asset->offset+=bytes;return int(bytes);
}
extern "C" void AAsset_close(AAsset* asset){delete asset;}
#define VKFN(result,name,args) extern "C" VKAPI_ATTR result VKAPI_CALL name args
VKFN(VkResult,vkCreateBuffer,(VkDevice,const VkBufferCreateInfo*,const VkAllocationCallbacks*,VkBuffer* out)){*out=handle<VkBuffer>();return VK_SUCCESS;}
VKFN(void,vkGetBufferMemoryRequirements,(VkDevice,VkBuffer,VkMemoryRequirements* out)){*out={128,16,1};}
VKFN(VkResult,vkAllocateMemory,(VkDevice,const VkMemoryAllocateInfo*,const VkAllocationCallbacks*,VkDeviceMemory* out)){*out=handle<VkDeviceMemory>();return VK_SUCCESS;}
VKFN(VkResult,vkBindBufferMemory,(VkDevice,VkBuffer,VkDeviceMemory,VkDeviceSize)){return VK_SUCCESS;}
VKFN(VkResult,vkCreateDescriptorSetLayout,(VkDevice,const VkDescriptorSetLayoutCreateInfo*,const VkAllocationCallbacks*,VkDescriptorSetLayout* out)){*out=handle<VkDescriptorSetLayout>();return VK_SUCCESS;}
VKFN(VkResult,vkCreatePipelineLayout,(VkDevice,const VkPipelineLayoutCreateInfo*,const VkAllocationCallbacks*,VkPipelineLayout* out)){*out=handle<VkPipelineLayout>();return VK_SUCCESS;}
VKFN(VkResult,vkCreateDescriptorPool,(VkDevice,const VkDescriptorPoolCreateInfo*,const VkAllocationCallbacks*,VkDescriptorPool* out)){*out=handle<VkDescriptorPool>();return VK_SUCCESS;}
VKFN(VkResult,vkAllocateDescriptorSets,(VkDevice,const VkDescriptorSetAllocateInfo*,VkDescriptorSet* out)){*out=handle<VkDescriptorSet>();return VK_SUCCESS;}
VKFN(void,vkUpdateDescriptorSets,(VkDevice,uint32_t,const VkWriteDescriptorSet*,uint32_t,const VkCopyDescriptorSet*)){}
VKFN(VkResult,vkCreateShaderModule,(VkDevice,const VkShaderModuleCreateInfo*,const VkAllocationCallbacks*,VkShaderModule* out)){++moduleCalls;*out=handle<VkShaderModule>();return VK_SUCCESS;}
VKFN(VkResult,vkCreateComputePipelines,(VkDevice,VkPipelineCache,uint32_t,const VkComputePipelineCreateInfo*,const VkAllocationCallbacks*,VkPipeline* out)){
    ++pipelineCalls;if(failPipeline){*out=VK_NULL_HANDLE;return VK_ERROR_INITIALIZATION_FAILED;}
    *out=handle<VkPipeline>();assert(pipelines.insert((uintptr_t)*out).second);createdPipelines.push_back((uintptr_t)*out);return VK_SUCCESS;
}
VKFN(void,vkDestroyShaderModule,(VkDevice,VkShaderModule,const VkAllocationCallbacks*)){}
VKFN(void,vkDestroyPipeline,(VkDevice,VkPipeline pipeline,const VkAllocationCallbacks*)){assert(pipelines.erase((uintptr_t)pipeline)==1);}
VKFN(void,vkDestroyDescriptorPool,(VkDevice,VkDescriptorPool,const VkAllocationCallbacks*)){}
VKFN(void,vkDestroyPipelineLayout,(VkDevice,VkPipelineLayout,const VkAllocationCallbacks*)){}
VKFN(void,vkDestroyDescriptorSetLayout,(VkDevice,VkDescriptorSetLayout,const VkAllocationCallbacks*)){}
VKFN(void,vkDestroyBuffer,(VkDevice,VkBuffer,const VkAllocationCallbacks*)){}
VKFN(void,vkFreeMemory,(VkDevice,VkDeviceMemory,const VkAllocationCallbacks*)){}
VKFN(void,vkCmdBindPipeline,(VkCommandBuffer,VkPipelineBindPoint,VkPipeline pipeline)){assert(pipelines.count((uintptr_t)pipeline)==1);lastBoundPipeline=(uintptr_t)pipeline;}
VKFN(void,vkCmdBindDescriptorSets,(VkCommandBuffer,VkPipelineBindPoint,VkPipelineLayout,uint32_t,uint32_t,const VkDescriptorSet*,uint32_t,const uint32_t*)){}
VKFN(void,vkCmdPushConstants,(VkCommandBuffer,VkPipelineLayout,VkShaderStageFlags,uint32_t,uint32_t,const void*)){}
VKFN(void,vkCmdDispatch,(VkCommandBuffer,uint32_t,uint32_t,uint32_t)){}
VKFN(void,vkCmdPipelineBarrier,(VkCommandBuffer,VkPipelineStageFlags,VkPipelineStageFlags,VkDependencyFlags,uint32_t,const VkMemoryBarrier*,uint32_t,const VkBufferMemoryBarrier*,uint32_t,const VkImageMemoryBarrier*)){}
#undef VKFN

static bool contains(const std::string& text,const std::string& value){return text.find(value)!=std::string::npos;}
static std::string digest(const Words& words){return rq::workload::hotloadSha256(reinterpret_cast<const uint8_t*>(words.data()),words.size()*4);}
int main(){
    using namespace rq::workload;
    GpuContext gpu;gpu.device=handle<VkDevice>();gpu.memoryProperties.memoryTypeCount=1;gpu.memoryProperties.memoryTypes[0].propertyFlags=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    AAssetManager assets;HotloadKernel kernel(gpu,&assets);const auto baseline=digest(fixture());const auto output=kernel.output().buffer;
    auto frame=[&](uint64_t serial){FrameContext f;f.generation=1;f.submission=serial;kernel.record(handle<VkCommandBuffer>(),f);kernel.confirmSubmitted(serial);kernel.confirmCompleted(serial);};
    frame(1);assert(moduleCalls==1&&pipelineCalls==1);
    const auto originalHandle=lastBoundPipeline;assert(originalHandle==createdPipelines[0]);
    auto good=fixture();for(size_t at=5;at<good.size();at+=good[at]>>16)if((good[at]&65535)==128)good[at]=(good[at]&0xffff0000)|132;
    const auto candidate=digest(good);
    auto identity=[&](const std::string& active,const char* activeTag,const std::string& previous,const char* previousTag){
        const auto status=kernel.statusJson();
        assert(contains(status,"\"active_digest\":\""+active+"\""));
        assert(contains(status,std::string("\"active_tag\":\"")+activeTag+"\""));
        assert(contains(status,"\"rollback_digest\":\""+previous+"\""));
        assert(contains(status,std::string("\"rollback_tag\":\"")+previousTag+"\""));
    };
    auto unchanged=[&](){assert(kernel.generation()==1&&kernel.tag()==0&&kernel.output().buffer==output);assert(contains(kernel.statusJson(),"\"active_digest\":\""+baseline+"\""));};
    auto bad=good;bad[0]=0;assert(contains(kernel.stage(bad,digest(bad),externalHotloadAbiSha256,1,baseline,"1"),"external_spirv_header"));unchanged();
    bad=good;mutate(bad,71,7,2,32);assert(contains(kernel.stage(bad,digest(bad),externalHotloadAbiSha256,1,baseline,"2"),"rejected"));unchanged();
    assert(contains(kernel.stage(good,candidate,externalHotloadAbiSha256,0,baseline,"3"),"stale_generation"));unchanged();
    assert(contains(kernel.externalRequest("activate",candidate,1,baseline,"4"),"unknown_candidate"));unchanged();
    assert(moduleCalls==1&&pipelineCalls==1); // Real candidate rejection paths never touched fake driver.
    assert(contains(kernel.statusJson(),"\"shader_module_create_calls\":1,\"compute_pipeline_create_calls\":1"));
    assert(contains(kernel.stage(good,candidate,externalHotloadAbiSha256,1,baseline,"5"),"\"phase\":\"staged\""));unchanged();
    assert(contains(kernel.externalRequest("activate",candidate,1,baseline,"6"),"queued"));
    failPipeline=true;kernel.afterRetirement(1);failPipeline=false;unchanged();assert(pipelines.size()==1);
    assert(contains(kernel.externalRequest("activate",candidate,1,baseline,"6"),"rejected"));assert(pipelineCalls==2);
    assert(contains(kernel.statusJson(),"\"shader_module_create_calls\":2,\"compute_pipeline_create_calls\":2"));
    assert(contains(kernel.externalRequest("activate",candidate,1,baseline,"7"),"queued"));kernel.afterRetirement(1);
    assert(kernel.generation()==2&&kernel.tag()==2&&pipelines.size()==2&&contains(kernel.statusJson(),candidate));
    identity(candidate,"external",baseline,"A0");
    assert(contains(kernel.externalRequest("rollback","",2,candidate,"8"),"busy"));
    assert(contains(kernel.rejectRequest("activate",candidate,"stale_or_stopped_producer",2,candidate,"80"),"rejected"));
    assert(contains(kernel.statusJson(),"\"request_id\":\"7\",\"action\":\"activate\"")); // pre-admission reject cannot cancel live activation
    frame(2);assert(contains(kernel.statusJson(),"\"active_completed_serial\":2"));
    const auto externalHandle=lastBoundPipeline;assert(externalHandle==createdPipelines[1]&&externalHandle!=originalHandle);
    assert(contains(kernel.externalRequest("rollback","",2,candidate,"9"),"queued"));kernel.afterRetirement(2);
    assert(kernel.generation()==3&&kernel.tag()==0&&pipelines.size()==2&&pipelineCalls==3);frame(3);assert(lastBoundPipeline==originalHandle);
    identity(baseline,"A0",candidate,"external");
    assert(contains(kernel.statusJson(),"\"rollback_digest\":\""+candidate+"\""));
    assert(contains(kernel.externalRequest("rollback","",3,baseline,"10"),"queued"));kernel.afterRetirement(3);frame(4);
    assert(kernel.generation()==4&&kernel.tag()==2&&lastBoundPipeline==externalHandle&&pipelines.count(originalHandle)==1);
    identity(candidate,"external",baseline,"A0");
    assert(contains(kernel.request("revert","11",4),"queued"));kernel.afterRetirement(4);frame(5);
    assert(kernel.generation()==5&&kernel.tag()==0&&pipelines.size()==2&&lastBoundPipeline==createdPipelines[2]);
    assert(pipelines.count(originalHandle)==0&&pipelines.count(externalHandle)==1); // only older retained pipeline retired
    identity(baseline,"A0",candidate,"external");
    assert(contains(kernel.statusJson(),"\"active_digest\":\""+baseline+"\""));
    assert(contains(kernel.statusJson(),"\"rollback_digest\":\""+candidate+"\""));
    assert(contains(kernel.stage(good,candidate,externalHotloadAbiSha256,5,baseline,"12"),"staged"));
    assert(contains(kernel.externalRequest("discard",candidate,5,baseline,"13"),"discarded"));
    assert(contains(kernel.externalRequest("activate",candidate,5,baseline,"14"),"unknown_candidate"));
    assert(kernel.generation()==5&&lastBoundPipeline==createdPipelines[2]&&pipelineCalls==4);
    assert(contains(kernel.stage(good,candidate,externalHotloadAbiSha256,5,baseline,"15"),"staged"));
    kernel.stop();assert(contains(kernel.statusJson(),"\"staged_digest\":\"\""));
    kernel.stop();kernel.destroyAfterRetirement();assert(pipelines.empty());
    HotloadKernel stopped(gpu,&assets);FrameContext first;first.generation=2;first.submission=1;
    stopped.record(handle<VkCommandBuffer>(),first);stopped.confirmSubmitted(1);stopped.confirmCompleted(1);
    assert(contains(stopped.stage(good,candidate,externalHotloadAbiSha256,1,baseline,"1"),"staged"));
    assert(contains(stopped.externalRequest("activate",candidate,1,baseline,"2"),"queued"));
    const auto callsBeforeStop=pipelineCalls;stopped.stop();stopped.afterRetirement(1);
    assert(stopped.generation()==1&&pipelineCalls==callsBeforeStop&&contains(stopped.statusJson(),"stopped_before_activation"));
    stopped.destroyAfterRetirement();assert(pipelines.empty());
    // Exact adapter transitions used by JNI and renderLoop: pause the worker
    // after its earlier running check but before it acquires the owner boundary.
    for(bool stopWins : {true,false}) {
        std::mutex owner,gate;std::condition_variable signal;
        bool waiting=false,resume=false;std::atomic<bool> running{true};
        auto owned=std::make_unique<HotloadKernel>(gpu,&assets);
        FrameContext initial;initial.generation=3;initial.submission=1;
        owned->record(handle<VkCommandBuffer>(),initial);owned->confirmSubmitted(1);owned->confirmCompleted(1);
        assert(contains(owned->stage(good,candidate,externalHotloadAbiSha256,1,baseline,"1"),"staged"));
        assert(contains(owned->externalRequest("activate",candidate,1,baseline,"2"),"queued"));
        const auto beforeModules=moduleCalls,beforePipelines=pipelineCalls;
        std::thread worker([&]{
            assert(running.load()); // Match the earlier producer loop check.
            {std::unique_lock<std::mutex> lock(gate);waiting=true;signal.notify_one();signal.wait(lock,[&]{return resume;});}
            retireHotloadOwner(owner,running,owned,1);
        });
        {std::unique_lock<std::mutex> lock(gate);signal.wait(lock,[&]{return waiting;});}
        if(stopWins)stopHotloadOwner(owner,running,owned);
        {std::lock_guard<std::mutex> lock(gate);resume=true;}signal.notify_one();worker.join();
        if(!stopWins)stopHotloadOwner(owner,running,owned); // Boundary completed before stop linearized.
        assert(!running.load());
        assert(contains(owned->statusJson(),"\"staged_digest\":\"\""));
        assert(owned->generation()==(stopWins?1u:2u));
        assert(owned->tag()==(stopWins?0u:2u));
        assert(contains(owned->statusJson(),"\"active_digest\":\""+(stopWins?baseline:candidate)+"\""));
        assert(moduleCalls==beforeModules+(stopWins?0u:1u)&&pipelineCalls==beforePipelines+(stopWins?0u:1u));
        assert(pipelines.size()==(stopWins?1u:2u)); // Stop performed no destruction.
        if(stopWins)assert(contains(owned->statusJson(),"stopped_before_activation"));
        const auto stoppedModules=moduleCalls,stoppedPipelines=pipelineCalls;
        retireHotloadOwner(owner,running,owned,1);
        assert(moduleCalls==stoppedModules&&pipelineCalls==stoppedPipelines);
        owned->destroyAfterRetirement();assert(pipelines.empty());
    }
    std::cout<<"PASS production HotloadKernel with fake driver: real bad bytes / ABI / stale / unknown / prepare failure / activation / rollback / baseline revert\n";
}
