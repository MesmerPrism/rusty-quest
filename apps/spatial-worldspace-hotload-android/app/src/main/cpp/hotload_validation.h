#pragma once
#include "hotload_sha256.h"
#include <cstring>
namespace rq::workload {
inline const char* hotloadBlobError(const uint8_t* bytes,size_t length,size_t expectedBytes,const std::string& digest,unsigned abi){
 if(abi!=1)return "catalog_abi_mismatch";
 if(length<20||length>65536||length%4||length!=expectedBytes)return "asset_length_mismatch";
 uint32_t magic=0;std::memcpy(&magic,bytes,4);
 if(magic!=0x07230203)return "asset_magic_mismatch";
 if(hotloadSha256(bytes,length)!=digest)return "asset_digest_mismatch";
 return nullptr;
}
inline const char* hotloadPresetError(const std::string& action){
 if(action=="bad-abi")return "preset_abi_mismatch_before_pipeline";
 if(action=="malformed")return "preset_malformed_before_pipeline";
 return nullptr;
}
}
