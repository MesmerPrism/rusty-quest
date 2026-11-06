#pragma once

#include <array>
#include <cstdint>

namespace rq::workload {
constexpr uint32_t atlasWidth = 1024;
constexpr uint32_t atlasHeight = 1024;
constexpr uint32_t headerTexels = 64;
constexpr uint32_t recordTexels = 8;
constexpr uint32_t recordBytes = 24;
constexpr uint32_t maxRecords = (atlasWidth * atlasHeight - headerTexels) / recordTexels;
constexpr uint32_t packetMagic = 0x34415752; // little-endian RWA4
constexpr uint32_t packetVersion = 4;
constexpr uint32_t handRecordLayout = 1; // XYZ + oct-normal + full32 pack frame/generation
constexpr float radiusLimit = 2.0f;
constexpr float coordinateLimit = 16.0f;

// Header words become RGB bytes with alpha255. All offsets are record indices.
enum HeaderWord : uint32_t {
    Magic=0, Version=1, Width=2, Height=3, Generation=4, Submission=5,
    Flags=6, MainCapacity=7, TracerCapacity=8, ParticlesOffset=9,
    ParticlesCount=10, AuxiliaryOffset=11, AuxiliaryCount=12,
    HandsOffset=13, HandsCount=14, GraftsOffset=15, GraftsCount=16,
    SimulationSteps=17, MonotonicLow=18, MonotonicHigh=19,
    PoseTimeLow=20, PoseTimeHigh=21, PoseRevisionLow=22, PoseRevisionHigh=23,
    PoseClockDomain=24, DrawMain=25, DrawTracers=26, DrawHands=27,
    DrawGrafts=28, CoordinateMapping=29, RadiusLimitBits=30,
    ControlRevision=31, Dataset=32, LeftActive=33, RightActive=34,
    SourceReady=35, PoseSource=36, InvalidCountAvailability=37,
    InvalidCount=38, CoordinateLimitBits=39,
    DiagnosticOffset=40, DiagnosticCount=41, DiagnosticAuxOffset=42, DiagnosticAuxCount=43,
    NativeKernelGeneration=44, NativeKernelTag=45,
    HandRecordLayout=46, SourceStateGeneration=47,
};
using PacketHeader = std::array<uint32_t,48>;

enum RecordFlags : uint16_t {
    Active=1, Invalid=2, Tracer=4, PoseInvalid=8,
    MaskLayerShift=4, MaskLayerMask=0x3f0, CohortShift=10, CohortMask=0xfc00,
};

// Particle primary bytes: float32 XYZ[0..11], octahedral SNORM16 normal[12..15],
// UNORM8 RGBA[16..19], UNORM16 radius[20..21], flags[22..23]. Particle auxiliary
// records preserve float32 aux XYZW[0..15] and atlas publication frame/generation[16..23].
// These AUX tokens are minted by atlas packing, not private-kernel completion.
// Hand/graft bytes: same XYZ/normal[0..15], actual hand-pack frame[16..19],
// generation[20..23]. Generation0 means inactive/invalid; no color/radius/flags.
// SimulationSteps/SourceStateGeneration describe the adapter's recorded source
// state candidate, not GPU completion. Flags bit0 is only the requested simulate
// hint; bit3 marks the GPU carrier; bit4 means a source dispatch recorded.
// No alpha-channel data, CPU dynamic geometry, or automatic SDK sampling proof.
} // namespace rq::workload
