#include "hotload_kernel.h"
#include <stdexcept>
namespace rq::workload {
struct HotloadKernel::Impl {};
HotloadKernel::HotloadKernel(const GpuContext&,AAssetManager*) { throw std::runtime_error("Hotload diagnostic producer is debug-only and not selected"); }
HotloadKernel::~HotloadKernel()=default;
ParticleOutput HotloadKernel::output()const{return {};}
std::string HotloadKernel::request(const std::string&,const std::string&,uint64_t){return "{\"phase\":\"unsupported\"}";}
std::string HotloadKernel::stage(std::vector<uint32_t>,const std::string&,const std::string&,uint64_t,const std::string&,const std::string&){return "{\"phase\":\"unsupported\"}";}
std::string HotloadKernel::externalRequest(const std::string&,const std::string&,uint64_t,const std::string&,const std::string&){return "{\"phase\":\"unsupported\"}";}
std::string HotloadKernel::rejectRequest(const std::string&,const std::string&,const std::string&,uint64_t,const std::string&,const std::string&){return "{\"phase\":\"unsupported\"}";}
std::string HotloadKernel::statusJson()const{return "{\"phase\":\"unsupported\"}";}
uint64_t HotloadKernel::generation()const{return 0;}
uint32_t HotloadKernel::tag()const{return 0;}
void HotloadKernel::afterRetirement(uint64_t){}
void HotloadKernel::record(VkCommandBuffer,const FrameContext&){}
void HotloadKernel::confirmSubmitted(uint64_t){}
void HotloadKernel::confirmCompleted(uint64_t){}
void HotloadKernel::stop(){}
void HotloadKernel::destroyAfterRetirement(){}
}
