#pragma once
// Host integration-test declarations only. No Android implementation is linked.
#include <cstdint>
#include <cstddef>
struct AAssetManager;
struct AAsset;
constexpr int AASSET_MODE_BUFFER=3;
extern "C" AAsset* AAssetManager_open(AAssetManager*,const char*,int);
extern "C" int64_t AAsset_getLength64(AAsset*);
extern "C" int AAsset_read(AAsset*,void*,size_t);
extern "C" void AAsset_close(AAsset*);
