# Owned display capture: process and wire boundary

Quest owns the independently authored Android producers:
[generic display/projection](../apps/display-stream-android/README.md) and
[experimental native square source](../apps/native-browser-capture-android/README.md).
[Rusty Hostess](https://github.com/MesmerPrism/rusty-hostess) owns host receive,
decode and operator transport. The MIT
[QuestMultiStream browser](https://github.com/MesmerPrism/QuestMultiStream)
consumes a separate process stream and converts it to its browser envelope.
No AGPL platform classes are copied into the MIT consumer. scrcpy, ADB recording,
MediaProjection, physical display mirror and native eye capture remain distinct
sources. A browser crop of the physical mirror does not become native eye capture.

## RMANVID1 version 1

All integers are big endian. The fixed stream header is 32 bytes, followed by
the specified number of UTF-8 JSON metadata bytes:

| Offset | Width | Value |
| ---: | ---: | --- |
| 0 | 8 | ASCII `RMANVID1` |
| 8 | 4 | version `1` |
| 12 | 4 | codec `1` = AVC |
| 16 | 4 | encoded width |
| 20 | 4 | encoded height |
| 24 | 4 | reserved `0` |
| 28 | 4 | metadata length |

Each access-unit packet has a 32-byte header followed by its payload:

| Offset | Width | Meaning |
| ---: | ---: | --- |
| 0 | 8 | MediaCodec presentation time, microseconds |
| 8 | 4 | flags: key `1`, codec config `2` (bit mask) |
| 12 | 4 | payload byte length |
| 16 | 8 | device elapsed nanoseconds sampled at emission, or zero unavailable |
| 24 | 8 | device Unix nanoseconds sampled at emission, or zero unavailable |

The last 16 bytes are emission-clock slots, not an end-to-end latency field or a
native server acknowledgment. Generic capture fills them; native capture sets
them to zero. Treat zero as unavailable. Both use identical framing; consumers
must not require native nonzero clocks or conflate the clock domains. Neither
codec EOS nor cleanup state is a packet type: empty EOS is omitted and a final
generic media payload retains config/key flags with the codec EOS bit removed.

| Behavior | Generic mirror/projection | Native square |
| --- | --- | --- |
| Media pipe | separate TCP socket, commonly ADB reverse | original stdout, raw or standard Base64 wrapper |
| Metadata | JSON object: source kind, encoding/display parameters, clocks; controlled shell includes `invocation_id` and `generation` | exactly `invocation_id` then `generation`; each lowercase 32-hex |
| JSON ordering | not semantic; use named keys | stable emitted order, still use named keys |
| Payload | actual MediaCodec output/config buffers | AnnexB normalized from AnnexB, AVCC configuration or four-byte length-prefix units |
| Config and video | codec config flag can precede video at its codec PTS | actual SPS+PPS config at PTS 0 before IDR; subsequent video PTS strictly advances |
| Dimensions | explicitly selected even dimensions | fixed 1280×1280 |
| Source | consented MediaProjection or shell physical/warped display mirror | installed service LEFT eye 0, relative FOV adjustment 0 |

Process config packets before video PTS checks: a config PTS of zero is not a
regressing video timestamp. Native payload maximum is `4 MiB - 12`; config
maximum is `256 KiB - 12`, matching the bounded QMS adapter. Generic source
forwards actual codec buffers; consumers retain their own bounded input checks.
No replacement SPS/PPS, invented timestamps or latency qualification is implied.

## Distinct control contracts

The controlled generic shell producer emits bounded hello/terminal JSON on
stdout, diagnostics on stderr, and media over TCP. Its original stdin consumes
one compact ordered JSON line with schema `rusty.quest.display_stream.control.v1`,
action `stop`, `invocation_id`, `generation`, `stop_nonce` in that order. Exact
identity matching only retires that attempt. Closing its channel wakes a blocked
writer; replacement is refused until the old worker and resources are retired.
The app-consented route uses its explicit Android STOP receiver instead.

The native helper's stdout is media, stderr is structured telemetry, and its
original stdin consumes `STOP <nonce>`. Its service Stop is global/asynchronous
despite the session string, and requires sole native capture ownership. These
controls are not interchangeable. Caller retirement and observer/viewer lifetime
are separate from source lifetime, and no terminal marker substitutes for actual
process exit, pipe EOF or native-server release acknowledgment.

Public source and host checks are separate from firmware compatibility, built
artifacts, browser reception and physical runtime acceptance. See each producer's
README for its exact build and evidence limits.
