param([string]$RepoRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..') }
$repo = (Resolve-Path -LiteralPath $RepoRoot).Path
Import-Module (Join-Path $repo 'tools\lib\QuestPropertyTransport.psm1') -Force

function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message } }
function Invoke-ExpectedFailure {
    param([string]$Label, [scriptblock]$Action)
    try { & $Action } catch {
        if ($_.Exception.Message -eq 'missing expected process failure') { throw }
        return $_.Exception.Message
    }
    if ($LASTEXITCODE -ne 0) { return "native process rejected $Label" }
    throw "Expected command failure: $Label"
}
function Get-MutexName { param([string]$Serial) $sha = [Security.Cryptography.SHA256]::Create(); try { $hash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Serial)))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }; return "Local\RustyMorphospaceQuestRun-" + $hash.Substring(0, 24) }

$temporary = Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-recording-host-reliability-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Force -Path $temporary | Out-Null
    $fakeAdb = Join-Path $temporary 'fake-adb.ps1'
    $fakeAdbText = @'
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
$statePath = $env:RUSTY_QUEST_FAKE_ADB_STATE
$logPath = $env:RUSTY_QUEST_FAKE_ADB_LOG
[pscustomobject]@{ arguments = @($Arguments) } | ConvertTo-Json -Compress | Add-Content -LiteralPath $logPath -Encoding UTF8
if ($Arguments -contains 'get-state') { 'device'; exit 0 }
$shellIndex = [Array]::IndexOf($Arguments, 'shell')
if ($shellIndex -lt 0) { exit 17 }
$command = [string]$Arguments[$shellIndex + 1]
if ($command -eq 'getprop') {
    $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json -AsHashtable
    foreach ($name in @($state.Keys | Sort-Object)) {
        if ($name -eq $env:RUSTY_QUEST_FAKE_ADB_OMIT_READBACK_NAME) { continue }
        $value = if ($name -eq $env:RUSTY_QUEST_FAKE_ADB_MISMATCH_READBACK_NAME) { 'mismatched-value' } else { [string]$state[$name] }
        '[{0}]: [{1}]' -f $name, $value
    }
    exit 0
}
if ($command.StartsWith('am force-stop ')) { 'stopped'; exit 0 }
if ($command.StartsWith('setprop ')) {
    $state = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json -AsHashtable
    $changed = $false
    foreach ($operation in @($command -split ' && ')) {
        $match = [regex]::Match($operation, "^setprop '([^']+)' '([^']*)'$")
        if (-not $match.Success) { 'unparseable setprop fixture command'; exit 29 }
        $name = $match.Groups[1].Value
        if ($name -eq $env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY) {
            if ($changed) { $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8 }
            "forced property failure: $name"; exit 19
        }
        $state[$name] = $match.Groups[2].Value
        $changed = $true
    }
    if ($changed) { $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8 }
    'ok'; exit 0
}
exit 23
'@
    Set-Content -LiteralPath $fakeAdb -Value $fakeAdbText -Encoding UTF8
    $fakeStatePath = Join-Path $temporary 'adb-state.json'
    $fakeLogPath = Join-Path $temporary 'adb-log.jsonl'
    $env:RUSTY_QUEST_FAKE_ADB_STATE = $fakeStatePath
    $env:RUSTY_QUEST_FAKE_ADB_LOG = $fakeLogPath
    [ordered]@{} | ConvertTo-Json | Set-Content -LiteralPath $fakeStatePath -Encoding UTF8

    $quoted = ConvertTo-QuestPropertyShellSingleQuoted -Value "operator's value with spaces"
    Assert-True ($quoted.StartsWith("'") -and $quoted.EndsWith("'") -and $quoted.Contains([string][char]34) -and $quoted.Contains('value with spaces')) 'Single-quoted transport did not preserve a spaced apostrophe value.'
    $batchEntries = @(
        [pscustomobject]@{ name = 'debug.test.one'; value = 'first value' },
        [pscustomobject]@{ name = 'debug.test.two'; value = "operator's value with spaces" },
        [pscustomobject]@{ name = 'debug.test.three'; value = 'third' }
    )
    $batches = @(New-QuestPropertySetpropBatches -Entries $batchEntries -MaxOperationsPerBatch 2 -MaxCommandUtf8Bytes 256)
    Assert-True ($batches.Count -eq 2 -and $batches[0].operation_count -eq 2 -and $batches[1].operation_count -eq 1) 'Bounded property batching did not preserve ordered chunk boundaries.'
    Assert-True ($batches[0].command_utf8_bytes -le 256 -and $batches[1].command_utf8_bytes -le 256) 'Bounded property batching exceeded its byte limit.'
    $specialCommand = [string]$batches[0].command
    Assert-True ($specialCommand.Contains(' && ') -and $batches[0].command_utf8_bytes -eq [Text.Encoding]::UTF8.GetByteCount($specialCommand)) 'Fail-fast separator or its UTF-8 bytes were not included in the bounded batch command.'
    Assert-True ($specialCommand -like "*$quoted*") "Fake ADB did not receive safely escaped apostrophe/space property transport. Expected '$quoted'; observed '$specialCommand'."
    $damagedReadback = Test-QuestPropertyExactReadback -Entries $batchEntries -Observed @{ 'debug.test.one' = 'wrong'; 'debug.test.two' = "operator's value with spaces" }
    Assert-True ($damagedReadback.readbacks[0].status -eq 'mismatched' -and $damagedReadback.readbacks[2].status -eq 'missing') 'Exact property readback did not distinguish mismatch and missing values.'

    $profile = Join-Path $repo 'fixtures\runtime-profiles\quest-native-renderer-breathing-room-pmb-scale.profile.json'
    $dryPlanPath = Join-Path $temporary 'dry-plan.json'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Apply-RuntimeProfile.ps1') -ProfilePath $profile -DryRun -PropertyScopeMode CompleteManifest -Out $dryPlanPath *> $null
    Assert-True ($LASTEXITCODE -eq 0) 'Runtime profile dry plan failed.'
    $dryPlan = Get-Content -Raw -LiteralPath $dryPlanPath | ConvertFrom-Json -AsHashtable
    $state = [ordered]@{}
    foreach ($operation in @($dryPlan.operations)) { $state[[string]$operation.name] = "before-apply-$([string]$operation.name)" }
    $state | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fakeStatePath -Encoding UTF8
    Remove-Item -LiteralPath $fakeLogPath -Force -ErrorAction SilentlyContinue
    $appliedPlanPath = Join-Path $temporary 'applied-plan.json'
    $applyOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Apply-RuntimeProfile.ps1') -ProfilePath $profile -Execute -PropertyScopeMode CompleteManifest -Adb $fakeAdb -Serial 'serial-test' -AdbServerPort 5039 -Out $appliedPlanPath 2>&1)
    Assert-True ($LASTEXITCODE -eq 0) "Runtime profile bounded apply failed against fake ADB: $($applyOutput -join ' | ')"
    $appliedPlan = Get-Content -Raw -LiteralPath $appliedPlanPath | ConvertFrom-Json -AsHashtable
    Assert-True ($appliedPlan.execution_status -eq 'passed') 'Successful runtime profile transport did not publish passed status.'
    Assert-True ($appliedPlan.transport_summary.mode -eq 'ordered-batched-setprop' -and [int]$appliedPlan.transport_summary.setprop_batch_count -gt 1) 'Runtime profile did not report bounded batched transport.'
    Assert-True (@($appliedPlan.readbacks).Count -eq @($dryPlan.operations).Count -and @($appliedPlan.readbacks | Where-Object { $_.status -in @('missing', 'mismatched') }).Count -eq 0) 'Runtime profile did not retain complete exact final-property readback evidence.'
    $finalSetOperation = @($dryPlan.operations | Where-Object { [string]$_.kind -eq 'set' })[-1]
    $firstName = [string]$finalSetOperation.name
    $appliedState = Get-Content -Raw -LiteralPath $fakeStatePath | ConvertFrom-Json -AsHashtable
    Assert-True ($appliedState[$firstName] -eq [string]$finalSetOperation.value) 'Fake ADB did not apply the bounded property batch before complete readback.'
    foreach ($row in @(Get-Content -LiteralPath $fakeLogPath | ForEach-Object { $_ | ConvertFrom-Json -AsHashtable })) {
        Assert-True ($row.arguments[0] -eq '-P' -and $row.arguments[1] -eq '5039' -and $row.arguments[2] -eq '-s' -and $row.arguments[3] -eq 'serial-test') 'Runtime profile did not preserve explicit serial and ADB port on every fake ADB call.'
    }

    $env:RUSTY_QUEST_FAKE_ADB_MISMATCH_READBACK_NAME = $firstName
    $mismatchPlanPath = Join-Path $temporary 'mismatch-plan.json'
    $mismatchOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Apply-RuntimeProfile.ps1') -ProfilePath $profile -Execute -PropertyScopeMode CompleteManifest -Adb $fakeAdb -Serial 'serial-test' -AdbServerPort 5039 -Out $mismatchPlanPath 2>&1)
    $mismatchExitCode = $LASTEXITCODE
    Assert-True ($mismatchExitCode -ne 0) "Mismatched global readback did not fail for $firstName expected '$($finalSetOperation.value)': $($mismatchOutput -join ' | ')"
    $mismatchPlan = Get-Content -Raw -LiteralPath $mismatchPlanPath | ConvertFrom-Json -AsHashtable
    Assert-True ($mismatchPlan.execution_status -eq 'failed' -and @($mismatchPlan.readbacks | Where-Object { $_.status -eq 'mismatched' }).Count -eq 1) 'Mismatched readback did not retain per-property failure evidence.'
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_MISMATCH_READBACK_NAME -ErrorAction SilentlyContinue
    $env:RUSTY_QUEST_FAKE_ADB_OMIT_READBACK_NAME = $firstName
    $missingPlanPath = Join-Path $temporary 'missing-plan.json'
    $missingOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Apply-RuntimeProfile.ps1') -ProfilePath $profile -Execute -PropertyScopeMode CompleteManifest -Adb $fakeAdb -Serial 'serial-test' -AdbServerPort 5039 -Out $missingPlanPath 2>&1)
    $missingExitCode = $LASTEXITCODE
    Assert-True ($missingExitCode -ne 0) "Missing global readback did not fail for ${firstName}: $($missingOutput -join ' | ')"
    $missingPlan = Get-Content -Raw -LiteralPath $missingPlanPath | ConvertFrom-Json -AsHashtable
    Assert-True (@($missingPlan.readbacks | Where-Object { $_.status -eq 'missing' }).Count -eq 1) 'Missing readback did not retain per-property failure evidence.'
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_OMIT_READBACK_NAME -ErrorAction SilentlyContinue
    $intermediateEntries = @(
        [pscustomobject]@{ name = 'debug.intermediate.first'; value = 'first' },
        [pscustomobject]@{ name = 'debug.intermediate.fail'; value = 'fail' },
        [pscustomobject]@{ name = 'debug.intermediate.later'; value = 'later' }
    )
    $intermediateState = [ordered]@{
        'debug.intermediate.first' = 'before-first'
        'debug.intermediate.fail' = 'before-fail'
        'debug.intermediate.later' = 'before-later'
    }
    $intermediateState | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fakeStatePath -Encoding UTF8
    $intermediateBatch = @(New-QuestPropertySetpropBatches -Entries $intermediateEntries -MaxOperationsPerBatch 3 -MaxCommandUtf8Bytes 256)
    Assert-True ($intermediateBatch.Count -eq 1 -and $intermediateBatch[0].command.Contains(' && ')) 'Intermediate failure fixture did not produce one fail-fast batch.'
    $env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY = 'debug.intermediate.fail'
    $intermediateFailure = Invoke-ExpectedFailure 'intermediate-setprop-failure' { Invoke-QuestPropertySetpropBatches -Adb $fakeAdb -Serial 'serial-test' -AdbServerPort 5039 -Batches $intermediateBatch | Out-Null }
    Assert-True ($intermediateFailure.Length -gt 0) 'An intermediate setprop failure was accepted.'
    $intermediateObserved = Get-Content -Raw -LiteralPath $fakeStatePath | ConvertFrom-Json -AsHashtable
    Assert-True ($intermediateObserved['debug.intermediate.first'] -eq 'first' -and $intermediateObserved['debug.intermediate.fail'] -eq 'before-fail' -and $intermediateObserved['debug.intermediate.later'] -eq 'before-later') 'Fail-fast property batching did not stop later application after an intermediate failure.'
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY -ErrorAction SilentlyContinue
    $env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY = $firstName
    $batchFailurePath = Join-Path $temporary 'batch-failure-plan.json'
    $batchFailureOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Apply-RuntimeProfile.ps1') -ProfilePath $profile -Execute -PropertyScopeMode ProfileOwned -Adb $fakeAdb -Serial 'serial-test' -AdbServerPort 5039 -Out $batchFailurePath 2>&1)
    $batchFailureExitCode = $LASTEXITCODE
    Assert-True ($batchFailureExitCode -ne 0) "Nonzero property batch did not fail: $($batchFailureOutput -join ' | ')"
    $batchFailurePlan = Get-Content -Raw -LiteralPath $batchFailurePath | ConvertFrom-Json -AsHashtable
    Assert-True ($batchFailurePlan.execution_status -eq 'failed') 'Nonzero property batch did not publish a failed plan.'
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY -ErrorAction SilentlyContinue

    $recoveryState = [ordered]@{ 'debug.recovery.one' = 'changed'; 'debug.recovery.two' = 'changed' }
    $recoveryState | ConvertTo-Json | Set-Content -LiteralPath $fakeStatePath -Encoding UTF8
    $enteredPath = Join-Path $temporary 'entered.json'
    $terminalPath = Join-Path $temporary 'terminal.json'
    $serial = 'recovery-serial'
    $package = 'io.github.mesmerprism.rustyquest.recovery_test'
    [ordered]@{
        schema = 'rusty.quest.run_isolation_receipt.v1'; phase = 'entered'; status = 'active'; entered_at = '2026-08-22T00:00:00.0000000Z'
        serial = $serial; package_name = $package; mutex_name = Get-MutexName $serial
        property_snapshot = @(
            [ordered]@{ name = 'debug.recovery.one'; value = 'one' },
            [ordered]@{ name = 'debug.recovery.two'; value = 'two words' }
        )
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $enteredPath -Encoding UTF8
    & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName $package -EnteredReceiptPath $enteredPath -TerminalReceiptPath $terminalPath -Adb $fakeAdb -AdbServerPort 5039 *> $null
    Assert-True ($LASTEXITCODE -eq 0) 'Receipt-bound recovery failed against fake ADB.'
    $terminal = Get-Content -Raw -LiteralPath $terminalPath | ConvertFrom-Json -AsHashtable
    Assert-True ($terminal.phase -eq 'cleaned' -and $terminal.status -eq 'pass' -and $terminal.recovery.mode -eq 'receipt-bound-cross-process') 'Receipt-bound recovery did not publish a clean terminal receipt.'
    Assert-True (@($terminal.property_restore | Where-Object { $_.status -ne 'matched' }).Count -eq 0) 'Recovery did not verify exact property restoration.'
    $recoveredState = Get-Content -Raw -LiteralPath $fakeStatePath | ConvertFrom-Json -AsHashtable
    Assert-True ($recoveredState['debug.recovery.one'] -eq 'one' -and $recoveredState['debug.recovery.two'] -eq 'two words') 'Receipt-bound recovery did not restore the fake ADB property state.'
    $damagedPath = Join-Path $temporary 'damaged-entered.json'
    $damaged = Get-Content -Raw -LiteralPath $enteredPath | ConvertFrom-Json -AsHashtable
    $damaged['phase'] = 'cleaned'
    $damaged | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $damagedPath -Encoding UTF8
    $damagedOutput = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName $package -EnteredReceiptPath $damagedPath -TerminalReceiptPath (Join-Path $temporary 'damaged-terminal.json') -Adb $fakeAdb 2>&1)
    Assert-True ($LASTEXITCODE -ne 0) "Already-terminal recovery receipt was accepted: $($damagedOutput -join ' | ')"
    $duplicatePath = Join-Path $temporary 'duplicate-entered.json'
    $duplicate = Get-Content -Raw -LiteralPath $enteredPath | ConvertFrom-Json -AsHashtable
    $duplicate['property_snapshot'] = @(
        [ordered]@{ name = 'debug.recovery.one'; value = 'one' },
        [ordered]@{ name = 'debug.recovery.two'; value = 'two words' },
        [ordered]@{ name = 'debug.recovery.one'; value = 'other' }
    )
    $duplicate | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $duplicatePath -Encoding UTF8
    $duplicateMessage = Invoke-ExpectedFailure 'duplicate-snapshot' { & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName $package -EnteredReceiptPath $duplicatePath -TerminalReceiptPath (Join-Path $temporary 'duplicate-terminal.json') -Adb $fakeAdb *> $null; if ($LASTEXITCODE -eq 0) { throw 'missing expected process failure' } }
    Assert-True ($duplicateMessage.Length -gt 0) 'Duplicate recovery property names were accepted.'
    $mismatchMessage = Invoke-ExpectedFailure 'mismatched-package' { & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName 'io.github.mesmerprism.rustyquest.other' -EnteredReceiptPath $enteredPath -TerminalReceiptPath (Join-Path $temporary 'mismatch-terminal.json') -Adb $fakeAdb *> $null; if ($LASTEXITCODE -eq 0) { throw 'missing expected process failure' } }
    Assert-True ($mismatchMessage.Length -gt 0) 'Mismatched recovery package was accepted.'
    $mutex = [Threading.Mutex]::new($false, (Get-MutexName $serial))
    try {
        Assert-True ($mutex.WaitOne(0)) 'Host reliability test could not acquire its fixture mutex.'
        $concurrentMessage = Invoke-ExpectedFailure 'concurrent-owner' { & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tools\Recover-QuestRunIsolation.ps1') -ExpectedSerial $serial -ExpectedPackageName $package -EnteredReceiptPath $enteredPath -TerminalReceiptPath (Join-Path $temporary 'concurrent-terminal.json') -Adb $fakeAdb -MutexTimeoutSeconds 0 *> $null; if ($LASTEXITCODE -eq 0) { throw 'missing expected process failure' } }
        Assert-True ($concurrentMessage.Length -gt 0) 'Concurrently owned recovery receipt was accepted.'
    } finally {
        try { $mutex.ReleaseMutex() } catch {}
        $mutex.Dispose()
    }
} finally {
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_STATE -ErrorAction SilentlyContinue
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_FAIL_PROPERTY -ErrorAction SilentlyContinue
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_MISMATCH_READBACK_NAME -ErrorAction SilentlyContinue
    Remove-Item Env:RUSTY_QUEST_FAKE_ADB_OMIT_READBACK_NAME -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}

Write-Host 'Rusty Quest recording host reliability validation passed'
