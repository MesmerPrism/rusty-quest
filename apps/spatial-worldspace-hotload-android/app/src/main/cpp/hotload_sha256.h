#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>
namespace rq::workload {
// SHA-256 (FIPS 180-4), isolated from driver and transaction operations.
inline std::string hotloadSha256(const uint8_t* bytes,size_t length) {
 static constexpr uint32_t k[64]={0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
 std::array<uint32_t,8> h={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
 std::vector<uint8_t> p(bytes,bytes+length);p.push_back(128);while(p.size()%64!=56)p.push_back(0);
 for(int i=7;i>=0;--i)p.push_back(uint8_t((uint64_t(length)*8)>>(i*8)));
 auto r=[](uint32_t x,unsigned n){return (x>>n)|(x<<(32-n));};
 for(size_t base=0;base<p.size();base+=64){uint32_t w[64]{};
  for(unsigned i=0;i<16;++i)for(unsigned j=0;j<4;++j)w[i]=(w[i]<<8)|p[base+i*4+j];
  for(unsigned i=16;i<64;++i)w[i]=w[i-16]+(r(w[i-15],7)^r(w[i-15],18)^(w[i-15]>>3))+w[i-7]+(r(w[i-2],17)^r(w[i-2],19)^(w[i-2]>>10));
  auto v=h;for(unsigned i=0;i<64;++i){uint32_t t1=v[7]+(r(v[4],6)^r(v[4],11)^r(v[4],25))+((v[4]&v[5])^(~v[4]&v[6]))+k[i]+w[i];uint32_t t2=(r(v[0],2)^r(v[0],13)^r(v[0],22))+((v[0]&v[1])^(v[0]&v[2])^(v[1]&v[2]));for(int j=7;j>0;--j)v[j]=v[j-1];v[4]+=t1;v[0]=t1+t2;}for(unsigned i=0;i<8;++i)h[i]+=v[i];
 }
 std::string out;for(auto x:h)for(int i=7;i>=0;--i)out.push_back("0123456789abcdef"[(x>>(i*4))&15]);return out;
}
}
