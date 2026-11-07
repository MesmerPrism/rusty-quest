#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "provenance_recorder.cpp"

namespace allocation_control {
std::atomic<size_t> array_calls{0};
std::atomic<bool> fail_next{false};
}
void* operator new[](size_t size, const std::nothrow_t&) noexcept {
  allocation_control::array_calls.fetch_add(1);
  if (allocation_control::fail_next.exchange(false)) return nullptr;
  try { return ::operator new[](size); } catch (...) { return nullptr; }
}
void operator delete[](void* pointer, const std::nothrow_t&) noexcept {
  ::operator delete[](pointer);
}

namespace {
void require(bool value, const char* message) {
  if (!value) throw std::runtime_error(message);
}

struct Capture {
  std::vector<std::string> rows;
  rqvp::Recorder* recorder = nullptr;
  unsigned calls = 0;
  unsigned fail_at = 0;
  unsigned reenter = 0;
  bool throw_failure = false;

  static bool sink(const char* line, size_t length, void* context) {
    auto& self = *static_cast<Capture*>(context);
    ++self.calls;
    require(length <= 2048 && length > 0 && line[length - 1] == '\n',
            "bounded complete JSONL required");
    if (self.calls == self.fail_at) {
      if (self.throw_failure) throw std::runtime_error("modeled sink failure");
      return false;
    }
    self.rows.emplace_back(line, length);
    const auto action = self.reenter;
    self.reenter = 0;
    if (action == 1) self.recorder->note(2, 77, "nested", nullptr, 0);
    if (action == 2) {
      std::thread child(
          [&self] { self.recorder->note(2, 88, "nested_thread", nullptr, 0); });
      child.join();
    }
    if (action == 3) {
      for (unsigned i = 0; i != 256; ++i)
        self.recorder->note(2, 99, "queued", nullptr, 0);
    }
    return true;
  }

  void print(const char* name) const {
    for (const auto& row : rows) std::cout << name << '\t' << row;
  }
};

struct PausedCapture {
  std::mutex mutex;
  std::condition_variable changed;
  bool entered = false;
  bool released = false;
  unsigned calls = 0;
  unsigned fail_at = 0;
  std::vector<std::string> rows;
  static bool sink(const char* line, size_t length, void* context) {
    auto& self = *static_cast<PausedCapture*>(context);
    ++self.calls;
    require(length <= 2048 && length > 0 && line[length - 1] == '\n',
            "bounded complete paused-sink JSONL required");
    if (self.calls == self.fail_at) return false;
    self.rows.emplace_back(line, length);
    if (self.rows.size() == 1) {
      std::unique_lock<std::mutex> guard(self.mutex);
      self.entered = true;
      self.changed.notify_all();
      self.changed.wait(guard, [&self] { return self.released; });
    }
    return true;
  }
  void wait() {
    std::unique_lock<std::mutex> guard(mutex);
    require(changed.wait_for(guard, std::chrono::seconds(2), [this] { return entered; }),
            "header drainer did not enter sink");
  }
  void release() {
    std::lock_guard<std::mutex> guard(mutex);
    released = true;
    changed.notify_all();
  }
  void print(const char* name) const {
    for (const auto& row : rows) std::cout << name << '\t' << row;
  }
};

void blocked_burst() {
  PausedCapture capture;
  rqvp::Recorder recorder(PausedCapture::sink, &capture);
  std::thread drainer([&recorder] { recorder.start(true, 1, 5); });
  capture.wait();
  std::atomic<bool> admitted{true};
  std::atomic<unsigned> done{0};
  std::condition_variable completed;
  std::mutex completion_mutex;
  std::vector<std::thread> writers;
  for (unsigned writer = 0; writer != 4; ++writer) {
    writers.emplace_back([&, writer] {
      for (unsigned item = 0; item != 32; ++item) {
        const rqvp::Field fields[] = {{"writer", writer}, {"item", item}};
        const auto call = recorder.begin(2, writer + 1, "blocked_pair", fields, 2);
        if (!call) admitted.store(false);
        recorder.end(3, writer + 1, "blocked_pair", call, 0, fields, 2);
      }
      { std::lock_guard<std::mutex> guard(completion_mutex); done.fetch_add(1); }
      completed.notify_all();
    });
  }
  bool nonblocking;
  {
    std::unique_lock<std::mutex> guard(completion_mutex);
    nonblocking = completed.wait_for(guard, std::chrono::seconds(2), [&done] { return done.load() == 4; });
  }
  capture.release();
  for (auto& writer : writers) writer.join();
  drainer.join();
  require(nonblocking, "metadata producers blocked behind sink work");
  require(admitted.load(), "256-row queued burst lost call pairs");
  recorder.incomplete(4, 1, "finite_tail");
  require(capture.rows.size() == 258, "256 queued rows did not drain intact");
  capture.print("blocked_burst");
}

void blocked_overflow() {
  PausedCapture capture;
  rqvp::Recorder recorder(PausedCapture::sink, &capture);
  std::thread drainer([&recorder] { recorder.start(true, 1, 5); });
  capture.wait();
  for (unsigned item = 0; item != 1100; ++item) {
    const rqvp::Field field = {"item", item};
    recorder.note(2, 1, "overflow", &field, 1);
  }
  capture.release();
  drainer.join();
  recorder.note(3, 1, "after_tail", nullptr, 0);
  capture.print("queue_limit");
}

void blocked_sink_failure() {
  PausedCapture capture;
  capture.fail_at = 2;
  rqvp::Recorder recorder(PausedCapture::sink, &capture);
  std::thread drainer([&recorder] { recorder.start(true, 1, 5); });
  capture.wait();
  for (unsigned item = 0; item != 8; ++item) {
    const rqvp::Field field = {"item", item};
    recorder.note(2, 1, "discarded", &field, 1);
  }
  capture.release();
  drainer.join();
  recorder.note(3, 1, "after_tail", nullptr, 0);
  require(capture.calls == 3 && capture.rows.size() == 2,
          "failed current row and pending rows were replayed or tail duplicated");
  capture.print("queued_sink_failure");
}

void allocation_and_tail() {
  require(sizeof(rqvp::Recorder) < 4096, "large metadata ring retained on recorder stack");
  const auto before = allocation_control::array_calls.load();
  Capture disabled;
  rqvp::Recorder off(Capture::sink, &disabled);
  off.start(false, 1, 1);
  off.start(true, 2, 1);
  off.note(3, 1, "off", nullptr, 0);
  require(disabled.calls == 0 && allocation_control::array_calls.load() == before,
          "constructor or disabled path allocated metadata storage");
  Capture failed;
  rqvp::Recorder inactive(Capture::sink, &failed);
  allocation_control::fail_next.store(true);
  inactive.start(true, 1, 1);
  inactive.start(true, 2, 1);
  inactive.note(3, 1, "after_allocation_failure", nullptr, 0);
  require(inactive.begin(3, 1, "after_allocation_failure", nullptr, 0) == 0 &&
              failed.calls == 0 && allocation_control::array_calls.load() == before + 1,
          "failed allocation invoked sink or reopened first-start admission");
  Capture success;
  rqvp::Recorder recorder(Capture::sink, &success);
  recorder.start(true, 1, 1);
  for (unsigned item = 0; item != 32; ++item) {
    const auto call = recorder.begin(2, 1, "allocation_once", nullptr, 0);
    require(call != 0, "selected allocation did not activate");
    recorder.end(3, 1, "allocation_once", call, 0, nullptr, 0);
  }
  recorder.incomplete(4, 1, "finite_tail");
  require(allocation_control::array_calls.load() == before + 2,
          "recording hot path allocated another queue");
  success.print("allocation_once");

  const auto maximum = std::numeric_limits<uint64_t>::max();
  const std::string reason(48, 'x');
  const auto tail = rqvp::format(maximum, maximum, maximum, maximum, "recorder", "incomplete", 0, 0,
      nullptr, 0, {maximum, maximum, maximum}, false, reason.c_str(), maximum, 1024, 1023, true);
  require(tail.valid && tail.size <= rqvp::kTerminalReserve && rqvp::kTerminalReserve <= 640,
          "worst-scalar terminal row exceeds bounded terminal reserve");
  std::cout << "max_tail\t" << std::string(tail.data, tail.size);
}

void defaults_and_pairs() {
  Capture off;
  rqvp::Recorder inert(Capture::sink, &off);
  require(inert.begin(0, 1, "before_start", nullptr, 0) == 0,
          "unstarted begin was admitted");
  inert.start(false, 1, 7);
  inert.start(true, 2, 7);
  inert.note(3, 1, "off", nullptr, 0);
  inert.end(3, 1, "off", 1, 0, nullptr, 0);
  inert.incomplete(3, 1, "off");
  require(off.calls == 0, "off selection or repeated start invoked sink");

  Capture capture;
  rqvp::Recorder recorder(Capture::sink, &capture);
  recorder.start(true, 100, 42);
  require(capture.rows.size() == 1, "selected/header missing");
  rqvp::Field fields[] = {{"handle", std::numeric_limits<uint64_t>::max()},
                          {"flags", 5}};
  const auto a = recorder.begin(101, 11, "vkCreateDevice", fields, 2);
  const auto b = recorder.begin(102, 12, "vkCreateImage", nullptr, 0);
  require(a != 0 && b != 0 && a != b, "call IDs absent or reused");
  recorder.end(103, 12, "vkCreateImage", b, -7, nullptr, 0);
  recorder.end(104, 11, "vkCreateDevice", a,
               std::numeric_limits<int32_t>::min(), fields, 2);
  recorder.incomplete(105, 11, "finite_tail");
  const auto final_count = capture.rows.size();
  recorder.note(106, 1, "after_tail", nullptr, 0);
  require(capture.rows.size() == final_count, "terminal capture reopened");
  capture.print("pairs");
}

void bounded_cases() {
  {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {4, 8192, 1000});
    recorder.start(true, 10, 1);
    for (unsigned i = 0; i != 10; ++i)
      recorder.note(11, 2, "event", nullptr, 0);
    require(capture.rows.size() <= 4, "event hard limit exceeded");
    capture.print("event_limit");
  }
  {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {100, 1024, 1000});
    recorder.start(true, 10, 1);
    for (unsigned i = 0; i != 30; ++i)
      recorder.note(11, 2, "bytes", nullptr, 0);
    size_t total = 0;
    for (const auto& row : capture.rows) total += row.size();
    require(total <= 1024, "byte hard limit exceeded");
    capture.print("byte_limit");
  }
  {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {100, 8192, 10});
    recorder.start(true, 100, 1);
    require(recorder.begin(109, 2, "within_window", nullptr, 0) != 0,
            "valid finite-window begin rejected");
    recorder.note(110, 2, "at_deadline", nullptr, 0);
    recorder.note(111, 2, "after_deadline", nullptr, 0);
    capture.print("time_limit");
  }
  {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {1, 1024, 10});
    recorder.start(true, 1, 1);
    recorder.note(2, 1, "no_capacity", nullptr, 0);
    require(capture.rows.size() <= 1,
            "terminal reservation exceeded tiny event limit");
    capture.print("tiny_limit");
  }
  {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {0, 0, 0});
    recorder.start(true, 1, 1);
    require(recorder.begin(2, 1, "zero_limits", nullptr, 0) == 0 &&
                capture.calls == 0,
            "zero limits invoked sink");
  }
  for (const uint64_t bytes : {uint64_t{1}, uint64_t{128}, uint64_t{256}, uint64_t{512}, uint64_t{640}}) {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture, {100, bytes, 1000});
    recorder.start(true, 1, 1);
    require(recorder.begin(2, 1, "after_tiny_bytes", nullptr, 0) == 0,
            "tiny-byte budget left recorder active without terminal reserve");
    size_t total = 0;
    for (const auto& row : capture.rows) total += row.size();
    require(total <= bytes && capture.rows.size() <= 1,
            "tiny-byte budget underflowed or emitted partial/excess rows");
    if (!capture.rows.empty()) capture.print(("tiny_bytes_" + std::to_string(bytes)).c_str());
  }
}

void invalid_metadata() {
  const char* new_reserved[] = {"max_queue_rows", "queue_capacity", "queued_rows", "drainer_active",
      "dropped_events_are_lower_bound", "post_terminal_events_counted"};
  for (unsigned index = 0; index != 6; ++index) {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture);
    recorder.start(true, 100, 1);
    const rqvp::Field field = {new_reserved[index], 999};
    recorder.note(101, 1, "spoof", &field, 1);
    recorder.note(102, 1, "after_invalid", nullptr, 0);
    capture.print(("reserved_" + std::to_string(index)).c_str());
  }
  const std::string long_name(100, 'x');
  for (unsigned test = 0; test != 8; ++test) {
    Capture capture;
    rqvp::Recorder recorder(Capture::sink, &capture);
    recorder.start(true, 100, 1);
    rqvp::Field reserved[] = {{"sequence", 9}};
    rqvp::Field duplicate[] = {{"handle", 1}, {"handle", 2}};
    rqvp::Field bad_name[] = {{"private/path", 1}};
    rqvp::Field long_field[] = {{long_name.c_str(), 1}};
    if (test == 0) recorder.note(101, 1, "private/path", nullptr, 0);
    if (test == 1) recorder.note(101, 1, "valid", reserved, 1);
    if (test == 2) recorder.note(101, 1, "valid", duplicate, 2);
    if (test == 3) recorder.note(101, 1, "valid", nullptr, 1);
    if (test == 4) recorder.note(101, 1, "valid", bad_name, 1);
    if (test == 5) recorder.note(101, 1, "valid", long_field, 1);
    if (test == 6) recorder.note(101, 1, long_name.c_str(), nullptr, 0);
    if (test == 7) recorder.note(101, 1, "valid", reserved, 1000);
    recorder.note(102, 1, "after_invalid", nullptr, 0);
    capture.print(("invalid_" + std::to_string(test)).c_str());
  }
  Capture clock;
  rqvp::Recorder recorder(Capture::sink, &clock);
  recorder.start(true, 100, 1);
  recorder.note(99, 1, "old_time", nullptr, 0);
  clock.print("clock_regression");

  Capture oversized;
  rqvp::Recorder large(Capture::sink, &oversized);
  large.start(true, 1, 1);
  std::vector<std::string> names;
  names.reserve(32);
  std::vector<rqvp::Field> fields;
  for (unsigned i = 0; i != 32; ++i) {
    auto name = "field_" + std::to_string(i);
    name.append(48 - name.size(), 'x');
    names.push_back(name);
  }
  for (const auto& name : names)
    fields.push_back({name.c_str(), std::numeric_limits<uint64_t>::max()});
  large.note(2, 1, "oversized", fields.data(), fields.size());
  oversized.print("row_limit");

  Capture many;
  rqvp::Recorder fitting(Capture::sink, &many);
  fitting.start(true, 1, 1);
  for (unsigned i = 0; i != 20; ++i) names[i] = "f" + std::to_string(i);
  for (unsigned i = 0; i != 20; ++i)
    fields[i] = {names[i].c_str(), std::numeric_limits<uint64_t>::max()};
  fitting.note(2, 1, "many_fields", fields.data(), 20);
  fitting.incomplete(3, 1, "finite_tail");
  many.print("many_fields");
}

void failures_and_reentry() {
  for (unsigned throwing = 0; throwing != 2; ++throwing) {
    Capture capture;
    capture.fail_at = 2;
    capture.throw_failure = throwing != 0;
    rqvp::Recorder recorder(Capture::sink, &capture);
    recorder.start(true, 1, 1);
    recorder.note(2, 1, "failed_write", nullptr, 0);
    recorder.note(3, 1, "after_failure", nullptr, 0);
    capture.print(throwing ? "sink_exception" : "sink_failure");
  }
  rqvp::Recorder no_sink(nullptr, nullptr);
  no_sink.start(true, 1, 1);
  require(no_sink.begin(2, 1, "no_sink", nullptr, 0) == 0,
          "missing sink left recorder active");
  for (unsigned action = 1; action != 4; ++action) {
    Capture capture;
    capture.reenter = action;
    rqvp::Recorder recorder(Capture::sink, &capture);
    capture.recorder = &recorder;
    recorder.start(true, 1, 1);
    recorder.incomplete(3, 1, "finite_tail");
    capture.print(action == 1   ? "reentrant"
                  : action == 2 ? "reentrant_thread"
                                : "reentrant_burst");
  }
}

void concurrent_pairs() {
  Capture capture;
  rqvp::Recorder recorder(Capture::sink, &capture);
  recorder.start(true, 1, 5);
  std::atomic<unsigned> begun{0};
  std::vector<std::thread> threads;
  for (unsigned i = 0; i != 8; ++i) {
    threads.emplace_back([&recorder, &begun, i] {
      const auto id = recorder.begin(2, i + 1, "concurrent", nullptr, 0);
      require(id != 0, "concurrent begin rejected");
      begun.fetch_add(1);
      while (begun.load() != 8) std::this_thread::yield();
      recorder.end(3, i + 1, "concurrent", id, 0, nullptr, 0);
    });
  }
  for (auto& thread : threads) thread.join();
  recorder.incomplete(4, 1, "finite_tail");
  capture.print("concurrent");
}
}  // namespace

int main() {
  try {
    blocked_burst();
    blocked_overflow();
    blocked_sink_failure();
    allocation_and_tail();
    defaults_and_pairs();
    bounded_cases();
    invalid_metadata();
    failures_and_reentry();
    concurrent_pairs();
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 2;
  }
}
