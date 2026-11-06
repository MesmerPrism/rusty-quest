#pragma once
#include "hotload_kernel.h"
#include <atomic>
#include <mutex>

namespace rq::workload {
// Shared producer transitions. Lock order is owner mutex, then kernel mutex.
// Stop linearizes against retirement/activation here; it never destroys GPU
// resources or waits for the queue. Worker cleanup retains those obligations.
inline void stopHotloadOwner(std::mutex& owner, std::atomic<bool>& running,
                             const std::unique_ptr<HotloadKernel>& kernel) {
    std::lock_guard<std::mutex> lock(owner);
    running.store(false);
    if (kernel) kernel->stop();
}

// Caller has already established fence completion and command-buffer reset.
inline void retireHotloadOwner(std::mutex& owner, const std::atomic<bool>& running,
                               const std::unique_ptr<HotloadKernel>& kernel,
                               uint64_t completed) {
    std::lock_guard<std::mutex> lock(owner);
    if (running.load() && kernel) kernel->afterRetirement(completed);
}
} // namespace rq::workload
