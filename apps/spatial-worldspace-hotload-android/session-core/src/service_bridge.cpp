#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include "rq_experiment_services.h"
namespace {
std::mutex guard;
void* held = nullptr;
bool same_module(const void* address,void* base) {
  Dl_info info{};
  return address && dladdr(address,&info)!=0 && info.dli_fbase==base;
}
}
extern "C" int32_t rq_session_install_loaded_services() {
  std::lock_guard<std::mutex> lock(guard);
  if (held) return 0;
  void* candidate=dlopen("libspatial_gpu_workload.so",RTLD_NOW|RTLD_NOLOAD);
  if (!candidate) return -1;
  void* symbol=dlsym(candidate,"rq_get_experiment_services_v1");
  Dl_info info{};
  bool located=symbol&&dladdr(symbol,&info)!=0&&info.dli_fname&&info.dli_fbase;
  const char* filename=located?std::strrchr(info.dli_fname,'/'):nullptr;
  filename=filename?filename+1:(located?info.dli_fname:"");
  if (!located||std::strcmp(filename,"libspatial_gpu_workload.so")!=0) {dlclose(candidate);return -2;}
  using Getter=const rq_experiment_services_v1*(*)();
  const auto* table=reinterpret_cast<Getter>(symbol)();
  if (!table||table->struct_size!=sizeof(*table)||table->abi_version!=1||
      !same_module(table,info.dli_fbase)||
      !same_module(reinterpret_cast<const void*>(table->status),info.dli_fbase)||
      !same_module(reinterpret_cast<const void*>(table->ensure_running),info.dli_fbase)||
      !same_module(reinterpret_cast<const void*>(table->reset_defaults),info.dli_fbase)||
      !same_module(reinterpret_cast<const void*>(table->apply_effective),info.dli_fbase)||
      !same_module(reinterpret_cast<const void*>(table->invalidate_controller),info.dli_fbase)) {dlclose(candidate);return -3;}
  // The same core owns installation. Keep the provider DSO loaded for all callbacks.
  const int32_t result=rq_session_core_install_services_v1(table);
  if (result!=0) {dlclose(candidate);return result;}
  held=candidate;return 0;
}
