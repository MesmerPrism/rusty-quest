# Rusty Fleet Agent Android

This package is the permission-minimal Quest adapter for low-rate Rusty Fleet
check-ins. Normal launcher activation is inert: it presents explicit Start and
Stop controls without starting the service. The non-exported foreground
service starts only when the app sends the exact
`io.github.mesmerprism.rustyquest.fleetagent.START` action after both
app-private files exist:

- `files/fleet-agent/profile.json`
- `files/fleet-agent/signing-seed.bin`

The profile must be explicitly enabled and the seed must be exactly 32 bytes
whose public-key fingerprint matches the enrollment profile. Neither file is
generated from intent extras or placed in public storage.

The package requests only Internet, notification, foreground-service, and
data-sync foreground-service permissions. It does not request ADB, package
visibility, usage stats, accessibility, storage, camera, microphone, BLE,
Wi-Fi mutation, media projection, spatial, or kiosk/device-owner authority.

Cleartext transport is permitted by the Android package only for the local M1
lane. Runtime validation restricts `http` endpoints to loopback, link-local,
or RFC 1918 addresses; nonlocal endpoints must use `https`. Signatures provide
integrity and enrollment binding, not confidentiality.

The service keeps no offline request queue. It reserves independent monotonic
per-peer status and per-epoch source revisions for each attempt, publishes one
bounded envelope, stores a compact app-private receipt, and waits for the
configured interval. Ordinary service restarts retain the producer epoch; an
app or configured identity/key generation change rotates the epoch and resets
only its source revision. The service is non-sticky and stops cleanly on the
exact stop action.

Content-addressed evidence APKs are debuggable and accept the exact
`DEBUG_START` and `DEBUG_STOP` activity actions for unattended serial-scoped
smoke tests. The activity ignores those actions when the application is not
debuggable, accepts no test values through the intent, and still requires the
same app-private enrollment profile and signing seed. This is a test
activation route, not a fleet control-plane contract.

Host validation:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentAndroid.ps1 -Tier Host
pwsh -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-FleetAgentAndroid.ps1 -Tier Host -Build
```

The `Host` tier is the fail-closed source/static gate. The build writes the APK
and a typed build manifest below
`target/fleet-agent-android/`. Device execution is routed through the explicit
serial smoke wrapper; raw profile, key, network, logcat, and receipt evidence
stays outside the public repository.
