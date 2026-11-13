# Live presentation without pixel witnesses

The build-selected Meta-input presentation diagnostic accepts the optional
boolean `save_witnesses` in its closed startup file
`files/presentation-diagnostic.json`. It defaults to `true`, preserving existing
finite witness runs. An explicit `false` disables raw, paired raw-pack and
processed-eye witness scheduling and their final pixel writers. Invalid types
are rejected; the setting cannot change after startup.

The effective presentation marker includes `saveWitnesses=false` and
`noPixelWitnesses=true`. Admission must join that marker to the actual new APK,
startup bytes, current process birth and publisher generation. An older APK or
an unjoined log line does not establish this behavior.

After successful parsing, validation and policy installation, no-save startup
atomically writes the bounded scalar `files/presentation-policy.json` receipt.
It records the current process id, SHA-256 and byte count of the bytes actually
parsed, `save_witnesses:false`, and `pixel_witness_policy:"disabled-no-save"`.
The marker repeats that digest/count and process id. The reader generation is
created later; admission joins its actual fresh marker from the same process
and birth. No generation or reader-retirement receipt is synthesized here.

Both GPU witness scheduling sites use the central `witness_timing` admission.
When saving is disabled it returns `None` before entering cadence/readback
admission, including for default raw mode zero, sequence zero. The three final
pixel writers also reject disabled saving before directories or files. Empty
witness directories are not created at startup in this mode.

Normal rendering, GPU submissions, fence retirement and completion/status
updates continue. `completedValid` reflects an actual completed frame and
remains independent of `witness_mask`; a zero mask is expected without saved
witnesses. Completion/status is not proof of physical scanout. Projection
exclusion, source ownership and the existing source/app cleanup contract remain
separate requirements. This switch does not claim application reader drain or
vendor retirement acknowledgement.

The selected external-Meta route uses `GpuFeedbackDiagnostic`; its general
display-composite export predicate is already false. Other camera/replay/capture
features must remain unselected in a live-only app lock and runtime profile.
This setting does not grant generic capture authority or change those products.

Focused host checks directly compile the production presentation config/writer,
presentation math and witness policy. The host substitutes only the unrelated
Android publisher's scalar completion type. Run from the repository root:

```powershell
$env:RUSTY_QUEST_NATIVE_RENDERER_PRESENTATION_DIAGNOSTIC = 'true'
cargo test --offline --locked --manifest-path apps/native-renderer-android/tests/no-save-host/Cargo.toml --target-dir target/no-save-host-tests
pwsh -NoProfile -File tools/checks/Test-NativeRendererNoSaveStatic.ps1
```

The standalone host crate has its own workspace boundary and lockfile; it does
not materialize sibling providers. Android Rust typing, an exact new locked APK,
current effective markers and live qualification remain distinct checks. No
host result is presented as device or visible-output acceptance.
