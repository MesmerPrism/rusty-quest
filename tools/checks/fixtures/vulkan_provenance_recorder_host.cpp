#include <atomic>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include "provenance_recorder.hpp"

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
      for (unsigned i = 0; i != 100; ++i)
        self.recorder->note(2, 99, "queued", nullptr, 0);
    }
    return true;
  }

  void print(const char* name) const {
    for (const auto& row : rows) std::cout << name << '\t' << row;
  }
};

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
}

void invalid_metadata() {
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
                                : "queue_limit");
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
