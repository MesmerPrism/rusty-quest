#include "../../main/cpp/hotload_spirv_contract.h"
#include <cassert>
#include <initializer_list>
#include <iostream>
#include <utility>

using Words = std::vector<uint32_t>;
static void add(Words& words, uint32_t opcode, std::initializer_list<uint32_t> operands) {
    words.push_back(static_cast<uint32_t>((operands.size()+1)<<16) | opcode);
    words.insert(words.end(), operands.begin(), operands.end());
}
static Words fixture(bool compilerInterface = false) {
    Words w = {0x07230203,0x00010300,0,64,0};
    add(w,17,{1});
    if (compilerInterface) add(w,11,{28,0x4c534c47,0x6474732e,0x3035342e,0});
    add(w,14,{0,1});
    if (compilerInterface) add(w,15,{5,15,0x6e69616d,0,26});
    else add(w,15,{5,15,0x6e69616d,0});
    add(w,16,{15,17,2,1,1});
    if (compilerInterface) { add(w,71,{26,11,28}); add(w,71,{27,11,25}); }
    add(w,71,{7,6,16}); add(w,71,{8,2}); add(w,72,{8,0,35,0}); add(w,72,{8,0,25});
    add(w,71,{10,34,0}); add(w,71,{10,33,0}); add(w,71,{11,2});
    add(w,72,{11,0,35,0}); add(w,72,{11,1,35,16});
    add(w,19,{1}); add(w,20,{2}); add(w,21,{3,32,0}); add(w,22,{4,32});
    add(w,23,{5,4,4}); add(w,23,{6,3,4}); add(w,29,{7,5}); add(w,30,{8,7});
    add(w,32,{9,12,8}); add(w,59,{9,10,12}); add(w,30,{11,6,5});
    add(w,32,{12,9,11}); add(w,59,{12,13,9}); add(w,33,{14,1});
    add(w,43,{3,17,1}); add(w,43,{3,18,2}); add(w,41,{2,19});
    if (compilerInterface) {
        add(w,23,{24,3,3}); add(w,32,{25,1,24}); add(w,59,{25,26,1});
        add(w,44,{24,27,18,17,17}); add(w,43,{4,30,0});
    }
    add(w,54,{1,15,0,14}); add(w,248,{16});
    if (compilerInterface) add(w,12,{4,29,28,13,30});
    add(w,247,{23,0}); add(w,250,{19,21,22});
    add(w,248,{21}); add(w,128,{3,20,17,18}); add(w,249,{23});
    add(w,248,{22}); add(w,249,{23}); add(w,248,{23}); add(w,253,{}); add(w,56,{});
    return w;
}
static void mutate(Words& w, uint32_t op, uint32_t first, size_t operand, uint32_t value) {
    for (size_t at=5; at<w.size(); at += w[at]>>16) {
        if ((w[at]&65535) == op && w[at+1] == first) { w[at+1+operand] = value; return; }
    }
    assert(false);
}
static const char* check(const Words& w) { return rq::workload::externalHotloadSpirvError(w.data(),w.size()); }
int main() {
    assert(check(fixture()) == nullptr);
    assert(check(fixture(true)) == nullptr); // Expected glslc imports and builtin decorations.
    auto arithmetic = fixture();
    for (size_t at=5; at<arithmetic.size(); at += arithmetic[at]>>16)
        if ((arithmetic[at]&65535) == 128) arithmetic[at] = (arithmetic[at]&0xffff0000) | 132;
    assert(check(arithmetic) == nullptr); // An independent instruction body is allowed.
    struct Change { uint32_t op, first; size_t operand; uint32_t value; };
    const Change changes[] = {
        {17,1,0,2}, {14,0,0,1}, {15,5,0,4}, {15,5,2,0x6e696162},
        {16,15,2,1}, {16,15,3,2}, {16,15,0,16}, {71,7,2,32},
        {71,10,2,1}, {72,8,3,16}, {72,11,3,4}, {23,5,2,3},
        {22,4,1,64}, {30,11,1,5}, {59,9,2,2}, {59,12,2,4},
        {32,9,2,63}, {29,7,1,64}, {71,8,1,3}
    };
    for (const auto& c : changes) { auto w=fixture(); mutate(w,c.op,c.first,c.operand,c.value); assert(check(w)); }
    for (const auto& extra : std::vector<Words>{{4u<<16|59,9,24,12}, {4u<<16|59,12,24,9},
            {4u<<16|59,9,24,0}, {2u<<16|17,1}, {4u<<16|71,24,34,0}, {3u<<16|71,24,2}}) {
        auto w=fixture(); w.insert(w.begin()+5,extra.begin(),extra.end()); assert(check(w));
    }
    for (const auto& c : std::vector<std::pair<size_t,uint32_t>>{{0,0},{1,0x00010400},{3,0},{3,65537},{4,1},{5,0},{5,0xffff0011}}) {
        auto w=fixture(); w[c.first]=c.second; assert(check(w));
    }
    auto truncated=fixture(); truncated.pop_back(); assert(check(truncated));
    assert(rq::workload::externalHotloadSpirvError(nullptr,5));
    auto oversized=fixture(); oversized.resize(16385); assert(check(oversized));
    std::cout << "external SPIR-V interface fixtures passed (not semantic/device validation)\n";
    return 0;
}
