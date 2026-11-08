# Packed Camera2 template experiment

App-owned `PackedStereoCaptureOwner` normally creates Camera2 TEMPLATE_RECORD
requests. A debuggable host can explicitly package integer resource
`rusty_quest_packed_camera_request_template` as `1` to select TEMPLATE_PREVIEW,
or `3` to select TEMPLATE_RECORD. An absent, unreadable or unsupported value
retains RECORD. Non-debuggable hosts do not consult this resource. The public
library packages no override. The selection is fixed when the capture owner
is constructed; there is no property, intent, live toggle or new provider verb.

The only changed camera control is the template argument for this shared
app-owned capture path. Existing FPS range selection and its setter, surface,
resolution, pairing, encoder, bitrate, queue/drop policy, media authority and
enrollment remain unchanged. The standalone compatibility source retains its
existing RECORD path.

Meta's [Spatial SDK Camera2 Kotlin example](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-pca-kotlin-api/)
creates TEMPLATE_PREVIEW requests with ImageReader and preview targets.
That is a reference for an experiment, not proof that its target topology or
behavior applies to this packed capture owner. Android's
[CameraDevice template contract](https://developer.android.com/reference/android/hardware/camera2/CameraDevice)
describes PREVIEW as prioritizing frame rate and RECORD as prioritizing stable
frame rate and recording quality. Templates initialize request defaults;
actual supported settings and results must be observed. No improvement is
claimed from selecting PREVIEW.

## Additive diagnostic fields

The existing `own_capture_stage` adds:

- `camera2_request_template`: `debuggable`, nullable `configured_template`
  and `selected_template`. These describe packaged selection, even before a
  camera request exists.
- `left_camera2_request` and `right_camera2_request`: nullable
  `requested_template`, `request`, and `result`. `requested_template` is the
  actual template argument after Camera2 returned a builder and its request
  was built. `request` reads that exact built request passed to repeating
  submission; it does not prove that submission succeeded.

Both request and result report nullable `capture_intent`,
`ae_target_fps_range` (`lower`/`upper`), `noise_reduction_mode`, `edge_mode`,
`aberration_mode`, and `video_stabilization_mode`. These are readbacks, not
new setters. Unsupported, absent or throwing optional getters report JSON
null. Result metadata additionally reports `frame_number`,
`sensor_timestamp_ns`, `sensor_frame_duration_ns`, and
`sensor_exposure_time_ns`, keeping the sensor clock distinct from callback
elapsed time. Capture intent is a Camera2 metadata value, not a template ID;
never infer one from the other.

Only the existing 600-second qualification ARM window can retain a result.
Each eye retains one immutable platform result reference and one small sample
wrapper; callback sampling reads no new metadata keys. Export reads the
retained object's keys. Its `probe` carries the sample's exact process epoch,
app generation, ARM generation, start and deadline plus current `active` state.
No timer, rearm or camera lifetime extension is added. Expired or previous-ARM
samples remain historical observations. Require their tuple to match the
current `stall_probe`, require both active states and the existing report-end
deadline guard before using them as current evidence. Sampling is per eye and
non-atomic with the rest of the report. Null is never effective FPS evidence.

The additive payload uses the existing full-response 32,768-byte cap. Retained
readers may ignore these keys; they do not validate new sample lineage.
Instrumentation and retained metadata can affect timing and memory; host
controls establish no hardware overhead bound, HAL cause, frame-rate result,
stream-gap fix, stereo presentation or product acceptance.

## Focused host checks

```powershell
python tools/checks/Test-PackedCameraRequestHost.py --output target/camera-request-host --json-jar <org-json.jar> --android-jar <android-36/android.jar>
```

The gate runs the actual selector and metadata projection with modeled
Android leaves, and extracts the production request setup and repeating-call
argument. It checks PREVIEW/RECORD, invalid/absent/unreadable and release cases,
unchanged target and narrowest supported FPS selection, no added setter,
actual request/result differences, null readbacks, off/expiry and old-window
fencing. It separately compiles all shared Java sources against Android API36
with Java8 output. The existing capture-stall and receiver-trace host gates
remain applicable. Full-envelope reader/cap compatibility requires a retained
ring-populated diagnostic fixture outside public source; a small projection
fixture alone is insufficient.
