param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$OutDir = "",
    [string]$Keystore = "",
    [string]$ProductSpecPath = "",
    [string]$ProductLockPath = "",
    [string]$ManifoldSourceRoot = "",
    [string[]]$MediaSessionBindingPath = @(),
    [string]$MediaStreamAarPath = "",
    [string]$ExpectedMediaStreamAarSha256 = "",
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode = 1,
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$')]
    [string]$VersionName = "0.1.0",
    [switch]$LegacyCameraP2pCompatibility,
    [ValidatePattern('^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$')]
    [string]$SpatialCameraPanelPackageName = "",
    [switch]$EnableConnectionHubDebugOperator,
    [switch]$EnableRemoteCameraDebugOperator,
    [switch]$RequireSharedMorphovisionSigner,
    [switch]$PrepareOnly,
    [switch]$ValidateRuntimeConfigOnly
)

$ErrorActionPreference = "Stop"
$SharedMorphovisionSignerSha256 = "722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8"
$ApprovedManifoldRevision = "ae3effb502e5b3bf565dc628b3ac74235397145d"
$ApprovedManifoldTree = "4a148035b8692be171833a7ba235a391403c8256"
$ApprovedLegacyProductSpecSha256 = "007cac98547be79ddfdade70cfeedbca1c154034e52a8d62b59946f3dea5b314"
$ApprovedLegacyProductLockSha256 = "f311d4fa9f5ddd37f6936b33f996885d12997edb9f89c36048945eb1f339268d"

function Get-LatestDirectory {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Parent,
        [Parameter(Mandatory=$true)]
        [string]$Pattern
    )

    $directory = Get-ChildItem -LiteralPath $Parent -Directory -Filter $Pattern |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $directory) {
        throw "No directory matching $Pattern under $Parent"
    }
    return $directory.FullName
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$File,
        [string[]]$Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Get-FileSha256Hex {
    param([Parameter(Mandatory=$true)][string]$Path)

    $cmd = Get-Command Get-FileHash -ErrorAction SilentlyContinue
    if ($null -ne $cmd) {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hash = $sha.ComputeHash($stream)
            return -join ($hash | ForEach-Object { $_.ToString("x2") })
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-TextSha256Hex {
    param([Parameter(Mandatory=$true)][string]$Text)

    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
    try {
        return [Convert]::ToHexString(
            [System.Security.Cryptography.SHA256]::HashData($bytes)
        ).ToLowerInvariant()
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

function Assert-ReusableOutputIsolated {
    param(
        [Parameter(Mandatory=$true)][string]$OutputPath,
        [string[]]$InputPath = @()
    )

    $outputFull = [IO.Path]::GetFullPath($OutputPath).TrimEnd("\", "/")
    $cursor = $outputFull
    while (-not [string]::IsNullOrWhiteSpace($cursor)) {
        if (Test-Path -LiteralPath $cursor) {
            $outputItem = Get-Item -LiteralPath $cursor -Force
            if (($outputItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "OutDir cannot traverse a reparse point because it is replaced during builds: $cursor"
            }
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -ceq $cursor) { break }
        $cursor = $parent
    }
    foreach ($candidate in @($InputPath | Where-Object {
                -not [string]::IsNullOrWhiteSpace([string]$_) })) {
        $candidateFull = if (Test-Path -LiteralPath $candidate) {
            (Resolve-Path -LiteralPath $candidate).Path.TrimEnd("\", "/")
        } else {
            [IO.Path]::GetFullPath($candidate).TrimEnd("\", "/")
        }
        $separator = [string][IO.Path]::DirectorySeparatorChar
        if ($candidateFull.Equals($outputFull, [StringComparison]::OrdinalIgnoreCase) -or
            $candidateFull.StartsWith($outputFull + $separator,
                [StringComparison]::OrdinalIgnoreCase) -or
            $outputFull.StartsWith($candidateFull + $separator,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "Reusable OutDir must be disjoint from every retained input: $candidateFull"
        }
    }
}

. (Join-Path $PSScriptRoot "../crates/rusty-quest-media-stream-android/tools/MediaStreamCargoInputs.ps1")

function Read-ValidatedSpatialVideoHubContract {
    param([Parameter(Mandatory=$true)][string]$RepoRoot)

    $path = Join-Path $RepoRoot "apps\spatial-video-control-example-android\contracts\connection-hub-media-surface.v1.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Spatial video Connection Hub surface contract does not exist: $path"
    }
    $contract = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    $expectedCommands = @(
        [ordered]@{ command = "command.spatial_video_control.pause"; required_controller_capability = "capability.spatial_video_control.pause" },
        [ordered]@{ command = "command.spatial_video_control.play"; required_controller_capability = "capability.spatial_video_control.play" },
        [ordered]@{ command = "command.spatial_video_control.select_next"; required_controller_capability = "capability.spatial_video_control.select_next" },
        [ordered]@{ command = "command.spatial_video_control.select_previous"; required_controller_capability = "capability.spatial_video_control.select_previous" }
    )
    if ([string]$contract.'$schema' -cne "rusty.quest.connection_hub.media_surface_contract.v1" -or
        [string]$contract.provider_id -cne "provider.quest.spatial-video-control-example" -or
        [string]$contract.surface_id -cne "surface.spatial_video_control.media" -or
        [string]$contract.typed_params_schema -cne "rusty.manifold.connection_hub.typed_params.empty.v1" -or
        @($contract.commands).Count -ne $expectedCommands.Count) {
        throw "Spatial video Connection Hub surface contract changed outside its closed reviewed boundary."
    }

    $canonical = "v1`n$($contract.surface_id)`n$($contract.display_label)`n$($contract.description)`n"
    $commands = @()
    for ($index = 0; $index -lt $expectedCommands.Count; $index += 1) {
        $actual = @($contract.commands)[$index]
        $expected = $expectedCommands[$index]
        if ([string]$actual.command -cne [string]$expected.command -or
            [string]$actual.required_controller_capability -cne [string]$expected.required_controller_capability -or
            [string]::IsNullOrWhiteSpace([string]$actual.display_label)) {
            throw "Spatial video Connection Hub surface command $index is not the exact reviewed command."
        }
        $canonical += "$($actual.command)|$($actual.display_label)|$($actual.required_controller_capability)`n"
        $commands += [ordered]@{
            command_id = [string]$actual.command
            typed_params_schema_id = "rusty.manifold.connection_hub.typed_params.empty.v1"
            typed_params_schema_sha256 = "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51"
            required_controller_capability = [string]$actual.required_controller_capability
        }
    }
    $canonicalSha256 = "sha256:" + [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))
    ).ToLowerInvariant()
    if ([string]$contract.canonical_contract_sha256 -cne $canonicalSha256) {
        throw "Spatial video Connection Hub surface contract canonical hash is invalid."
    }
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $path).Path
        canonical_sha256 = $canonicalSha256
        commands = $commands
    }
}

function Read-ValidatedSpatialCameraPanelLockedPlaylistHubContract {
    param([Parameter(Mandatory=$true)][string]$RepoRoot)

    $path = Join-Path $RepoRoot "apps\manifold-broker-android\contracts\spatial-camera-panel-locked-playlist-surface.v1.json"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Spatial Camera Panel locked-playlist Hub surface contract does not exist: $path"
    }
    $contract = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    $expectedProperties = @(
        '$schema', 'availability', 'canonical_contract_sha256', 'canonical_version',
        'commands', 'description', 'direct_item_activation', 'display_label',
        'lifecycle', 'max_state_bytes', 'max_state_keys', 'max_string_bytes',
        'ordered_item_list', 'provider_id', 'state_keys', 'surface_id',
        'typed_params_schema', 'runtime_surface_contract_sha256'
    )
    $actualProperties = @($contract.PSObject.Properties.Name | Sort-Object)
    if (($actualProperties -join "`n") -cne (($expectedProperties | Sort-Object) -join "`n")) {
        throw "Spatial Camera Panel locked-playlist Hub surface contract field set changed."
    }
    $expectedCommands = @(
        [ordered]@{ command = "command.spatial_camera_panel.locked_playlist.next"; display_label = "Next"; required_controller_capability = "capability.spatial_camera_panel.locked_playlist.next" },
        [ordered]@{ command = "command.spatial_camera_panel.locked_playlist.pause"; display_label = "Pause"; required_controller_capability = "capability.spatial_camera_panel.locked_playlist.pause" },
        [ordered]@{ command = "command.spatial_camera_panel.locked_playlist.previous"; display_label = "Previous"; required_controller_capability = "capability.spatial_camera_panel.locked_playlist.previous" },
        [ordered]@{ command = "command.spatial_camera_panel.locked_playlist.resume"; display_label = "Resume"; required_controller_capability = "capability.spatial_camera_panel.locked_playlist.resume" }
    )
    $expectedStateKeys = @(
        "active_index", "active_label", "item_count", "item_duration_seconds",
        "item_elapsed_seconds", "paused", "phase",
        "playlist_title", "progress", "revision", "running"
    )
    if ([string]$contract.'$schema' -cne "rusty.quest.connection_hub.locked_playlist_surface_contract.v1" -or
        [string]$contract.canonical_version -cne "locked-playlist-v2" -or
        [string]$contract.provider_id -cne "provider.quest.spatial-camera-panel-locked-playlist" -or
        [string]$contract.surface_id -cne "surface.spatial_camera_panel.locked_playlist" -or
        [string]$contract.display_label -cne "Spatial Camera Locked Playlist" -or
        [string]$contract.description -cne "Control the active locked media sequence offered by Spatial Camera Panel." -or
        [string]$contract.typed_params_schema -cne "rusty.manifold.connection_hub.typed_params.empty.v1" -or
        [string]$contract.availability -cne "effective_locked_playlist_only" -or
        [string]$contract.lifecycle -cne "unregister_when_unavailable" -or
        [string]$contract.direct_item_activation -cne "unsupported-alpha4-empty-args" -or
        [string]$contract.ordered_item_list -cne "unsupported-alpha4-scalar-state" -or
        [int]$contract.max_state_keys -ne 16 -or
        [int]$contract.max_state_bytes -ne 4096 -or
        [int]$contract.max_string_bytes -ne 256 -or
        @($contract.commands).Count -ne $expectedCommands.Count -or
        @($contract.state_keys).Count -ne $expectedStateKeys.Count) {
        throw "Spatial Camera Panel locked-playlist Hub surface contract changed outside its closed reviewed boundary."
    }

    $canonical = "locked-playlist-v2`nprovider|$($contract.provider_id)`nsurface|$($contract.surface_id)`nlabel|$($contract.display_label)`ndescription|$($contract.description)`ntyped_params|$($contract.typed_params_schema)`navailability|$($contract.availability)`nlifecycle|$($contract.lifecycle)`ndirect_item_activation|$($contract.direct_item_activation)`nordered_item_list|$($contract.ordered_item_list)`nmax_state_keys|$($contract.max_state_keys)`nmax_state_bytes|$($contract.max_state_bytes)`nmax_string_bytes|$($contract.max_string_bytes)`n"
    $runtimeCanonical = "v1`n$($contract.surface_id)`n$($contract.display_label)`n$($contract.description)`n"
    $commands = @()
    for ($index = 0; $index -lt $expectedCommands.Count; $index += 1) {
        $actual = @($contract.commands)[$index]
        $expected = $expectedCommands[$index]
        $actualCommandProperties = @($actual.PSObject.Properties.Name | Sort-Object)
        if (($actualCommandProperties -join "`n") -cne "command`ndisplay_label`nrequired_controller_capability" -or
            [string]$actual.command -cne [string]$expected.command -or
            [string]$actual.display_label -cne [string]$expected.display_label -or
            [string]$actual.required_controller_capability -cne [string]$expected.required_controller_capability) {
            throw "Spatial Camera Panel locked-playlist Hub surface command $index is not the exact reviewed command."
        }
        $canonical += "command|$($actual.command)|$($actual.display_label)|$($actual.required_controller_capability)`n"
        $runtimeCanonical += "$($actual.command)|$($actual.display_label)|$($actual.required_controller_capability)`n"
        $commands += [ordered]@{
            command_id = [string]$actual.command
            typed_params_schema_id = "rusty.manifold.connection_hub.typed_params.empty.v1"
            typed_params_schema_sha256 = "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51"
            required_controller_capability = [string]$actual.required_controller_capability
        }
    }
    for ($index = 0; $index -lt $expectedStateKeys.Count; $index += 1) {
        $actualStateKey = [string]@($contract.state_keys)[$index]
        if ($actualStateKey -cne [string]$expectedStateKeys[$index]) {
            throw "Spatial Camera Panel locked-playlist Hub state key $index is not the exact reviewed scalar key."
        }
        $canonical += "state|$actualStateKey`n"
    }
    $canonicalSha256 = "sha256:" + [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))
    ).ToLowerInvariant()
    if ([string]$contract.canonical_contract_sha256 -cne $canonicalSha256) {
        throw "Spatial Camera Panel locked-playlist Hub surface contract canonical hash is invalid."
    }
    $runtimeSha256 = "sha256:" + [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($runtimeCanonical))
    ).ToLowerInvariant()
    if ([string]$contract.runtime_surface_contract_sha256 -cne $runtimeSha256) {
        throw "Spatial Camera Panel locked-playlist Hub runtime surface hash is invalid."
    }
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $path).Path
        canonical_sha256 = $canonicalSha256
        runtime_sha256 = $runtimeSha256
        commands = $commands
        state_keys = $expectedStateKeys
    }
}

function Resolve-ProductInputPath {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$RepoRoot
    )

    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $RepoRoot $Path
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Label does not exist: $candidate"
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-ExactClientGrantCapabilities {
    param(
        [Parameter(Mandatory=$true)]$ClientLock,
        [Parameter(Mandatory=$true)]$ProductLock
    )
    $allowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($commandId in @($ProductLock.command_ids)) {
        $suffix = ([string]$commandId) -replace '^command\.', ''
        [void]$allowed.Add("capability.command.$suffix")
    }
    if (@($ProductLock.features | ForEach-Object { [string]$_ }) -contains "connection_hub") {
        [void]$allowed.Add("capability.connection_hub.provider.register")
    }
    $features = @($ProductLock.features | ForEach-Object { [string]$_ })
    $commands = @($ProductLock.command_ids | ForEach-Object { [string]$_ })
    $streams = @($ProductLock.stream_ids | ForEach-Object { [string]$_ })
    $mediaSelected = $features -contains "media_session"
    $peerSelected = ($features -contains "direct_p2p") -or
        ($features -contains "ble_rendezvous") -or
        ($commands -contains "command.peer.status.get") -or
        ($streams -contains "stream.peer.status")
    $result = foreach ($capability in @($ClientLock.capabilities | ForEach-Object { [string]$_ })) {
        if ($allowed.Contains($capability) -or
            ($mediaSelected -and ($capability -eq "capability.media.session.observe" -or $capability.StartsWith("capability.sink.", [System.StringComparison]::Ordinal))) -or
            ($peerSelected -and $capability -eq "capability.peer.session.observe")) {
            $capability
        }
    }
    return @($result | Sort-Object -Unique)
}

function Read-ValidatedClientLock {
    param([Parameter(Mandatory=$true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Broker client lock does not exist: $Path"
    }
    $json = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path))
    $lock = $json | ConvertFrom-Json
    if ([string]$lock.schema -ne "rusty.quest.broker_client_spec.v1" -or
        [string]::IsNullOrWhiteSpace([string]$lock.client_id) -or
        [string]::IsNullOrWhiteSpace([string]$lock.package_name) -or
        @($lock.adapter_permissions).Count -ne 1 -or
        [string]$lock.adapter_permissions[0] -ne "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION" -or
        @($lock.runtime_properties).Count -ne 0 -or
        @($lock.application_defaults).Count -ne 0) {
        throw "Broker client lock is not a closed signature-scoped client spec: $Path"
    }
    $capabilities = @($lock.capabilities | ForEach-Object { [string]$_ })
    $sortedCapabilities = @($capabilities | Sort-Object -Unique)
    if ($capabilities.Count -ne $sortedCapabilities.Count -or
        (@(Compare-Object $capabilities $sortedCapabilities -SyncWindow 0).Count -ne 0)) {
        throw "Broker client lock capabilities must be unique and ordinally sorted: $Path"
    }
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $Path).Path
        json = $json
        lock = $lock
        sha256 = Get-FileSha256Hex -Path $Path
    }
}

function New-SpecializedSpatialCameraPanelClientInput {
    param(
        [Parameter(Mandatory=$true)]$ClientInput,
        [Parameter(Mandatory=$true)][string]$PackageName
    )

    if ([string]$ClientInput.lock.client_id -cne "client.quest.spatial-camera-panel" -or
        [string]$ClientInput.lock.package_name -cne
            "io.github.mesmerprism.rustyquest.spatial_camera_panel") {
        throw "Spatial Camera Panel specialization requires the exact baseline client lock."
    }
    $lock = $ClientInput.lock | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $lock.package_name = $PackageName
    $json = $lock | ConvertTo-Json -Depth 20 -Compress
    return [pscustomobject]@{
        path = [string]$ClientInput.path
        json = $json
        lock = $lock
        sha256 = Get-TextSha256Hex -Text $json
        source_sha256 = [string]$ClientInput.sha256
        specialized = $true
    }
}

function Assert-UniqueAndroidAdmissionSubjects {
    param(
        [Parameter(Mandatory=$true)]$ClientLockInputs,
        [Parameter(Mandatory=$true)][string]$SigningFingerprint
    )

    $subjects = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($binding in @($ClientLockInputs)) {
        $packageName = [string]$binding.input.lock.package_name
        $subject = "$packageName`n$SigningFingerprint"
        if (-not $subjects.Add($subject)) {
            throw "Duplicate Android package+signer admission subject is ambiguous: $packageName / $SigningFingerprint"
        }
    }
}

function Get-RuntimeConfigDigest {
    param(
        [Parameter(Mandatory=$true)][string]$CargoManifest,
        [Parameter(Mandatory=$true)][string]$RuntimeConfigPath
    )

    $output = @(& cargo run --quiet --locked --manifest-path $CargoManifest `
        -p rusty-quest-broker-authority --bin runtime_config_digest -- `
        $RuntimeConfigPath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "runtime config digest failed: $($output -join [Environment]::NewLine)"
    }
    $digest = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -match '^[0-9a-f]{64}$' }) | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($digest)) {
        throw "runtime config digest did not emit one lowercase SHA-256"
    }
    $validatorPath = Join-Path (Split-Path -Parent $CargoManifest) `
        "target\debug\runtime_config_digest.exe"
    if (-not (Test-Path -LiteralPath $validatorPath -PathType Leaf)) {
        throw "runtime config digest validator executable is missing."
    }
    return [pscustomobject]@{
        digest = $digest
        validator_sha256 = Get-FileSha256Hex $validatorPath
    }
}

function Test-HasInputPath {
    param([string[]]$Path)
    return @(Expand-InputPaths -Path $Path).Count -gt 0
}

function Expand-InputPaths {
    param([string[]]$Path)
    return @($Path | ForEach-Object {
        ([string]$_).Split(",", [System.StringSplitOptions]::RemoveEmptyEntries) |
            ForEach-Object { $_.Trim().Trim("'").Trim('"') } |
            Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
    })
}

function Read-MediaLifecycleAuthority {
    param(
        [Parameter(Mandatory=$true)]$ClientLock,
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)]$MediaBindingByRelativePath,
        [string]$PackageNameOverride = ""
    )

    $clientId = [string]$ClientLock.client_id
    $mapping = @{
        "client.quest.native-renderer" = @{
            lifecycle = "fixtures\broker-clients\native-renderer.media-lifecycle.json"
            feature = "apps\native-renderer-android\morphospace\conformance-locks\broker-media-client.feature.lock.json"
        }
        "client.quest.spatial-camera-panel" = @{
            lifecycle = "fixtures\broker-clients\spatial-camera-panel.media-lifecycle.json"
            feature = "apps\spatial-camera-panel-android\legacy-workspaces\mixed-integration-v1\conformance-locks\broker-media-client.feature.lock.json"
        }
    }
    if (-not $mapping.ContainsKey($clientId)) {
        return $null
    }

    $lifecyclePath = Join-Path $RepoRoot $mapping[$clientId].lifecycle
    $featurePath = Join-Path $RepoRoot $mapping[$clientId].feature
    $lifecycleJson = [System.IO.File]::ReadAllText($lifecyclePath)
    $lifecycle = $lifecycleJson | ConvertFrom-Json
    if ([string]$lifecycle.client_id -cne $clientId) {
        throw "Broker media lifecycle client identity differs from its client lock."
    }
    if ([string]::IsNullOrWhiteSpace($PackageNameOverride)) {
        if ([string]$lifecycle.package_name -cne [string]$ClientLock.package_name) {
            throw "Broker media lifecycle package differs from its client lock."
        }
    } else {
        if ($clientId -cne "client.quest.spatial-camera-panel" -or
            [string]$ClientLock.package_name -cne $PackageNameOverride -or
            [string]$lifecycle.package_name -cne
                "io.github.mesmerprism.rustyquest.spatial_camera_panel") {
            throw "Spatial Camera Panel lifecycle specialization is not based on the exact baseline lock."
        }
        $lifecycle.package_name = $PackageNameOverride
        $lifecycleJson = $lifecycle | ConvertTo-Json -Depth 20 -Compress
    }
    $relativeMediaPath = ([string]$lifecycle.media_binding_path).Replace("/", "\")
    if (-not $MediaBindingByRelativePath.ContainsKey($relativeMediaPath)) {
        return $null
    }
    $mediaPath = [string]$MediaBindingByRelativePath[$relativeMediaPath]
    return [ordered]@{
        media_lifecycle_lock_json = $lifecycleJson
        media_lifecycle_lock_sha256 = Get-TextSha256Hex -Text $lifecycleJson
        app_feature_lock_json = [System.IO.File]::ReadAllText($featurePath)
        app_feature_lock_sha256 = Get-FileSha256Hex -Path $featurePath
        media_binding_json = [System.IO.File]::ReadAllText($mediaPath)
        media_binding_sha256 = Get-FileSha256Hex -Path $mediaPath
    }
}

$appRoot = Resolve-Path (Join-Path $PSScriptRoot "..\apps\manifold-broker-android")
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$targetRoot = Join-Path $repoRoot "target"
if ($PrepareOnly -and $ValidateRuntimeConfigOnly) {
    throw "-PrepareOnly and -ValidateRuntimeConfigOnly are mutually exclusive."
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $targetRoot "manifold-broker-android"
}
$keystoreWasExplicit = -not [string]::IsNullOrWhiteSpace($Keystore)
if ($RequireSharedMorphovisionSigner -and -not $keystoreWasExplicit) {
    throw "Shared Morphovision package builds require an explicit local -Keystore binding."
}
$signingAlias = if ($RequireSharedMorphovisionSigner) {
    $env:RUSTY_QUEST_MORPHOVISION_SIGNING_ALIAS
} else {
    "androiddebugkey"
}
$signingStorePassword = if ($RequireSharedMorphovisionSigner) {
    $env:RUSTY_QUEST_MORPHOVISION_SIGNING_STORE_PASSWORD
} else {
    "android"
}
$signingKeyPassword = if ($RequireSharedMorphovisionSigner) {
    $env:RUSTY_QUEST_MORPHOVISION_SIGNING_KEY_PASSWORD
} else {
    "android"
}
if ($RequireSharedMorphovisionSigner -and (
        [string]::IsNullOrWhiteSpace($signingAlias) -or
        [string]::IsNullOrWhiteSpace($signingStorePassword) -or
        [string]::IsNullOrWhiteSpace($signingKeyPassword))) {
    throw "Shared Morphovision signer alias and passwords require local environment bindings."
}

$resolvedOutParent = Split-Path -Parent $OutDir
New-Item -ItemType Directory -Force -Path $targetRoot, $resolvedOutParent | Out-Null
$resolvedTargetRoot = (Resolve-Path $targetRoot).Path.TrimEnd("\")
$resolvedOutFull = [System.IO.Path]::GetFullPath($OutDir).TrimEnd("\")
if (-not $resolvedOutFull.StartsWith($resolvedTargetRoot + "\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutDir must be under the repo target directory: $resolvedOutFull"
}
$retainedBuildInputs = @(
    $ProductSpecPath, $ProductLockPath, $ManifoldSourceRoot, $Keystore, $MediaStreamAarPath) +
    @(Expand-InputPaths -Path $MediaSessionBindingPath)
if (-not [string]::IsNullOrWhiteSpace($MediaStreamAarPath)) {
    if ($ExpectedMediaStreamAarSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        -not (Test-Path -LiteralPath $MediaStreamAarPath -PathType Leaf) -or
        (Get-FileSha256Hex -Path $MediaStreamAarPath) -cne $ExpectedMediaStreamAarSha256) {
        throw 'An explicit media AAR requires its exact SHA256 before preparing output.'
    }
} elseif ($ExpectedMediaStreamAarSha256) { throw 'An expected AAR hash requires its explicit input path.' }
Assert-ReusableOutputIsolated -OutputPath $resolvedOutFull `
    -InputPath $retainedBuildInputs
if (Test-Path $OutDir) {
    $resolvedOutDir = (Resolve-Path $OutDir).Path
    Remove-Item -LiteralPath $resolvedOutDir -Recurse -Force
}

$legacyProductId = "broker.legacy_camera_p2p.standalone"
$selectedManifoldRevision = $null
$selectedManifoldTree = $null
$selectedManifoldSourceClean = $null
$selectedManifoldSourceExplicit = $false
if ($LegacyCameraP2pCompatibility) {
    if (-not [string]::IsNullOrWhiteSpace($ProductSpecPath) -or
        -not [string]::IsNullOrWhiteSpace($ProductLockPath)) {
        throw "-LegacyCameraP2pCompatibility cannot be combined with explicit product input paths."
    }
    if ([string]::IsNullOrWhiteSpace($ManifoldSourceRoot)) {
        if (-not $PrepareOnly -or
            -not [string]::IsNullOrWhiteSpace($SpatialCameraPanelPackageName)) {
            throw "Legacy compatibility package builds require an explicit clean -ManifoldSourceRoot."
        }
        # The product-input-only compatibility check predates source-root admission and
        # remains inert: it emits no runtime config, Java, native code, or APK.
        $resolvedManifoldSourceRoot = (Resolve-Path -LiteralPath (
            Join-Path $repoRoot "..\rusty-manifold")).Path
    } elseif (-not (Test-Path -LiteralPath $ManifoldSourceRoot -PathType Container)) {
        throw "The explicit -ManifoldSourceRoot does not exist."
    } else {
        $resolvedManifoldSourceRoot = (Resolve-Path -LiteralPath $ManifoldSourceRoot).Path
    }
    $selectedManifoldRevision = (& git -C $resolvedManifoldSourceRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $selectedManifoldRevision -notmatch '^[0-9a-f]{40}$') {
        throw "Unable to resolve the explicit Manifold source revision."
    }
    $selectedManifoldTree = (& git -C $resolvedManifoldSourceRoot rev-parse "$selectedManifoldRevision`^{tree}").Trim()
    $manifoldDirty = @(& git -C $resolvedManifoldSourceRoot status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $selectedManifoldTree -notmatch '^[0-9a-f]{40}$' -or
        $manifoldDirty.Count -ne 0) {
        throw "Legacy compatibility packaging requires exact tracked-clean Manifold source."
    }
    if ($selectedManifoldRevision -cne $ApprovedManifoldRevision -or
        $selectedManifoldTree -cne $ApprovedManifoldTree) {
        throw "Legacy compatibility packaging requires the approved Manifold commit and tree."
    }
    $selectedManifoldSourceClean = $true
    $selectedManifoldSourceExplicit = -not [string]::IsNullOrWhiteSpace($ManifoldSourceRoot)
    $manifoldFixtures = Join-Path $resolvedManifoldSourceRoot "fixtures\broker-product"
    $ProductSpecPath = Join-Path $manifoldFixtures "legacy-camera-p2p-standalone.json"
    $ProductLockPath = Join-Path $manifoldFixtures "legacy-camera-p2p-standalone.lock.json"
    if ((Get-FileSha256Hex $ProductSpecPath) -cne $ApprovedLegacyProductSpecSha256 -or
        (Get-FileSha256Hex $ProductLockPath) -cne $ApprovedLegacyProductLockSha256) {
        throw "Legacy compatibility product spec/lock bytes differ from the approved inputs."
    }
} elseif ([string]::IsNullOrWhiteSpace($ProductSpecPath) -or
          [string]::IsNullOrWhiteSpace($ProductLockPath)) {
    throw "Explicit -ProductSpecPath and -ProductLockPath are required. Use -LegacyCameraP2pCompatibility only for the broad camera/P2P validation package."
}

$resolvedProductSpecPath = Resolve-ProductInputPath -Path $ProductSpecPath -Label "Product spec" -RepoRoot $repoRoot
$resolvedProductLockPath = Resolve-ProductInputPath -Path $ProductLockPath -Label "Product lock" -RepoRoot $repoRoot
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$isolatedCargo = $null
if ($LegacyCameraP2pCompatibility) {
    $isolatedCargo = New-IsolatedBrokerCargoMaterialization `
        -RepoRoot $repoRoot -ManifoldRoot $resolvedManifoldSourceRoot `
        -OutputRoot $OutDir
}
$productInputsDir = Join-Path $OutDir "product-inputs"
$productCargoManifest = if ($null -ne $isolatedCargo) {
    [string]$isolatedCargo.manifest
} else { Join-Path $repoRoot "Cargo.toml" }
Invoke-Checked "broker product preparation" "cargo" @(
    "run", "--quiet", "--locked", "--manifest-path", $productCargoManifest,
    "-p", "rusty-quest-broker-product",
    "--bin", "prepare_android_broker_product",
    "--",
    $resolvedProductSpecPath,
    $resolvedProductLockPath,
    $productInputsDir
)

$productInputsReceiptPath = Join-Path $productInputsDir "product-package-inputs.json"
$generatedManifestPath = Join-Path $productInputsDir "AndroidManifest.xml"
$generatedProductConfigPath = Join-Path $productInputsDir "generated\io\github\mesmerprism\rustymanifold\broker\GeneratedBrokerProductConfig.java"
$canonicalProductSpecPath = Join-Path $productInputsDir "product-spec.json"
$acceptedProductLockPath = Join-Path $productInputsDir "accepted-product-lock.json"
$commandRegistryPath = Join-Path $productInputsDir "command-registry.json"
$manifestProjectionPath = Join-Path $productInputsDir "manifest-projection.json"
foreach ($path in @($productInputsReceiptPath, $generatedManifestPath, $generatedProductConfigPath, $canonicalProductSpecPath, $acceptedProductLockPath, $commandRegistryPath, $manifestProjectionPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Broker product preparation omitted required output: $path"
    }
}
$productInputs = Get-Content -Raw -LiteralPath $productInputsReceiptPath | ConvertFrom-Json
if ($productInputs.'$schema' -ne "rusty.quest.broker.android_package_inputs.v1" -or
    $productInputs.runtime_mode -ne "standalone") {
    throw "Broker product preparation did not return a standalone accepted package receipt."
}
$preparedHashes = [ordered]@{
    product_spec_sha256 = Get-FileSha256Hex -Path $canonicalProductSpecPath
    manifold_lock_sha256 = Get-FileSha256Hex -Path $acceptedProductLockPath
    manifest_projection_sha256 = Get-FileSha256Hex -Path $manifestProjectionPath
    android_manifest_sha256 = Get-FileSha256Hex -Path $generatedManifestPath
    command_registry_sha256 = Get-FileSha256Hex -Path $commandRegistryPath
}
foreach ($field in $preparedHashes.Keys) {
    if ([string]$productInputs.$field -ne [string]$preparedHashes[$field]) {
        throw "Prepared broker product hash mismatch for $field."
    }
}
$sensitiveFeatures = @($productInputs.features | Where-Object { $_ -in @("camera_media", "direct_p2p", "ble_rendezvous") })
if ($LegacyCameraP2pCompatibility) {
    if ($productInputs.product_id -ne $legacyProductId) {
        throw "Legacy compatibility selection resolved the wrong product: $($productInputs.product_id)"
    }
} elseif ($productInputs.product_id -eq $legacyProductId -or $sensitiveFeatures.Count -gt 0) {
    throw "Camera, direct-P2P, and BLE broker packaging is restricted to an explicit compatibility product or a dedicated provider package."
}
if (-not [string]::IsNullOrWhiteSpace($SpatialCameraPanelPackageName)) {
    if (-not $LegacyCameraP2pCompatibility -or
        -not $EnableRemoteCameraDebugOperator -or
        -not $RequireSharedMorphovisionSigner) {
        throw "Spatial Camera Panel package specialization requires the legacy compatibility product, remote-camera debug operator, and shared signer gate."
    }
}

if ($PrepareOnly) {
    Write-Output $productInputsReceiptPath
    return
}

if ([string]::IsNullOrWhiteSpace($AndroidHome)) {
    throw "ANDROID_HOME or -AndroidHome is required."
}
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JAVA_HOME or -JavaHome is required."
}

$buildTools = Get-LatestDirectory -Parent (Join-Path $AndroidHome "build-tools") -Pattern "*"
$platformRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "platforms") -Pattern "android-*"
$platformJar = Join-Path $platformRoot "android.jar"
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
$keytool = Join-Path $JavaHome "bin\keytool.exe"
$ndkRoot = Get-LatestDirectory -Parent (Join-Path $AndroidHome "ndk") -Pattern "*"
$ndkBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$androidClang = Join-Path $ndkBin "aarch64-linux-android29-clang.cmd"
$androidAr = Join-Path $ndkBin "llvm-ar.exe"

foreach ($tool in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool, $androidClang, $androidAr)) {
    if (-not (Test-Path $tool)) {
        throw "Required tool not found: $tool"
    }
}

$classesDir = Join-Path $OutDir "classes"
$dexDir = Join-Path $OutDir "dex"
$classesJar = Join-Path $OutDir "classes.jar"
$mediaStreamAarClassesJar = ""
$apkUnsigned = Join-Path $OutDir "rusty-manifold-broker-unsigned.apk"
$apkUnaligned = Join-Path $OutDir "rusty-manifold-broker-unaligned.apk"
$apkAligned = Join-Path $OutDir "rusty-manifold-broker-aligned.apk"
$apkSigned = Join-Path $OutDir "rusty-manifold-broker.apk"
if ([string]::IsNullOrWhiteSpace($Keystore)) {
    $Keystore = Join-Path $targetRoot "rusty-manifold-broker-debug.keystore"
}

New-Item -ItemType Directory -Force -Path $classesDir, $dexDir | Out-Null
if ([string]::IsNullOrWhiteSpace($MediaStreamAarPath)) {
    if ($ExpectedMediaStreamAarSha256) { throw 'An expected AAR hash requires an explicit AAR path.' }
    $aarBuild = & (Join-Path $repoRoot 'crates/rusty-quest-media-stream-android/android/Build-MediaStreamAar.ps1') `
        -AndroidHome $AndroidHome -JavaHome $JavaHome -OutDir (Join-Path $OutDir 'media-stream-aar-build')
    $MediaStreamAarPath = $aarBuild.aar_path
    $ExpectedMediaStreamAarSha256 = $aarBuild.aar_sha256
}
. (Join-Path $repoRoot 'crates/rusty-quest-media-stream-android/tools/MediaStreamAarInputs.ps1')
$mediaStreamInput = Expand-ValidatedMediaStreamAar -Path $MediaStreamAarPath `
    -ExpectedSha256 $ExpectedMediaStreamAarSha256 -OutputRoot (Join-Path $OutDir 'media-stream-aar')
$mediaStreamAarClassesJar = $mediaStreamInput.classes_jar_path

if ($RequireSharedMorphovisionSigner -and -not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    throw "The explicit shared Morphovision signing keystore does not exist."
}
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-Checked "keytool" $keytool @(
        "-genkeypair",
        "-v",
        "-keystore", $Keystore,
        "-storepass", $signingStorePassword,
        "-keypass", $signingKeyPassword,
        "-alias", $signingAlias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-dname", "CN=Rusty Manifold Broker,O=Rusty Quest,C=US"
    )
}

$certificatePath = Join-Path $OutDir "broker-signing-certificate.der"
Invoke-Checked "keytool certificate export" $keytool @(
    "-exportcert",
    "-keystore", $Keystore,
    "-storepass", $signingStorePassword,
    "-alias", $signingAlias,
    "-file", $certificatePath
)
$certificateSha256 = Get-FileSha256Hex -Path $certificatePath
if ($RequireSharedMorphovisionSigner -and
    -not [string]::Equals(
        $certificateSha256,
        $SharedMorphovisionSignerSha256,
        [System.StringComparison]::Ordinal)) {
    throw "Explicit shared Morphovision signer fingerprint mismatch."
}
$acceptedProductLockJson = [System.IO.File]::ReadAllText($acceptedProductLockPath)
$acceptedProductSpecJson = [System.IO.File]::ReadAllText($canonicalProductSpecPath)
$acceptedProductLock = $acceptedProductLockJson | ConvertFrom-Json
$mediaSelected = @($acceptedProductLock.features | ForEach-Object { [string]$_ }) -contains "media_session"
$connectionHubSelected = @($acceptedProductLock.features | ForEach-Object { [string]$_ }) -contains "connection_hub"
if ($connectionHubSelected) {
    $manifoldSourceCandidate = if ([string]::IsNullOrWhiteSpace($ManifoldSourceRoot)) {
        Join-Path $repoRoot "..\rusty-manifold"
    } else {
        $ManifoldSourceRoot
    }
    if (-not (Test-Path -LiteralPath $manifoldSourceCandidate -PathType Container)) {
        throw "Connection Hub Manifold source root does not exist: $manifoldSourceCandidate"
    }
    $manifoldSourceRoot = (Resolve-Path -LiteralPath $manifoldSourceCandidate).Path
    $connectionHubNativeRoot = Join-Path $appRoot "connection-hub-native"
    $manifoldSourceLockPath = Join-Path $appRoot "native\manifold-source.lock.json"
    $manifoldSourceLock = Get-Content -Raw -LiteralPath $manifoldSourceLockPath | ConvertFrom-Json
    $manifoldRevision = (& git -C $manifoldSourceRoot rev-parse HEAD).Trim()
    $manifoldTree = (& git -C $manifoldSourceRoot rev-parse "$manifoldRevision`^{tree}").Trim()
    $manifoldDirty = @(& git -C $manifoldSourceRoot status --porcelain)
    if ($LASTEXITCODE -ne 0 -or $manifoldDirty.Count -ne 0 -or
        $manifoldRevision -ne [string]$manifoldSourceLock.revision -or
        $manifoldTree -ne [string]$manifoldSourceLock.tree) {
        throw "Connection Hub Manifold source does not match the exact clean native pin."
    }
    $connectionHubTypedParamsSchemaPath = Join-Path $manifoldSourceRoot "fixtures\connection-hub\typed-params-empty.schema.json"
    if (-not (Test-Path -LiteralPath $connectionHubTypedParamsSchemaPath -PathType Leaf) -or
        (Get-FileSha256Hex -Path $connectionHubTypedParamsSchemaPath) -ne "7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51") {
        throw "Connection Hub empty typed-parameter schema bytes do not match the sealed authority digest."
    }
    $connectionHubProtocolV1Path = Join-Path $appRoot "contracts\connection-hub-protocol-v1.json"
    $connectionHubProtocolV2Path = Join-Path $appRoot "contracts\connection-hub-protocol-v2.json"
    if ((Get-FileSha256Hex -Path $connectionHubProtocolV1Path) -ne
            "fa00d34511b2ee5576eebdd815e58ae032e37b10c209e41289cfd876c78c9c78") {
        throw "Connection Hub legacy v1 protocol vector bytes changed."
    }
    $connectionHubProtocolV2 = Get-Content -Raw -LiteralPath $connectionHubProtocolV2Path | ConvertFrom-Json
    if ([string]$connectionHubProtocolV2.'$schema' -ne "rusty.quest.connection_hub.protocol_vectors.v2" -or
        [string]$connectionHubProtocolV2.legacy_protocol_sha256 -ne
            "sha256:fa00d34511b2ee5576eebdd815e58ae032e37b10c209e41289cfd876c78c9c78") {
        throw "Connection Hub v2 protocol vector does not preserve the exact v1 compatibility bytes."
    }
}
if ($EnableConnectionHubDebugOperator -and -not $connectionHubSelected) {
    throw "-EnableConnectionHubDebugOperator is valid only for the connection_hub product."
}
$mediaSessionBindings = @()
$mediaSessionBindingReceipts = @()
$mediaBindingByRelativePath = @{}
if ($mediaSelected) {
    if (-not (Test-HasInputPath -Path $MediaSessionBindingPath)) {
        throw "Media-session products require -MediaSessionBindingPath with exact Manifold and Quest canonical bindings."
    }
    foreach ($bindingPath in @(Expand-InputPaths -Path $MediaSessionBindingPath)) {
        $resolvedMediaSessionBindingPath = Resolve-ProductInputPath `
            -Path $bindingPath `
            -Label "Media session binding" `
            -RepoRoot $repoRoot
        $mediaSessionBinding = Get-Content -Raw -LiteralPath $resolvedMediaSessionBindingPath | ConvertFrom-Json
        if ($null -eq $mediaSessionBinding.manifold -or
            $null -eq $mediaSessionBinding.quest -or
            [string]$mediaSessionBinding.manifold.'$schema' -ne "rusty.manifold.media.session_product_binding.v1" -or
            [string]$mediaSessionBinding.quest.'$schema' -ne "rusty.quest.media_stream_runtime_product_binding.v1") {
            throw "Media session binding is not an exact Manifold/Quest product binding: $resolvedMediaSessionBindingPath"
        }
        $mediaSessionBindings += $mediaSessionBinding
        $repoRootPrefix = ([string]$repoRoot).TrimEnd("\") + "\"
        if (-not $resolvedMediaSessionBindingPath.StartsWith($repoRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Media session binding must be under the repo root: $resolvedMediaSessionBindingPath"
        }
        $relative = $resolvedMediaSessionBindingPath.Substring($repoRootPrefix.Length).Replace("/", "\")
        $mediaBindingByRelativePath[$relative] = $resolvedMediaSessionBindingPath
        $mediaSessionBindingReceipts += [ordered]@{
            path = $relative.Replace("\", "/")
            sha256 = Get-FileSha256Hex -Path $resolvedMediaSessionBindingPath
            manifold_session_id = [string]$mediaSessionBinding.manifold.descriptor.session_id
            quest_runtime_spec_id = [string]$mediaSessionBinding.quest.spec.runtime_spec_id
        }
    }
} elseif (Test-HasInputPath -Path $MediaSessionBindingPath) {
    throw "-MediaSessionBindingPath cannot widen a product that did not select media_session."
}
if (-not [string]::IsNullOrWhiteSpace($SpatialCameraPanelPackageName)) {
    $requiredSupplierBindings = @(
        "fixtures\media-runtime-products\camera2-surface.binding.json",
        "fixtures\media-runtime-products\spatial-camera-panel-display.binding.json"
    )
    $actualSupplierBindings = @($mediaBindingByRelativePath.Keys | Sort-Object)
    if ($actualSupplierBindings.Count -ne 2 -or
        (($actualSupplierBindings -join "`n") -cne
            (($requiredSupplierBindings | Sort-Object) -join "`n"))) {
        throw "Spatial Camera Panel specialization requires exactly the camera2 and Spatial display media bindings."
    }
}
$initialLeases = @()
$spatialClientInput = Read-ValidatedClientLock -Path (
    Join-Path $repoRoot "fixtures\broker-clients\spatial-camera-panel.client.json")
if (-not [string]::IsNullOrWhiteSpace($SpatialCameraPanelPackageName)) {
    $spatialClientInput = New-SpecializedSpatialCameraPanelClientInput `
        -ClientInput $spatialClientInput `
        -PackageName $SpatialCameraPanelPackageName
}
$clientLockInputs = @(
    [ordered]@{
        grant_id = "grant.quest.authorized"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\admission-probe.client.json")
    },
    [ordered]@{
        grant_id = "grant.quest.native-renderer"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\native-renderer.client.json")
    },
    [ordered]@{
        grant_id = "grant.quest.spatial-camera-panel"
        input = $spatialClientInput
    }
)
if ($connectionHubSelected) {
    $clientLockInputs += [ordered]@{
        grant_id = "grant.quest.spatial-video-control-example"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\spatial-video-control-example.client.json")
    }
    $clientLockInputs += [ordered]@{
        grant_id = "grant.quest.connection-hub-sample"
        input = Read-ValidatedClientLock -Path (Join-Path $repoRoot "fixtures\broker-clients\connection-hub-sample.client.json")
    }
}
Assert-UniqueAndroidAdmissionSubjects `
    -ClientLockInputs $clientLockInputs `
    -SigningFingerprint "sha256:$certificateSha256"
$generatedGrants = @()
$packagedClientLocks = @()
$spatialMediaLifecycleAuthority = $null
foreach ($binding in $clientLockInputs) {
    $clientLock = $binding.input.lock
    $generatedCapabilities = @(Get-ExactClientGrantCapabilities -ClientLock $clientLock -ProductLock $acceptedProductLock)
    $generatedGrants += [ordered]@{
        grant_id = [string]$binding.grant_id
        client_lock_id = [string]$clientLock.feature_lock_id
        client_lock_fingerprint = "sha256:$([string]$binding.input.sha256)"
        identity = [ordered]@{
            client_id = [string]$clientLock.client_id
            platform_subject = [string]$clientLock.package_name
            signing_fingerprint = "sha256:$certificateSha256"
        }
        capabilities = $generatedCapabilities
        expires_at_ms = 4102444800000
        revoked = $false
    }
    $packagedClientLock = [ordered]@{
        grant_id = [string]$binding.grant_id
        client_lock_json = [string]$binding.input.json
        client_lock_sha256 = [string]$binding.input.sha256
    }
    if ($mediaSelected) {
        $mediaLifecycleAuthority = Read-MediaLifecycleAuthority `
            -ClientLock $clientLock `
            -RepoRoot $repoRoot `
            -MediaBindingByRelativePath $mediaBindingByRelativePath `
            -PackageNameOverride $(if (
                [string]$clientLock.client_id -ceq "client.quest.spatial-camera-panel") {
                    $SpatialCameraPanelPackageName
                } else { "" })
        if ($null -ne $mediaLifecycleAuthority) {
            $packagedClientLock["media_lifecycle_authority"] = $mediaLifecycleAuthority
            $mediaLifecycleLock = $mediaLifecycleAuthority.media_lifecycle_lock_json | ConvertFrom-Json
            $initialLeases += [ordered]@{
                lease_id = [string]$mediaLifecycleLock.broker_runtime_lease_id
                scope = "lease.media.session"
                holder_id = [string]$mediaLifecycleLock.client_id
                expires_at_ms = 4102444800000
            }
            if ([string]$clientLock.client_id -ceq "client.quest.spatial-camera-panel") {
                $spatialMediaLifecycleAuthority = $mediaLifecycleAuthority
            }
        }
    }
    $packagedClientLocks += $packagedClientLock
}
$admissionConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.admission_config.v1"
    snapshot = [ordered]@{
        '$schema' = "rusty.manifold.admission.snapshot.v2"
        authority_id = "authority.admission.quest"
        authority_revision = 1
        grants = $generatedGrants
        active_tokens = @()
        revoked_token_ids = @()
        consumed_request_ids = @()
        consumed_use_request_ids = @()
        reviewed_sweep_ids = @()
        audit_events = @()
        max_token_ttl_ms = 60000
    }
}
$connectionHubNativeConfig = $null
if ($connectionHubSelected) {
    $lockedPlaylistProviderInput = @($clientLockInputs | Where-Object {
        [string]$_.grant_id -eq "grant.quest.spatial-camera-panel"
    })[0].input
    $spatialProviderInput = @($clientLockInputs | Where-Object {
        [string]$_.grant_id -eq "grant.quest.spatial-video-control-example"
    })[0].input
    $sampleProviderInput = @($clientLockInputs | Where-Object {
        [string]$_.grant_id -eq "grant.quest.connection-hub-sample"
    })[0].input
    $lockedPlaylistHubContract = Read-ValidatedSpatialCameraPanelLockedPlaylistHubContract -RepoRoot $repoRoot
    $spatialVideoHubContract = Read-ValidatedSpatialVideoHubContract -RepoRoot $repoRoot
    $lockedPlaylistCommands = @($lockedPlaylistHubContract.commands)
    $connectionHubCommands = @($spatialVideoHubContract.commands)
    $connectionHubNativeConfig = [ordered]@{
        '$schema' = "rusty.quest.connection_hub.native_config.v1"
        product_id = [string]$productInputs.product_id
        product_lock_id = [string]$productInputs.manifold_lock_id
        product_lock_sha256 = "sha256:$([string]$productInputs.manifold_lock_sha256)"
        product_lock = $acceptedProductLock
        packaged_product_lock_json = $acceptedProductLockJson
        manifold_revision = [string]$manifoldSourceLock.revision
        manifold_tree = [string]$manifoldSourceLock.tree
        debug_test_hooks_enabled = [bool]$EnableConnectionHubDebugOperator
        policy = [ordered]@{
            '$schema' = "rusty.manifold.connection_hub.policy.v3"
            authority_id = "authority.connection-hub.quest"
            admission_authority_id = "authority.admission.quest"
            broker_product_lock_id = [string]$productInputs.manifold_lock_id
            broker_product_lock_fingerprint = [string]$productInputs.manifold_lock_fingerprint
            broker_product_lock_sha256 = "sha256:$([string]$productInputs.manifold_lock_sha256)"
            trusted_operator_evidence_ids = @("evidence.operator.wearer-action")
            allowed_controller_capabilities = @(
                "capability.connection_hub_sample.toggle",
                "capability.spatial_camera_panel.locked_playlist.next",
                "capability.spatial_camera_panel.locked_playlist.pause",
                "capability.spatial_camera_panel.locked_playlist.previous",
                "capability.spatial_camera_panel.locked_playlist.resume",
                "capability.spatial_video_control.pause",
                "capability.spatial_video_control.play",
                "capability.spatial_video_control.select_next",
                "capability.spatial_video_control.select_previous"
            )
            provider_grants = @(
                [ordered]@{
                    provider_id = "provider.quest.connection-hub-sample"
                    client_id = [string]$sampleProviderInput.lock.client_id
                    client_lock_id = [string]$sampleProviderInput.lock.feature_lock_id
                    client_lock_sha256 = "sha256:$([string]$sampleProviderInput.sha256)"
                    surface_contract_sha256 = "sha256:48019a4a7a00c9ee6d694927727f54093b6946c1f894b99214c5aeb5629472c4"
                    allowed_commands = @(
                        [ordered]@{
                            command_id = "command.connection_hub_sample.toggle"
                            typed_params_schema_id = "rusty.manifold.connection_hub.typed_params.empty.v1"
                            typed_params_schema_sha256 = "sha256:7eedc1ccca80b83dbd121d1e4bae4f6a6c9c1561e1a08d6d5919c668d5406a51"
                            required_controller_capability = "capability.connection_hub_sample.toggle"
                        }
                    )
                },
                [ordered]@{
                    provider_id = "provider.quest.spatial-camera-panel-locked-playlist"
                    client_id = [string]$lockedPlaylistProviderInput.lock.client_id
                    client_lock_id = [string]$lockedPlaylistProviderInput.lock.feature_lock_id
                    client_lock_sha256 = "sha256:$([string]$lockedPlaylistProviderInput.sha256)"
                    surface_contract_sha256 = [string]$lockedPlaylistHubContract.runtime_sha256
                    allowed_commands = $lockedPlaylistCommands
                },
                [ordered]@{
                    provider_id = "provider.quest.spatial-video-control-example"
                    client_id = [string]$spatialProviderInput.lock.client_id
                    client_lock_id = [string]$spatialProviderInput.lock.feature_lock_id
                    client_lock_sha256 = "sha256:$([string]$spatialProviderInput.sha256)"
                    surface_contract_sha256 = [string]$spatialVideoHubContract.canonical_sha256
                    allowed_commands = $connectionHubCommands
                }
            )
            max_controller_ttl_ms = if ($EnableConnectionHubDebugOperator) { 60000 } else { 31622400000 }
            max_session_ttl_ms = if ($EnableConnectionHubDebugOperator) { 15000 } else { 2592000000 }
            max_surface_lease_ttl_ms = if ($EnableConnectionHubDebugOperator) { 10000 } else { 86400000 }
            authenticated_activity_controller_ttl_ms = if ($EnableConnectionHubDebugOperator) { 60000 } else { 31622400000 }
            authenticated_activity_session_ttl_ms = if ($EnableConnectionHubDebugOperator) { 15000 } else { 2592000000 }
        }
    }
}
$runtimeConfig = [ordered]@{
    '$schema' = "rusty.quest.broker.runtime_config.v2"
    bridge_kind = "standalone_process_jni"
    adapter_config = [ordered]@{
        '$schema' = "rusty.manifold.broker.adapter_config.v2"
        adapter_id = "adapter.quest.manifold_broker.standalone"
        mode = "standalone"
        product_lock_id = [string]$productInputs.manifold_lock_id
        product_lock_fingerprint = [string]$productInputs.manifold_lock_fingerprint
        product_lock_sha256 = "sha256:$([string]$productInputs.manifold_lock_sha256)"
        authority_host_id = "host.quest.manifold_broker"
        authority_owner_id = "module.runtime.host"
    }
    product_lock = $acceptedProductLock
    packaged_authority = [ordered]@{
        product_spec_json = $acceptedProductSpecJson
        product_spec_sha256 = Get-FileSha256Hex -Path $canonicalProductSpecPath
        product_lock_json = $acceptedProductLockJson
        product_lock_sha256 = Get-FileSha256Hex -Path $acceptedProductLockPath
        client_locks = $packagedClientLocks
    }
    initial_leases = $initialLeases
    admission = $admissionConfig
}
if ($mediaSessionBindings.Count -eq 1) {
    $runtimeConfig["media_session"] = $mediaSessionBindings[0]
} elseif ($mediaSessionBindings.Count -gt 1) {
    $runtimeConfig["media_sessions"] = $mediaSessionBindings
}
$runtimeConfigPath = Join-Path $OutDir "broker-runtime-config.json"
[System.IO.File]::WriteAllText(
    $runtimeConfigPath,
    ($runtimeConfig | ConvertTo-Json -Depth 30),
    (New-Object System.Text.UTF8Encoding($false)))
$runtimeCargoManifest = if ($null -ne $isolatedCargo) {
    [string]$isolatedCargo.manifest
} else { Join-Path $repoRoot "Cargo.toml" }
$runtimeConfigValidation = Get-RuntimeConfigDigest `
    -CargoManifest $runtimeCargoManifest -RuntimeConfigPath $runtimeConfigPath
$runtimeConfigSha256 = [string]$runtimeConfigValidation.digest
if ($ValidateRuntimeConfigOnly) {
    Write-Output $runtimeConfigPath
    return
}
$runtimeConfigJson = $runtimeConfig | ConvertTo-Json -Depth 30 -Compress
$runtimeConfigJava = $runtimeConfigJson.Replace('\', '\\').Replace('"', '\"')
$generatedPackageDir = Join-Path $OutDir "generated\io\github\mesmerprism\rustymanifold\broker"
New-Item -ItemType Directory -Force -Path $generatedPackageDir | Out-Null
$generatedRuntimeConfigPath = Join-Path $generatedPackageDir "GeneratedBrokerRuntimeConfig.java"
$generatedRuntimeConfigSource = @"
package io.github.mesmerprism.rustymanifold.broker;

final class GeneratedBrokerRuntimeConfig {
    static final String JSON = "$runtimeConfigJava";
    static final String SHA256 = "$runtimeConfigSha256";
    private GeneratedBrokerRuntimeConfig() {}
}
"@
[System.IO.File]::WriteAllText(
    $generatedRuntimeConfigPath,
    $generatedRuntimeConfigSource,
    (New-Object System.Text.UTF8Encoding($false)))
$generatedConnectionHubConfigPath = $null
if ($connectionHubSelected) {
    $connectionHubConfigJson = $connectionHubNativeConfig | ConvertTo-Json -Depth 20 -Compress
    $connectionHubConfigJava = $connectionHubConfigJson.Replace('\', '\\').Replace('"', '\"')
    $generatedConnectionHubConfigPath = Join-Path $generatedPackageDir "GeneratedConnectionHubConfig.java"
    $generatedConnectionHubConfigSource = @"
package io.github.mesmerprism.rustymanifold.broker;

final class GeneratedConnectionHubConfig {
    static final String JSON = "$connectionHubConfigJava";
    static final boolean DEBUG_OPERATOR_ENABLED = $($EnableConnectionHubDebugOperator.ToString().ToLowerInvariant());
    private GeneratedConnectionHubConfig() {}
}
"@
    [System.IO.File]::WriteAllText(
        $generatedConnectionHubConfigPath,
        $generatedConnectionHubConfigSource,
        (New-Object System.Text.UTF8Encoding($false)))
}
$packagingManifestPath = $generatedManifestPath
if ($connectionHubSelected) {
    $manifestText = [System.IO.File]::ReadAllText($generatedManifestPath)
    $closingApplication = "    </application>"
    if ([regex]::Matches($manifestText, [regex]::Escape($closingApplication)).Count -ne 1) {
        throw "Connection Hub Android manifest has an unexpected application boundary."
    }
    $operatorProvider = @"
        <provider
            android:name=".ConnectionHubOperatorProvider"
            android:authorities="io.github.mesmerprism.rustymanifold.broker.connection-hub-operator"
            android:exported="true"
            android:permission="android.permission.DUMP" />
        <provider
            android:name=".ConnectionHubWearerControlProvider"
            android:authorities="io.github.mesmerprism.rustymanifold.broker.connection-hub-wearer-control"
            android:exported="true"
            android:permission="io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION" />
"@
    $operatorManifestDir = Join-Path $OutDir "operator"
    New-Item -ItemType Directory -Force -Path $operatorManifestDir | Out-Null
    $packagingManifestPath = Join-Path $operatorManifestDir "AndroidManifest.xml"
    $manifestText = $manifestText.Replace(
        $closingApplication,
        "$operatorProvider`n$closingApplication")
    [System.IO.File]::WriteAllText(
        $packagingManifestPath,
        $manifestText,
        (New-Object System.Text.UTF8Encoding($false)))
}
if ($connectionHubSelected) {
    $manifestText = [System.IO.File]::ReadAllText($packagingManifestPath)
    $publishedHubService = @"
        <service
            android:name=".ConnectionHubStartService"
            android:exported="true"
            android:permission="android.permission.DUMP"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
"@
    if ([regex]::Matches($manifestText, [regex]::Escape($publishedHubService)).Count -ne 1) {
        throw "Connection Hub build requires the exact DUMP-gated published foreground service."
    }
}
if ($EnableConnectionHubDebugOperator) {
    $manifestText = [System.IO.File]::ReadAllText($packagingManifestPath)
    $closingApplication = "    </application>"
    if ([regex]::Matches($manifestText, [regex]::Escape($closingApplication)).Count -ne 1) {
        throw "Connection Hub Android manifest has an unexpected application boundary."
    }
    $debugProvider = @"
        <provider
            android:name=".ConnectionHubDebugControlProvider"
            android:authorities="io.github.mesmerprism.rustymanifold.broker.debug-connection-hub-control"
            android:exported="true"
            android:permission="android.permission.DUMP" />
"@
    $debugManifestDir = Join-Path $OutDir "debug-operator"
    New-Item -ItemType Directory -Force -Path $debugManifestDir | Out-Null
    $packagingManifestPath = Join-Path $debugManifestDir "AndroidManifest.xml"
    $manifestText = $manifestText.Replace(
        $closingApplication,
        "$debugProvider`n$closingApplication")
    [System.IO.File]::WriteAllText(
        $packagingManifestPath,
        $manifestText,
        (New-Object System.Text.UTF8Encoding($false)))
}
if ($EnableRemoteCameraDebugOperator) {
    $manifestText = [System.IO.File]::ReadAllText($packagingManifestPath)
    $closingApplication = "    </application>"
    if ([regex]::Matches($manifestText, [regex]::Escape($closingApplication)).Count -ne 1) {
        throw "Remote Camera Android manifest has an unexpected application boundary."
    }
    $remoteCameraDebugProvider = @"
        <provider
            android:name=".RemoteCameraDebugControlProvider"
            android:authorities="io.github.mesmerprism.rustymanifold.broker.debug-remote-camera-control"
            android:exported="true"
            android:permission="android.permission.DUMP" />
"@
    $remoteCameraDebugManifestDir = Join-Path $OutDir "remote-camera-debug-operator"
    New-Item -ItemType Directory -Force -Path $remoteCameraDebugManifestDir | Out-Null
    $packagingManifestPath = Join-Path $remoteCameraDebugManifestDir "AndroidManifest.xml"
    $manifestText = $manifestText.Replace(
        $closingApplication,
        "$remoteCameraDebugProvider`n$closingApplication")
    [System.IO.File]::WriteAllText(
        $packagingManifestPath,
        $manifestText,
        (New-Object System.Text.UTF8Encoding($false)))
}
$sourceFiles = Get-ChildItem -Path (Join-Path $appRoot "src\main\java") -Recurse -Filter *.java |
    Where-Object {
        if ($_.Name -eq "ConnectionHubDebugControlProvider.java") {
            return [bool]$EnableConnectionHubDebugOperator
        }
        if ($_.Name -eq "RemoteCameraDebugControlProvider.java") {
            return [bool]$EnableRemoteCameraDebugOperator
        }
        $connectionHubSelected -or
        ($_.Name -notlike "ConnectionHub*.java" -and
         $_.Name -notlike "AndroidConnectionHub*.java" -and
         $_.Name -notlike "ManifoldConnectionHub*.java" -and
         $_.Name -notlike "Hub*.java" -and
         $_.Name -notlike "UnavailableManifoldConnectionHub*.java")
    } |
    ForEach-Object { $_.FullName }
$sharedBrokerTransportJavaRoot = Join-Path $repoRoot "crates\rusty-quest-broker-transport\android"
$sharedBrokerTransportJava = @(Get-ChildItem -LiteralPath $sharedBrokerTransportJavaRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
if ($sharedBrokerTransportJava.Count -lt 1) {
    throw "Shared broker transport Android sources are incomplete: $sharedBrokerTransportJavaRoot"
}
$sharedBrokerAdmissionJavaRoot = Join-Path $repoRoot "crates\rusty-quest-broker-admission\android"
$sharedBrokerAdmissionJava = @(Get-ChildItem -LiteralPath $sharedBrokerAdmissionJavaRoot -Recurse -Filter *.java |
    ForEach-Object { $_.FullName })
if ($sharedBrokerAdmissionJava.Count -lt 1) {
    throw "Shared broker admission Android sources are incomplete: $sharedBrokerAdmissionJavaRoot"
}
$sourceFiles = @($sourceFiles) + @($sharedBrokerTransportJava) + @($sharedBrokerAdmissionJava)
$sourceFiles = @($sourceFiles) + @($generatedProductConfigPath, $generatedRuntimeConfigPath)
if ($connectionHubSelected) {
    $sourceFiles += $generatedConnectionHubConfigPath
}
if ($sourceFiles.Count -eq 0) {
    throw "No Java sources found under $appRoot"
}
$sourceList = Join-Path $OutDir "sources.rsp"
$sourceFiles | Set-Content -Encoding ASCII -Path $sourceList

$javacClasspath = $platformJar
if (-not [string]::IsNullOrWhiteSpace($mediaStreamAarClassesJar)) { $javacClasspath += [IO.Path]::PathSeparator + $mediaStreamAarClassesJar }
$javacArguments = @("--release", "8", "-encoding", "UTF-8", "-classpath", $javacClasspath, "-d", $classesDir)
$javacArguments += "@$sourceList"
Invoke-Checked "javac" $javac $javacArguments
Invoke-Checked "jar class pack" $jar @("cf", $classesJar, "-C", $classesDir, ".")
$d8Inputs = @($classesJar)
if (-not [string]::IsNullOrWhiteSpace($mediaStreamAarClassesJar)) { $d8Inputs += $mediaStreamAarClassesJar }
Invoke-Checked "d8" $d8 (@("--lib", $platformJar, "--output", $dexDir) + $d8Inputs)

$previousLinker = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$previousCc = $env:CC_aarch64_linux_android
$previousAr = $env:AR_aarch64_linux_android
# MSVC also links host build scripts during an Android build. Keep their output
# paths short even when the retained evidence capsule is deeply nested.
$nativeCargoTargetRoot = Join-Path $repoRoot ('target/mbn-' + [guid]::NewGuid().ToString('N').Substring(0,12))
if (Test-Path -LiteralPath $nativeCargoTargetRoot) { throw 'Native Cargo output must be a new capsule.' }
$nativeTargetAncestor = $nativeCargoTargetRoot
while ($nativeTargetAncestor) {
    if ((Test-Path -LiteralPath $nativeTargetAncestor) -and
        ((Get-Item -LiteralPath $nativeTargetAncestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Native Cargo output cannot traverse a reparse point.'
    }
    $nativeTargetAncestor = [IO.Path]::GetDirectoryName($nativeTargetAncestor)
}
[void][IO.Directory]::CreateDirectory($nativeCargoTargetRoot)
try {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidClang
    $env:CC_aarch64_linux_android = $androidClang
    $env:AR_aarch64_linux_android = $androidAr
    if ($connectionHubSelected) {
        $connectionHubNativeTarget = $nativeCargoTargetRoot
        $generatedNativeManifest = if ($null -ne $isolatedCargo) {
            [string]$isolatedCargo.connection_hub_native_manifest
        } else { Join-Path $connectionHubNativeRoot "Cargo.toml" }
        Invoke-Checked "isolated Connection Hub native" "cargo" @(
            "build",
            "--locked",
            "--manifest-path", $generatedNativeManifest,
            "--target-dir", $connectionHubNativeTarget,
            "--target", "aarch64-linux-android"
        )
    } else {
        $standaloneNativeTarget = $nativeCargoTargetRoot
        $standaloneNativeManifest = if ($null -ne $isolatedCargo) {
            [string]$isolatedCargo.manifest
        } else { Join-Path $repoRoot "Cargo.toml" }
        Invoke-Checked "standalone broker admission native" "cargo" @(
            "build", "--locked", "--manifest-path", $standaloneNativeManifest,
            "--target-dir", $standaloneNativeTarget,
            "--target", "aarch64-linux-android",
            "-p", "rusty-quest-manifold-broker-authority-native"
        )
    }
} finally {
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $previousLinker
    $env:CC_aarch64_linux_android = $previousCc
    $env:AR_aarch64_linux_android = $previousAr
}
$nativeSoSource = if ($connectionHubSelected) {
    Join-Path $connectionHubNativeTarget "aarch64-linux-android\debug\librusty_quest_manifold_broker_authority.so"
} else {
    Join-Path $standaloneNativeTarget "aarch64-linux-android\debug\librusty_quest_manifold_broker_authority.so"
}
if (-not (Test-Path -LiteralPath $nativeSoSource -PathType Leaf)) {
    throw "Standalone broker authority native library not found: $nativeSoSource"
}
$nativeLibRoot = Join-Path $OutDir "native-package"
$nativeAbiDir = Join-Path $nativeLibRoot "lib\arm64-v8a"
New-Item -ItemType Directory -Force -Path $nativeAbiDir | Out-Null
$nativeSoPackaged = Join-Path $nativeAbiDir "librusty_quest_manifold_broker_authority.so"
Copy-Item -LiteralPath $nativeSoSource -Destination $nativeSoPackaged
Invoke-Checked "aapt2 link" $aapt2 @(
    "link",
    "-o", $apkUnsigned,
    "--manifest", $packagingManifestPath,
    "-I", $platformJar,
    "--min-sdk-version", "29",
    "--target-sdk-version", "34",
    "--version-code", [string]$VersionCode,
    "--version-name", $VersionName
)

Copy-Item $apkUnsigned $apkUnaligned
Invoke-Checked "jar dex update" $jar @("uf", $apkUnaligned, "-C", $dexDir, "classes.dex")
Invoke-Checked "jar native library update" $jar @("uf", $apkUnaligned, "-C", $nativeLibRoot, "lib")
$productPackageRoot = Join-Path $OutDir "product-package"
$productAssetDir = Join-Path $productPackageRoot "assets\manifold"
New-Item -ItemType Directory -Force -Path $productAssetDir | Out-Null
Copy-Item -LiteralPath $acceptedProductLockPath -Destination (Join-Path $productAssetDir "accepted-product-lock.json")
Copy-Item -LiteralPath $commandRegistryPath -Destination (Join-Path $productAssetDir "command-registry.json")
Copy-Item -LiteralPath $manifestProjectionPath -Destination (Join-Path $productAssetDir "manifest-projection.json")
Copy-Item -LiteralPath $runtimeConfigPath -Destination (Join-Path $productAssetDir "runtime-config.json")
$connectionHubPackagedAssets = @()
if ($connectionHubSelected) {
    Copy-Item -LiteralPath $connectionHubTypedParamsSchemaPath -Destination (Join-Path $productAssetDir "connection-hub-typed-params-empty.schema.json")
    Copy-Item -LiteralPath $connectionHubProtocolV1Path -Destination (Join-Path $productAssetDir "connection-hub-protocol-v1.json")
    Copy-Item -LiteralPath $connectionHubProtocolV2Path -Destination (Join-Path $productAssetDir "connection-hub-protocol-v2.json")
    $connectionHubAssetSource = Join-Path $appRoot "src\main\assets\connection-hub"
    $connectionHubAssetTarget = Join-Path $productPackageRoot "assets\connection-hub"
    if (-not (Test-Path -LiteralPath $connectionHubAssetSource -PathType Container)) {
        throw "Connection Hub fixed browser assets are missing: $connectionHubAssetSource"
    }
    New-Item -ItemType Directory -Force -Path $connectionHubAssetTarget | Out-Null
    Copy-Item -Path (Join-Path $connectionHubAssetSource "*") -Destination $connectionHubAssetTarget -Recurse
    $connectionHubPackagedAssets = @(
        "assets/manifold/connection-hub-typed-params-empty.schema.json",
        "assets/manifold/connection-hub-protocol-v1.json",
        "assets/manifold/connection-hub-protocol-v2.json",
        "assets/connection-hub/index.html",
        "assets/connection-hub/protocol.js",
        "assets/connection-hub/app.js",
        "assets/connection-hub/styles.css"
    )
}
Invoke-Checked "jar product assets update" $jar @("uf", $apkUnaligned, "-C", $productPackageRoot, "assets")
Invoke-Checked "zipalign" $zipalign @("-f", "4", $apkUnaligned, $apkAligned)

Invoke-Checked "apksigner" $apksigner @(
    "sign",
    "--ks", $Keystore,
    "--ks-pass", "pass:$signingStorePassword",
    "--key-pass", "pass:$signingKeyPassword",
    "--ks-key-alias", $signingAlias,
    "--out", $apkSigned,
    $apkAligned
)
Invoke-Checked "apksigner verification" $apksigner @("verify", "--verbose", $apkSigned)

$sha256 = Get-FileSha256Hex -Path $apkSigned
$manifestProjection = Get-Content -Raw -LiteralPath $manifestProjectionPath | ConvertFrom-Json
$androidPermissions = @($manifestProjection.permissions | ForEach-Object { [string]$_.name } | Sort-Object -Unique)
[xml]$generatedAndroidManifest = [System.IO.File]::ReadAllText($packagingManifestPath)
$androidNamespace = "http://schemas.android.com/apk/res/android"
$androidComponents = @()
foreach ($kind in @("activity", "service", "provider")) {
    foreach ($node in @($generatedAndroidManifest.SelectNodes("/manifest/application/$kind"))) {
        $component = [ordered]@{
            kind = $kind
            name = [string]$node.GetAttribute("name", $androidNamespace)
            exported = [System.Convert]::ToBoolean($node.GetAttribute("exported", $androidNamespace))
        }
        $permission = [string]$node.GetAttribute("permission", $androidNamespace)
        if (-not [string]::IsNullOrWhiteSpace($permission)) {
            $component["permission"] = $permission
        }
        $foregroundServiceType = [string]$node.GetAttribute("foregroundServiceType", $androidNamespace)
        if (-not [string]::IsNullOrWhiteSpace($foregroundServiceType)) {
            $component["foreground_service_type"] = $foregroundServiceType
        }
        $androidComponents += $component
    }
}
$manifest = [ordered]@{
    '$schema' = "rusty.quest.manifold_broker_android.build_manifest.v2"
    package_name = "io.github.mesmerprism.rustymanifold.broker"
    version_code = $VersionCode
    version_name = $VersionName
    activity = if ($connectionHubSelected) { "io.github.mesmerprism.rustymanifold.broker/.ConnectionHubStartActivity" } else { "io.github.mesmerprism.rustymanifold.broker/.BrokerStartActivity" }
    authority = "rusty.manifold"
    endpoint_path = "/manifold/v1/events"
    broker_port = 8765
    admission_permission = "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION"
    admission_service = if ($connectionHubSelected) { "io.github.mesmerprism.rustymanifold.broker/.ConnectionHubAdmissionService" } else { "io.github.mesmerprism.rustymanifold.broker/.ManifoldAdmissionService" }
    admission_decision_owner = "rusty.manifold.admission"
    admission_client_signing_certificate_sha256 = $certificateSha256
    admission_native_library_sha256 = Get-FileSha256Hex -Path $nativeSoPackaged
    native_cargo_target = $nativeCargoTargetRoot
    manifold_product_id = [string]$productInputs.product_id
    manifold_product_lock_id = [string]$productInputs.manifold_lock_id
    manifold_product_lock_fingerprint = [string]$productInputs.manifold_lock_fingerprint
    manifold_product_lock_sha256 = [string]$productInputs.manifold_lock_sha256
    manifold_product_spec_sha256 = [string]$productInputs.product_spec_sha256
    manifold_product_features = @($productInputs.features)
    manifold_product_modules = @($acceptedProductLock.module_ids)
    manifold_product_permissions = @($acceptedProductLock.permissions)
    manifold_source_revision = $selectedManifoldRevision
    manifold_source_tree = $selectedManifoldTree
    manifold_source_tracked_clean = $selectedManifoldSourceClean
    manifold_source_root_explicit = [bool]$selectedManifoldSourceExplicit
    manifold_source_approved = [bool]($LegacyCameraP2pCompatibility -and
        $selectedManifoldRevision -ceq $ApprovedManifoldRevision -and
        $selectedManifoldTree -ceq $ApprovedManifoldTree)
    manifold_cargo_resolution_verified = [bool]($null -ne $isolatedCargo)
    manifold_cargo_dependency_packages = $(if ($null -ne $isolatedCargo) {
        @($isolatedCargo.manifold_packages)
    } else { @() })
    media_session_bindings = @($mediaSessionBindingReceipts)
    media_stream_aar_sha256 = $mediaStreamInput.aar_sha256
    media_stream_classes_jar_sha256 = $mediaStreamInput.classes_jar_sha256
    media_stream_aar_native_libraries = @()
    spatial_camera_panel_package_name = [string]$spatialClientInput.lock.package_name
    spatial_camera_panel_package_specialized = -not [string]::IsNullOrWhiteSpace(
        $SpatialCameraPanelPackageName)
    spatial_camera_panel_client_lock_sha256 = [string]$spatialClientInput.sha256
    spatial_camera_panel_grant_sha256 = Get-TextSha256Hex -Text (
        @($generatedGrants | Where-Object {
            [string]$_.grant_id -ceq "grant.quest.spatial-camera-panel"
        })[0] | ConvertTo-Json -Depth 20 -Compress)
    spatial_camera_panel_media_lifecycle_sha256 = $(if (
        $null -ne $spatialMediaLifecycleAuthority) {
            [string]$spatialMediaLifecycleAuthority.media_lifecycle_lock_sha256
        } else { $null })
    android_permissions = $androidPermissions
    android_components = $androidComponents
    generated_android_manifest_sha256 = [string]$productInputs.android_manifest_sha256
    packaging_android_manifest_sha256 = Get-FileSha256Hex -Path $packagingManifestPath
    connection_hub_debug_operator = [bool]$EnableConnectionHubDebugOperator
    remote_camera_debug_operator = [bool]$EnableRemoteCameraDebugOperator
    shared_morphovision_signer_required = [bool]$RequireSharedMorphovisionSigner
    expected_shared_morphovision_signer_sha256 = $(if ($RequireSharedMorphovisionSigner) { $SharedMorphovisionSignerSha256 } else { $null })
    artifact_signer_sha256 = $certificateSha256
    generated_manifest_projection_sha256 = [string]$productInputs.manifest_projection_sha256
    generated_command_registry_sha256 = [string]$productInputs.command_registry_sha256
    broker_runtime_config_sha256 = Get-FileSha256Hex -Path $runtimeConfigPath
    broker_runtime_config_canonical_sha256 = $runtimeConfigSha256
    broker_runtime_config_validator_sha256 = [string]$runtimeConfigValidation.validator_sha256
    packaged_client_lock_sha256 = @($clientLockInputs | ForEach-Object { [string]$_.input.sha256 })
    broker_runtime_provider_epoch_policy = "fresh-process-entropy_same-process-rebind-continuity"
    product_inputs_receipt_sha256 = Get-FileSha256Hex -Path $productInputsReceiptPath
    generated_android_manifest = $generatedManifestPath
    packaged_product_lock_asset = "assets/manifold/accepted-product-lock.json"
    packaged_command_registry_asset = "assets/manifold/command-registry.json"
    packaged_manifest_projection_asset = "assets/manifold/manifest-projection.json"
    packaged_runtime_config_asset = "assets/manifold/runtime-config.json"
    connection_hub_typed_params_schema_asset = if ($connectionHubSelected) { "assets/manifold/connection-hub-typed-params-empty.schema.json" } else { $null }
    connection_hub_typed_params_schema_sha256 = if ($connectionHubSelected) { Get-FileSha256Hex -Path $connectionHubTypedParamsSchemaPath } else { $null }
    connection_hub_protocol_v1_sha256 = if ($connectionHubSelected) { Get-FileSha256Hex -Path $connectionHubProtocolV1Path } else { $null }
    connection_hub_protocol_v2_sha256 = if ($connectionHubSelected) { Get-FileSha256Hex -Path $connectionHubProtocolV2Path } else { $null }
    connection_hub_browser_assets = $connectionHubPackagedAssets
    legacy_camera_p2p_compatibility = [bool]$LegacyCameraP2pCompatibility
    compatibility_diagnostic = [ordered]@{
        schema = "rusty.quest.manifold_broker.compatibility_diagnostic_build.v1"
        static_gate_required = [bool]$EnableRemoteCameraDebugOperator
        rollback_evidence_required = -not [string]::IsNullOrWhiteSpace(
            $SpatialCameraPanelPackageName)
        remote_camera_debug_provider_authority = $(if ($EnableRemoteCameraDebugOperator) {
            "io.github.mesmerprism.rustymanifold.broker.debug-remote-camera-control"
        } else { $null })
        install_policy = "inspected-same-version-replace-with-installed-byte-readback"
        restore_policy = "same-version-same-signer-rollback-reinstall"
        uninstall_permitted = $false
        data_clear_permitted = $false
        global_log_clear_permitted = $false
        blanket_force_stop_permitted = $false
        downgrade_permitted = $false
        adb_lifecycle_permitted = $false
    }
    apk_path = $apkSigned
    apk_sha256 = $sha256
    apk_size = (Get-Item -LiteralPath $apkSigned).Length
    validation = [ordered]@{
        product_spec_lock_validated = $true
        generated_manifest_validated = $true
        runtime_config_validated = $true
        apk_signature_verified = $true
        packaged_assets_validated = $true
    }
    live_stream_events_synthesized = $false
}
$manifestPath = Join-Path $OutDir "build-manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $manifestPath

Write-Output $apkSigned
