param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$paths = [ordered]@{
    workspace = Join-Path $RepoRoot "Cargo.toml"
    crate = Join-Path $RepoRoot "crates\rusty-quest-fleet-agent\Cargo.toml"
    source = Join-Path $RepoRoot "crates\rusty-quest-fleet-agent\src\lib.rs"
    guide = Join-Path $RepoRoot "docs\FLEET_AGENT.md"
    default_profile = Join-Path $RepoRoot "fixtures\fleet-agent\fleet-agent.disabled.profile.json"
    golden_claims = Join-Path $RepoRoot "fixtures\fleet-agent\checkin-claims-golden.valid.json"
    golden_readme = Join-Path $RepoRoot "fixtures\fleet-agent\README.md"
}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) {
        throw "Missing Fleet Agent surface $($entry.Key): $($entry.Value)"
    }
}

function Assert-Match([string]$Text, [string]$Pattern, [string]$Message) {
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

$workspace = Get-Content -Raw -LiteralPath $paths.workspace
$crate = Get-Content -Raw -LiteralPath $paths.crate
$source = Get-Content -Raw -LiteralPath $paths.source
$guide = Get-Content -Raw -LiteralPath $paths.guide
$profile = Get-Content -Raw -LiteralPath $paths.default_profile | ConvertFrom-Json
$golden = Get-Content -Raw -LiteralPath $paths.golden_readme

Assert-Match $workspace '"crates/rusty-quest-fleet-agent"' "Workspace must include the Fleet Agent contract crate."
Assert-Match $crate 'rev = "8181683be4a3abbc5daa0c4497c7aeb9e76316a8"' "Fleet contract dependency must remain pinned to the accepted public revision."
Assert-Match $crate 'ed25519-dalek = \{ version = "=2\.1\.1"' "Fleet Agent must retain the qualified Ed25519 implementation."
if ($crate -match '(?m)^\s*(tokio|reqwest|hyper|axum|jni|ndk|windows)\s*=') {
    throw "Source-only Fleet Agent contract crate must not activate transport or platform dependencies."
}

foreach ($token in @(
    'CHECKIN_SIGNATURE_ALGORITHM',
    'claims.signing_bytes',
    'received_time_ms: 0',
    'ForegroundAuthority::PlatformLimited',
    'ForegroundAuthority::ParticipatingApp',
    'FLEET_AGENT_PACKAGE',
    'capability.monitoring',
    'signing_identity_mismatch',
    'agent_disabled')) {
    Assert-Match $source ([regex]::Escape($token)) "Fleet Agent contract source is missing boundary token: $token"
}
foreach ($forbidden in @(
    'Command::new\("adb"\)|process::Command.*adb',
    'MediaCodec',
    'CameraManager',
    'WifiP2pManager',
    'Bluetooth',
    'UsageStatsManager',
    'AccessibilityService',
    'QUERY_ALL_PACKAGES',
    'java\.net\.ServerSocket')) {
    if ($source -match $forbidden) {
        throw "Fleet Agent contract source crosses a forbidden baseline boundary: $forbidden"
    }
}

if ($profile.schema -ne "rusty.quest.fleet_agent_profile.v1" -or $profile.enabled -ne $false) {
    throw "Committed Fleet Agent profile must remain the v1 inert default."
}
if ($profile.hub_endpoint -notmatch '^http://192\.0\.2\.') {
    throw "Inert fixture endpoint must stay in the documentation-only address range."
}
Assert-Match $golden '8181683be4a3abbc5daa0c4497c7aeb9e76316a8' "Golden fixture provenance must name the accepted Fleet revision."
Assert-Match $golden 'a9dd28a3681ccd242fee648a7010b85a69df38147f487e8c4e7e2b08116b8432' "Golden signing-message digest is missing."
Assert-Match $guide 'The producer creates proposals' "Guide must preserve the proposal-only authority boundary."
Assert-Match $guide 'host-owned receive time|Hub owns receive time' "Guide must preserve host-owned receive time."

Write-Output "Rusty Quest Fleet Agent static validation passed"
