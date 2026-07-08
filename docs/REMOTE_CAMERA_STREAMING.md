# Remote Camera Streaming

Rusty Quest owns the platform contract for remote camera session plans and the
Quest-owned Android broker package adapter that can execute accepted platform
commands. The first Morphospace slices describe how Quest and Android phone
camera endpoints are allowed to pair, send H.264 video lanes, apply
backpressure, bind local runtime ports, and report evidence.

This document records the public, architecture-aligned shape of the feature. It
does not import legacy runtime code or take Manifold command/session authority.
The current broker adapter can arm local receiver sockets, bind peer transport
ingress sockets, arm a sender source socket, bridge that source socket to a
peer route, and expose status. The Quest broker now compiles a Camera2 to
MediaCodec sender-source adapter and a diagnostic synthetic MediaCodec source.
The Quest-side direct TCP broker lane has live headset evidence for the outside
left/right eye camera map. The Rusty direct-p2p socket authority now has
one-way two-Quest native OpenXR projection evidence for broker-owned media over
Wi-Fi Direct. Android-phone runtime adapters, TLS relay handshakes, reverse-path
stability, simultaneous two-way media, and Makepad direct-broker projection
promotion remain pending.

## Lineage

This feature was derived from the public Rusty-XR Quest streaming work, not by
copying the old runtime authority model. When comparing behavior, start with
the legacy Rusty-XR docs `QUEST_TO_QUEST_ONLINE_STREAMING_ROADMAP.md`,
`QUEST_TO_QUEST_INTERNET_RELAY_MVP.md`,
`QUEST_TO_QUEST_NATIVE_RELAY_SESSION_2026_05_19.md`, and
`QUEST_Q2Q_AGENT_ONBOARDING.md`. The concrete legacy source paths were the
Quest broker H.264 sender/proxy classes, the composite-layer H.264 consumer and
Camera2 services, the `tools/video/q2q_relay.py` relay, and the companion
`Q2QRelayTransport.kt` phone adapter.

The lessons carried forward are receiver-first startup, explicit left/right
camera ids `50` and `51`, Android platform Camera2/MediaCodec H.264 before
larger media stacks, binary media-plane payloads, compact low-rate runtime
properties, and separate packet/decode/hardware-buffer/projection evidence.
The overreach rejected was making Makepad, the phone companion, or this Quest
adapter the session authority; Manifold remains the accepted command/session
authority.

For chronology, inspect the public Rusty-XR git history around commits
`ed0db1b` through `4514764`, then the Android companion Q2Q commits
`73844b4`, `ea201e9`, and `5f6f137`. The Morphospace port is tracked in the
private refactor iteration docs named
`remote-camera-streaming-morphospace-plan-2026-06-12.md`,
`remote-camera-streaming-morphospace-iterations-2026-06-12.md`, and
`remote-camera-streaming-legacy-lineage-2026-06-13.md`.

## Scope

The first supported topologies are:

- `quest_to_quest_two_way`: two Quest devices each publish stereo H.264 lanes
  to the other device.
- `quest_android_phone_duplex`: a Quest publishes stereo H.264 lanes to an
  Android phone while the phone publishes a mono H.264 lane back to the Quest.

The session schema is `rusty.quest.remote_camera_session.v1`. Plans live under
`fixtures/remote-camera-sessions/` and are validated by the
`rusty-quest-remote-camera` crate.

For a specific Quest endpoint, the crate can derive a
`rusty.quest.runtime_profile.v1` profile that contains only low-rate launch
state: enabled/session/topology ids, endpoint id/kind/role, lane counts,
privacy tier, transport kind, adapter kind, sender source kind, local sender
source ports, per-lane sender media profiles, camera hints, camera permission
policy, local receiver ports, peer transport ingress ports, and outgoing
transport routes.
The profile fixture
`fixtures/runtime-profiles/quest-remote-camera-q2q-diagnostic.profile.json`
proves that the remote-camera plan maps into the existing dry-run property
write path without carrying media payloads.

Quest stereo Camera2 endpoints must use the dedicated outside eye camera map:
left eye camera id `50`, right eye camera id `51`. The low-rate runtime
property for this map is
`debug.rustyquest.remote_camera.sender_camera_ids=left:50,right:51`.
`debug.rustyquest.remote_camera.sender_camera_id` stays `none` for stereo Quest
endpoints so the runtime cannot silently collapse both eyes onto one fallback
camera.

The Quest-owned `apps/manifold-broker-android` package recognizes
`command.remote_camera.start_receiver`, `command.remote_camera.start_sender`,
`command.remote_camera.get_status`, and `command.remote_camera.stop` command
envelopes. Receiver start binds local receiver sockets from the generated
`debug.rustyquest.remote_camera.receiver_ports` property so a Makepad external
H.264 player can connect through the existing binary media path, and also binds
peer transport ingress sockets from
`debug.rustyquest.remote_camera.transport_receive_ports`. Sender start reads
`debug.rustyquest.remote_camera.transport_routes` or a raw command-message
`transport_routes` override, then reads
`debug.rustyquest.remote_camera.sender_source_kind` and
`debug.rustyquest.remote_camera.sender_media_profiles` plus the optional
`debug.rustyquest.remote_camera.sender_camera_ids` map to decide whether the
local source is an external H.264 socket, a diagnostic synthetic MediaCodec
surface, or one Camera2 capture feeding a MediaCodec surface per Quest eye. If
the source is available and a route exists, sender start opens a binary TCP
bridge from the local H.264 source socket to the modeled peer ingress. If no
route is present it reports `sender_transport_pending`; if Camera2 permission
or source startup is unavailable it reports `sender_source_unavailable` without
starting a transport bridge thread.

Android `setprop` values are capped on-device, so generated direct-LAN launch
properties use compact low-rate strings. For example, sender media profiles use
`left:720x720@30:2500000;right:720x720@30:2500000`, and direct routes use
`left:quest-b.local:9079;right:quest-b.local:9080`. The broker runtime also
accepts the earlier verbose parser shape for compatibility with command-message
overrides.

## Boundaries

Rusty Quest owns:

- device-kind declarations for Quest, Android phone, and relay endpoints;
- platform media lane requirements for Camera2-style H.264 sources;
- local diagnostic versus encrypted relay privacy tiers;
- receiver-first startup, slow-peer closure, and queue bounds;
- local runtime adapter kind, sender source kind, camera permission policy, app
  receiver port bindings, and peer transport route bindings;
- operator-visible safety requirements;
- validation evidence required before runtime promotion.

Rusty Quest does not own:

- Manifold command/session authority or live stream routing;
- Makepad widgets, texture upload, projection draw, or app-shell state;
- Optics projection truth beyond declaring that stream metadata must exist;
- Matter geometry, PMD, mesh, SDF, or particle truth;
- high-rate frame payloads inside settings or control JSON.

## Media Plane

Every video lane must use the `binary-media` high-rate payload plane. JSON is
allowed for session plans and low-rate metadata only. The validator rejects
plans that try to put high-rate camera payloads in control JSON or inline JSON
frame payloads.

The first codec is H.264 because the reference work used Android MediaCodec
paths on both Quest and phone. The contract keeps this as a narrow first slice
instead of turning codec support into a generic media framework.

The current diagnostic packet stream emitted by the broker uses a compact
binary header and packet records. Stream bytes remain on TCP sockets; JSON is
limited to session plans, runtime properties, command acknowledgements, and
status evidence.

## Security

All remote camera plans require:

- a visible streaming indicator;
- explicit pairing;
- an immediate stop command;
- receiver-first startup;
- bounded queues with slow-peer close behavior.

Local LAN diagnostics may use unencrypted transport. Relay-backed or non-local
topologies must require encrypted transport on every lane.

## Validation

Run:

```powershell
cargo test -p rusty-quest-remote-camera
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Apply-RuntimeProfile.ps1 -ProfilePath fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json -DryRun -Out local-artifacts\remote-camera-property-write-plan.json
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The broker package also has static and compile validation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

Live headset evidence for the Quest-side direct TCP broker lane was first
captured on 2026-06-12 in the local developer evidence archive as
`remote-camera-broker-20260612-stereo-ids`.

The installed broker APK SHA-256 was
`4C5ED7DDEC5738A70DFB9B76DB5AD8609B60311B56A492B424D3F2AF1B5C2024`. The
`websocket-smoke-summary-stereo-ids-clean.json` run used a Manifold hello,
`command.remote_camera.start_receiver`,
`command.remote_camera.start_sender`, `command.remote_camera.get_status`, and
`command.remote_camera.stop` against `ws://127.0.0.1:8765/manifold/v1/events`.
Its live status snapshot reported four active lanes, zero failed lanes, left
camera selection `50`, right camera selection `51`, and H.264 stream metadata
on both receiver sockets. That captured build used the interim `RMQVID01`
stream magic.

The current Manifold-framing build was then captured in the local developer
evidence archive as `remote-camera-broker-20260612-rmanvid1-smoke`.

That run installed APK SHA-256
`44E9E907F4FC68ADD0912613760275460D2FC10D2C2798A0D8B7EC53C4A3C474`, applied
the same Quest stereo camera map, used a per-command loopback route override
for a one-headset direct TCP smoke, and reported `RMANVID1` H.264 stream magic
on both receiver sockets. The compact status check reported four active lanes,
zero failures, two `source_streaming_camera2` sources, left camera id `50`,
right camera id `51`, and `high_rate_json_payload=false`. The current smoke
therefore proves the Quest broker can source and bridge both Quest outside eye
cameras on one headset with the repo-family Manifold H.264 stream framing.

That evidence does not yet prove two physical Quest devices, Android-phone
runtime execution, TLS relay handshakes, headset-to-phone evidence, or Makepad
projection validation. Those belong to later Quest, Manifold, Android-phone,
and Quest Makepad slices.

## Direct P2P Socket Authority

`apps/manifold-broker-android` includes a Rusty-owned direct Wi-Fi socket
authority named `rusty_direct_p2p_socket_authority`. It is intentionally narrow:
it applies only to Rusty-owned broker sockets and bridge transports that can
bind or route over the selected Wi-Fi Direct interface/address. It does not
replace Android `ConnectivityManager.Network` for third-party Android APIs, and
it does not make unbound shell or Termux reachability an acceptance signal.

The shared PowerShell helper
`tools/qcl100_native_projection/DirectP2pMediaAuthority.ps1` builds the receiver
and sender parameter sets used by both the native QCL100 runner and the Makepad
QCL099 direct-broker mode. That keeps the route vocabulary, local bind
selection, direct-p2p address refresh, and authority summary in one place rather
than duplicating the socket model in each projection variant.

The accepted evidence split is:

- no-media lower gate: route-clear, QCL041 direct socket, XR readiness,
  no-media launch, cleanup, and zero-fatal evidence may accept
  `rusty_direct_p2p_socket_authority` for Rusty-owned sockets;
- native OpenXR media: a directional owner-to-client run has proved broker
  Camera2/H.264 bytes consumed by the native custom stereo projection path with
  a fresh final-window renderer scorecard;
- Makepad media: a both-headset QCL099 direct-broker diagnostic has proved
  direct-p2p receiver-observed bytes, direct sender socket authority, Makepad
  projection readiness on both headsets, clean cleanup, and zero Makepad fatal
  counts; it is a pass candidate, not QCL099 promotion;
- simultaneous duplex: same-group two-way native render parity remains
  unpromoted until both directions produce receiver-observed media bytes,
  final-window renderer adoption, clean address refresh, cleanup, and zero
  native/system fatal lines in the same measured run.

Live direct-p2p media runs must use the monitored QCL099/QCL100 wrappers with
regular progress artifacts and hard budgets. A missing final summary, stale
peer address, header-only byte count, one-sided projection, native/system fatal
line, or cleanup failure is fail-closed evidence, not a partial promotion.
Broker-owned direct-p2p media now also has a strict address-refresh gate:
sender startup is blocked unless both peers report observed QCL041 `p2p0`
addresses in the Wi-Fi Direct range and the two observed addresses are distinct.
Requested/default addresses can appear in summaries for operator context, but
they are not enough to launch broker media.
When the group-owner side has not yet written its own local-address field, the
gate may use the peer-observed QCL041 `WifiP2pInfo` group-owner address as the
group-owner peer address; this is still framework/live-artifact evidence and
not a requested/default endpoint.
The monitor wrappers must be dry-run/static validated after routing changes so
child processes start from the repo root, relative runner parameter paths are
stable, and blocked final summaries are surfaced as blocked monitor statuses.
QCL100 live bridge commands are bounded by `LiveBridgeCommandTimeoutSeconds`
and write `*-live-command-attempt.json` receipts before they block on broker
execution. The monitor reports the latest command/status on each poll so
source-start or receiver-start stalls are visible before the overall run
budget expires.
The broker-owned direct-p2p path also writes
`qcl100-qcl041-relays-launched.json` and
`qcl100-direct-p2p-address-refresh-attempt.json` so stalls between QCL041 group
formation and sender startup are distinguishable from bridge-command stalls.
QCL041 artifact reads are bounded ADB app-file reads and still require parseable
JSON, but the shared reader accepts valid stdout even when `run-as cat` exits
non-zero and records `qcl041_json_stdout_accepted_with_nonzero_exit_code` as a
warning. This protects the QCL099 Makepad and QCL100 native broker paths from
discarding in-progress QCL041 address evidence that the device already wrote.
QCL100 monitor summaries also salvage owner/client QCL041 app artifacts from
the devices on timeout or phase-stall and record post-cleanup Wi-Fi plus `p2p0`
readback for both serials. This is required evidence because a stalled reverse
run can leave the Wi-Fi Direct client with stale `192.168.49.*` on `p2p0` and
can allow the headset to autojoin infrastructure Wi-Fi after cleanup.
The next source-level receiver-transport patch keeps the native QCL100 and
Makepad QCL099 direct-broker paths on the same authority model: broker
receivers are deferred until after QCL041 direct-p2p address refresh, bind their
transport listener to the receiver's observed local `p2p0` address, and must
emit `rusty.quest.remote_camera.receiver_start_readiness.v1` with
`receiver_ready=true` before sender startup is allowed. This is a diagnostic
gate only until a new live run produces receiver-observed media and renderer
evidence.
The latest reverse direct-p2p diagnostic advanced past this gate but is still
non-promoting: address refresh passed with owner `192.168.49.27` and client
`192.168.49.1`, receiver start and source-only start were accepted, and the
client broker reported fresh Camera2 source frames/bytes. The peer sender lanes
still timed out from `192.168.49.1` to the owner receiver at
`192.168.49.27:9079/9080`, owner final status failed, and no final native
projection summary was emitted. Treat this as a sender-to-receiver transport
blocker, not receiver-observed media or renderer adoption evidence.
The receiver-readiness explicit-bind repeat remains non-promoting:
`qcl100-receiver-ready-explicit-bind-client-to-owner-20260707T2106Z` passed
strict address refresh and sent the owner receiver request with
`transport_bind_host=192.168.49.27`, but the broker WebSocket command failed
before `sent` or `authority_accepted`. No receiver-readiness runtime event,
sender startup, media bytes, renderer scorecard, duplex claim, or Makepad
promotion came out of that run. Cleanup required Settings `Disconnect` without
`Forget`, then active-group QCL041 cleanup, and the final strict passive
route-clear proof is
`qcl100-route-clear-after-activegroup-cleanup-20260707T211957Z`.

The follow-up Hostess/Rusty Quest command-route patch improves this failure
mode without promoting media. Hostess now performs a bounded broker WebSocket
hello/hello_ack readiness probe after forwarded-socket readiness and records a
`wait-broker-websocket-ready` setup action; failed live bridge attempts now
surface formatted execution issues such as `broker websocket handshake failed:
no HTTP response` in the QCL100 wrapper. Targeted Hostess unit tests and the
Rusty Quest native renderer static check passed.

The next two live client-to-owner retries remain non-promoting. The first
advanced past the previous receiver command blocker: direct-p2p address refresh
passed with owner `192.168.49.27` and client `192.168.49.1`,
`owner-start-receiver` passed, `client-start-source-only` passed, and
`client-final-status` passed. It was interrupted after an operator-observed
boot-sound-like event and emitted no final QCL100 summary or renderer
scorecard. The second retry again passed address refresh but failed
`owner-start-receiver` before `sent` or `authority_accepted` with the explicit
broker WebSocket no-response issue. The W-ending headset (`340YC10G7T0JBW`)
again produced the operator-observed boot-like sound. ADB uptime did not reset
and therefore did not prove an OS reboot, but logcat near the event contained
surface shutdown and Meta crash-upload activity. Live QCL100/QCL099 media work
is paused until that crash/relaunch path is understood.
Use `tools/Invoke-Qcl100CrashRelaunchWatch.ps1` for the repeat diagnosis
route. It writes `qcl100-crash-relaunch-watch-summary.json`, records
`boot_count`, `/proc/uptime`, boot reason, device date, and bounded logcat
tail/focused excerpts for both serials, and classifies reboot only when uptime
or boot count actually changes. Surface shutdown plus crash-uploader evidence
is therefore not treated as an OS reboot by itself. The wrapper is passive: it
does not clear logcat, launch packages, force-stop packages, mutate Wi-Fi, or
send `command.remote_camera.*` media commands. It also does not clear the media
pause by itself; the summary keeps `live_qcl100_qcl099_media_paused=true` and
`non_media_broker_hello_allowed=false` and requires human review before another
media attempt. If route state is stale, use only route-cleanup/preflight-only
work; do not run a non-media broker hello probe until strict route-clear
evidence is clean and the crash/relaunch watch has been reviewed. The
dedicated recovery wrapper is
`tools/Invoke-Qcl100RouteClearRecovery.ps1`. It runs the monitored QCL100
runner only with `-PreflightOnly`, QCL041 preclear, strict infrastructure-Wi-Fi
disconnected, stale-`p2p0` cleared, candidate-route-clear requirements, and
`-SkipWakePrep`; it does not expose broker receiver/source, no-media launch,
native renderer, Makepad projection, or promotion actions. Use `-DryRun` to
inspect the generated monitor command before a leased live recovery attempt;
non-dry-run use requires both `-OwnerLeaseId` and `-ClientLeaseId` and blocks
before monitor or ADB work if either is missing. Non-dry-run recovery also
performs a passive SensorLock preflight and writes
`qcl100-route-clear-sensorlock-preflight.json`; if protected
`com.oculus.os.vrlockscreen/.SensorLockActivity` UI is active, the wrapper
records `blocked_sensorlock` before QCL041 preclear, monitor execution, broker
hello, media, or native renderer launch.

Route-clear recovery `qcl100-route-clear-recovery-live-20260707T2312Z` ran
through the lease-gated wrapper and stopped at `blocked_preflight` before
broker/media work. QCL041 preclear cleared the client stale `p2p0` address,
but the owner remained connected to `MagentaWLAN-R5V4`, owner `p2p0` still had
`192.168.49.27`, and owner routes to `192.168.49.1`/`192.168.49.46` still used
`wlan0`.

Current live-state follow-up
`qcl100-route-clear-recovery-sensorlock-guard-20260708T013402Z` proved that
guard in the blocked rig state: owner SensorLock was active, the wrapper wrote
`blocked_sensorlock`, and both monitor/final summaries were absent. This is
route-cleanup safety evidence only; it does not clear Wi-Fi, launch QCL041, send
broker/media commands, or change promotion status.

Latest route-clear and direct-p2p short-media evidence:
`qcl100-owner-wifi-uiautomator-disconnect-20260707T2337Z` used the documented
UIAutomator Quest Settings workflow to dry-run and then click Wi-Fi
`Disconnect` without targeting `Forget`; it required no manual headset input.
Because QCL041 preclear-only still left owner stale `p2p0`, the bounded
active-group cleanup
`qcl041-activegroup-clean-owner-stale-p2p0-uiautomator-20260707T2343Z` cleared
that address, and the canonical wrapper
`qcl100-route-clear-after-uiautomator-activegroup-cleanup-20260707T2346Z`
completed `preflight_only` with both headsets infrastructure-disconnected, no
`p2p0` IPv4, and candidate Wi-Fi Direct routes unreachable. No broker command,
media command, native renderer, QCL099 Makepad projection, duplex evidence, or
promotion claim came out of this recovery.

The current scoped custom-authority media diagnostic is
`qcl100-direct-lower-gate-evidence-post-no-media-20260708T0024Z-control-tcp-short-media-v4`.
It used the Rusty direct QCL041 control-TCP matrix gate
`qcl100-direct-lower-gate-evidence-post-no-media-20260708T0024Z-qcl041-control-tcp-gate-v2`,
`transport_owner=broker`,
`qcl100_lower_gate_authority=rusty_direct_p2p_socket_authority`,
`qcl082_transport_protocol=control-tcp`, `direction=owner-to-client`, and
`lane_mode=left-only`. The final summary passed
`freshness_acceptance.passed=true`, `direct_p2p_media_ready=true`,
`direct_p2p_native_projection_ready=true`, client broker receiver-observed
bytes, client native-renderer stream and scorecard freshness, zero
native/system fatal lines, and zero parity blockers. It also kept
`same_group_duplex_claimed=false`. The post-run route-clear wrapper
`qcl100-route-clear-recovery-after-control-tcp-short-media-v4-20260708T0108Z`
restored both headsets to infrastructure-disconnected, no-`p2p0`-IPv4,
candidate-routes-unreachable state, and the passive watch
`qcl100-crash-relaunch-watch-post-short-media-control-tcp-v4-20260708T0109Z`
did not prove an OS reboot or fresh crash/relaunch token cluster. This is only
a one-way Rusty-owned broker direct-p2p diagnostic; it is not QCL100 promotion,
not reverse-direction proof, and not same-group duplex.

The current QCL099 Makepad direct-broker diagnostic is
`qcl099-direct-p2p-broker-makepad-20260708T0135Z`. It used
`transport_owner=broker` and
`qcl100_lower_gate_authority=rusty_direct_p2p_socket_authority`; the summary
passed `direct_p2p_receiver_observed_bytes_ready=true`,
`direct_p2p_sender_authority_ready=true`, `direct_p2p_media_ready=true`,
`projection_ready_both_headsets=true`, and
`direct_p2p_makepad_projection_ready_both_headsets=true`. The owner receiver
observed 5,032,695 bytes, the client receiver observed 6,750,195 bytes, both
senders reported Rusty direct-p2p socket authority, both Makepad fatal counts
were zero, and monitor cleanup had `cleanup_readback_clean=true`. Passive watch
`qcl099-crash-relaunch-watch-post-direct-broker-makepad-20260708T0137Z` did not
prove an OS reboot or a fresh SurfaceUtils/crash-uploader cluster; it only
recorded MRSS watchdog warnings. This is a QCL099 diagnostic pass candidate,
not QCL099 promotion and not QCL100 duplex promotion.
The static/self-test gate is:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-Qcl100CrashRelaunchWatchStatic.ps1 -RepoRoot .
```
