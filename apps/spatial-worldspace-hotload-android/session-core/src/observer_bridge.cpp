#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <time.h>
#include "submission_observer_status.h"

// Only this bridge reads the live observer. No caller-supplied diagnostic bool.
struct CoreWitness {
  uint64_t library, instance_generation, session_generation, lifetime, focus;
  uint64_t sequence, observed_ns, poll_ns, now_ns;
  uint32_t eligible, reserved;
};
static std::mutex library_mutex;
static void* held_handle = nullptr;
static const rq_xr_submission_observer_status_api_v1* held_api = nullptr;
static void* held_getter = nullptr;
extern "C" uint64_t rq_session_host_monotonic_ns() {
  timespec value{};
  if (clock_gettime(CLOCK_MONOTONIC, &value) != 0 || value.tv_sec < 0) return 0;
  return uint64_t(value.tv_sec) * 1000000000ULL + uint64_t(value.tv_nsec);
}
extern "C" uint64_t rq_session_android_elapsed_ns() {
  timespec value{};
  if (clock_gettime(CLOCK_BOOTTIME, &value) != 0 || value.tv_sec < 0) return 0;
  return uint64_t(value.tv_sec) * 1000000000ULL + uint64_t(value.tv_nsec);
}
extern "C" int32_t rq_session_read_witness(uint64_t instance, uint64_t session,
    uint64_t expected_instance, uint64_t expected_session, CoreWitness* out) {
  if (!out || !instance || !session) return -1;
  const rq_xr_submission_observer_status_api_v1* api;
  void* getter;
  {
    std::lock_guard lock(library_mutex);
    if (!held_api) {
      void* candidate = dlopen("libXR_APILAYER_MESMERPRISM_submission_observer.so", RTLD_NOW | RTLD_NOLOAD);
      if (!candidate) return -2;
      auto symbol = dlsym(candidate, "rq_xr_submission_observer_get_status_api_v1");
      using Getter = const rq_xr_submission_observer_status_api_v1* (*)();
      const auto* value = symbol ? reinterpret_cast<Getter>(symbol)() : nullptr;
      if (!value || value->struct_size != sizeof(*value) || value->abi_version != 1 || !value->read_context) {
        dlclose(candidate); return -3;
      }
      Dl_info definition{}, callback{};
      const bool located = dladdr(symbol, &definition) != 0 && definition.dli_fname && definition.dli_fbase;
      const char* base = located ? std::strrchr(definition.dli_fname, '/') : nullptr;
      base = base ? base + 1 : (located ? definition.dli_fname : "");
      if (!located || std::strcmp(base, "libXR_APILAYER_MESMERPRISM_submission_observer.so") != 0 ||
          dladdr(reinterpret_cast<void*>(value->read_context), &callback) == 0 ||
          callback.dli_fbase != definition.dli_fbase) {
        dlclose(candidate); return -6;
      }
      // Module provenance only; APK composition separately verifies packaged binary hashes.
      held_handle = candidate; held_api = value; held_getter = symbol;
    }
    api = held_api; getter = held_getter;
  }
  rq_xr_observer_context_status_v1 state{};
  state.struct_size = sizeof(state); state.abi_version = 1;
  if (api->read_context(instance, session, expected_instance, expected_session, &state) != RQ_XR_OBSERVER_OK) return -4;
  const auto& f = state.frame;
  if (state.struct_size != sizeof(state) || state.abi_version != 1 ||
      f.struct_size != sizeof(f) || f.abi_version != 1 ||
      state.context_state != RQ_XR_CONTEXT_TRACKED || state.instance_retiring || state.session_retiring ||
      state.observation_disabled || state.opt_out_reason || state.library_counter_exhausted ||
      state.wait_calls.counter_exhausted || state.begin_calls.counter_exhausted || state.end_calls.counter_exhausted ||
      !state.instance_generation || !state.session_generation ||
      state.instance_generation != f.instance_generation || state.session_generation != f.session_generation) return -5;
  *out = {reinterpret_cast<uint64_t>(getter), f.instance_generation, f.session_generation,
    f.lifetime_revision, f.focus_revision, f.accepted_submission_sequence,
    f.observed_host_monotonic_ns, f.last_poll_host_monotonic_ns, rq_session_host_monotonic_ns(),
    uint32_t(f.submission_witness_eligible == 1 && f.reason == RQ_XR_OBSERVER_VALID_SUBMISSION &&
      f.focus_known == 1 && f.focused == 1 && f.should_render == 1 && f.matched_wait_begin == 1 &&
      f.end_succeeded == 1 && f.projection_metadata_valid == 1 && f.downstream_end_result == 0 &&
      f.projection_count == 1 && f.view_count == 2 && !f.gpu_completion_observed && !f.sdk_pixels_observed), 0};
  return 0;
}
