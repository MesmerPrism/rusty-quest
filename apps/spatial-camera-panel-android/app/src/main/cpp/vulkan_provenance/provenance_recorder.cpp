#include "provenance_recorder.hpp"

#include <cstring>
#include <limits>

namespace rqvp {
namespace {
constexpr size_t kMaxFields = 32;
constexpr size_t kTerminalReserve = 384;

bool token(const char* value, size_t maximum) noexcept {
  if (value == nullptr || value[0] == '\0') return false;
  for (size_t i = 0; i <= maximum; ++i) {
    const unsigned char c = static_cast<unsigned char>(value[i]);
    if (c == 0) return i != 0;
    const bool letter =
        (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    if (!letter &&
        (i == 0 || !((c >= '0' && c <= '9') || c == '.' || c == '-')))
      return false;
  }
  return false;
}

bool metadata(const char* operation, const Field* fields,
              size_t count) noexcept {
  if (!token(operation, 64) || count > kMaxFields ||
      (count != 0 && fields == nullptr))
    return false;
  constexpr const char* reserved[] = {"version",
                                      "sequence",
                                      "ns",
                                      "tid",
                                      "pid",
                                      "op",
                                      "phase",
                                      "call",
                                      "result",
                                      "reason",
                                      "coverage_complete",
                                      "window_ended",
                                      "dropped_events"};
  for (size_t i = 0; i != count; ++i) {
    if (!token(fields[i].name, 48)) return false;
    for (const char* name : reserved)
      if (std::strcmp(name, fields[i].name) == 0) return false;
    for (size_t j = 0; j != i; ++j)
      if (std::strcmp(fields[j].name, fields[i].name) == 0) return false;
  }
  return true;
}

struct Line {
  char data[2048]{};
  size_t size = 0;
  bool valid = true;

  void character(char c) noexcept {
    if (size == sizeof(data))
      valid = false;
    else
      data[size++] = c;
  }
  void text(const char* value) noexcept {
    while (*value != '\0') character(*value++);
  }
  void number(uint64_t value) noexcept {
    char digits[20];
    size_t count = 0;
    do {
      digits[count++] = static_cast<char>('0' + value % 10);
      value /= 10;
    } while (value != 0);
    while (count != 0) character(digits[--count]);
  }
  void field(const char* name, uint64_t value) noexcept {
    text(",\"");
    text(name);
    text("\":");
    number(value);
  }
};

Line format(uint64_t sequence, uint64_t ns, uint64_t tid, uint64_t pid,
            const char* operation, const char* phase, uint64_t call,
            int32_t result, const Field* fields, size_t count,
            const Limits& limits, bool header, const char* reason = nullptr,
            uint64_t dropped = 0) noexcept {
  Line row;
  row.text("{\"version\":1,\"sequence\":");
  row.number(sequence);
  row.field("ns", ns);
  row.field("tid", tid);
  row.field("pid", pid);
  row.text(",\"op\":\"");
  row.text(operation);
  row.text("\",\"phase\":\"");
  row.text(phase);
  row.text("\"");
  row.field("call", call);
  row.text(",\"result\":");
  if (result < 0) {
    row.character('-');
    row.number(static_cast<uint64_t>(-static_cast<int64_t>(result)));
  } else
    row.number(static_cast<uint64_t>(result));
  for (size_t i = 0; i != count; ++i)
    row.field(fields[i].name, fields[i].value);
  if (header) {
    row.field("max_events", limits.events);
    row.field("max_bytes", limits.bytes);
    row.field("duration_ns", limits.duration_ns);
    row.field("coverage_complete", 0);
    row.field("window_ended", 0);
  }
  if (reason != nullptr) {
    row.text(",\"reason\":\"");
    row.text(reason);
    row.text("\"");
    row.field("coverage_complete", 0);
    row.field("window_ended", 1);
    row.field("dropped_events", dropped);
  }
  row.text("}\n");
  return row;
}
}  // namespace

Recorder::Recorder(Sink sink, void* context, Limits limits)
    : sink_(sink), context_(context), limits_(limits) {}

bool Recorder::push_locked(const char* data, size_t size, uint64_t ns,
                           uint64_t tid, bool terminal) noexcept {
  if (queued_ == kQueueRows || events_used_ >= limits_.events ||
      bytes_used_ > limits_.bytes || size > limits_.bytes - bytes_used_ ||
      sequence_ == std::numeric_limits<uint64_t>::max())
    return false;
  Row& row = queue_[(head_ + queued_) % kQueueRows];
  std::memcpy(row.data, data, size);
  row.size = size;
  row.ns = ns;
  row.tid = tid;
  row.terminal = terminal;
  ++queued_;
  ++sequence_;
  ++events_used_;
  bytes_used_ += static_cast<uint64_t>(size);
  if (terminal) terminal_queued_ = true;
  return true;
}

void Recorder::stop_locked(uint64_t ns, uint64_t tid, const char* reason,
                           uint64_t dropped) noexcept {
  active_ = false;
  if (terminal_queued_ || terminal_attempted_ ||
      sequence_ == std::numeric_limits<uint64_t>::max())
    return;
  const auto row =
      format(sequence_ + 1, ns, tid, pid_, "recorder", "incomplete", 0, 0,
             nullptr, 0, limits_, false, reason, dropped);
  if (row.valid) push_locked(row.data, row.size, ns, tid, true);
}

bool Recorder::ready_locked(uint64_t ns, uint64_t tid) noexcept {
  if (!active_) return false;
  if (ns < start_ns_) {
    stop_locked(ns, tid, "clock-regressed");
    return false;
  }
  if (ns - start_ns_ >= limits_.duration_ns) {
    stop_locked(ns, tid, "duration-limit");
    return false;
  }
  return true;
}

bool Recorder::emit_locked(uint64_t ns, uint64_t tid, const char* operation,
                           const char* phase, uint64_t call, int32_t result,
                           const Field* fields, size_t count,
                           bool header) noexcept {
  if (sequence_ == std::numeric_limits<uint64_t>::max()) {
    active_ = false;
    return false;
  }
  const auto row = format(sequence_ + 1, ns, tid, pid_, operation, phase, call,
                          result, fields, count, limits_, header);
  if (!row.valid) {
    stop_locked(ns, tid, "row-limit");
    return false;
  }
  if (events_used_ >= limits_.events || limits_.events - events_used_ <= 1) {
    stop_locked(ns, tid, "event-limit");
    return false;
  }
  if (bytes_used_ > limits_.bytes || row.size > limits_.bytes - bytes_used_ ||
      kTerminalReserve > limits_.bytes - bytes_used_ - row.size) {
    stop_locked(ns, tid, "byte-limit");
    return false;
  }
  if (queued_ >= kQueueRows - 1) {
    stop_locked(ns, tid, "queue-limit");
    return false;
  }
  return push_locked(row.data, row.size, ns, tid, false);
}

bool Recorder::claim_drain_locked() noexcept {
  if (draining_ || queued_ == 0) return false;
  draining_ = true;
  return true;
}

void Recorder::drain() noexcept {
  try {
    for (;;) {
      Row row;
      {
        std::lock_guard<std::mutex> guard(mutex_);
        if (queued_ == 0) {
          draining_ = false;
          return;
        }
        row = queue_[head_];
        head_ = (head_ + 1) % kQueueRows;
        --queued_;
        if (row.terminal) {
          terminal_queued_ = false;
          terminal_attempted_ = true;
        }
      }
      // External sink work never holds the metadata mutex. Reentrant and other
      // threads can enqueue bounded rows; only this drainer calls the sink.
      bool accepted = false;
      try {
        accepted = sink_ != nullptr && sink_(row.data, row.size, context_);
      } catch (...) {
      }
      if (!accepted) {
        std::lock_guard<std::mutex> guard(mutex_);
        active_ = false;
        const auto dropped = static_cast<uint64_t>(queued_);
        while (queued_ != 0) {
          bytes_used_ -= static_cast<uint64_t>(queue_[head_].size);
          --events_used_;
          head_ = (head_ + 1) % kQueueRows;
          --queued_;
        }
        terminal_queued_ = false;
        if (row.terminal) {
          draining_ = false;
          return;
        }
        stop_locked(row.ns, row.tid, "sink-failure", dropped);
      }
    }
  } catch (...) {
    // A synchronization failure cannot be reported safely through that same
    // synchronization primitive. Never propagate it into an intercepted call.
  }
}

void Recorder::start(bool selected, uint64_t ns, uint64_t pid) noexcept {
  try {
    bool drive;
    {
      std::lock_guard<std::mutex> guard(mutex_);
      if (started_) return;
      started_ = true;
      active_ = selected;
      start_ns_ = ns;
      pid_ = pid;
      if (selected && ready_locked(ns, 0))
        emit_locked(ns, 0, "recorder", "header", 0, 0, nullptr, 0, true);
      drive = claim_drain_locked();
    }
    if (drive) drain();
  } catch (...) {
  }
}

uint64_t Recorder::begin(uint64_t ns, uint64_t tid, const char* operation,
                         const Field* fields, size_t count) noexcept {
  uint64_t id = 0;
  try {
    bool drive;
    {
      std::lock_guard<std::mutex> guard(mutex_);
      if (ready_locked(ns, tid)) {
        if (!metadata(operation, fields, count))
          stop_locked(ns, tid, "invalid-metadata");
        else if (call_id_ == std::numeric_limits<uint64_t>::max())
          stop_locked(ns, tid, "call-id-limit");
        else if (emit_locked(ns, tid, operation, "begin", call_id_ + 1, 0,
                             fields, count))
          id = ++call_id_;
      }
      drive = claim_drain_locked();
    }
    if (drive) drain();
  } catch (...) {
  }
  return id;
}

void Recorder::end(uint64_t ns, uint64_t tid, const char* operation,
                   uint64_t call, int32_t result, const Field* fields,
                   size_t count) noexcept {
  try {
    bool drive;
    {
      std::lock_guard<std::mutex> guard(mutex_);
      if (ready_locked(ns, tid)) {
        if (call == 0 || call > call_id_ || !metadata(operation, fields, count))
          stop_locked(ns, tid, "invalid-metadata");
        else
          emit_locked(ns, tid, operation, "end", call, result, fields, count);
      }
      drive = claim_drain_locked();
    }
    if (drive) drain();
  } catch (...) {
  }
}

void Recorder::note(uint64_t ns, uint64_t tid, const char* operation,
                    const Field* fields, size_t count) noexcept {
  try {
    bool drive;
    {
      std::lock_guard<std::mutex> guard(mutex_);
      if (ready_locked(ns, tid)) {
        if (!metadata(operation, fields, count))
          stop_locked(ns, tid, "invalid-metadata");
        else
          emit_locked(ns, tid, operation, "note", 0, 0, fields, count);
      }
      drive = claim_drain_locked();
    }
    if (drive) drain();
  } catch (...) {
  }
}

void Recorder::incomplete(uint64_t ns, uint64_t tid,
                          const char* reason) noexcept {
  try {
    bool drive;
    {
      std::lock_guard<std::mutex> guard(mutex_);
      if (ready_locked(ns, tid))
        stop_locked(ns, tid, token(reason, 48) ? reason : "invalid-metadata");
      drive = claim_drain_locked();
    }
    if (drive) drain();
  } catch (...) {
  }
}
}  // namespace rqvp
