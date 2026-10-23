#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <mutex>
#include <sstream>
#include <string>
#include "submission_observer_status.h"

namespace {
constexpr const char* kLibrary = "libXR_APILAYER_MESMERPRISM_submission_observer.so";
std::mutex handleMutex;
void* heldHandle = nullptr;
const rq_xr_submission_observer_status_api_v1* heldApi = nullptr;
void* heldGetter = nullptr;
struct Library { const rq_xr_submission_observer_status_api_v1* api; void* getter; const char* failure; };
Library library() {
  { std::lock_guard guard(handleMutex); if (heldApi) return {heldApi, heldGetter, nullptr}; }
  // Do not manufacture loader discovery by loading an otherwise absent layer.
  void* candidate = dlopen(kLibrary, RTLD_NOW | RTLD_NOLOAD);
  if (!candidate) return {nullptr, nullptr, "not_visible_in_caller_namespace"};
  void* symbol = dlsym(candidate, "rq_xr_submission_observer_get_status_api_v1");
  using Getter = const rq_xr_submission_observer_status_api_v1* (*)();
  const auto* api = symbol ? reinterpret_cast<Getter>(symbol)() : nullptr;
  if (!api || api->struct_size != sizeof(*api) || api->abi_version != 1 || !api->read_context) {
    dlclose(candidate); return {nullptr, nullptr, "status_api_unavailable_or_bad_abi"};
  }
  Library result;
  bool extra = false;
  { std::lock_guard guard(handleMutex);
    if (!heldApi) { heldHandle = candidate; heldApi = api; heldGetter = symbol; }
    else extra = true;
    result = {heldApi, heldGetter, nullptr}; }
  if (extra) dlclose(candidate);
  // Retain one reference until process exit. This prevents a readback consumer's
  // library-lifetime generations resetting on an unload/reload at the same base.
  // It does not retain any XR instance, session, space, swapchain or graphics owner.
  return result;
}
std::string quote(const char* input) {
  std::ostringstream out; out << '"';
  if (input) for (const unsigned char value : std::string(input)) {
    if (value == '"' || value == '\\') out << '\\' << char(value);
    else if (value < 32) { const char* hex = "0123456789abcdef"; out << "\\u00" << hex[value >> 4] << hex[value & 15]; }
    else out << char(value);
  }
  out << '"'; return out.str();
}
const char* boolean(bool value) { return value ? "true" : "false"; }
void calls(std::ostringstream& out, const rq_xr_observer_calls_v1& counts) {
  out << "{\"forwarded\":\"" << counts.forwarded << "\",\"returned\":\"" << counts.returned
      << "\",\"exactSuccess\":\"" << counts.exact_success << "\",\"lastResult\":" << counts.last_result
      << ",\"counterExhausted\":" << boolean(counts.counter_exhausted != 0) << '}';
}
void age(std::ostringstream& out, uint64_t now, uint64_t then) {
  if (!then || now < then) out << "null";
  else out << '"' << now - then << '"';
}
std::string read(uint64_t instance, uint64_t session, uint64_t expectedInstance, uint64_t expectedSession) {
  const auto loaded = library();
  std::ostringstream out;
  out << "{\"schema\":\"rusty.quest.spatial_submission_observer.debug_status.v1\",\"buildSelected\":true"
      << ",\"processId\":" << getpid() << ",\"readbackAvailable\":" << boolean(loaded.api != nullptr)
      << ",\"gpuCompletionProof\":false,\"sdkPixelProof\":false,\"presentationProof\":false";
  if (!loaded.api) { out << ",\"phase\":" << quote(loaded.failure) << '}'; return out.str(); }
  Dl_info identity{};
  const bool identified = dladdr(loaded.getter, &identity) != 0;
  out << ",\"libraryReferenceHeld\":true,\"libraryIdentityAvailable\":" << boolean(identified)
      << ",\"libraryPath\":" << quote(identified ? identity.dli_fname : nullptr)
      << ",\"libraryBase\":\"" << (identified ? identity.dli_fbase : nullptr) << '"';
  rq_xr_observer_context_status_v1 status{}; status.struct_size = sizeof(status); status.abi_version = 1;
  const auto result = loaded.api->read_context(instance, session, expectedInstance, expectedSession, &status);
  out << ",\"apiResult\":" << result;
  if (result != RQ_XR_OBSERVER_OK) { out << ",\"phase\":\"status_query_rejected\"}"; return out.str(); }
  out << ",\"phase\":\"queried\",\"contextState\":" << status.context_state
      << ",\"instanceGeneration\":\"" << status.instance_generation << "\",\"sessionGeneration\":\"" << status.session_generation
      << "\",\"negotiationEntries\":\"" << status.negotiation_entries << "\",\"negotiationSuccesses\":\"" << status.negotiation_successes
      << "\",\"layerCreateEntries\":\"" << status.layer_create_entries << "\",\"layerCreateCalls\":";
  calls(out, status.layer_create);
  out << ",\"libraryCounterExhausted\":" << boolean(status.library_counter_exhausted != 0)
      << ",\"observationDisabled\":" << boolean(status.observation_disabled != 0)
      << ",\"optOutReason\":" << status.opt_out_reason << ",\"instanceRetiring\":" << boolean(status.instance_retiring != 0)
      << ",\"sessionRetiring\":" << boolean(status.session_retiring != 0) << ",\"waitCalls\":";
  calls(out, status.wait_calls); out << ",\"beginCalls\":"; calls(out, status.begin_calls);
  out << ",\"endCalls\":"; calls(out, status.end_calls);
  const auto& frame = status.frame;
  out << ",\"frame\":{\"acceptedSequence\":\"" << frame.accepted_submission_sequence
      << "\",\"endSequence\":\"" << frame.end_call_sequence << "\",\"reason\":" << frame.reason
      << ",\"downstreamEndResult\":" << frame.downstream_end_result << ",\"sessionState\":" << frame.session_state
      << ",\"focusKnown\":" << boolean(frame.focus_known != 0) << ",\"focused\":" << boolean(frame.focused != 0)
      << ",\"eligibleAtRead\":" << boolean(frame.submission_witness_eligible != 0)
      << ",\"ageNs\":";
  age(out, status.observed_host_monotonic_ns, frame.observed_host_monotonic_ns);
  out << ",\"pollAgeNs\":"; age(out, status.observed_host_monotonic_ns, frame.last_poll_host_monotonic_ns);
  out << ",\"focusEventAgeNs\":"; age(out, status.observed_host_monotonic_ns, frame.focus_event_host_monotonic_ns);
  out << "}}"; return out.str();
}
} // namespace
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_spatial_1worldspace_1hotload_SubmissionObserverNative_read(
    JNIEnv* environment, jobject, jlong instance, jlong session, jlong expectedInstance, jlong expectedSession) {
  try {
    const auto text = read(static_cast<uint64_t>(instance), static_cast<uint64_t>(session),
                           static_cast<uint64_t>(expectedInstance), static_cast<uint64_t>(expectedSession));
    return environment->NewStringUTF(text.c_str());
  } catch (...) {
    return environment->NewStringUTF("{\"buildSelected\":true,\"readbackAvailable\":false,\"phase\":\"readback_failed\",\"gpuCompletionProof\":false,\"sdkPixelProof\":false,\"presentationProof\":false}");
  }
}
