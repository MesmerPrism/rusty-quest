# Intended breath phase audio

The optional private `phase_audio` selection in a validated experiment-session
profile selects exactly three packaged MP3 cue identities (`inhale`, `retention`,
`exhale`). Absence or `enabled: false` keeps existing nonlooping condition audio.
When selected, the existing condition-a/b track serves as the looping background;
its source SHA remains the timeline's `audio_source_sha256`. The cue channel adds
no third background, audio-focus request, ducking, classifier trigger, or timer.

Native bias and cues share `current_breath_guidance_sample`, the generation-bound
intended timeline and official active-time clock. Segment index distinguishes both
hold starts and adjacent equal phase segments. Zero visual bias does not remove
the intended cue schedule. The OpenXR bridge sends only the newly observed segment.
The async owner coalesces obsolete pending samples and cues the current segment once
on a forward jump; it never replays skipped intermediate phases. A pause/resume in
the same segment does not repeat the cue. A backward time/index regression pauses
cue admission. Retired generations cannot resume. Background pause preserves media
position; phase edges only replace the cue. The bridge records intended phase,
segment start, observed active time and logical lag. These are not measured acoustic
onset, zero latency, speaker mixing or physical synchronization evidence.

All three cue players are verified and prepared on the app audio worker before
selection can start. No file/hash/decode preparation runs at each edge. Existing
condition audio owns background start/pause/resume/stop and actual audio-started
receipts. The official session active clock remains duration/completion authority;
looped MediaPlayer position never drives bias. Natural `audio-ended` is not forged
for a looping track; existing threshold persistence and explicit condition terminal
stop do not depend on natural completion. Reaching the completion threshold marks
completion durable; it does not automatically end the existing official run.
Looping music remains active until the existing explicit pause/terminal stop.
Terminal cleanup waits for cue release
and background owner cleanup, and does not report completion after cleanup failure.

`tools/generate_breath_phase_audio_fixture.py` creates distinct quiet background
tracks and short tones, with a 4s inhale / 4s hold / 4s exhale / 4s hold repeated
schedule. These are explicitly placeholders, not spoken instructions. Supply an
explicit FFmpeg executable and a fresh output directory. The private owner selects
a new profile and provider/feature closure; accepted profiles/assets stay unchanged.

Normal `Test-NativeRendererConditionAudio.ps1` runs the pure cue state controls and
actual production background/cue owners against a fake Android transport. The
shell gate tests closed opt-in/disabled and hostile cue asset joins. The panel gate
compiles production Android sources against the SDK. None prove real audio effects;
the next exact APK/native JNI compile and controlled playback slice remain required.

Packaged profile and guidance metadata are materialized under
`viscereality-recordings/packaged-closures/<compiled-profile-sha256>/`.
Java creates only new files or verifies byte-identical existing files; Rust reads
only the directory selected by the compiled profile anchor. Missing, partial,
linked or mismatched selected files deny initialization with no legacy fallback.
Older fixed profile/guidance files and timestamped session journals remain intact.
The metadata directory is not a session timestamp and is skipped by existing
session recovery. This supports same-package upgrades without rewriting prior
session identities. The ordinary shell gate runs production Java closure and
Rust reader controls; the panel gate compiles the Android producer integration.
