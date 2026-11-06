#pragma once
#include "../../../main/cpp/hotload_sha256.h"
namespace rq::workload {
struct HotloadCatalogEntry {const char* asset;unsigned bytes;const char* sha256;unsigned abi;};
inline const auto fakeCatalogWords=::fixture();
inline const auto fakeCatalogDigest=hotloadSha256(reinterpret_cast<const uint8_t*>(fakeCatalogWords.data()),fakeCatalogWords.size()*4);
inline const HotloadCatalogEntry hotloadCatalog[2]={{"a",unsigned(fakeCatalogWords.size()*4),fakeCatalogDigest.c_str(),1},{"b",unsigned(fakeCatalogWords.size()*4),fakeCatalogDigest.c_str(),1}};
}
