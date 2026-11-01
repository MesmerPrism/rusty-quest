# Selected own-app boot entry

`ui.experiment_boot_autostart` is an explicit additional selection for a remote
VR preparation app. It adds only the non-exported `NativeRendererBootReceiver`,
the normal `RECEIVE_BOOT_COMPLETED` permission, and the explicit overlay
permission required for this selected background launch route. It is disabled when not
selected. The selected feature also requires `remote-vr` preparation defaults.

The receiver accepts only the protected system `BOOT_COMPLETED` action and its
exact application selection metadata. It requires an existing user-granted
overlay permission and a readable system boot count. It durably records one
attempt for that boot before requesting its own `NativeActivity` with MAIN and
LAUNCHER. It has no external package, component, intent, profile or command input.
It does not enable permissions, switch HOME, install a device owner or claim
lock-task enforcement. Unknown boot identity or missing permission denies.

The request log is deliberately `launch-requested-unconfirmed`. Android can
restrict background launches; actual current app initialization, immersive
presentation and headset discoverability must be observed after a real boot.
Physical unlock, power and Guardian actions remain platform prerequisites. An
intent dispatch or simulated boot broadcast is not a cold-boot witness.

## Private diagnostic receipt

The selected app keeps one bounded receipt in the private SharedPreferences file
`shared_prefs/native_boot_observation.xml`, schema `rusty.quest.boot_observation.v1`.
It is independent of the existing `native_boot_launch.xml` admission store. No
receipt read or write participates in admission, consumes a trusted epoch, or
retries a launch. Missing or failing diagnostic storage leaves those paths intact.
The next observed system `boot_count` replaces the previous receipt. A negative
boot count is explicitly unknown and cannot join evidence to a real boot.

All 26 fields have fixed names. `boot_count` binds the receipt;
`observation_sequence` and `receiver_deliveries` are saturating counters.
`receiver_last_reason` is one of `not-observed`, `received`,
`overlay-missing`, `boot-count-unavailable`, `already-attempted`,
`admission-storage-failed`, `admitted`, or `platform-check-failed`.
`admission_outcome` is `not-admitted`, `storage-failed`, or `committed`.
`dispatch_outcome` is `not-requested`, `requested-unconfirmed`, or
`platform-launch-failed`. Duplicate delivery updates its receiver observation
while retaining the original admission and dispatch witnesses.

The pairs `<event>_order` and `<event>_elapsed_ms` exist for `receiver_first`,
`receiver_last`, `admission`, `dispatch`, `native_created`, `native_resumed`,
`trusted_launch`, `panel_created`, and `panel_resumed`. Zero order and -1 elapsed
mean unobserved. First Activity/trusted observations are retained in actual
observation order; trusted admission can precede the Java Activity-created
callback. An order counter that has saturated no longer distinguishes events.
Times use Android elapsed realtime and are comparable only within the recorded
boot. No wall clock, exception text, profile, sensor or recording payload is stored.

`native_component` is the exact selected physical native host observed by the
existing manifest selector: stock `android.app.NativeActivity` or the selected
`NativeProductPanelActivity`. The unselected physical native host and other
packages cannot become native witnesses. Own control-panel observations have
separate fields. Lifecycle/trusted observations are inert when the boot feature
metadata is absent. Receiver diagnostics also require positively established
boot-feature selection: false, missing or unavailable metadata performs no
diagnostic preference access or write, including on exception paths. Denied
outcomes are recorded only for a positively selected receiver. Activity creation,
resume and trusted launch each prove only
their named observation; none establishes a current immersive session, submitted
frame, focus, or presentation. Those require the existing native evidence.

After the next attended physical boot and normal unlock, fetch the receipt
before any manual interface launch. These exact read-only commands do not start
the app (replace the serial and package placeholders with the installed app):

```powershell
adb -s <quest-serial> shell settings get global boot_count
adb -s <quest-serial> shell run-as <installed-package> cat shared_prefs/native_boot_observation.xml
adb -s <quest-serial> shell run-as <installed-package> cat shared_prefs/native_boot_launch.xml
```

`run-as` requires an already debuggable installed package. If unavailable, retain
that limitation; these commands do not authorize a permission or package change.
Match the receipt's boot count to the system count. An absent or older receipt
does not establish why delivery or observation is missing. A cached process also
does not prove receiver delivery. `overlay-missing` records the actual
`Settings.canDrawOverlays` prerequisite result; app-op default output alone does
not establish that result. Check the existing user-granted overlay prerequisite
as part of the attended boot test. No overlay grant, settings UI, protected-input
simulation or automatic session Start is added here.

See [Android background activity restrictions](https://developer.android.com/guide/components/activities/secure-bal)
and [Meta's boot-launch guidance](https://developers.meta.com/vr/support/investigations/2685347365156014/).
The existing soft kiosk is described in [NATIVE_RENDERER_SOFT_KIOSK.md](NATIVE_RENDERER_SOFT_KIOSK.md).

Run `tools/checks/Test-NativeRendererBootEntry.ps1` for real Android receiver
compilation, production-body receiver/Application/launch observations over host
Android boundaries, failure isolation, hostile/duplicate boot admission and
selected feature closure. `Test-PolarTrustedColdStart.ps1` exercises the real
selected/unselected Polar bridge with both physical native hosts. Host controls
do not establish receiver delivery, platform launch acceptance, actual callback
order, immersive presentation or sensor operation on a headset. Device acceptance
remains a separate attended boot test.
