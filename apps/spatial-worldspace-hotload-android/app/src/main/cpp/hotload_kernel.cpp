#include "hotload_kernel.h"
#include "hotload_state.h"
#include "hotload_validation.h"
#include "hotload_spirv_contract.h"
#include "hotload_catalog.h"
#include <cstring>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <vector>
namespace rq::workload {
namespace {
void check(VkResult result,const char* op){if(result!=VK_SUCCESS)throw std::runtime_error(std::string(op)+":"+std::to_string(result));}
std::string escaped(const std::string& s){std::string o;for(char c:s){if(c=='"'||c=='\\')o+='\\';if(static_cast<unsigned char>(c)<32)o+=' ';else o+=c;}return o;}
}
struct HotloadKernel::Impl {
 GpuContext gpu; AAssetManager* assets; mutable std::mutex mutex; HotloadState state;
 VkBuffer buffer=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;
 VkDescriptorSetLayout descriptorLayout=VK_NULL_HANDLE;VkPipelineLayout layout=VK_NULL_HANDLE;
 VkDescriptorPool pool=VK_NULL_HANDLE;VkDescriptorSet set=VK_NULL_HANDLE;VkPipeline pipeline=VK_NULL_HANDLE;
 VkPipeline rollback=VK_NULL_HANDLE;
 unsigned rollbackTag=0;
 std::string activeDigest=hotloadCatalog[0].sha256,activeOrigin="packaged-a",rollbackDigest,rollbackOrigin;
 std::vector<uint32_t> stagedCode;
 std::string stagedDigest,stagedExpectedDigest;
 uint64_t stagedExpectedGeneration=0;
 HotloadReceipt lastRejection;
 uint64_t lastSubmitted=0,lastCompleted=0,recordedGeneration=0,recordedSerial=0;
 uint64_t activeFirstSerial=0,activeCompletedSerial=0;
 uint64_t shaderModuleCreateCalls=0,computePipelineCreateCalls=0;
 bool destroyed=false,initialized=false,stopped=false;
 Impl(const GpuContext& c,AAssetManager* a):gpu(c),assets(a){}
 std::string json(const HotloadReceipt& r)const {
  std::ostringstream o;o<<"{\"schema\":\"rusty.quest.hotload.native_receipt.v1\",\"kernel_generation\":"<<state.generation<<",\"active_tag\":\""<<(state.tag==2?"external":state.tag==1?"B1":"A0")<<"\",\"active_digest\":\""<<activeDigest<<"\",\"active_origin\":\""<<activeOrigin<<"\",\"request_id\":\""<<escaped(r.id)<<"\",\"action\":\""<<escaped(r.action)<<"\",\"expected_generation\":"<<r.expected<<",\"receipt_generation\":"<<r.generation<<",\"phase\":\""<<r.phase<<"\",\"reason\":\""<<escaped(r.reason)<<"\",\"candidate_digest\":\""<<escaped(r.candidate)<<"\",\"first_serial\":"<<r.firstSerial<<",\"completed_serial\":"<<r.completedSerial<<",\"old_pipeline_retired\":"<<(r.retired?"true":"false")<<",\"command_buffer_reset_policy\":\"owner_fence_success_then_command_buffer_reset_before_activation\",\"last_submitted_serial\":"<<lastSubmitted<<",\"last_completed_serial\":"<<lastCompleted
   <<",\"active_first_serial\":"<<activeFirstSerial<<",\"active_completed_serial\":"<<activeCompletedSerial<<",\"shader_module_create_calls\":"<<shaderModuleCreateCalls<<",\"compute_pipeline_create_calls\":"<<computePipelineCreateCalls<<",\"staged_digest\":\""<<stagedDigest<<"\",\"rollback_digest\":\""<<rollbackDigest<<"\",\"rollback_origin\":\""<<rollbackOrigin<<"\",\"rollback_tag\":\""<<(!rollback?"":rollbackTag==2?"external":rollbackTag==1?"B1":"A0")<<"\",\"previous_pipeline_retained\":"<<(rollback?"true":"false")<<",\"external_abi_sha256\":\""<<externalHotloadAbiSha256<<"\",\"shader_semantic_safety_proof\":false,\"private_state_migration\":false,\"last_rejection\":{\"request_id\":\""<<escaped(lastRejection.id)<<"\",\"candidate_digest\":\""<<escaped(lastRejection.candidate)<<"\",\"reason\":\""<<escaped(lastRejection.reason)<<"\"}}";return o.str();
 }
 std::string result(const HotloadReceipt& r){if(r.phase=="rejected")lastRejection=r;return json(r);}
 std::string reject(const char* reason){state.reject(reason);return result(state.current());}
 void clearStaged(){stagedCode.clear();stagedDigest.clear();stagedExpectedDigest.clear();stagedExpectedGeneration=0;}
 VkPipeline prepareCode(const std::vector<uint32_t>& code) {
  VkShaderModuleCreateInfo sm{};sm.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;sm.codeSize=code.size()*4;sm.pCode=code.data();VkShaderModule module=VK_NULL_HANDLE;
  ++shaderModuleCreateCalls;check(vkCreateShaderModule(gpu.device,&sm,nullptr,&module),"shader_module");
  VkComputePipelineCreateInfo pc{};pc.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;pc.layout=layout;pc.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;pc.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;pc.stage.module=module;pc.stage.pName="main";
  VkPipeline next=VK_NULL_HANDLE;++computePipelineCreateCalls;VkResult result=vkCreateComputePipelines(gpu.device,VK_NULL_HANDLE,1,&pc,nullptr,&next);vkDestroyShaderModule(gpu.device,module,nullptr);
  if(result!=VK_SUCCESS){if(next)vkDestroyPipeline(gpu.device,next,nullptr);check(result,"compute_pipeline");}return next;
 }
 VkPipeline prepare(unsigned tag) {
  const auto& e=hotloadCatalog[tag];
  if(e.abi!=1||e.bytes<20||e.bytes>65536||e.bytes%4)throw std::runtime_error("catalog_abi_or_length");
  AAsset* a=AAssetManager_open(assets,e.asset,AASSET_MODE_BUFFER);if(!a)throw std::runtime_error("missing_catalog_asset");
  if(AAsset_getLength64(a)!=e.bytes){AAsset_close(a);throw std::runtime_error("asset_length_mismatch");}
  std::vector<uint32_t> code(e.bytes/4);size_t read=0;
  while(read<e.bytes){int n=AAsset_read(a,reinterpret_cast<uint8_t*>(code.data())+read,e.bytes-read);if(n<=0)break;read+=size_t(n);}AAsset_close(a);
  if(read!=e.bytes)throw std::runtime_error("asset_truncated");
  if(const char* error=hotloadBlobError(reinterpret_cast<const uint8_t*>(code.data()),e.bytes,e.bytes,e.sha256,e.abi))throw std::runtime_error(error);
  return prepareCode(code);
 }
 void release(){clearStaged();if(rollback)vkDestroyPipeline(gpu.device,rollback,nullptr);rollback=VK_NULL_HANDLE;if(pipeline)vkDestroyPipeline(gpu.device,pipeline,nullptr);if(pool)vkDestroyDescriptorPool(gpu.device,pool,nullptr);if(layout)vkDestroyPipelineLayout(gpu.device,layout,nullptr);if(descriptorLayout)vkDestroyDescriptorSetLayout(gpu.device,descriptorLayout,nullptr);if(buffer)vkDestroyBuffer(gpu.device,buffer,nullptr);if(memory)vkFreeMemory(gpu.device,memory,nullptr);pipeline=VK_NULL_HANDLE;buffer=VK_NULL_HANDLE;memory=VK_NULL_HANDLE;pool=VK_NULL_HANDLE;layout=VK_NULL_HANDLE;descriptorLayout=VK_NULL_HANDLE;destroyed=true;}
 void initialize(){
  VkBufferCreateInfo bi{};bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;bi.size=128;bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;check(vkCreateBuffer(gpu.device,&bi,nullptr,&buffer),"output_buffer");
  VkMemoryRequirements req{};vkGetBufferMemoryRequirements(gpu.device,buffer,&req);uint32_t type=UINT32_MAX;
  for(uint32_t i=0;i<gpu.memoryProperties.memoryTypeCount;++i)if(req.memoryTypeBits&(1u<<i)){type=i;if(gpu.memoryProperties.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)break;}
  if(type==UINT32_MAX)throw std::runtime_error("memory_type");
  VkMemoryAllocateInfo ma{};ma.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;ma.allocationSize=req.size;ma.memoryTypeIndex=type;check(vkAllocateMemory(gpu.device,&ma,nullptr,&memory),"output_memory");check(vkBindBufferMemory(gpu.device,buffer,memory,0),"output_bind");
  VkDescriptorSetLayoutBinding b{};b.binding=0;b.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;b.descriptorCount=1;b.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;
  VkDescriptorSetLayoutCreateInfo dl{};dl.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;dl.bindingCount=1;dl.pBindings=&b;check(vkCreateDescriptorSetLayout(gpu.device,&dl,nullptr,&descriptorLayout),"descriptor_layout");
  VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,32};VkPipelineLayoutCreateInfo pl{};pl.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;pl.setLayoutCount=1;pl.pSetLayouts=&descriptorLayout;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&push;check(vkCreatePipelineLayout(gpu.device,&pl,nullptr,&layout),"pipeline_layout");
  VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1};VkDescriptorPoolCreateInfo dp{};dp.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;dp.maxSets=1;dp.poolSizeCount=1;dp.pPoolSizes=&size;check(vkCreateDescriptorPool(gpu.device,&dp,nullptr,&pool),"descriptor_pool");
  VkDescriptorSetAllocateInfo da{};da.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;da.descriptorPool=pool;da.descriptorSetCount=1;da.pSetLayouts=&descriptorLayout;check(vkAllocateDescriptorSets(gpu.device,&da,&set),"descriptor_set");
  VkDescriptorBufferInfo info{buffer,0,128};VkWriteDescriptorSet write{};write.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;write.dstSet=set;write.dstBinding=0;write.descriptorCount=1;write.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;write.pBufferInfo=&info;vkUpdateDescriptorSets(gpu.device,1,&write,0,nullptr);pipeline=prepare(0);
 }
};
HotloadKernel::HotloadKernel(const GpuContext& c,AAssetManager* a):impl_(std::make_unique<Impl>(c,a)){try{impl_->initialize();}catch(...){impl_->release();throw;}}
// Unproven teardown intentionally retains Vulkan resources until process exit.
HotloadKernel::~HotloadKernel()=default;
ParticleOutput HotloadKernel::output()const{std::lock_guard<std::mutex> lock(impl_->mutex);return {impl_->buffer,0,128,2,0,2,4,impl_->initialized};}
std::string HotloadKernel::request(const std::string& action,const std::string& id,uint64_t expected){
 std::lock_guard<std::mutex> lock(impl_->mutex);auto r=impl_->state.request(action,id,expected);
 if(r.phase=="queued"&&action!="a"&&action!="b"&&action!="revert"&&action!="bad-abi"&&action!="malformed"&&action!="stale")return impl_->reject("external_action_requires_candidate_route");
 if(r.phase=="queued"&&(impl_->destroyed||impl_->stopped||!impl_->lastCompleted))return impl_->reject("producer_not_ready");
 return impl_->result(r);
}
std::string HotloadKernel::rejectRequest(const std::string& action,const std::string& candidate,const std::string& reason,uint64_t expected,const std::string& expectedDigest,const std::string& id){
 // Rejection before transaction admission must never cancel an existing request.
 std::lock_guard<std::mutex> lock(impl_->mutex);HotloadReceipt r{id,action,"rejected",reason,expected,impl_->state.generation};
 r.candidate=candidate;r.expectedDigest=expectedDigest;return impl_->result(r);
}
std::string HotloadKernel::stage(std::vector<uint32_t> code,const std::string& digest,const std::string& abi,uint64_t expected,const std::string& expectedDigest,const std::string& id){
 std::lock_guard<std::mutex> lock(impl_->mutex);auto r=impl_->state.request("stage",id,expected,digest,expectedDigest);
 if(r.phase!="queued")return impl_->result(r);
 if(impl_->destroyed||impl_->stopped||!impl_->lastCompleted)return impl_->reject("producer_not_ready");
 if(expectedDigest!=impl_->activeDigest)return impl_->reject("stale_active_digest");
 if(abi!=externalHotloadAbiSha256)return impl_->reject("external_abi_digest_mismatch");
 if(code.size()<5||code.size()>16384)return impl_->reject("external_length");
 if(hotloadSha256(reinterpret_cast<const uint8_t*>(code.data()),code.size()*4)!=digest)return impl_->reject("external_digest_mismatch");
 if(const char* error=externalHotloadSpirvError(code.data(),code.size()))return impl_->reject(error);
 // Own precisely the verified bytes; activation never reopens any file.
 impl_->stagedCode=std::move(code);impl_->stagedDigest=digest;impl_->stagedExpectedGeneration=expected;impl_->stagedExpectedDigest=expectedDigest;
 impl_->state.finishImmediate("staged");return impl_->result(impl_->state.current());
}
std::string HotloadKernel::externalRequest(const std::string& action,const std::string& candidate,uint64_t expected,const std::string& expectedDigest,const std::string& id){
 std::lock_guard<std::mutex> lock(impl_->mutex);auto r=impl_->state.request(action,id,expected,candidate,expectedDigest);
 if(r.phase!="queued")return impl_->result(r);
 if(impl_->destroyed||impl_->stopped||!impl_->lastCompleted)return impl_->reject("producer_not_ready");
 if(expectedDigest!=impl_->activeDigest)return impl_->reject("stale_active_digest");
 if(action=="activate"||action=="discard"){
  if(candidate.empty()||candidate!=impl_->stagedDigest)return impl_->reject("unknown_candidate");
  if(action=="discard"){impl_->clearStaged();impl_->state.finishImmediate("discarded");return impl_->result(impl_->state.current());}
  if(impl_->stagedExpectedGeneration!=expected||impl_->stagedExpectedDigest!=expectedDigest)return impl_->reject("stale_staged_candidate");
 }else if(action=="rollback"){
  if(!candidate.empty())return impl_->reject("rollback_takes_no_candidate");
  if(!impl_->rollback)return impl_->reject("no_completed_predecessor");
 }else return impl_->reject("unknown_external_action");
 return impl_->result(r);
}
std::string HotloadKernel::statusJson()const{std::lock_guard<std::mutex> lock(impl_->mutex);auto* pending=impl_->state.pending();return impl_->json(pending?*pending:impl_->state.current());}
uint64_t HotloadKernel::generation()const{std::lock_guard<std::mutex> lock(impl_->mutex);return impl_->state.generation;}
uint32_t HotloadKernel::tag()const{std::lock_guard<std::mutex> lock(impl_->mutex);return impl_->state.tag;}
void HotloadKernel::afterRetirement(uint64_t completed){std::lock_guard<std::mutex> lock(impl_->mutex);if(impl_->destroyed||impl_->stopped)return;if(completed!=impl_->lastSubmitted) return;impl_->lastCompleted=completed;impl_->state.completed(completed);auto* r=impl_->state.pending();if(!r||r->phase!="queued")return;
 if(const char* error=hotloadPresetError(r->action)){impl_->reject(error);return;}
 if(!r->expectedDigest.empty()&&r->expectedDigest!=impl_->activeDigest){impl_->reject("stale_active_digest_at_activation");return;}
 if(r->action=="activate"&&(r->candidate!=impl_->stagedDigest||r->expected!=impl_->stagedExpectedGeneration)){impl_->reject("stale_staged_candidate_at_activation");return;}
 const bool rollback=r->action=="rollback";
 unsigned tag=rollback?impl_->rollbackTag:r->action=="activate"?2:r->action=="b"?1:0;
 std::string digest=rollback?impl_->rollbackDigest:tag==2?impl_->stagedDigest:hotloadCatalog[tag].sha256;
 std::string origin=rollback?impl_->rollbackOrigin:tag==2?"external":tag==1?"packaged-b":"packaged-a";
 try{
  VkPipeline next=rollback?impl_->rollback:tag==2?impl_->prepareCode(impl_->stagedCode):impl_->prepare(tag);
  if(!next){impl_->reject("no_completed_predecessor");return;}
  const unsigned previousTag=impl_->state.tag;impl_->state.prepared();
  if(!impl_->state.activate(tag,true)){if(!rollback)vkDestroyPipeline(impl_->gpu.device,next,nullptr);impl_->reject("activation_guard");return;}
  // Both predecessor uses and recorded command references retired at this boundary.
  // Keep the immediately previous completed pipeline for explicit rollback.
  if(impl_->rollback&&impl_->rollback!=next)vkDestroyPipeline(impl_->gpu.device,impl_->rollback,nullptr);
  impl_->rollback=impl_->pipeline;impl_->rollbackTag=previousTag;impl_->rollbackDigest.swap(impl_->activeDigest);impl_->rollbackOrigin.swap(impl_->activeOrigin);
  impl_->pipeline=next;impl_->activeDigest.swap(digest);impl_->activeOrigin.swap(origin);impl_->activeFirstSerial=0;impl_->activeCompletedSerial=0;impl_->clearStaged();
 }catch(const std::exception& e){impl_->reject(e.what());}
}
void HotloadKernel::record(VkCommandBuffer cmd,const FrameContext& frame){std::lock_guard<std::mutex> lock(impl_->mutex);if(impl_->destroyed||impl_->stopped)return;struct Push{uint32_t id[4];float timing[4];}p{{uint32_t(frame.generation),uint32_t(impl_->state.generation),impl_->state.tag,0},{float(frame.elapsedSeconds),frame.deltaSeconds,0,0}};static_assert(sizeof(p)==32,"hotload push ABI");vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,impl_->pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,impl_->layout,0,1,&impl_->set,0,nullptr);vkCmdPushConstants(cmd,impl_->layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);vkCmdDispatch(cmd,1,1,1);VkBufferMemoryBarrier b{};b.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;b.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.buffer=impl_->buffer;b.offset=0;b.size=128;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,1,&b,0,nullptr);impl_->recordedGeneration=impl_->state.generation;impl_->recordedSerial=frame.submission;}
void HotloadKernel::confirmSubmitted(uint64_t serial){std::lock_guard<std::mutex> lock(impl_->mutex);if(impl_->destroyed||serial==0||serial!=impl_->recordedSerial||serial<=impl_->lastSubmitted)return;impl_->lastSubmitted=serial;impl_->initialized=true;if(!impl_->activeFirstSerial)impl_->activeFirstSerial=serial;impl_->state.submitted(serial,impl_->recordedGeneration);}
void HotloadKernel::confirmCompleted(uint64_t serial){std::lock_guard<std::mutex> lock(impl_->mutex);if(serial>impl_->lastSubmitted||serial<impl_->lastCompleted)return;impl_->lastCompleted=serial;if(impl_->activeFirstSerial&&serial>=impl_->activeFirstSerial)impl_->activeCompletedSerial=serial;impl_->state.completed(serial);}
void HotloadKernel::stop(){std::lock_guard<std::mutex> lock(impl_->mutex);impl_->stopped=true;impl_->state.stop();impl_->clearStaged();}
void HotloadKernel::destroyAfterRetirement(){std::lock_guard<std::mutex> lock(impl_->mutex);impl_->stopped=true;impl_->state.stop();if(!impl_->destroyed)impl_->release();}
}
