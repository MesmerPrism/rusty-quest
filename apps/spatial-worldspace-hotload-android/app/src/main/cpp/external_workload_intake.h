#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace rq::workload {
// A distinct resident-workload slot, not the 64KiB public diagnostic kernel.
inline constexpr size_t externalWorkloadMaximumBytes = 262144;
inline bool externalWorkloadDigest(const std::string& value) {
    if(value.size()!=64)return false;
    for(char c:value)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    return true;
}
inline const char* externalWorkloadContext(bool running,uint64_t actualProducer,
    uint64_t expectedProducer,uint64_t kernel,const std::string& active,uint64_t request) {
    if(!running||!actualProducer||expectedProducer!=actualProducer)return "stale_or_stopped_producer";
    if(!kernel||!request||!externalWorkloadDigest(active))return "invalid_context";
    return "";
}
inline const char* externalWorkloadStage(size_t bytes,const std::string& digest,const std::string& abi) {
    if(bytes<20||bytes>externalWorkloadMaximumBytes||bytes%4)return "external_length";
    if(!externalWorkloadDigest(digest)||!externalWorkloadDigest(abi))return "invalid_digest";
    return ""; // The owning backend validates actual bytes, reflection and live kernel state.
}
inline const char* externalWorkloadAction(const std::string& action,const std::string& candidate) {
    if(action=="activate"||action=="discard")return externalWorkloadDigest(candidate)?"":"invalid_candidate";
    if(action=="revert")return candidate.empty()?"":"unexpected_candidate";
    return "unknown_action";
}
}
