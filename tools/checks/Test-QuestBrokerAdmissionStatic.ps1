param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..") }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root "apps\manifold-broker-android\AndroidManifest.xml")
$service = Get-Content -Raw -LiteralPath (Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldAdmissionService.java")
$bridge = Get-Content -Raw -LiteralPath (Join-Path $root "apps\manifold-broker-android\src\main\java\io\github\mesmerprism\rustymanifold\broker\ManifoldAdmissionNativeBridge.java")
$native = Get-Content -Raw -LiteralPath (Join-Path $root "apps\manifold-broker-android\native\src\admission_jni.rs")
$questProjection = Get-Content -Raw -LiteralPath (Join-Path $root "crates\rusty-quest-broker-admission\src\lib.rs")
$build = Get-Content -Raw -LiteralPath (Join-Path $root "tools\Build-ManifoldBrokerAndroid.ps1")
$clientBuild = Get-Content -Raw -LiteralPath (Join-Path $root "tools\Build-BrokerAdmissionClients.ps1")
$client = Get-Content -Raw -LiteralPath (Join-Path $root "apps\broker-admission-client-android\src\main\java\io\github\mesmerprism\rustymanifold\admission_client\AdmissionClientActivity.java")

foreach ($token in @(
    'android:name="io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"',
    'android:protectionLevel="signature"',
    'android:name=".ManifoldAdmissionService"',
    'android:permission="io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"')) {
    if ($manifest -notmatch [regex]::Escape($token)) { throw "Admission manifest is missing '$token'." }
}
foreach ($token in @("message.sendingUid", "getPackagesForUid", "GET_SIGNING_CERTIFICATES", "SHA-256", "SecureRandom", "ManifoldAdmissionNativeBridge.execute")) {
    if ($service -notmatch [regex]::Escape($token)) { throw "Admission Binder service is missing '$token'." }
}
if ($service -match 'capability\.command\.' -or $service -match 'grant_id') {
    throw "Android Binder service contains grant/capability policy."
}
foreach ($source in @($bridge, $native, $questProjection)) {
    if ($source -notmatch 'rusty\.manifold\.admission' -and $source -notmatch 'QuestBrokerAdmissionRuntime') {
        throw "Admission bridge does not preserve Manifold decision ownership."
    }
}
foreach ($token in @("aarch64-linux-android", "librusty_quest_manifold_broker_authority.so", "broker-signing-certificate.der", "admission_client_signing_certificate_sha256")) {
    if ($build -notmatch [regex]::Escape($token)) { throw "Broker build is missing admission package input '$token'." }
}
if ($clientBuild -notmatch 'different_from_broker' -or $clientBuild -notmatch 'same_as_broker') {
    throw "Admission client build does not preserve the signing differential."
}
foreach ($token in @("RUSTY_QUEST_BROKER_ADMISSION_CLIENT", 'accepted ? "accepted"', "replayed_request", "token_revoked", "status=unauthorized-rejected", "reason=signature-permission")) {
    if ($client -notmatch [regex]::Escape($token)) { throw "Admission device client is missing '$token'." }
}

Write-Host "Quest broker admission static gate passed"
