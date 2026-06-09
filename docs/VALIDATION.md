# Rusty Quest Validation

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_all.ps1
```

The current validation path is dry-run only. It validates runtime profile
fixtures and generates a deterministic property write plan without touching a
headset or ADB server.

