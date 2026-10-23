#include "workload_backend.h"
#include <stdexcept>
namespace rq::workload {
std::unique_ptr<ComputeWorkload> createExternalWorkload(const GpuContext&,AAssetManager*,uint32_t) {
    throw std::runtime_error("No external workload selected; real simulation unavailable");
}
}
