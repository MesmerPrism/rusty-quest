# Selected own-app boot entry

`ui.experiment_boot_autostart` is an explicit additional selection for a remote
VR preparation app. It adds only the non-exported `NativeRendererBootReceiver`,
the normal `RECEIVE_BOOT_COMPLETED` permission, and the declared overlay
permission used by the existing own-app return route. It is disabled when not
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

See [Android background activity restrictions](https://developer.android.com/guide/components/activities/secure-bal)
and [Meta's boot-launch guidance](https://developers.meta.com/vr/support/investigations/2685347365156014/).
The existing soft kiosk is described in [NATIVE_RENDERER_SOFT_KIOSK.md](NATIVE_RENDERER_SOFT_KIOSK.md).

Run `tools/checks/Test-NativeRendererBootEntry.ps1` for actual Android receiver
compilation, hostile/duplicate boot admission controls and selected feature
closure. Device acceptance remains a separate attended boot test.
