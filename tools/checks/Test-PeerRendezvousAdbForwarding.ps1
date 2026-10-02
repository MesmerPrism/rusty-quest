[CmdletBinding()]
param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$tokens = $null; $errors = $null
$pairPath = Join-Path $RepoRoot 'tools/Invoke-PeerRendezvousAndroidPair.ps1'
$ast = [Management.Automation.Language.Parser]::ParseFile($pairPath, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Pair syntax errors' }
$role = $ast.Find({param($n) $n -is [Management.Automation.Language.AssignmentStatementAst] -and
    $n.Left -is [Management.Automation.Language.VariableExpressionAst] -and $n.Left.VariablePath.UserPath -eq 'roleJob'}, $true)
if ($null -eq $role) { throw 'Actual role job missing' }
$roleJob = $role.Right.Expression.ScriptBlock.GetScriptBlock()
$starts = @($ast.FindAll({param($n) $n -is [Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Start-Job'}, $true))
if ($starts.Count -ne 2) { throw 'Expected exact server/client production dispatch commands' }
$default = @($ast.ParamBlock.Parameters | Where-Object {$_.Name.VariablePath.UserPath -eq 'Adb'})[0].DefaultValue.SafeGetValue()
if ($default -cne 'S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe') { throw 'Default ADB behavior changed' }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('peer-adb-forward-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$smokePath = Join-Path $fixture 'stub smoke.ps1'
[IO.File]::WriteAllText($smokePath, @'
param($Serial,$CoordinationMode,$Mode,$RunId,$SessionTag,$PeerTag,$SharedSecret,$DurationSeconds,$RolePreference,$ApkPath,$OutDir,$Adb,$QuestLeaseId,[switch]$SkipInstall)
if([string]::IsNullOrWhiteSpace($Adb)){throw 'Selected ADB was omitted'}
[pscustomobject]@{serial=$Serial;mode=$Mode;adb=$Adb;run=$RunId;coordination=$CoordinationMode;lease=$QuestLeaseId;skip=[bool]$SkipInstall;out=$OutDir;apk=$ApkPath}
'@, [Text.UTF8Encoding]::new($false))
$repoRoot = $RepoRoot; $ApkPath = Join-Path $fixture 'inert.apk'; $RunId = 'fixture-pair'; $Suffix = 'a'
$SharedSecret = 'inert-fixture-secret-never-a-device'; $CoordinationMode = 'user_authorized_serial_scoped'
$ServerSerial = 'fixture-server'; $ClientSerial = 'fixture-client'; $ServerLeaseId = ''; $ClientLeaseId = ''
$ServerPeerTag = 'fixture-s'; $ClientPeerTag = 'fixture-c'; $ServerRolePreference = 'group_owner'; $ClientRolePreference = 'client'
$ServerDurationSeconds = 35; $ClientDurationSeconds = 30; $ServerRunId = 'fixture-server-run'; $ClientRunId = 'fixture-client-run'
$sessionTag = 'fixture-session'; $serverDir = Join-Path $fixture 'server'; $clientDir = Join-Path $fixture 'client'
$passed = 0
foreach ($selected in @((Join-Path $fixture 'alternate provider 空白/adb executable.ps1'), $default)) {
    $Adb = $selected; $SkipPhaseInstall = ($selected -cne $default)
    $jobs = @()
    try {
        foreach ($start in $starts) { $jobs += & ([ScriptBlock]::Create($start.Extent.Text)) }
        Wait-Job -Job $jobs -Timeout 30 | Out-Null
        for ($i=0; $i -lt 2; $i++) {
            if ($jobs[$i].State -ne 'Completed') { throw 'Production role job did not complete' }
            $rows = @(Receive-Job -Job $jobs[$i] -ErrorAction Stop)
            if ($rows.Count -ne 1) { throw 'Unexpected role output' }
            $row = $rows[0]; $expectedMode = @('server','client')[$i]; $expectedSerial = @($ServerSerial,$ClientSerial)[$i]
            if ($row.adb -cne $selected -or $row.mode -cne $expectedMode -or $row.serial -cne $expectedSerial -or
                $row.coordination -cne $CoordinationMode -or $row.skip -ne $SkipPhaseInstall -or $row.apk -cne $ApkPath -or
                $row.out -cne @($serverDir,$clientDir)[$i] -or -not [string]::IsNullOrEmpty($row.lease)) { throw 'Actual role argument propagation differs' }
            $passed++
        }
    } finally {
        foreach ($job in $jobs) { if ($job.State -notin @('Completed','Failed','Stopped')) {Stop-Job $job}; Remove-Job $job -Force }
    }
}
# A missing selection must reach the stub as missing and fail, never silently
# use its own executable default. This reproduces the prior dropped argument.
$Adb = ''
$negative = & ([ScriptBlock]::Create($starts[0].Extent.Text))
try {
    Wait-Job -Job $negative -Timeout 30 | Out-Null
    $failure = $null
    try { Receive-Job $negative -ErrorAction Stop | Out-Null } catch { $failure = $_ }
    if ($null -eq $failure -or $failure.ToString() -notmatch 'Selected ADB was omitted') { throw 'Missing ADB selection did not fail closed' }
    $passed++
} finally { if($negative.State -notin @('Completed','Failed','Stopped')){Stop-Job $negative};Remove-Job $negative -Force }
# Execute the actual smoke transport function with an inert script path. It
# proves the selected value is used at the lowest ADB invocation, not just passed.
$smokeAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Invoke-PeerRendezvousAndroidSmoke.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Smoke syntax errors' }
$invoke = $smokeAst.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Adb'}, $true)
. ([ScriptBlock]::Create($invoke.Extent.Text))
$Adb = Join-Path $fixture 'alternate adb.ps1'; $Serial = 'fixture-only'
[IO.File]::WriteAllText($Adb, "[pscustomobject]@{arguments=@(`$args)}; `$global:LASTEXITCODE=0", [Text.UTF8Encoding]::new($false))
$result = Invoke-Adb -Arguments @('shell','closed-fixture-read')
if ($result.exit_code -ne 0 -or ($result.output[0].arguments -join '|') -cne '-s|fixture-only|shell|closed-fixture-read') { throw 'Smoke selected executable dispatch differs' }
$passed++
[IO.File]::WriteAllText($Adb, "'fixture selected executable failure'; `$global:LASTEXITCODE=7", [Text.UTF8Encoding]::new($false))
$failure = $null
try { Invoke-Adb -Arguments @('shell','closed-fixture-read') | Out-Null } catch { $failure = $_ }
if ($null -eq $failure -or $failure.ToString() -notmatch 'fixture selected executable failure') { throw 'Selected executable failure was hidden' }
$allowed = Invoke-Adb -Arguments @('shell','closed-fixture-read') -AllowFailure
if ($allowed.exit_code -ne 7) { throw 'Selected executable nonzero status was not preserved' }
$passed += 2
Write-Output "peer_adb_forwarding=pass cases=$passed device_calls=0 fixture=$fixture"
