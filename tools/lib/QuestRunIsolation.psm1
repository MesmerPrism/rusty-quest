Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "QuestPropertyTransport.psm1") -Force
Import-Module (Join-Path $PSScriptRoot "SourceComposition.psm1")

function Get-QuestRunIsolationHash {
    param([Parameter(Mandatory=$true)][string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function ConvertTo-QuestShellSingleQuoted {
    param([Parameter(Mandatory=$true)][AllowEmptyString()][string]$Value)
    $singleQuoteEscape = "'" + [char]34 + "'" + [char]34 + "'"
    return "'" + $Value.Replace("'", $singleQuoteEscape) + "'"
}

function Get-QuestRunIsolationMutexName {
    param([Parameter(Mandatory=$true)][string]$Serial)
    return "Local\RustyMorphospaceQuestRun-" + (Get-QuestRunIsolationHash -Value $Serial).Substring(0, 24)
}

function Get-QuestRunIsolationSnapshotHash {
    param([Parameter(Mandatory=$true)][AllowEmptyCollection()]$Snapshot)
    if (@($Snapshot).Count -eq 0) { return Get-QuestRunIsolationHash -Value '[]' }
    $canonical = @($Snapshot | ForEach-Object {
        [ordered]@{ name = [string]$_.name; value = [string]$_.value }
    }) | ConvertTo-Json -Compress -Depth 4
    return Get-QuestRunIsolationHash -Value $canonical
}

function Assert-QuestRunIsolationEnteredReceipt {
    param(
        [Parameter(Mandatory=$true)]$Receipt,
        [Parameter(Mandatory=$true)][string]$ExpectedSerial,
        [Parameter(Mandatory=$true)][string]$ExpectedPackageName
    )
    if ([string]$Receipt.schema -ne "rusty.quest.run_isolation_receipt.v1") { throw "Entered run-isolation receipt schema is not supported." }
    if ([string]$Receipt.phase -ne "entered" -or [string]$Receipt.status -ne "active") { throw "Run-isolation recovery accepts only an active entered receipt." }
    if ([string]$Receipt.serial -ne $ExpectedSerial) { throw "Entered run-isolation receipt serial does not match the expected serial." }
    if ([string]$Receipt.package_name -ne $ExpectedPackageName) { throw "Entered run-isolation receipt package does not match the expected package." }
    $expectedMutexName = Get-QuestRunIsolationMutexName -Serial $ExpectedSerial
    if ([string]$Receipt.mutex_name -ne $expectedMutexName) { throw "Entered run-isolation receipt mutex identity is not bound to the expected serial." }
    $snapshot = @($Receipt.property_snapshot)
    $hasNoneBinding = $Receipt.PSObject.Properties.Name -contains 'property_policy_binding'
    if ($snapshot.Count -eq 0) {
        if (-not $hasNoneBinding) { throw "Entered run-isolation receipt property snapshot is empty." }
        $null = Assert-QuestRunNonePropertyBinding -Binding $Receipt.property_policy_binding -ExpectedPackageName $ExpectedPackageName
        return @()
    }
    if ($hasNoneBinding) { throw 'Explicit-none receipt cannot carry a property snapshot.' }
    $seen = @{}
    foreach ($entry in $snapshot) {
        $name = [string]$entry.name
        $value = [string]$entry.value
        if ($name -notmatch '^[A-Za-z0-9._-]{1,92}$') { throw "Entered run-isolation receipt contains an unsafe property name." }
        if ($value.IndexOf([char]0) -ge 0 -or $value.Contains("`r") -or $value.Contains("`n")) { throw "Entered run-isolation receipt contains an unsafe property value." }
        if ($seen.ContainsKey($name)) { throw "Entered run-isolation receipt contains duplicate property names: $name" }
        $seen[$name] = $true
    }
    return @($snapshot | ForEach-Object { [pscustomobject][ordered]@{ name = [string]$_.name; value = [string]$_.value } })
}

function Write-QuestRunIsolationReceiptAtomically {
    param([Parameter(Mandatory=$true)]$Receipt, [Parameter(Mandatory=$true)][string]$Path)
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = Join-Path $parent ((Split-Path -Leaf $Path) + "." + [guid]::NewGuid().ToString("N") + ".tmp")
    try {
        $Receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding UTF8
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-QuestRunIsolationAdb {
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [string]$AdbServerPort,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $base = @()
    if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) { $base += @("-P", $AdbServerPort) }
    $base += @("-s", $Serial)
    $output = @(& $Adb @base @Arguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) { throw "ADB isolation command failed ($exitCode): $($Arguments -join ' ')`n$($output -join "`n")" }
    return [pscustomobject][ordered]@{ exit_code = $exitCode; output = $output -join "`n"; arguments = $Arguments }
}

function Get-QuestPropertyValue {
    param([string]$Adb, [string]$Serial, [string]$AdbServerPort, [string]$Name)
    $command = "getprop $(ConvertTo-QuestShellSingleQuoted -Value $Name)"
    $result = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", $command)
    return ([string]$result.output) -replace "(`r`n|`n|`r)$", ""
}

function Set-QuestPropertyValue {
    param([string]$Adb, [string]$Serial, [string]$AdbServerPort, [string]$Name, [AllowEmptyString()][string]$Value)
    $command = "setprop $(ConvertTo-QuestShellSingleQuoted -Value $Name) $(ConvertTo-QuestShellSingleQuoted -Value $Value)"
    $null = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", $command)
}

function Get-QuestPropertySnapshot {
    param([string]$Adb, [string]$Serial, [string]$AdbServerPort, [string[]]$PropertyNames)
    $result = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "getprop")
    $observed = @{}
    foreach ($line in @(([string]$result.output) -split "`r?`n")) {
        $match = [regex]::Match($line, '^\[(?<name>[^\]]+)\]: \[(?<value>.*)\]$')
        if ($match.Success) { $observed[$match.Groups['name'].Value] = $match.Groups['value'].Value }
    }
    return @($PropertyNames | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique | ForEach-Object {
        $name = [string]$_
        [pscustomobject][ordered]@{ name = $name; value = if ($observed.ContainsKey($name)) { [string]$observed[$name] } else { "" } }
    })
}

function Set-QuestPropertyBatch {
    param([string]$Adb, [string]$Serial, [string]$AdbServerPort, [Parameter(Mandatory=$true)]$Entries)
    $batches = @(New-QuestPropertySetpropBatches -Entries $Entries)
    if ($batches.Count -eq 0) { return }
    $null = Invoke-QuestPropertySetpropBatches -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Batches $batches
}

function Get-QuestRunCapsuleInstallApk {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)]$Capsule
    )
    $sourcePath = (Resolve-Path -LiteralPath ([string]$Capsule.apk.path)).Path
    $expectedSha256 = ([string]$Capsule.apk.sha256).ToLowerInvariant()
    if ($expectedSha256 -notmatch '^[0-9a-f]{64}$') { throw "Run capsule APK hash is invalid." }
    $sourceSha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceSha256 -ne $expectedSha256) { throw "Run capsule APK source hash mismatch: $sourcePath" }

    $stageRoot = Join-Path ([IO.Path]::GetFullPath($RepoRoot)) "target\apk-r"
    New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
    $stagePath = Join-Path $stageRoot "$expectedSha256.apk"
    if (-not (Test-Path -LiteralPath $stagePath -PathType Leaf)) {
        $temporaryPath = Join-Path $stageRoot ("$expectedSha256." + [guid]::NewGuid().ToString("N") + ".tmp")
        try {
            Copy-Item -LiteralPath $sourcePath -Destination $temporaryPath
            $temporarySha256 = (Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($temporarySha256 -ne $expectedSha256) { throw "Short APK staging copy hash mismatch: $temporaryPath" }
            try {
                [IO.File]::Move($temporaryPath, $stagePath)
            } catch {
                if (-not (Test-Path -LiteralPath $stagePath -PathType Leaf) -or
                    (Get-FileHash -LiteralPath $stagePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha256) {
                    throw
                }
            }
        } finally {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
    $stagedSha256 = (Get-FileHash -LiteralPath $stagePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($stagedSha256 -ne $expectedSha256) { throw "Existing short APK staging cache is damaged: $stagePath" }
    return (Resolve-Path -LiteralPath $stagePath).Path
}

function Get-QuestRunFileRecord {
    param([Parameter(Mandatory=$true)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Capsule input is not a file: $Path" }
    return [pscustomobject][ordered]@{ path = $resolved; sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Assert-QuestRunNoneProfile {
    param([Parameter(Mandatory=$true)]$Capsule)
    $package = 'io.github.mesmerprism.rustyquest.development_feedback'
    if ($Capsule.app_lane -cne 'development-feedback-android' -or $Capsule.app_id -cne 'development-feedback' -or
        $Capsule.android.package_name -cne $package -or $Capsule.android.activity -cne "$package/$package.FeedbackActivity" -or
        $null -ne $Capsule.property_manifest -or $null -eq $Capsule.runtime_profile) { throw 'Neutral capsule identity/property contract differs.' }
    if (-not [IO.Path]::IsPathFullyQualified([string]$Capsule.runtime_profile.path)) { throw 'Neutral runtime profile path must be absolute.' }
    $profileRecord = Get-QuestRunFileRecord -Path $Capsule.runtime_profile.path
    if ($profileRecord.sha256 -cne $Capsule.runtime_profile.sha256) { throw 'Neutral runtime profile hash differs.' }
    $profile = Get-Content -LiteralPath $profileRecord.path -Raw | ConvertFrom-Json
    $fields = @('schema','app_lane','owner_package','property_policy','properties')
    if (@($profile.PSObject.Properties.Name).Count -ne $fields.Count -or
        @($profile.PSObject.Properties.Name | Where-Object { $_ -cnotin $fields }).Count -ne 0 -or
        $profile.schema -cne 'rusty.quest.development_feedback.runtime_profile.v1' -or
        $profile.app_lane -cne $Capsule.app_lane -or $profile.owner_package -cne $package -or
        $profile.property_policy -cne 'none' -or $profile.properties -isnot [array] -or $profile.properties.Count -ne 0) {
        throw 'Neutral runtime profile must be the closed package-owned explicit-none declaration.'
    }
}

function Read-QuestRunCapsule {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)][Alias('Path')][string]$CapsulePath, [string]$ExpectedLane = '')
    $path = (Resolve-Path -LiteralPath $CapsulePath -ErrorAction Stop).Path
    $capsule = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($ExpectedLane -and $capsule.app_lane -cne $ExpectedLane) { throw 'APK run capsule lane mismatch.' }
    if ($capsule.app_lane -cne 'development-feedback-android') {
        if ($capsule.app_id -ceq 'development-feedback' -or $capsule.android.package_name -ceq 'io.github.mesmerprism.rustyquest.development_feedback') { throw 'Neutral capsule cannot use a legacy lane.' }
        # Preserve historical manual/DirtyDevFast admission rather than applying
        # the clean-only JSON schema to legacy callers.
        $validation = @(& pwsh -NoProfile -File (Join-Path $PSScriptRoot '../Test-ApkRunCapsule.ps1') -CapsulePath $path -ExpectedLane $ExpectedLane 2>&1)
        if ($LASTEXITCODE -ne 0) { throw "Legacy capsule validation failed: $($validation -join ' ')" }
        return $capsule
    }
    if (-not (Get-Content -LiteralPath $path -Raw | Test-Json -SchemaFile (Join-Path $PSScriptRoot '../../schemas/rusty.quest.apk_run_capsule.v1.schema.json') -ErrorAction Stop)) { throw 'Neutral capsule schema differs.' }
    Assert-QuestRunNoneProfile -Capsule $capsule
    foreach ($field in @('build_lock','build_manifest','apk','runtime_profile')) {
        if (-not [IO.Path]::IsPathFullyQualified([string]$capsule.$field.path)) { throw "Neutral capsule $field path must be absolute." }
        if ((Get-QuestRunFileRecord -Path $capsule.$field.path).sha256 -cne $capsule.$field.sha256) { throw "Neutral capsule $field hash differs." }
    }
    $source = $capsule.source
    if (@($source.packages).Count -ne 1 -or $source.packages[0] -cne $capsule.android.package_name) { throw 'Neutral source application selection differs.' }
    $records = @([pscustomobject]@{ repository_id='rusty-quest'; role='primary'; repository=$source.repository; commit=$source.commit; tree=$source.tree; tracked_worktree_clean=$source.tracked_worktree_clean; worktree_overlay_sha256='' })
    $records += @($source.dependencies | ForEach-Object { [pscustomobject]@{ repository_id=$_.repository_id; role=$_.role; repository=$_.repository; commit=$_.commit; tree=$_.tree; tracked_worktree_clean=$_.tracked_worktree_clean; worktree_overlay_sha256='' } })
    Assert-QuestBuildSourceCompositionCurrent -Composition ([pscustomobject]@{schema='rusty.quest.apk_source_composition.v1';fingerprint=$source.composition_fingerprint;packages=@($source.packages);repositories=$records})
    return $capsule
}

function New-QuestRunCapsule {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][Alias('Path')][string]$CapsulePath,
        [Parameter(Mandatory=$true)][string]$RepoRoot,
        [Parameter(Mandatory=$true)][string]$CapsuleId,
        [Parameter(Mandatory=$true)][string]$BuildLockPath,
        [Parameter(Mandatory=$true)][string]$BuildManifestPath,
        [Parameter(Mandatory=$true)][string]$ApkPath,
        [Parameter(Mandatory=$true)][string]$RuntimeProfilePath
    )
    $package = 'io.github.mesmerprism.rustyquest.development_feedback'
    $composition = Get-QuestBuildSourceComposition -RepoRoot $RepoRoot -ApplicationId $package
    $primary = @($composition.repositories | Where-Object role -CEQ 'primary')[0]
    $capsule = [ordered]@{
        schema='rusty.quest.apk_run_capsule.v1';capsule_id=$CapsuleId;app_id='development-feedback';app_lane='development-feedback-android'
        source=[ordered]@{repository=$primary.repository;commit=$primary.commit;tree=$primary.tree;tracked_worktree_clean=$primary.tracked_worktree_clean;composition_fingerprint=$composition.fingerprint;packages=@($composition.packages);dependencies=@($composition.repositories | Where-Object role -CNE 'primary' | Select-Object repository_id,role,repository,commit,tree,tracked_worktree_clean)}
        build_lock=Get-QuestRunFileRecord $BuildLockPath;build_manifest=Get-QuestRunFileRecord $BuildManifestPath;apk=Get-QuestRunFileRecord $ApkPath;runtime_profile=Get-QuestRunFileRecord $RuntimeProfilePath;property_manifest=$null
        android=[ordered]@{package_name=$package;activity="$package/$package.FeedbackActivity"}
        cleanup=[ordered]@{policy='always-force-stop-and-restore-exact-property-snapshot';serial_exclusive_mutex=$true;restore_on_failure=$true}
    }
    Assert-QuestRunNoneProfile -Capsule ([pscustomobject]$capsule)
    $path = [IO.Path]::GetFullPath($CapsulePath)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($capsule | ConvertTo-Json -Depth 20))
    $stream = [IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
    # This is a file/source binding, not APK inspection or build provenance.
    return Read-QuestRunCapsule -CapsulePath $path -ExpectedLane 'development-feedback-android'
}

function Get-QuestRunNonePropertyBinding {
    param([string]$CapsulePath,[string]$ExpectedPackageName)
    $capsule = Read-QuestRunCapsule -CapsulePath $CapsulePath -ExpectedLane 'development-feedback-android'
    $file = Get-QuestRunFileRecord $CapsulePath
    $binding = [pscustomobject][ordered]@{capsule_path=$file.path;capsule_sha256=$file.sha256;runtime_profile_path=$capsule.runtime_profile.path;runtime_profile_sha256=$capsule.runtime_profile.sha256;package_name=$capsule.android.package_name;app_lane=$capsule.app_lane;property_policy='none'}
    return Assert-QuestRunNonePropertyBinding -Binding $binding -ExpectedPackageName $ExpectedPackageName
}

function Assert-QuestRunNonePropertyBinding {
    param([Parameter(Mandatory=$true)]$Binding,[string]$ExpectedPackageName)
    $fields = @('capsule_path','capsule_sha256','runtime_profile_path','runtime_profile_sha256','package_name','app_lane','property_policy')
    if (@($Binding.PSObject.Properties.Name).Count -ne $fields.Count -or @($Binding.PSObject.Properties.Name | Where-Object { $_ -cnotin $fields }).Count -ne 0 -or $Binding.package_name -cne $ExpectedPackageName -or $Binding.property_policy -cne 'none') { throw 'Explicit-none receipt binding differs.' }
    $file = Get-QuestRunFileRecord $Binding.capsule_path
    if ($file.sha256 -cne $Binding.capsule_sha256) { throw 'Entered capsule hash differs.' }
    $capsule = Get-Content -LiteralPath $file.path -Raw | ConvertFrom-Json
    if (-not (Get-Content -LiteralPath $file.path -Raw | Test-Json -SchemaFile (Join-Path $PSScriptRoot '../../schemas/rusty.quest.apk_run_capsule.v1.schema.json') -ErrorAction Stop)) { throw 'Entered neutral capsule schema differs.' }
    Assert-QuestRunNoneProfile -Capsule $capsule
    if ($capsule.android.package_name -cne $Binding.package_name -or $capsule.app_lane -cne $Binding.app_lane -or $capsule.runtime_profile.path -cne $Binding.runtime_profile_path -or $capsule.runtime_profile.sha256 -cne $Binding.runtime_profile_sha256) { throw 'Entered capsule/profile binding differs.' }
    return $Binding
}

function Enter-QuestRunIsolation {
    [CmdletBinding(DefaultParameterSetName='Properties')]
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$Serial,
        [string]$AdbServerPort,
        [Parameter(Mandatory=$true)][string]$PackageName,
        [Parameter(Mandatory=$true,ParameterSetName='Properties')][string[]]$PropertyNames,
        [Parameter(Mandatory=$true,ParameterSetName='ExplicitNone')][string]$RunCapsulePath,
        [Parameter(Mandatory=$true)][string]$ReceiptPath,
        [int]$MutexTimeoutSeconds = 120
    )
    $noneBinding = if ($PSCmdlet.ParameterSetName -eq 'ExplicitNone') { Get-QuestRunNonePropertyBinding -CapsulePath $RunCapsulePath -ExpectedPackageName $PackageName } else { $null }
    $mutexName = Get-QuestRunIsolationMutexName -Serial $Serial
    $mutex = [Threading.Mutex]::new($false, $mutexName)
    $acquired = $false
    try {
        $acquired = $mutex.WaitOne([Math]::Max(0, $MutexTimeoutSeconds) * 1000)
        if (-not $acquired) { throw "Quest $Serial is already owned by another Rusty Morphospace run transaction ($mutexName)." }
        $state = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("get-state")
        if ([string]$state.output.Trim() -ne "device") { throw "Quest $Serial is not in device state." }
        $snapshots = @()
        if ($null -eq $noneBinding) { $snapshots = @(Get-QuestPropertySnapshot -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -PropertyNames $PropertyNames) }
        $foreground = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $Serial -AdbServerPort $AdbServerPort -Arguments @("shell", "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'") -AllowFailure
        $receipt = [pscustomobject][ordered]@{
            schema = "rusty.quest.run_isolation_receipt.v1"; phase = "entered"; status = "active"
            entered_at = [DateTime]::UtcNow.ToString("o"); serial = $Serial; package_name = $PackageName
            mutex_name = $mutexName; property_snapshot = $snapshots; foreground_before = [string]$foreground.output
        }
        if ($null -ne $noneBinding) { $receipt | Add-Member -NotePropertyName property_policy_binding -NotePropertyValue $noneBinding }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReceiptPath) | Out-Null
        Write-QuestRunIsolationReceiptAtomically -Receipt $receipt -Path $ReceiptPath
        return [pscustomobject]@{
            Adb = $Adb; Serial = $Serial; AdbServerPort = $AdbServerPort; PackageName = $PackageName
            ReceiptPath = $ReceiptPath; Mutex = $mutex; MutexName = $mutexName; Acquired = $true; PropertySnapshot = $snapshots
            CompletePropertyClear = $false
            PropertyPolicyBinding = $noneBinding
        }
    } catch {
        if ($acquired) { try { $mutex.ReleaseMutex() } catch {} }
        $mutex.Dispose()
        throw
    }
}

function Clear-QuestRunIsolationProperties {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)]$Context)
    if ($Context.PSObject.Properties.Name -contains 'PropertyPolicyBinding' -and $null -ne $Context.PropertyPolicyBinding) {
        if (@($Context.PropertySnapshot).Count -ne 0) { throw 'Explicit-none context cannot carry properties.' }
        $null = Assert-QuestRunNonePropertyBinding -Binding $Context.PropertyPolicyBinding -ExpectedPackageName $Context.PackageName
        $Context.CompletePropertyClear = $true
        return
    }
    $clears = @($Context.PropertySnapshot | ForEach-Object { [pscustomobject]@{ name = [string]$_.name; value = "" } })
    Set-QuestPropertyBatch -Adb $Context.Adb -Serial $Context.Serial -AdbServerPort $Context.AdbServerPort -Entries $clears
    $readback = @(Get-QuestPropertySnapshot -Adb $Context.Adb -Serial $Context.Serial -AdbServerPort $Context.AdbServerPort -PropertyNames @($clears.name))
    foreach ($entry in $readback) {
        if (-not [string]::IsNullOrEmpty([string]$entry.value)) { throw "Complete property clear failed for '$([string]$entry.name)': observed '$([string]$entry.value)'" }
    }
    $Context.CompletePropertyClear = $true
}

function Exit-QuestRunIsolation {
    [CmdletBinding()]
    param([Parameter(Mandatory=$true)]$Context)
    $errors = [Collections.Generic.List[string]]::new()
    $restores = @()
    try {
        $isNone = $Context.PSObject.Properties.Name -contains 'PropertyPolicyBinding' -and $null -ne $Context.PropertyPolicyBinding
        if ($isNone -and @($Context.PropertySnapshot).Count -ne 0) { throw 'Explicit-none context cannot carry properties.' }
        if ($isNone) { $null = Assert-QuestRunNonePropertyBinding -Binding $Context.PropertyPolicyBinding -ExpectedPackageName $Context.PackageName }
        $stop = Invoke-QuestRunIsolationAdb -Adb $Context.Adb -Serial $Context.Serial -AdbServerPort $Context.AdbServerPort -Arguments @("shell", "am force-stop $(ConvertTo-QuestShellSingleQuoted -Value $Context.PackageName)") -AllowFailure
        if ($stop.exit_code -ne 0) { $errors.Add("force-stop failed: $($stop.output)") }
        if (-not $isNone) { try {
            Set-QuestPropertyBatch -Adb $Context.Adb -Serial $Context.Serial -AdbServerPort $Context.AdbServerPort -Entries @($Context.PropertySnapshot)
        } catch {
            $errors.Add("property restore batch failed: $($_.Exception.Message)")
        }
        $restoreReadback = @{}
        try {
            foreach ($entry in @(Get-QuestPropertySnapshot -Adb $Context.Adb -Serial $Context.Serial -AdbServerPort $Context.AdbServerPort -PropertyNames @($Context.PropertySnapshot.name))) {
                $restoreReadback[[string]$entry.name] = [string]$entry.value
            }
        } catch {
            $errors.Add("property restore readback failed: $($_.Exception.Message)")
        }
        foreach ($entry in @($Context.PropertySnapshot)) {
            $status = "matched"
            $errorText = ""
            try {
                if (-not $restoreReadback.ContainsKey([string]$entry.name)) { throw "property missing from restore readback" }
                $observed = [string]$restoreReadback[[string]$entry.name]
                if ($observed -ne [string]$entry.value) { throw "expected '$([string]$entry.value)' observed '$observed'" }
            } catch {
                $status = "failed"; $errorText = $_.Exception.Message; $errors.Add("$([string]$entry.name): $errorText")
                $observed = ""
            }
            $restores += [pscustomobject][ordered]@{ name = [string]$entry.name; expected_value = [string]$entry.value; observed_value = $observed; status = $status; error = $errorText }
        }
        }
        $receipt = [pscustomobject][ordered]@{
            schema = "rusty.quest.run_isolation_receipt.v1"; phase = "cleaned"; status = if ($errors.Count -eq 0) { "pass" } else { "partial" }
            completed_at = [DateTime]::UtcNow.ToString("o"); serial = [string]$Context.Serial; package_name = [string]$Context.PackageName
            mutex_name = [string]$Context.MutexName; force_stop_exit_code = $stop.exit_code; complete_property_clear = [bool]$Context.CompletePropertyClear
            property_restore = $restores; errors = @($errors)
        }
        if ($isNone) { $receipt | Add-Member -NotePropertyName property_policy_binding -NotePropertyValue $Context.PropertyPolicyBinding }
        Write-QuestRunIsolationReceiptAtomically -Receipt $receipt -Path $Context.ReceiptPath
        return $receipt
    } finally {
        if ($Context.Acquired) { try { $Context.Mutex.ReleaseMutex() } catch {} }
        $Context.Mutex.Dispose()
    }
}

function Recover-QuestRunIsolation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)][string]$Adb,
        [Parameter(Mandatory=$true)][string]$ExpectedSerial,
        [string]$AdbServerPort,
        [Parameter(Mandatory=$true)][string]$ExpectedPackageName,
        [Parameter(Mandatory=$true)][string]$EnteredReceiptPath,
        [Parameter(Mandatory=$true)][string]$TerminalReceiptPath,
        [ValidateRange(0, 120)][int]$MutexTimeoutSeconds = 0
    )
    $enteredFullPath = (Resolve-Path -LiteralPath $EnteredReceiptPath -ErrorAction Stop).Path
    $terminalFullPath = [IO.Path]::GetFullPath($TerminalReceiptPath)
    if ($enteredFullPath -eq $terminalFullPath) { throw "Recovery terminal receipt path must be distinct from the entered receipt path." }
    $rawBytes = [IO.File]::ReadAllBytes($enteredFullPath)
    try { $enteredReceipt = ([Text.Encoding]::UTF8.GetString($rawBytes) | ConvertFrom-Json -Depth 32) }
    catch { throw "Entered run-isolation receipt is not valid JSON: $($_.Exception.Message)" }
    $snapshot = @(Assert-QuestRunIsolationEnteredReceipt -Receipt $enteredReceipt -ExpectedSerial $ExpectedSerial -ExpectedPackageName $ExpectedPackageName)
    $mutexName = Get-QuestRunIsolationMutexName -Serial $ExpectedSerial
    $mutex = [Threading.Mutex]::new($false, $mutexName)
    $acquired = $false
    $errors = [Collections.Generic.List[string]]::new()
    $restores = @()
    try {
        $acquired = $mutex.WaitOne($MutexTimeoutSeconds * 1000)
        if (-not $acquired) { throw "Quest $ExpectedSerial is already owned by another Rusty Morphospace run transaction ($mutexName)." }
        $isNone = $enteredReceipt.PSObject.Properties.Name -contains 'property_policy_binding'
        if ($isNone) { $null = Assert-QuestRunNonePropertyBinding -Binding $enteredReceipt.property_policy_binding -ExpectedPackageName $ExpectedPackageName }
        $state = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $ExpectedSerial -AdbServerPort $AdbServerPort -Arguments @("get-state")
        if ([string]$state.output.Trim() -ne "device") { throw "Quest $ExpectedSerial is not in device state." }
        $stop = Invoke-QuestRunIsolationAdb -Adb $Adb -Serial $ExpectedSerial -AdbServerPort $AdbServerPort -Arguments @("shell", "am force-stop $(ConvertTo-QuestShellSingleQuoted -Value $ExpectedPackageName)") -AllowFailure
        if ($stop.exit_code -ne 0) { $errors.Add("force-stop failed: $($stop.output)") }
        if (-not $isNone) { try {
            Set-QuestPropertyBatch -Adb $Adb -Serial $ExpectedSerial -AdbServerPort $AdbServerPort -Entries $snapshot
        } catch {
            $errors.Add("property restore batch failed: $($_.Exception.Message)")
        }
        $observed = @{}
        try {
            $globalReadback = Get-QuestPropertyGlobalReadback -Adb $Adb -Serial $ExpectedSerial -AdbServerPort $AdbServerPort
            $observed = $globalReadback.observed
        } catch {
            $errors.Add("property restore readback failed: $($_.Exception.Message)")
        }
        $restoreResult = Test-QuestPropertyExactReadback -Entries $snapshot -Observed $observed
        $restores = @($restoreResult.readbacks)
        foreach ($errorText in @($restoreResult.errors)) { $errors.Add($errorText) }
        }
        $receipt = [pscustomobject][ordered]@{
            schema = "rusty.quest.run_isolation_receipt.v1"; phase = "cleaned"; status = if ($errors.Count -eq 0) { "pass" } else { "partial" }
            completed_at = [DateTime]::UtcNow.ToString("o"); serial = $ExpectedSerial; package_name = $ExpectedPackageName
            mutex_name = $mutexName; force_stop_exit_code = $stop.exit_code; complete_property_clear = $null
            property_restore = $restores; errors = @($errors)
            recovery = [ordered]@{
                mode = "receipt-bound-cross-process"
                entered_receipt_sha256 = ([BitConverter]::ToString(([Security.Cryptography.SHA256]::Create().ComputeHash($rawBytes))).Replace("-", "").ToLowerInvariant())
                property_snapshot_sha256 = Get-QuestRunIsolationSnapshotHash -Snapshot $snapshot
            }
        }
        if ($isNone) { $receipt | Add-Member -NotePropertyName property_policy_binding -NotePropertyValue $enteredReceipt.property_policy_binding }
        Write-QuestRunIsolationReceiptAtomically -Receipt $receipt -Path $terminalFullPath
        if ($errors.Count -ne 0) { throw "Quest run-isolation recovery completed partially: $($errors -join '; ')" }
        return $receipt
    } finally {
        if ($acquired) { try { $mutex.ReleaseMutex() } catch {} }
        $mutex.Dispose()
    }
}

Export-ModuleMember -Function Read-QuestRunCapsule, New-QuestRunCapsule, Get-QuestRunCapsuleInstallApk, Enter-QuestRunIsolation, Clear-QuestRunIsolationProperties, Exit-QuestRunIsolation, Recover-QuestRunIsolation
