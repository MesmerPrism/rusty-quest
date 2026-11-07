#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace rqvp {
struct Field {
  const char* name;
  uint64_t value;
};
using Sink = bool (*)(const char*, size_t, void*);
struct Limits {
  uint64_t events = 262144;
  uint64_t bytes = 33554432;
  uint64_t duration_ns = 120000000000;
};

// Caller-owned tokens and Fields remain valid for each call. Sink consumes a
// row synchronously and must not retain its buffer. No driver/API work belongs
// here. The first start freezes selection. A terminal row describes a finite
// trace, never complete process coverage. Duration is checked on recorder
// entry.
class Recorder {
 public:
  Recorder(Sink, void*, Limits = {});
  void start(bool selected, uint64_t now_ns, uint64_t pid) noexcept;
  uint64_t begin(uint64_t now_ns, uint64_t tid, const char* operation,
                 const Field* fields, size_t count) noexcept;
  void end(uint64_t now_ns, uint64_t tid, const char* operation,
           uint64_t call_id, int32_t result, const Field* fields,
           size_t count) noexcept;
  void note(uint64_t now_ns, uint64_t tid, const char* operation,
            const Field* fields, size_t count) noexcept;
  void incomplete(uint64_t now_ns, uint64_t tid, const char* reason) noexcept;

 private:
  static constexpr size_t kLineBytes = 2048;
  static constexpr size_t kQueueRows = 16;
  struct Row {
    char data[kLineBytes]{};
    size_t size = 0;
    uint64_t ns = 0;
    uint64_t tid = 0;
    bool terminal = false;
  };

  bool ready_locked(uint64_t now_ns, uint64_t tid) noexcept;
  bool emit_locked(uint64_t now_ns, uint64_t tid, const char* operation,
                   const char* phase, uint64_t call_id, int32_t result,
                   const Field* fields, size_t count,
                   bool header = false) noexcept;
  void stop_locked(uint64_t now_ns, uint64_t tid, const char* reason,
                   uint64_t dropped = 0) noexcept;
  bool push_locked(const char* data, size_t size, uint64_t now_ns, uint64_t tid,
                   bool terminal) noexcept;
  bool claim_drain_locked() noexcept;
  void drain() noexcept;

  std::mutex mutex_;
  Sink sink_;
  void* context_;
  Limits limits_;
  Row queue_[kQueueRows]{};
  size_t head_ = 0;
  size_t queued_ = 0;
  uint64_t pid_ = 0;
  uint64_t start_ns_ = 0;
  uint64_t sequence_ = 0;
  uint64_t call_id_ = 0;
  uint64_t events_used_ = 0;
  uint64_t bytes_used_ = 0;
  bool started_ = false;
  bool active_ = false;
  bool draining_ = false;
  bool terminal_queued_ = false;
  bool terminal_attempted_ = false;
};
}  // namespace rqvp
