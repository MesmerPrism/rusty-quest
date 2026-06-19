param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = Resolve-Path $RepoRoot
$toolsRoot = Join-Path $repoRootPath "tools"

function Read-RequiredText {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path $Path)) {
        throw "Missing native renderer runtime-profile static file ($Label): $Path"
    }
    return Get-Content -Raw -Path $Path
}

function Assert-ContainsTokens {
    param(
        [string]$Text,
        [string[]]$Tokens,
        [string]$Label
    )
    foreach ($token in $Tokens) {
        if ($Text -notmatch [regex]::Escape($token)) {
            throw "Native renderer runtime-profile static check failed for ${Label}: missing token: $token"
        }
    }
}

function Assert-PowerShellParses {
    param(
        [string]$Path,
        [string]$Label
    )
    $parseTokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$parseTokens, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count -gt 0) {
        throw "Native renderer runtime-profile static check failed for ${Label}: $($parseErrors[0].Message)"
    }
}

$runtimeProfileToolPath = Join-Path $toolsRoot "Apply-RuntimeProfile.ps1"
$runtimeProfileCratePath = Join-Path $repoRootPath "crates\rusty-quest-profile\src\lib.rs"

$runtimeProfileToolText = Read-RequiredText $runtimeProfileToolPath "runtime profile apply tool"
$runtimeProfileCrate = Read-RequiredText $runtimeProfileCratePath "runtime profile Rust validator"

Assert-PowerShellParses $runtimeProfileToolPath "runtime profile apply tool"

Assert-ContainsTokens $runtimeProfileToolText @(
    'RUSTY_QUEST_ADB_SERVER_PORT',
    'AdbServerPort',
    'Resolve-AdbServerPortArgument',
    'NativeRendererPropertyManifestRelativePath',
    'Import-NativeRendererPropertyManifest',
    'Assert-NativeRendererManifestProperty',
    'value_kind',
    'allowed_values',
    'adb_scope',
    'device-scoped-adb',
    'adb_serial_required',
    'adb_serial',
    'adb_server_port',
    '-P',
    '-s',
    '-Serial or RUSTY_QUEST_SERIAL is required with -Execute',
    'device-scoped ADB writes must not use an implicit target'
) "runtime profile apply tool serial-scoped ADB and manifest gate"

Assert-ContainsTokens $runtimeProfileCrate @(
    'NATIVE_RENDERER_PROPERTY_MANIFEST_JSON',
    'NATIVE_RENDERER_PROPERTY_MANIFEST_SCHEMA',
    'validate_native_renderer_profile_against_manifest',
    'validate_native_renderer_manifest_value',
    'manifest allowed_values',
    'f32_pair',
    'native-renderer-manifest-invalid-camera-output.profile.json'
) "runtime profile Rust validator manifest gate"

Write-Host "Rusty Quest native renderer runtime-profile static validation passed"
