# Experimental native browser capture producer

This independently authored AGPL-3.0-or-later sidecar adapts an existing Quest
systemdriver screen-capture service to an owned AVC encoder Surface and
[RMANVID1 v1](../../docs/DISPLAY_CAPTURE_WIRE.md). It ships no Meta code, APK,
native library, AIDL implementation or firmware. The installed Driver supplies
its typed AIDL classes at runtime. Hostess and the MIT QuestMultiStream browser
consume the process/wire interface; they need not link this implementation.

The public entry class is
`io.github.mesmerprism.rustyquest.nativecapture.NativeBrowserCapture`.
The helper uses `BroadcastReceiver.peekService` for the already-running service.
It never starts/binds that service, impersonates a Meta application, sets hidden
API exemptions, invents Binder transaction numbers or loads Meta native globals.
It requires actual shell UID 2000, a real `com.android.shell` Application and
attribution, and the normally granted
`horizonos.permission.METACAM_SCREEN_CAPTURE` permission. Failure is refusal,
not a permission-grant or alternate-identity fallback.

## Build and host checks

From the repository root, with Python 3, JDK 17, Android platform 34 and SDK
build-tools 36.0.0 installed:

```powershell
python tools/Build-NativeBrowserCapture.py --java-home $env:JAVA_HOME --android-home $env:ANDROID_HOME
```

This compiles seven pure Java test groups, all five production classes against
API 34, and D8 with minimum API 31. It creates
`target/native-browser-capture-android/native-browser-capture.jar` and a local
input/artifact hash receipt. Output must be a new directory below `target/`;
select `--out-dir target/native-browser-repeat` for a repeat. There is no APK,
device discovery, installation, capture or ADB operation. The synthetic binary
and Base64 fixtures in that directory exercise production framing; they are
not playable H.264 or proof of headset compatibility.

The host tests cover config/key/PTS ordering, malformed AnnexB/AVCC/length-prefix
refusal, real SPS/PPS requirements, global Stop/grace/original-Binder-death
ownership, closed arguments, exact Driver profiles and streaming Base64 padding
and finalization. Android Base64, codec, service and cleanup execution still
require separate device evidence for the selected artifact.

## Firmware and geometry limits

Both profiles below require the actual PackageManager installed source path,
exact byte count and SHA-256, exact service manifest/permission, original Binder
descriptor and independently checked service/interface version **10/10**.
The fingerprint is an installed firmware artifact fingerprint, not a device ID.
No other fingerprint is admitted. A matching hash alone does not prove runtime
compatibility. OS marketing-version mapping was not established.

| Profile | Driver APK SHA-256 | Bytes | Typed interface |
| --- | --- | ---: | --- |
| A | `ac164cf7d9394cc07006bb3977cc2090287231f019c24be006959ee8a537237e` | 37386220 | 10/10 required |
| B | `01b4a76ae81f11d824499d4830e4cc8788c58062c29015c50816c61530b910a5` | 36415479 | 10/10 required |

This source fixes the request to **1280×1280, LEFT eye 0, relative capture-aspect
FOV adjustment 0**, AVC baseline requested at 8 Mbit/s and 30 fps, no B frames.
Actual codec format is checked. The nonempty invocation becomes `captureSessionId`;
an empty session is never sent. Privacy explicitly uses `liftInhibit=false` and
`hideCaptureIndicator=false`. Other inspected request defaults must match before
Start. There is no BOTH-eye/FOV option in this helper. Relative zero is not a
calibrated absolute FOV, and this is not an assertion of MQDH Cinematic casting
equivalence, global FOV restoration or peripheral geometry quality.

The predecessor's October 2026 experiments exercised simultaneous square browser
streams and normal Stop. This publication renames its private diagnostic package
and adds a portable builder; those earlier experiments are historical evidence,
not qualification of this public JAR, other firmware or arbitrary geometry.

## Original process launch contract

The operator must hold the exact headset and **sole native ScreenCapture Video
scope** for the whole original invocation, including uncertain cleanup. Native
Stop is global and asynchronous; a session string is telemetry, not an OS
ownership fence. Do not start this alongside another owner of that service.

Stage only your locally built JAR through your authorized serial-scoped launcher.
Use `app_process /system/bin` with `CLASSPATH` set to its staged path and the
public entry class above. Keep that exact process's stdin, stdout and stderr.
Pass these paired, closed arguments:

```text
--driver-apk CANONICAL_INSTALLED_PACKAGE_MANAGER_SOURCE_PATH
--driver-sha256 EXACT_PROFILE_A_OR_B_SHA256
--invocation FRESH_LOWERCASE_HEX32
--generation FRESH_LOWERCASE_HEX32
--stop-nonce FRESH_LOWERCASE_HEX32
--wire-encoding base64
```

The optional `--duration-ms` is 2000 through 60000 and begins after accepted
native Start. Omit it for operator-stop lifetime. `--wire-encoding raw` is also
accepted, but the observed Windows ADB shell transport changed LF bytes to CRLF;
use `base64` there. Decode standard streaming Base64 before the unchanged
RMANVID1 reader, accepting whitespace and final padding. Never repair binary
bytes by inverse-CRLF substitution. Stdout is media only; drain JSON diagnostics
from stderr independently. Do not use a launch mode that merges stderr into it.

Only the original stdin accepts `STOP <exact stop-nonce>\n` (CRLF also accepted).
EOF also requests Stop without certifying a nonce match. Wrong or oversized
input fails and requests cleanup. A viewer disconnect is not a Stop request.
The host must continue draining both pipes through process exit and full EOF.

## Cleanup and evidence

The native control worker and encoder/wire worker are distinct: blocked stdout
cannot prevent dispatch of the single original-proxy Stop. Every attempted
Start, including a partial failure, gets at most one Stop. After accepted Stop,
the helper drains through a two-second grace, EOS and a joined encoder thread,
then retires codec, its input Surface, original death monitor and control worker.
Only then does it finalize Base64 and emit a terminal event.

Unknown/rejected Stop or unknown resource retirement retains the original
process and resources, emitting `CLEANUP_UNKNOWN_HELD` without terminal success.
Continue custody and reconcile that same original; never kill/retry or treat an
unrelated service restart as original-Binder death. A blocked stdin reader is a
daemon with no join claim; original process exit and full EOF close that scope.

Stderr schema is `rusty.quest.native_browser_capture.event.v1`. `READY` means
native request admission, not decoded video. `TERMINAL` has `retired` or
`failed_retired`, actual Stop/retirement facts, wire finalization and counters.
`own_resources_retired` describes client resources only.
`native_server_acknowledged` is always false: accepted asynchronous Stop plus a
grace is no native-server Surface-release acknowledgment. Exit 0 requires known
successful client retirement and wire finalization; known failed retirement is
exit 1. Full process/pipe completion and useful browser reception remain host
observations, separate from these markers.
