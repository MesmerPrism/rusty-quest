param(
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
}
$buildPath = Join-Path $RepoRoot 'tools\Build-ManifoldBrokerAndroid.ps1'
$deployPath = Join-Path $RepoRoot 'tools\Invoke-ConnectionHubQuest.ps1'
if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
    throw "Missing broker build script: $buildPath"
}
$build = Get-Content -Raw -LiteralPath $buildPath
$deploy = Get-Content -Raw -LiteralPath $deployPath

function Require([string]$Pattern, [string]$Failure) {
    if ($build -cnotmatch $Pattern) {
        throw $Failure
    }
}

Require '\[switch\]\$RequireSharedMorphovisionSigner' `
    'Shared-package builds do not expose an explicit signer gate.'
Require '\$keystoreWasExplicit = -not \[string\]::IsNullOrWhiteSpace\(\$Keystore\)' `
    'The signer gate does not distinguish an explicit local binding from the broker default.'
Require '\$RequireSharedMorphovisionSigner -and -not \$keystoreWasExplicit' `
    'The broker default signer can silently enter the shared-package path.'
Require 'Shared Morphovision package builds require an explicit local -Keystore binding\.' `
    'The missing explicit signer rejection is absent.'
Require '722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8' `
    'The public shared Morphovision certificate fingerprint is not pinned.'
Require '\$certificateSha256,\s*\$SharedMorphovisionSignerSha256' `
    'The selected certificate is not compared to the pinned shared fingerprint.'
Require 'Explicit shared Morphovision signer fingerprint mismatch\.' `
    'An explicit mismatched signer is not rejected.'
Require 'artifact_signer_sha256 = \$certificateSha256' `
    'The public build receipt does not record the actual signer fingerprint.'
Require 'RUSTY_QUEST_MORPHOVISION_SIGNING_ALIAS' `
    'The shared signer alias is not supplied through a local environment binding.'
Require 'RUSTY_QUEST_MORPHOVISION_SIGNING_STORE_PASSWORD' `
    'The shared signer store password is not supplied through a local environment binding.'
Require 'RUSTY_QUEST_MORPHOVISION_SIGNING_KEY_PASSWORD' `
    'The shared signer key password is not supplied through a local environment binding.'
if ($deploy -cnotmatch '-RequireSharedMorphovisionSigner') {
    throw 'The normal Hub build/deploy path does not force the shared signer gate.'
}
if ($deploy -cnotmatch '\$sharedSigner -ne \$ExpectedSignerSha256') {
    throw 'The deploy path does not reject an inspected mismatched signer before install.'
}
if ($deploy -cmatch 'keystore_sha256\s*=') {
    throw 'The deploy receipt records keystore identity instead of only public certificate readback.'
}

Write-Host 'Manifold broker shared Morphovision signer gate: PASS'
