# Rusty Manifold Broker Android

This app is the Rusty Quest-owned Android package surface for the Morphospace
Manifold broker identity:

```text
io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity
```

It is a platform adapter scaffold, not Manifold core authority. The app starts
a local WebSocket endpoint at `/manifold/v1/events` on TCP port `8765`,
accepts `rusty.manifold.command.envelope.v1` command envelopes, and replies
with command acknowledgements. It intentionally does not synthesize live Polar,
controller, or Makepad stream events, so live recording cannot pass without
real providers.

Build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```
