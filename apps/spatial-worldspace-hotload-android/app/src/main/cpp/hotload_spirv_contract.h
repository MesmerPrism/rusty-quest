#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rq::workload {
// Canonical UTF-8 JSON, no trailing newline. Keep the host preparer in agreement.
inline constexpr const char* externalHotloadAbiJson = R"abi({"entry":"main","executionModel":"GLCompute","localSize":[2,1,1],"memoryModel":"Logical/GLSL450","output":{"allocationBytes":128,"binding":0,"memberOffset":0,"set":0,"storage":"StorageBuffer","stride":16,"type":"runtime-array<vec4<f32>>","writeOnly":true},"pushConstants":{"bytes":32,"members":[{"offset":0,"type":"vec4<u32>"},{"offset":16,"type":"vec4<f32>"}]},"schema":"rusty.quest.hotload.external_abi.v1","spirv":"1.3","target":"vulkan1.1"})abi";
inline constexpr const char* externalHotloadAbiSha256 = "1d98ff07adb9a48e771d56921b2bd617898ae60c5799e75774d243cf8b5bf02a";

// This bounded interface parser is deliberately not a SPIR-V semantic validator.
// Trusted host preparation MUST run spirv-val for Vulkan 1.1. Neither that tool
// nor this parser proves termination, output bounds, or meaningful output values.
// No literal instruction-body template: arithmetic, branches and helper functions
// may change while resources and dispatch ABI remain fixed.
inline const char* externalHotloadSpirvError(const uint32_t* words, size_t wordCount) {
    if (!words || wordCount < 5 || wordCount > 16384) return "external_spirv_size";
    if (words[0] != 0x07230203 || words[1] != 0x00010300 || words[4] != 0)
        return "external_spirv_header";
    const uint32_t bound = words[3];
    if (bound < 2 || bound > 65536) return "external_spirv_bound";
    struct Node { uint32_t op = 0; std::vector<uint32_t> args; };
    struct Decoration { uint32_t target, member, kind; std::vector<uint32_t> args; };
    std::vector<Node> nodes(bound);
    std::vector<Decoration> decorations;
    std::vector<uint32_t> globals, interfaces;
    uint32_t entry = 0, modeEntry = 0;
    unsigned capabilities = 0, memories = 0, entries = 0, modes = 0, imports = 0;
    bool inFunction = false;
    auto id = [bound](uint32_t value) { return value > 0 && value < bound; };
    auto literal = [](const uint32_t* p, size_t n, std::string& s, size_t& consumed) {
        s.clear();
        for (size_t i = 0; i < n; ++i) for (unsigned j = 0; j < 4; ++j) {
            const auto c = static_cast<unsigned char>(p[i] >> (8*j));
            if (!c) {
                if (j < 3 && (p[i] >> (8*(j+1))) != 0) return false;
                consumed = i+1; return true;
            }
            s += static_cast<char>(c);
        }
        return false;
    };
    for (size_t at = 5; at < wordCount;) {
        const uint32_t count = words[at] >> 16, op = words[at] & 65535;
        if (!count || count > wordCount-at) return "external_spirv_instruction";
        const uint32_t* p = words+at+1;
        const size_t n = count-1;
        uint32_t result = 0;
        if (op == 17) {
            if (n != 1 || p[0] != 1 || ++capabilities != 1) return "external_spirv_capability";
        } else if (op == 14) {
            if (n != 2 || p[0] != 0 || p[1] != 1 || ++memories != 1) return "external_spirv_memory_model";
        } else if (op == 15) {
            std::string name; size_t used = 0;
            if (n < 3 || p[0] != 5 || !id(p[1]) || ++entries != 1 ||
                !literal(p+2,n-2,name,used) || name != "main") return "external_spirv_entry";
            entry = p[1];
            for (size_t j = 2+used; j < n; ++j) {
                if (!id(p[j])) return "external_spirv_interface";
                for (auto old : interfaces) if (old == p[j]) return "external_spirv_interface";
                interfaces.push_back(p[j]);
            }
        } else if (op == 16) {
            if (n != 5 || !id(p[0]) || p[1] != 17 || p[2] != 2 || p[3] != 1 || p[4] != 1 || ++modes != 1)
                return "external_spirv_execution_mode";
            modeEntry = p[0];
        } else if (op == 11) {
            std::string name; size_t used = 0;
            if (n < 2 || !id(p[0]) || ++imports != 1 || !literal(p+1,n-1,name,used) ||
                used != n-1 || name != "GLSL.std.450") return "external_spirv_import";
            result = p[0];
        } else if (op == 10 || op == 73 || op == 74 || op == 75 || op == 331 || (op >= 48 && op <= 52)) {
            return "external_spirv_unsupported_feature";
        } else if (op == 71 || op == 72) {
            const size_t fixed = op == 71 ? 2 : 3;
            if (n < fixed || !id(p[0])) return "external_spirv_decoration";
            const uint32_t kind = p[fixed-1];
            const bool argument = kind == 6 || kind == 11 || kind == 33 || kind == 34 || kind == 35;
            const bool flag = kind == 0 || kind == 2 || kind == 19 || kind == 20 || kind == 23 || kind == 24 || kind == 25 || kind == 42;
            if ((!argument && !flag) || n != fixed+(argument ? 1 : 0)) return "external_spirv_decoration";
            const uint32_t member = op == 71 ? UINT32_MAX : p[1];
            for (const auto& d : decorations) if (d.target == p[0] && d.member == member && d.kind == kind)
                return "external_spirv_duplicate_decoration";
            decorations.push_back({p[0],member,kind,std::vector<uint32_t>(p+fixed,p+n)});
        } else if (op >= 19 && op <= 33) {
            if (n < 1 || !id(p[0]) || (op >= 25 && op <= 27) || op == 31)
                return "external_spirv_type";
            result = p[0];
            const size_t required = op == 19 || op == 20 ? 1 : op == 22 || op == 29 ? 2 :
                op == 21 || op == 23 || op == 24 || op == 28 || op == 32 ? 3 : 0;
            if ((required && n != required) || (op == 33 && n < 2)) return "external_spirv_type";
            if (op == 21 && (p[1] != 32 || p[2] > 1)) return "external_spirv_type";
            if (op == 22 && p[1] != 32) return "external_spirv_type";
        } else if (op == 59) {
            if ((n != 3 && n != 4) || !id(p[0]) || !id(p[1])) return "external_spirv_variable";
            result = p[1];
            if (inFunction) {
                if (p[2] != 7) return "external_spirv_variable";
            } else {
                if (p[2] != 1 && p[2] != 6 && p[2] != 9 && p[2] != 12) return "external_spirv_resource";
                if (p[2] != 6 && n != 3) return "external_spirv_resource";
                globals.push_back(result);
            }
        } else if (op == 54) {
            if (n != 4 || inFunction || !id(p[1])) return "external_spirv_function";
            result = p[1]; inFunction = true;
        } else if (op == 56) {
            if (n != 0 || !inFunction) return "external_spirv_function";
            inFunction = false;
        }
        if (result) {
            if (nodes[result].op) return "external_spirv_duplicate_id";
            nodes[result] = {op,std::vector<uint32_t>(p,p+n)};
        }
        at += count;
    }
    if (inFunction || capabilities != 1 || memories != 1 || entries != 1 || modes != 1 || modeEntry != entry || nodes[entry].op != 54)
        return "external_spirv_module";
    auto dec = [&](uint32_t target, uint32_t member, uint32_t kind, uint32_t value, bool hasValue) {
        for (const auto& d : decorations) if (d.target == target && d.member == member && d.kind == kind)
            return hasValue ? d.args.size() == 1 && d.args[0] == value : d.args.empty();
        return false;
    };
    auto node = [&](uint32_t index, uint32_t op) { return id(index) && nodes[index].op == op; };
    auto vector = [&](uint32_t index, bool integer, uint32_t width) {
        if (!node(index,23)) return false;
        const auto& a = nodes[index].args;
        if (a[2] != width || !node(a[1],integer ? 21 : 22)) return false;
        const auto& scalar = nodes[a[1]].args;
        return scalar[1] == 32 && (!integer || scalar[2] == 0);
    };
    uint32_t output = 0, outputStruct = 0, runtimeArray = 0, pushStruct = 0;
    unsigned outputCount = 0, pushCount = 0;
    for (auto g : globals) {
        const auto& v = nodes[g].args;
        if (!node(v[0],32)) return "external_spirv_pointer";
        const auto& ptr = nodes[v[0]].args;
        if (ptr[1] != v[2] || !id(ptr[2])) return "external_spirv_pointer";
        const uint32_t type = ptr[2];
        if (v[2] == 12) {
            if (++outputCount != 1 || !dec(g,UINT32_MAX,33,0,true) || !dec(g,UINT32_MAX,34,0,true) ||
                !node(type,30) || nodes[type].args.size() != 2 || !dec(type,UINT32_MAX,2,0,false) ||
                !dec(type,0,35,0,true) || !dec(type,0,25,0,false)) return "external_spirv_output_layout";
            const uint32_t array = nodes[type].args[1];
            if (!node(array,29) || !dec(array,UINT32_MAX,6,16,true) || !vector(nodes[array].args[1],false,4))
                return "external_spirv_output_type";
            output = g; outputStruct = type; runtimeArray = array;
        } else if (v[2] == 9) {
            if (++pushCount != 1 || !node(type,30) || nodes[type].args.size() != 3 ||
                !dec(type,UINT32_MAX,2,0,false) || !dec(type,0,35,0,true) || !dec(type,1,35,16,true) ||
                !vector(nodes[type].args[1],true,4) || !vector(nodes[type].args[2],false,4))
                return "external_spirv_push_layout";
            pushStruct = type;
        } else if (v[2] == 1) {
            bool listed = false, valid = false;
            for (auto interfaceId : interfaces) if (interfaceId == g) listed = true;
            for (uint32_t builtin : {24u,26u,27u,28u}) if (dec(g,UINT32_MAX,11,builtin,true) && vector(type,true,3)) valid = true;
            if (dec(g,UINT32_MAX,11,29,true) && node(type,21) && nodes[type].args[1] == 32 && nodes[type].args[2] == 0) valid = true;
            if (!listed || !valid) return "external_spirv_input";
        }
    }
    if (outputCount != 1 || pushCount != 1) return "external_spirv_resources";
    for (auto interfaceId : interfaces)
        if (!node(interfaceId,59) || nodes[interfaceId].args[2] != 1) return "external_spirv_interface";
    // Close the layout annotation surface: unused or misplaced descriptors and
    // layout decorations cannot hide additional interfaces in an unused type.
    for (const auto& d : decorations) {
        if ((d.kind == 33 || d.kind == 34) && (d.target != output || d.member != UINT32_MAX)) return "external_spirv_extra_layout";
        if (d.kind == 2 && ((d.target != outputStruct && d.target != pushStruct) || d.member != UINT32_MAX)) return "external_spirv_extra_layout";
        if (d.kind == 6 && (d.target != runtimeArray || d.member != UINT32_MAX)) return "external_spirv_extra_layout";
        if (d.kind == 35 && !((d.target == outputStruct && d.member == 0) || (d.target == pushStruct && d.member < 2)))
            return "external_spirv_extra_layout";
    }
    return nullptr;
}
} // namespace rq::workload
