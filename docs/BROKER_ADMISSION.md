# Broker Cross-App Admission

The selected Quest product design is a signature-scoped Android Binder service
at
`io.github.mesmerprism.rustymanifold.broker/.ManifoldAdmissionService`. The
custom permission
`io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION` has
`protectionLevel="signature"`.

## Boundary

The service reads `Message.sendingUid`, resolves packages through
`PackageManager`, and hashes the single APK signing certificate with SHA-256.
It also supplies 256 bits from `SecureRandom`. Those are platform evidence and
entropy, not admission decisions.

The packaged `librusty_quest_manifold_broker_authority.so` passes the operation
through `rusty-quest-broker-admission` into `rusty-manifold-admission`.
Manifold checks the product-generated client grant, exact identity, capability
subset, expected revision, request freshness/replay, token lifetime/collision,
token use, revocation, and expiry. Every response identifies
`rusty.manifold.admission` as decision owner and
`local_token_or_grant_policy=false`.

The existing WebSocket compatibility server is not the admission surface and
does not turn transport acknowledgement into authorization.

## Build and static validation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-ManifoldBrokerAndroid.ps1 `
  -AndroidHome S:\Work\tools\Android\windows-sdk `
  -JavaHome S:\Work\tools\Java\temurin-17
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\Build-BrokerAdmissionClients.ps1 `
  -AndroidHome S:\Work\tools\Android\windows-sdk `
  -JavaHome S:\Work\tools\Java\temurin-17
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\checks\Test-QuestBrokerAdmissionStatic.ps1 -RepoRoot .
```

The broker build generates its grant from the actual broker signing
certificate and embeds the arm64 JNI library. The authorized client uses that
same keystore. The unauthorized client uses a separate generated test key.

## Device suite

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "& {
  & '.\tools\Invoke-BrokerAdmissionDeviceSuite.ps1' `
    -Serial @('<serial-a>','<serial-b>') `
    -OutDir '<private-evidence-dir>'
}"
```

For every serial, acceptance requires:

- successful token issue and one-time capability use;
- `replayed_request` for the repeated use id;
- successful explicit revocation and `token_revoked` afterward;
- `signature-permission` denial for the differently signed client;
- zero package fatals;
- force-stop and uninstall cleanup for broker and both clients.

Raw logcat, package dumps, serials, generated keystores, and device summaries
remain private local evidence and are not committed.
