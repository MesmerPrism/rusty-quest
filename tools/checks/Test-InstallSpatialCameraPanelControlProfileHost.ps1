param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$installerPath = Join-Path $repoRootPath "tools\Install-SpatialCameraPanelControlProfile.ps1"
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("rusty-quest-control-profile-host-test-" + [guid]::NewGuid().ToString("N"))

function Assert-True {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,

        [Parameter(Mandatory)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $statePath = Join-Path $tempRoot "fake-adb-state.json"
    $fakeAdbPath = Join-Path $tempRoot "fake-adb.ps1"
    $profilePath = Join-Path $tempRoot "profile.json"
    $firstReceiptPath = Join-Path $tempRoot "first-host-receipt.json"
    $secondReceiptPath = Join-Path $tempRoot "second-host-receipt.json"

    [IO.File]::WriteAllText(
        $profilePath,
        '{"schema":"rusty.quest.spatial_camera_panel.control_profile.v1","profile_id":"unit-test-profile","revision":7,"quest_controls":{}}',
        [Text.UTF8Encoding]::new($false)
    )

    $fakeAdb = @'
param()

$ErrorActionPreference = "Stop"
$statePath = $env:RUSTY_QUEST_FAKE_ADB_STATE
if ([string]::IsNullOrWhiteSpace($statePath)) {
    throw "RUSTY_QUEST_FAKE_ADB_STATE is required."
}

if (Test-Path -LiteralPath $statePath -PathType Leaf) {
    $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json -AsHashtable
} else {
    $state = [ordered]@{
        active = $null
        pending = $null
        receipt = $null
        commands = @()
    }
}
$state.commands = @($state.commands) + ,($args -join " ")

function Save-State {
    [IO.File]::WriteAllText(
        $statePath,
        (($state | ConvertTo-Json -Depth 10) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false)
    )
}

if ($args.Count -lt 3 -or $args[0] -ne "-s" -or $args[1] -ne "mock-serial") {
    Save-State
    throw "Unexpected serial-scoped ADB arguments: $($args -join ' ')"
}

$verb = $args[2]
if ($verb -eq "get-state") {
    Save-State
    "device"
    exit 0
}
if ($verb -eq "push") {
    $source = [string]$args[3]
    $destination = [string]$args[4]
    $state.pending = [ordered]@{
        path = $destination
        length = [long](Get-Item -LiteralPath $source).Length
        modified_unix_seconds = 1700000000L
        sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        payload = (Get-Content -LiteralPath $source -Raw | ConvertFrom-Json -AsHashtable)
    }
    Save-State
    exit 0
}
if ($verb -ne "shell") {
    Save-State
    throw "Unexpected fake ADB verb: $verb"
}

$command = [string]$args[3]
switch ($command) {
    "getprop" {
        Save-State
        "Mock Quest"
        exit 0
    }
    "mkdir" {
        Save-State
        exit 0
    }
    "test" {
        $devicePath = [string]$args[5]
        $exists =
            ($null -ne $state.active -and [string]$state.active.path -eq $devicePath) -or
            ($null -ne $state.pending -and [string]$state.pending.path -eq $devicePath)
        Save-State
        if ($exists) {
            exit 0
        }
        exit 1
    }
    "stat" {
        $devicePath = [string]$args[6]
        $entry =
            if ($null -ne $state.active -and [string]$state.active.path -eq $devicePath) {
                $state.active
            } elseif ($null -ne $state.pending -and [string]$state.pending.path -eq $devicePath) {
                $state.pending
            } else {
                $null
            }
        Save-State
        if ($null -eq $entry) {
            exit 1
        }
        "$([long]$entry.length):$([long]$entry.modified_unix_seconds)"
        exit 0
    }
    "touch" {
        if ($null -eq $state.pending) {
            Save-State
            exit 1
        }
        $modifiedToken = [string]$args[6]
        if ($modifiedToken -notmatch '^@(?<seconds>[0-9]+)$') {
            Save-State
            throw "Unexpected touch timestamp: $modifiedToken"
        }
        $state.pending.modified_unix_seconds = [long]$Matches.seconds
        Save-State
        exit 0
    }
    "mv" {
        if ($null -eq $state.pending) {
            Save-State
            exit 1
        }
        $state.active = $state.pending
        $state.active.path = [string]$args[5]
        $state.pending = $null
        $state.receipt = [ordered]@{
            schema = "rusty.quest.spatial_camera_panel.control_profile_apply_receipt.v1"
            status = "applied"
            profile_id = "unit-test-profile"
            revision = 7
            profile_sha256 = [string]$state.active.sha256
            applied_unix_ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            route_active = $true
            effective = [ordered]@{}
        }
        if ($state.active.path.EndsWith('/active.modules.json')) {
            $state.receipt = [ordered]@{
                schema = 'rusty.quest.spatial_camera_panel.development_module_receipt.v1'
                status = 'observed'; phase = 'consumer_readback'; consumer = 'observation'; mode = 'status'
                profile_id = $state.active.payload.profile_id; revision = $state.active.payload.revision
                profile_sha256 = $state.active.sha256; source_file = 'active.modules.json'
                source_length = $state.active.length
                source_modified_unix_ms = ([long]$state.active.modified_unix_seconds * 1000L)
                watcher_id = '11111111-2222-3333-4444-555555555555'
                owner_process_pid = 123; owner_process_start_ticks = '3456'
                consumer_receipt = @{outcome='accepted'; renderer_calls=0}
            }
            switch ($state.module_case) {
                'wrong_revision' { $state.receipt.revision = 999 }
                'wrong_hash' { $state.receipt.profile_sha256 = 'f' * 64 }
                'wrong_owner' { $state.receipt.owner_process_start_ticks = '3457' }
                'watched' { $state.receipt.status = 'watched'; $state.receipt.phase = 'watched' }
                'retired' { $state.receipt.status = 'retired'; $state.receipt.phase = 'retired' }
            }
        }
        Save-State
        exit 0
    }
    "cat" {
        $receipt = $state.receipt
        if ([string]$args[4] -like '*/module-watch-receipt.json') {
            $receipt = @{
                schema='rusty.quest.spatial_camera_panel.development_module_watch.v1'; phase='armed'
                watcher_id='11111111-2222-3333-4444-555555555555'
                owner_process_pid=123; owner_process_start_ticks='3456'
            }
        }
        Save-State
        if ($null -eq $receipt) {
            exit 1
        }
        $receipt | ConvertTo-Json -Depth 10 -Compress
        exit 0
    }
    default {
        Save-State
        throw "Unexpected fake ADB shell command: $($args -join ' ')"
    }
}
'@
    [IO.File]::WriteAllText($fakeAdbPath, $fakeAdb, [Text.UTF8Encoding]::new($false))

    $priorFakeState = $env:RUSTY_QUEST_FAKE_ADB_STATE
    $env:RUSTY_QUEST_FAKE_ADB_STATE = $statePath
    try {
        $firstOutput = & $installerPath `
            -Serial "mock-serial" `
            -ProfilePath $profilePath `
            -TimeoutSeconds 2 `
            -OutPath $firstReceiptPath `
            -Adb $fakeAdbPath
        $firstExitCode = $LASTEXITCODE
        Assert-True ($firstExitCode -eq 0) "First identical-profile publication failed."
        $first = ($firstOutput -join "`n") | ConvertFrom-Json

        $secondOutput = & $installerPath `
            -Serial "mock-serial" `
            -ProfilePath $profilePath `
            -TimeoutSeconds 2 `
            -OutPath $secondReceiptPath `
            -Adb $fakeAdbPath
        $secondExitCode = $LASTEXITCODE
        Assert-True ($secondExitCode -eq 0) "Second identical-profile publication failed."
        $second = ($secondOutput -join "`n") | ConvertFrom-Json
    } finally {
        $env:RUSTY_QUEST_FAKE_ADB_STATE = $priorFakeState
    }

    Assert-True ([string]$first.profile_sha256 -eq [string]$second.profile_sha256) "Test publications did not use identical bytes."
    Assert-True (-not [bool]$first.publication_signature.prior_present) "First publication unexpectedly found an active profile."
    Assert-True ([bool]$second.publication_signature.prior_present) "Second publication did not observe the first active profile."
    Assert-True ([bool]$first.publication_signature.changed_from_prior) "First publication did not change the absent signature."
    Assert-True ([bool]$second.publication_signature.changed_from_prior) "Second identical publication did not advance the signature."
    Assert-True (
        [long]$second.publication_signature.modified_unix_seconds -gt
            [long]$first.publication_signature.modified_unix_seconds
    ) "Second identical publication did not receive a strictly newer modification time."
    Assert-True ([bool]$first.publication_signature.atomic_replace) "First publication did not report atomic replacement."
    Assert-True ([bool]$second.publication_signature.atomic_replace) "Second publication did not report atomic replacement."
    Assert-True ((Test-Path -LiteralPath $firstReceiptPath -PathType Leaf)) "First fresh host receipt was not written."
    Assert-True ((Test-Path -LiteralPath $secondReceiptPath -PathType Leaf)) "Second fresh host receipt was not written."

    $finalState = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $touchCommands = @($finalState.commands | Where-Object { [string]$_ -match ' shell touch -m -d @' })
    $moveCommands = @($finalState.commands | Where-Object { [string]$_ -match ' shell mv ' })
    Assert-True ($touchCommands.Count -eq 2) "Expected one generation assignment per publication."
    Assert-True ($moveCommands.Count -eq 2) "Expected one atomic move per publication."
    $touchIndexes = @()
    $moveIndexes = @()
    for ($commandIndex = 0; $commandIndex -lt $finalState.commands.Count; $commandIndex += 1) {
        $commandText = [string]$finalState.commands[$commandIndex]
        if ($commandText -match ' shell touch -m -d @') {
            $touchIndexes += $commandIndex
        } elseif ($commandText -match ' shell mv ') {
            $moveIndexes += $commandIndex
        }
    }
    Assert-True (
        $touchIndexes[0] -lt $moveIndexes[0] -and
            $touchIndexes[1] -lt $moveIndexes[1]
    ) "Each publication must assign its generation before the atomic move."
    Assert-True (
        [long]$finalState.active.modified_unix_seconds -eq
            [long]$second.publication_signature.modified_unix_seconds
    ) "Final active file signature does not match the second host receipt."

    $moduleProfilePath = Join-Path $tempRoot 'modules.json'
    $moduleBytes = '{"schema":"rusty.quest.spatial_camera_panel.development_modules.v1","profile_id":"unit-modules","revision":42,"development_observation_module":{"mode":"status"}}'
    [IO.File]::WriteAllText($moduleProfilePath, $moduleBytes)
    $moduleArguments = @{
        Serial='mock-serial'; ProfilePath=$moduleProfilePath; TimeoutSeconds=1; Adb=$fakeAdbPath
        ReceiptMode='ModulesOnly'; ExpectedWatcherId='11111111-2222-3333-4444-555555555555'
        ExpectedOwnerPid=123; ExpectedOwnerStartTicks='3456'
    }
    $env:RUSTY_QUEST_FAKE_ADB_STATE = $statePath
    try {
        foreach ($case in @('observed', 'wrong_revision', 'wrong_hash', 'wrong_owner', 'watched', 'retired')) {
            $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
            $state.module_case = $case
            [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 20))
            $failure = $null; $observed = $null
            try { $observed = ((& $installerPath @moduleArguments) -join "`n") | ConvertFrom-Json } catch { $failure = $_ }
            if ($case -eq 'observed') {
                Assert-True ($null -eq $failure) "Module-only matching readback failed: $failure"
                Assert-True ($observed.status -eq 'observed') 'Consumer readback was conflated with acceptance/use.'
                Assert-True ($observed.device_profile_path -eq 'external-files/control-profiles/active.modules.json') 'Module-only publication escaped its fixed path.'
                Assert-True ($observed.app_receipt.observation.consumer_receipt.renderer_calls -eq 0) 'Fixture unexpectedly attested use.'
            } else { Assert-True ($null -ne $failure) "Module-only $case was accepted as current readback." }
        }
        $before = (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).commands.Count
        foreach ($bad in @(
            $moduleBytes.Replace('"revision":42', '"revision":42,"revision":43'),
            $moduleBytes.Replace('"revision":42', '"revision":42,"quest_controls":{}'),
            $moduleBytes.Replace('"revision":42', '"revision":42,"peer_network_endpoints":[]'),
            $moduleBytes.Replace('"status"', '"unknown"'),
            (' ' * 65537))) {
            [IO.File]::WriteAllText($moduleProfilePath, $bad)
            $failure = $null
            try { & $installerPath @moduleArguments | Out-Null } catch { $failure = $_ }
            Assert-True ($null -ne $failure) 'Invalid module-only input was published.'
        }
        Assert-True (((Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json).commands.Count) -eq $before) 'Invalid module-only input contacted ADB.'
        [IO.File]::WriteAllText($moduleProfilePath, $moduleBytes)
        $moduleArguments.ExpectedOwnerStartTicks = '3457'
        $failure = $null
        try { & $installerPath @moduleArguments | Out-Null } catch { $failure = $_ }
        Assert-True ($null -ne $failure) 'A different owner passed watcher preflight.'
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $newCommands = @($state.commands | Select-Object -Skip $before)
        Assert-True (@($newCommands | Where-Object { $_ -match ' push | shell mv ' }).Count -eq 0) 'Owner mismatch published bytes.'
    } finally { $env:RUSTY_QUEST_FAKE_ADB_STATE = $priorFakeState }
    Write-Output "Control-profile host tests passed: legacy identical publication, module-only fixed path, owner/publication joins, watched/retired and invalid-input rejection."
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $resolvedTestRoot = [IO.Path]::GetFullPath($tempRoot)
        if ($resolvedTestRoot.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force
        }
    }
}
