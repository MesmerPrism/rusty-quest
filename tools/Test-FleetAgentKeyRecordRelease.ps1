# Copyright (C) 2026 Rusty Quest contributors
# SPDX-License-Identifier: AGPL-3.0-or-later

[CmdletBinding(DefaultParameterSetName = "Capsule")]
param(
    [Parameter(Mandatory, ParameterSetName = "Capsule")]
    [string] $CapsuleRoot,
    [Parameter(Mandatory, ParameterSetName = "MachinePathPolicySelfTest")]
    [switch] $MachinePathPolicySelfTest,
    [Parameter(Mandatory, ParameterSetName = "PeReproPolicySelfTest")]
    [switch] $PeReproPolicySelfTest,
    [string] $ExpectedCapsuleVersion = "",
    [string] $ExpectedManifestSha256 = "",
    [string] $ExpectedExecutableSha256 = "",
    [string] $ExpectedSourceCommit = "",
    [string] $ExpectedSourceTree = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Sha256([string] $LiteralPath) {
    return (Get-FileHash -LiteralPath $LiteralPath -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ExactPropertySet($Value, [string[]] $Names, [string] $Label) {
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $expected = @($Names | Sort-Object)
    if (@(Compare-Object $actual $expected -SyncWindow 0).Count -ne 0 -or
        $actual.Count -ne $expected.Count) {
        throw "$Label contains an unknown or missing field."
    }
}

function Assert-Sha256([string] $Value, [string] $Label) {
    if ($Value -cnotmatch '^[0-9a-f]{64}$') { throw "$Label is not lowercase SHA-256." }
}

function Assert-BoundedFileSize([string] $LiteralPath, [long] $DeclaredSize, [string] $Label) {
    $actualSize = [long](Get-Item -LiteralPath $LiteralPath).Length
    if ($DeclaredSize -lt 1 -or $DeclaredSize -gt 134217728 -or $actualSize -ne $DeclaredSize) {
        throw "$Label size is outside the supported capsule bound or drifted."
    }
}

function Test-MachineLocalPathText([string] $Text) {
    $serverComponent = '[A-Za-z0-9][A-Za-z0-9._$-]*'
    $shareComponent = '[\x20-\x7e-[\\/:<>:"|?*]]+'
    $patterns = @(
        '(?i)[A-Z]:[\\/](?![\\/])',
        '(?i)\\\\\?\\[A-Z]:[\\/](?![\\/])',
        ('(?i)\\\\(?!\?\\UNC(?:\\|$))' + $serverComponent +
            '[\\/]' + $shareComponent + '(?=[\\/\x00]|$)'),
        ('(?i)\\\\\?\\UNC\\' + $serverComponent +
            '\\' + $shareComponent + '(?=[\\\x00]|$)')
    )
    return @($patterns | Where-Object { [regex]::IsMatch($Text, $_) }).Count -gt 0
}

function Assert-NoMachineLocalPathByteSequence([string] $LiteralPath) {
    $bytes = [IO.File]::ReadAllBytes($LiteralPath)
    if (Test-MachineLocalPathText ([Text.Encoding]::Latin1.GetString($bytes))) {
        throw "Fleet Agent key-record release executable contains a machine-local ASCII path."
    }
    foreach ($encoding in @([Text.Encoding]::Unicode, [Text.Encoding]::BigEndianUnicode)) {
        foreach ($offset in @(0, 1)) {
            $count = $bytes.Length - $offset
            if ($count -lt 4) { continue }
            if (($count % 2) -ne 0) { $count-- }
            if (Test-MachineLocalPathText ($encoding.GetString($bytes, $offset, $count))) {
                throw "Fleet Agent key-record release executable contains a machine-local UTF-16 path."
            }
        }
    }
}

function Get-MachinePathPolicyProbeByteSequence(
    [string] $Text,
    [Text.Encoding] $Encoding,
    [ValidateSet(0, 1)]
    [int] $Offset
) {
    $payload = $Encoding.GetBytes($Text)
    $bytes = [byte[]]::new($Offset + $payload.Length)
    if ($Offset -eq 1) { $bytes[0] = 0x7e }
    [Array]::Copy($payload, 0, $bytes, $Offset, $payload.Length)
    return $bytes
}

function Invoke-MachinePathPolicySelfTest {
    $pathForms = @(
        'Q:\synthetic\artifact.obj',
        '\\?\Q:\synthetic\artifact.obj',
        '\\synthetic-host\synthetic-share\artifact.obj',
        '\\?\UNC\synthetic-host\synthetic-share\artifact.obj'
    )
    $layouts = @(
        [pscustomobject]@{ Name = "ascii"; Encoding = [Text.Encoding]::ASCII; Offset = 0 },
        [pscustomobject]@{ Name = "utf16le-offset0"; Encoding = [Text.Encoding]::Unicode; Offset = 0 },
        [pscustomobject]@{ Name = "utf16le-offset1"; Encoding = [Text.Encoding]::Unicode; Offset = 1 },
        [pscustomobject]@{ Name = "utf16be-offset0"; Encoding = [Text.Encoding]::BigEndianUnicode; Offset = 0 },
        [pscustomobject]@{ Name = "utf16be-offset1"; Encoding = [Text.Encoding]::BigEndianUnicode; Offset = 1 }
    )
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) (
        "rusty-quest-key-record-path-policy-" + [guid]::NewGuid().ToString("N"))
    try {
        [void][IO.Directory]::CreateDirectory($probeRoot)
        foreach ($pathForm in $pathForms) {
            foreach ($layout in $layouts) {
                $probePath = Join-Path $probeRoot ("reject-" + [guid]::NewGuid().ToString("N"))
                [IO.File]::WriteAllBytes(
                    $probePath,
                    (Get-MachinePathPolicyProbeByteSequence `
                        -Text $pathForm -Encoding $layout.Encoding -Offset $layout.Offset))
                $rejected = $false
                try {
                    Assert-NoMachineLocalPathByteSequence -LiteralPath $probePath
                }
                catch {
                    if ($_.Exception.Message -notmatch 'machine-local') { throw }
                    $rejected = $true
                }
                if (-not $rejected) {
                    throw "Fleet Agent key-record release path policy missed $($layout.Name)."
                }
            }
        }
        foreach ($layout in $layouts) {
            $probePath = Join-Path $probeRoot ("accept-" + [guid]::NewGuid().ToString("N"))
            [IO.File]::WriteAllBytes(
                $probePath,
                (Get-MachinePathPolicyProbeByteSequence `
                    -Text '\\?\UNC\' -Encoding $layout.Encoding -Offset $layout.Offset))
            Assert-NoMachineLocalPathByteSequence -LiteralPath $probePath
        }
    }
    finally {
        if (Test-Path -LiteralPath $probeRoot) {
            Remove-Item -LiteralPath $probeRoot -Recurse -Force
        }
    }
}

if ($MachinePathPolicySelfTest) {
    Invoke-MachinePathPolicySelfTest
    Write-Output "Rusty Quest Fleet Agent key-record release machine-path policy self-test passed"
    return
}

function Assert-X64WindowsExecutable([string] $LiteralPath) {
    $bytes = [IO.File]::ReadAllBytes($LiteralPath)
    if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a) {
        throw "Fleet Agent key-record release artifact is not a Windows PE executable."
    }
    $peOffset = [BitConverter]::ToInt32($bytes, 0x3c)
    if ($peOffset -lt 0 -or $peOffset + 6 -gt $bytes.Length -or
        $bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45 -or
        $bytes[$peOffset + 2] -ne 0 -or $bytes[$peOffset + 3] -ne 0 -or
        [BitConverter]::ToUInt16($bytes, $peOffset + 4) -ne 0x8664) {
        throw "Fleet Agent key-record release artifact target is not x86_64-pc-windows-msvc."
    }
}

function Get-PeDebugDirectoryTypeOffsetList([byte[]] $Bytes) {
    $peOffset = [BitConverter]::ToInt32($Bytes, 0x3c)
    $sectionCount = [BitConverter]::ToUInt16($Bytes, $peOffset + 6)
    $optionalHeaderSize = [BitConverter]::ToUInt16($Bytes, $peOffset + 20)
    $optionalHeaderOffset = $peOffset + 24
    if ($optionalHeaderSize -lt 168 -or
        $optionalHeaderOffset + $optionalHeaderSize -gt $Bytes.Length -or
        [BitConverter]::ToUInt16($Bytes, $optionalHeaderOffset) -ne 0x20b -or
        [BitConverter]::ToUInt32($Bytes, $optionalHeaderOffset + 108) -le 6) {
        throw "Fleet Agent key-record release PE optional header is invalid."
    }
    $debugDirectoryRva = [BitConverter]::ToUInt32($Bytes, $optionalHeaderOffset + 160)
    $debugDirectorySize = [BitConverter]::ToUInt32($Bytes, $optionalHeaderOffset + 164)
    if ($debugDirectoryRva -eq 0 -or $debugDirectorySize -lt 28 -or
        ($debugDirectorySize % 28) -ne 0 -or $debugDirectorySize -gt 1792) {
        throw "Fleet Agent key-record release PE debug directory is invalid."
    }
    $sectionTableOffset = $optionalHeaderOffset + $optionalHeaderSize
    if ($sectionCount -lt 1 -or $sectionCount -gt 96 -or
        $sectionTableOffset + (40 * $sectionCount) -gt $Bytes.Length) {
        throw "Fleet Agent key-record release PE section table is invalid."
    }
    $debugDirectoryOffset = $null
    for ($index = 0; $index -lt $sectionCount; $index++) {
        $sectionOffset = $sectionTableOffset + (40 * $index)
        $virtualSize = [uint64][BitConverter]::ToUInt32($Bytes, $sectionOffset + 8)
        $virtualAddress = [uint64][BitConverter]::ToUInt32($Bytes, $sectionOffset + 12)
        $rawSize = [uint64][BitConverter]::ToUInt32($Bytes, $sectionOffset + 16)
        $rawOffset = [uint64][BitConverter]::ToUInt32($Bytes, $sectionOffset + 20)
        $sectionSpan = [Math]::Max($virtualSize, $rawSize)
        if ([uint64]$debugDirectoryRva -ge $virtualAddress -and
            [uint64]$debugDirectoryRva -lt $virtualAddress + $sectionSpan) {
            $relativeOffset = [uint64]$debugDirectoryRva - $virtualAddress
            if ($relativeOffset + [uint64]$debugDirectorySize -gt $rawSize -or
                $rawOffset + $relativeOffset + [uint64]$debugDirectorySize -gt
                    [uint64]$Bytes.Length) {
                throw "Fleet Agent key-record release PE debug directory escaped its section."
            }
            $debugDirectoryOffset = [int]($rawOffset + $relativeOffset)
            break
        }
    }
    if ($null -eq $debugDirectoryOffset) {
        throw "Fleet Agent key-record release PE debug directory is not file-backed."
    }
    return @(0..([int]($debugDirectorySize / 28) - 1) | ForEach-Object {
        $debugDirectoryOffset + ($_ * 28) + 12
    })
}

function Assert-PeReproducible([string] $LiteralPath) {
    $bytes = [IO.File]::ReadAllBytes($LiteralPath)
    $reproEntries = @(Get-PeDebugDirectoryTypeOffsetList -Bytes $bytes | Where-Object {
        [BitConverter]::ToUInt32($bytes, $_) -eq 16
    })
    if ($reproEntries.Count -ne 1) {
        throw "Fleet Agent key-record release executable lacks one IMAGE_DEBUG_TYPE_REPRO marker."
    }
}

function Get-PeReproPolicyProbeByteSequence([uint32] $DebugType) {
    $bytes = [byte[]]::new(1024)
    $bytes[0] = 0x4d
    $bytes[1] = 0x5a
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x80), 0, $bytes, 0x3c, 4)
    [Array]::Copy([byte[]](0x50, 0x45, 0, 0), 0, $bytes, 0x80, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint16]0x8664), 0, $bytes, 0x84, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint16]1), 0, $bytes, 0x86, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint16]240), 0, $bytes, 0x94, 2)
    $optionalHeaderOffset = 0x98
    [Array]::Copy([BitConverter]::GetBytes([uint16]0x20b), 0, $bytes, $optionalHeaderOffset, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint32]16), 0, $bytes, $optionalHeaderOffset + 108, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x1000), 0, $bytes, $optionalHeaderOffset + 160, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]28), 0, $bytes, $optionalHeaderOffset + 164, 4)
    $sectionOffset = $optionalHeaderOffset + 240
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes(".rdata"), 0, $bytes, $sectionOffset, 6)
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x200), 0, $bytes, $sectionOffset + 8, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x1000), 0, $bytes, $sectionOffset + 12, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x200), 0, $bytes, $sectionOffset + 16, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]0x200), 0, $bytes, $sectionOffset + 20, 4)
    [Array]::Copy([BitConverter]::GetBytes($DebugType), 0, $bytes, 0x20c, 4)
    return $bytes
}

function Invoke-PeReproPolicySelfTest {
    $probeRoot = Join-Path ([IO.Path]::GetTempPath()) (
        "rusty-quest-key-record-pe-repro-policy-" + [guid]::NewGuid().ToString("N"))
    try {
        [void][IO.Directory]::CreateDirectory($probeRoot)
        $probePath = Join-Path $probeRoot "probe.exe"
        [IO.File]::WriteAllBytes($probePath, (Get-PeReproPolicyProbeByteSequence -DebugType 16))
        Assert-X64WindowsExecutable -LiteralPath $probePath
        Assert-PeReproducible -LiteralPath $probePath
        [IO.File]::WriteAllBytes($probePath, (Get-PeReproPolicyProbeByteSequence -DebugType 2))
        $rejected = $false
        try {
            Assert-PeReproducible -LiteralPath $probePath
        }
        catch {
            if ($_.Exception.Message -notmatch 'IMAGE_DEBUG_TYPE_REPRO') { throw }
            $rejected = $true
        }
        if (-not $rejected) {
            throw "Fleet Agent key-record release PE reproducibility policy accepted a missing marker."
        }
    }
    finally {
        if (Test-Path -LiteralPath $probeRoot) {
            Remove-Item -LiteralPath $probeRoot -Recurse -Force
        }
    }
}

if ($PeReproPolicySelfTest) {
    Invoke-PeReproPolicySelfTest
    Write-Output "Rusty Quest Fleet Agent key-record release PE reproducibility policy self-test passed"
    return
}

$root = (Resolve-Path -LiteralPath $CapsuleRoot).Path
$expectedFiles = @(
    "LICENSE",
    "SOURCE-NOTICE.md",
    "checksums.sha256",
    "fleet-agent-key-record.exe",
    "provenance.json",
    "release-manifest.json"
) | Sort-Object
$actualFiles = @(Get-ChildItem -LiteralPath $root -File -Recurse | ForEach-Object {
    [IO.Path]::GetRelativePath($root, $_.FullName).Replace("\", "/")
} | Sort-Object)
if (@(Compare-Object $actualFiles $expectedFiles -SyncWindow 0).Count -ne 0 -or
    $actualFiles.Count -ne $expectedFiles.Count) {
    throw "Fleet Agent key-record release capsule has an extra or missing file."
}

$manifestPath = Join-Path $root "release-manifest.json"
$manifestHash = Get-Sha256 $manifestPath
if ($ExpectedManifestSha256 -and $manifestHash -cne $ExpectedManifestSha256) {
    throw "Fleet Agent key-record release manifest does not match the expected SHA-256."
}
$manifestText = Get-Content -Raw -LiteralPath $manifestPath
$manifest = $manifestText | ConvertFrom-Json -Depth 30
Assert-ExactPropertySet $manifest @(
    "schema", "capsule_version", "tool_contract", "source", "artifact",
    "distribution", "payload") "release manifest"
Assert-ExactPropertySet $manifest.tool_contract @(
    "schema", "executable", "argument_contract", "output_schema") "tool contract"
Assert-ExactPropertySet $manifest.source @(
    "repository_url", "commit", "tree", "provenance_path", "provenance_sha256") "source"
Assert-ExactPropertySet $manifest.artifact @(
    "path", "sha256", "size_bytes", "target", "profile") "artifact"
Assert-ExactPropertySet $manifest.distribution @(
    "portable", "supported", "inert_until_invoked", "install_contract",
    "private_material_included", "live_onboarding_claim") "distribution"

if ($manifest.schema -cne "rusty.quest.fleet_agent_key_record_release_capsule.v1" -or
    $manifest.capsule_version -cne "1.0.0" -or
    ($ExpectedCapsuleVersion -and $manifest.capsule_version -cne $ExpectedCapsuleVersion) -or
    $manifest.tool_contract.schema -cne "rusty.quest.fleet_agent_key_record_tool_contract.v1" -or
    $manifest.tool_contract.executable -cne "fleet-agent-key-record.exe" -or
    $manifest.tool_contract.argument_contract -cne
        "--key-id <dotted-id> --seed-file <private-seed-file>" -or
    $manifest.tool_contract.output_schema -cne "rusty.quest.fleet_agent_key_record.v1" -or
    $manifest.source.repository_url -cne "https://github.com/MesmerPrism/rusty-quest" -or
    $manifest.source.provenance_path -cne "provenance.json" -or
    $manifest.artifact.path -cne "fleet-agent-key-record.exe" -or
    $manifest.artifact.target -cne "x86_64-pc-windows-msvc" -or
    $manifest.artifact.profile -cne "release" -or
    $manifest.distribution.portable -ne $true -or
    $manifest.distribution.supported -ne $true -or
    $manifest.distribution.inert_until_invoked -ne $true -or
    $manifest.distribution.install_contract -cne "copy_capsule_byte_for_byte" -or
    $manifest.distribution.private_material_included -ne $false -or
    $manifest.distribution.live_onboarding_claim -ne $false) {
    throw "Fleet Agent key-record release contract is unsupported or stale."
}
foreach ($pair in @(
    @([string]$manifest.source.commit, "source commit", 40),
    @([string]$manifest.source.tree, "source tree", 40),
    @([string]$manifest.source.provenance_sha256, "provenance SHA-256", 64),
    @([string]$manifest.artifact.sha256, "artifact SHA-256", 64)
)) {
    $pattern = if ($pair[2] -eq 40) { '^[0-9a-f]{40}$' } else { '^[0-9a-f]{64}$' }
    if ($pair[0] -cnotmatch $pattern) { throw "$($pair[1]) is malformed." }
}
if (($ExpectedSourceCommit -and $manifest.source.commit -cne $ExpectedSourceCommit) -or
    ($ExpectedSourceTree -and $manifest.source.tree -cne $ExpectedSourceTree)) {
    throw "Fleet Agent key-record release source binding drifted."
}

$artifactPath = Join-Path $root "fleet-agent-key-record.exe"
$provenancePath = Join-Path $root "provenance.json"
if ((Get-Sha256 $artifactPath) -cne $manifest.artifact.sha256 -or
    ($ExpectedExecutableSha256 -and
        (Get-Sha256 $artifactPath) -cne $ExpectedExecutableSha256) -or
    (Get-Sha256 $provenancePath) -cne $manifest.source.provenance_sha256) {
    throw "Fleet Agent key-record release artifact or provenance bytes drifted."
}
Assert-BoundedFileSize -LiteralPath $artifactPath `
    -DeclaredSize ([long]$manifest.artifact.size_bytes) -Label "release artifact"
Assert-X64WindowsExecutable -LiteralPath $artifactPath
Assert-PeReproducible -LiteralPath $artifactPath
Assert-NoMachineLocalPathByteSequence -LiteralPath $artifactPath

$provenanceText = Get-Content -Raw -LiteralPath $provenancePath
$provenance = $provenanceText | ConvertFrom-Json -Depth 30
Assert-ExactPropertySet $provenance @(
    "schema", "capsule_version", "source", "build", "claims") "provenance"
Assert-ExactPropertySet $provenance.source @(
    "repository_url", "commit", "tree", "package", "composition_fingerprint",
    "repositories", "workspace_parse_only_repositories", "files") "provenance source"
Assert-ExactPropertySet $provenance.build @(
    "target", "profile", "rustc", "cargo", "locked_dependencies",
    "isolated_git_materializations", "post_build_identity_verified", "path_remap_root",
    "symbols_stripped", "linker_reproducibility_argument", "pe_reproducibility_marker",
    "cargo_config_sha256") "provenance build"
Assert-ExactPropertySet $provenance.claims @(
    "owner", "helper_only", "runtime_activation", "enrollment_authority",
    "device_authority", "private_seed_included", "profile_included",
    "hub_configuration_included") "provenance claims"
if ($provenance.schema -cne
        "rusty.quest.fleet_agent_key_record_release_provenance.v1" -or
    $provenance.capsule_version -cne $manifest.capsule_version -or
    $provenance.source.repository_url -cne $manifest.source.repository_url -or
    $provenance.source.commit -cne $manifest.source.commit -or
    $provenance.source.tree -cne $manifest.source.tree -or
    $provenance.source.package -cne "rusty-quest-fleet-agent" -or
    $provenance.source.composition_fingerprint -cnotmatch '^[0-9a-f]{64}$' -or
    $provenance.build.target -cne $manifest.artifact.target -or
    $provenance.build.profile -cne "release" -or
    $provenance.build.locked_dependencies -ne $true -or
    $provenance.build.isolated_git_materializations -ne $true -or
    $provenance.build.post_build_identity_verified -ne $true -or
    $provenance.build.path_remap_root -cne "/rusty-build" -or
    $provenance.build.symbols_stripped -ne $true -or
    $provenance.build.linker_reproducibility_argument -cne "/Brepro" -or
    $provenance.build.pe_reproducibility_marker -cne "IMAGE_DEBUG_TYPE_REPRO" -or
    $provenance.build.cargo_config_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
    $provenance.claims.owner -cne "rusty-quest" -or
    $provenance.claims.helper_only -ne $true -or
    $provenance.claims.runtime_activation -cne "explicit_fleet_onboard_invocation" -or
    $provenance.claims.enrollment_authority -ne $false -or
    $provenance.claims.device_authority -ne $false -or
    $provenance.claims.private_seed_included -ne $false -or
    $provenance.claims.profile_included -ne $false -or
    $provenance.claims.hub_configuration_included -ne $false) {
    throw "Fleet Agent key-record release provenance is invalid."
}

$expectedRepositories = @(
    @("rusty-fleet", "contract-dependency", "https://github.com/MesmerPrism/rusty-fleet"),
    @("rusty-manifold", "contract-dependency", "https://github.com/MesmerPrism/rusty-manifold"),
    @("rusty-quest", "release-owner", "https://github.com/MesmerPrism/rusty-quest")
)
$repositories = @($provenance.source.repositories)
if ($repositories.Count -ne $expectedRepositories.Count) {
    throw "Fleet Agent key-record release provenance repository set is not closed."
}
for ($index = 0; $index -lt $repositories.Count; $index++) {
    $repository = $repositories[$index]
    Assert-ExactPropertySet $repository @(
        "repository_id", "role", "repository_url", "commit", "tree") "repository"
    $expected = $expectedRepositories[$index]
    if ($repository.repository_id -cne $expected[0] -or
        $repository.role -cne $expected[1] -or
        $repository.repository_url -cne $expected[2] -or
        $repository.commit -cnotmatch '^[0-9a-f]{40}$' -or
        $repository.tree -cnotmatch '^[0-9a-f]{40}$') {
        throw "Fleet Agent key-record release provenance repository drifted."
    }
}

$expectedWorkspaceParseOnlyRepositories = @(
    @("rusty-lattice", "https://github.com/MesmerPrism/rusty-lattice"),
    @("rusty-matter", "https://github.com/MesmerPrism/rusty-matter"),
    @("rusty-optics", "https://github.com/MesmerPrism/rusty-optics")
)
$workspaceParseOnlyRepositories = @($provenance.source.workspace_parse_only_repositories)
if ($workspaceParseOnlyRepositories.Count -ne $expectedWorkspaceParseOnlyRepositories.Count) {
    throw "Fleet Agent key-record release workspace parse-only repository set is not closed."
}
for ($index = 0; $index -lt $workspaceParseOnlyRepositories.Count; $index++) {
    $repository = $workspaceParseOnlyRepositories[$index]
    Assert-ExactPropertySet $repository @(
        "repository_id", "role", "repository_url", "commit", "tree") `
        "workspace parse-only repository"
    $expected = $expectedWorkspaceParseOnlyRepositories[$index]
    if ($repository.repository_id -cne $expected[0] -or
        $repository.role -cne "workspace-parse-only" -or
        $repository.repository_url -cne $expected[1] -or
        $repository.commit -cnotmatch '^[0-9a-f]{40}$' -or
        $repository.tree -cnotmatch '^[0-9a-f]{40}$') {
        throw "Fleet Agent key-record release workspace parse-only repository drifted."
    }
}
$targetRepositoryIds = @($repositories.repository_id)
if (@($workspaceParseOnlyRepositories | Where-Object {
    $targetRepositoryIds -ccontains [string]$_.repository_id
}).Count -ne 0) {
    throw "Fleet Agent key-record release parse-only and target repository roles overlap."
}

$expectedSourcePaths = @(
    "Cargo.lock", "Cargo.toml", "crates/rusty-quest-fleet-agent/Cargo.toml",
    "crates/rusty-quest-fleet-agent/src/bin/fleet-agent-key-record.rs",
    "crates/rusty-quest-fleet-agent/src/lib.rs",
    "tools/Build-FleetAgentKeyRecordRelease.ps1",
    "tools/Test-FleetAgentKeyRecordRelease.ps1",
    "tools/lib/SourceComposition.psm1")
$sourceFiles = @($provenance.source.files)
if (@(Compare-Object @($sourceFiles.path) $expectedSourcePaths -SyncWindow 0).Count -ne 0 -or
    $sourceFiles.Count -ne $expectedSourcePaths.Count) {
    throw "Fleet Agent key-record release source-file set drifted."
}
foreach ($file in $sourceFiles) {
    Assert-ExactPropertySet $file @("path", "sha256") "source file"
    Assert-Sha256 ([string]$file.sha256) "source file SHA-256"
}

$payload = @($manifest.payload)
if ($payload.Count -ne 4) { throw "Fleet Agent key-record release payload is not closed." }
$expectedPayloadPaths = @("fleet-agent-key-record.exe", "provenance.json", "LICENSE", "SOURCE-NOTICE.md")
if (@(Compare-Object @($payload.path) $expectedPayloadPaths -SyncWindow 0).Count -ne 0) {
    throw "Fleet Agent key-record release payload path set drifted."
}
foreach ($entry in $payload) {
    Assert-ExactPropertySet $entry @("path", "sha256", "size_bytes") "payload entry"
    Assert-Sha256 ([string]$entry.sha256) "payload SHA-256"
    $path = Join-Path $root ([string]$entry.path)
    if ((Get-Sha256 $path) -cne $entry.sha256) {
        throw "Fleet Agent key-record release payload bytes drifted."
    }
    Assert-BoundedFileSize -LiteralPath $path -DeclaredSize ([long]$entry.size_bytes) `
        -Label "payload entry"
}

$checksumLines = @(Get-Content -LiteralPath (Join-Path $root "checksums.sha256"))
$expectedChecksumLines = @(
    "$(Get-Sha256 $artifactPath)  fleet-agent-key-record.exe",
    "$(Get-Sha256 $provenancePath)  provenance.json",
    "$(Get-Sha256 (Join-Path $root 'LICENSE'))  LICENSE",
    "$(Get-Sha256 (Join-Path $root 'SOURCE-NOTICE.md'))  SOURCE-NOTICE.md",
    "$manifestHash  release-manifest.json"
)
if (@(Compare-Object $checksumLines $expectedChecksumLines -SyncWindow 0).Count -ne 0 -or
    $checksumLines.Count -ne $expectedChecksumLines.Count) {
    throw "Fleet Agent key-record release checksum projection drifted."
}

$publicText = $manifestText + "`n" + $provenanceText + "`n" +
    (Get-Content -Raw -LiteralPath (Join-Path $root "SOURCE-NOTICE.md"))
foreach ($pattern in @(
    '[A-Za-z]:\\', '\\\\[^\\]', 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY',
    'signing-seed\.bin', 'hub_endpoint', 'state_directory', 'device_serial',
    'pairing_code')) {
    if ($publicText -match $pattern) {
        throw "Fleet Agent key-record release contains prohibited private or machine-local material."
    }
}

[pscustomobject][ordered]@{
    schema = "rusty.quest.fleet_agent_key_record_release_validation.v1"
    status = "pass"
    capsule_version = [string]$manifest.capsule_version
    source_commit = [string]$manifest.source.commit
    source_tree = [string]$manifest.source.tree
    manifest_sha256 = $manifestHash
    executable_sha256 = [string]$manifest.artifact.sha256
    executable_size_bytes = [long]$manifest.artifact.size_bytes
    private_material_included = $false
    live_onboarding_claim = $false
} | ConvertTo-Json -Depth 5
