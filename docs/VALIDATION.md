# Rusty Quest Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The runtime profile validation path is dry-run only. It validates runtime profile
fixtures and generates a deterministic property write plan without touching a
headset or ADB server.

The Manifold broker Android scaffold has two validation levels:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Test-ManifoldBrokerAndroid.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1
```

The static test verifies package naming, `/manifold/v1/events`, Manifold
command-envelope acknowledgement support, and absence of legacy Rusty-XR
tokens. The build command requires an Android SDK and JDK in the current
process and writes a debug APK plus build manifest under `target/`.
