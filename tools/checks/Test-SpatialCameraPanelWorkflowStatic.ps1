param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$workspaceRoot = Join-Path $repoRootPath "apps\spatial-camera-panel-android\morphospace"

function Read-JsonDocument {
    param([Parameter(Mandatory=$true)][string]$RelativePath)

    $path = Join-Path $workspaceRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Spatial Camera Panel workflow file: $RelativePath"
    }
    try {
        return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    } catch {
        throw "Invalid Spatial Camera Panel workflow JSON '$RelativePath': $($_.Exception.Message)"
    }
}

function Assert-EqualSet {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string[]]$Actual,
        [Parameter(Mandatory=$true)][string[]]$Expected
    )

    $actualValue = @($Actual | Sort-Object) -join "|"
    $expectedValue = @($Expected | Sort-Object) -join "|"
    if ($actualValue -ne $expectedValue) {
        throw "$Label mismatch. Expected '$expectedValue', got '$actualValue'."
    }
}

$spec = Read-JsonDocument "project.spec.json"
$lock = Read-JsonDocument "feature.lock.json"
$state = Read-JsonDocument "workspace.state.json"
$particle = Read-JsonDocument "module-candidates\surface-particle-substrate.json"
$hand = Read-JsonDocument "module-candidates\tracked-hand-substrate.json"
$wf003 = Read-JsonDocument "iteration-units\wf-003.json"
$mod001 = Read-JsonDocument "iteration-units\mod-001.json"
$receipt = Read-JsonDocument "receipts\wf-003-workspace-adoption.json"

if ($spec.schema -ne "rusty.morphospace.workflow.project_spec.v1" -or
    $lock.schema -ne "rusty.morphospace.workflow.feature_lock.v1" -or
    $state.schema -ne "rusty.morphospace.workflow.workspace_state.v1") {
    throw "Spatial Camera Panel workflow uses an unsupported schema."
}
if ($spec.project_id -ne "spatial-camera-panel" -or
    $lock.project_id -ne $spec.project_id -or
    $state.project_id -ne $spec.project_id) {
    throw "Spatial Camera Panel workflow project ids do not agree."
}
if ($spec.activation_model.default -ne "disabled" -or
    $spec.activation_model.unlisted_modules -ne "inert" -or
    $lock.default_activation -ne "disabled") {
    throw "Spatial Camera Panel workflow must fail closed for absent features."
}

$enabled = @($lock.features | Where-Object { $_.enabled -eq $true } | ForEach-Object { [string]$_.feature_id })
Assert-EqualSet -Label "Enabled workflow feature set" -Actual $enabled -Expected @("spatial-panel-shell")

$disabledExpected = @(
    "camera-hwb-projection",
    "surface-particle-runtime",
    "tracked-hand-surface",
    "spatial-stereo-video",
    "spatial-asset-model",
    "spatial-virtual-room"
)
$disabled = @($lock.features | Where-Object { $_.enabled -eq $false } | ForEach-Object { [string]$_.feature_id })
Assert-EqualSet -Label "Explicitly disabled workflow feature set" -Actual $disabled -Expected $disabledExpected

$moduleIds = @($spec.modules | ForEach-Object { [string]$_.module_id })
$featureIds = @($lock.features | ForEach-Object { [string]$_.feature_id })
if ($moduleIds -contains "remote-peer-media" -or $featureIds -contains "remote-peer-media") {
    throw "Remote peer media must remain absent and inert in the Spatial Camera Panel composition."
}

if (@($lock.features | Where-Object { $_.enabled -eq $true } | ForEach-Object { @($_.permissions) }).Count -ne 0) {
    throw "The enabled base panel shell must not gain permissions through workflow adoption."
}
if ($state.current_unit -ne $null -or $state.next_ready_unit -ne "mod-001") {
    throw "Spatial Camera Panel compact state must expose mod-001 as the only next unit."
}
if ($wf003.status -ne "accepted" -or $mod001.status -ne "ready" -or
    @($mod001.prerequisites) -notcontains "wf-003") {
    throw "Spatial Camera Panel iteration-unit state is not resumable."
}
if ($particle.maturity -ne "candidate" -or $particle.proposed_lane -ne "matter" -or
    $hand.maturity -ne "candidate" -or $hand.proposed_lane -ne "lattice") {
    throw "Spatial Camera Panel candidate lane declarations drifted."
}
if ($receipt.runtime_behavior_changed -ne $false -or $receipt.package_or_permission_changed -ne $false) {
    throw "WF-003 must remain a behavior-neutral workflow adoption."
}

$eventPath = Join-Path $workspaceRoot "iteration-events.jsonl"
$eventLines = @(Get-Content -LiteralPath $eventPath | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($eventLines.Count -ne 1) {
    throw "WF-003 adoption expects exactly one local projection event."
}
$event = $eventLines[0] | ConvertFrom-Json
if ($event.event_id -ne "wf-003-accepted" -or $event.unit_id -ne "wf-003") {
    throw "WF-003 local projection event is inconsistent."
}

Write-Host "Spatial Camera Panel workflow static gate passed"
