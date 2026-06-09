# Rusty Quest

Rusty Quest is the Morphospace lane for Quest platform behavior: runtime
profiles, Android property hygiene, permissions, launch planning, and platform
validation evidence.

This repo treats ADB and Android properties as transports. They are generated
from validated profiles and produce dry-run/readback evidence rather than
becoming hand-written launch authority.

## Android Broker Package

`apps/manifold-broker-android` is the Quest-owned Android package scaffold for
the Morphospace Manifold broker identity used by Hostess:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

It exposes `/manifold/v1/events` on local TCP port `8765`, accepts
`rusty.manifold.command.envelope.v1` WebSocket command envelopes, and returns
acknowledgements. It does not synthesize live provider stream events, so Polar,
controller, and Makepad evidence still requires real providers.

## Validation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```
