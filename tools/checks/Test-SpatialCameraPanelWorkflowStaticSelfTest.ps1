param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$gate = Join-Path $repoRootPath "tools\checks\Test-SpatialCameraPanelWorkflowStatic.ps1"
$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$testRoot = Join-Path $tempBase ("rusty-quest-morphospace-workflow-selftest-" + [guid]::NewGuid().ToString("N"))

function Write-TestJson {
    param([string]$Path, [object]$Value)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 32) + [Environment]::NewLine), $encoding)
}

function New-TestRepo {
    param([string]$Name)
    $root = Join-Path $testRoot $Name
    & git clone --shared --no-checkout --quiet -- $repoRootPath $root
    if ($LASTEXITCODE -ne 0) { throw "Unable to create workflow self-test Git object store at '$root'." }
    $spatialTarget = Join-Path $root "apps\spatial-camera-panel-android"
    $nativeTarget = Join-Path $root "apps\native-renderer-android"
    New-Item -ItemType Directory -Path $spatialTarget, $nativeTarget -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRootPath "apps\spatial-camera-panel-android\morphospace") -Destination $spatialTarget -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRootPath "apps\native-renderer-android\morphospace") -Destination $nativeTarget -Recurse
    $publicEvidence = @(
        "crates\rusty-quest-particle-adapter",
        "crates\rusty-quest-hand-adapter",
        "fixtures\particle-adapter",
        "fixtures\hand-adapter",
        "fixtures\runtime-profiles\quest-native-renderer-particle-adapter-conformance.profile.json",
        "fixtures\runtime-profiles\quest-spatial-camera-panel-particle-adapter-conformance.profile.json",
        "fixtures\runtime-profiles\quest-spatial-camera-panel-hand-adapter-conformance.profile.json",
        "fixtures\native-app-builds\native-openxr-hand-lab.app.json",
        "apps\native-renderer-android\native\src\particle_adapter_consumer.rs",
        "apps\native-renderer-android\native\src\hand_adapter_consumer.rs",
        "apps\spatial-camera-panel-android\native-receipt\src\particle_adapter_consumer.rs",
        "apps\spatial-camera-panel-android\native-receipt\src\hand_adapter_consumer.rs"
        "apps\spatial-camera-panel-android\native-receipt\src\adapter_lock_authority.rs"
        "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterNativeAuthority.kt"
        "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterLockBinding.kt"
        "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleRuntimeCoordinator.kt"
        "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleParameterCoordinator.kt"
        "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleProjectionUpdateCoordinator.kt"
        "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialAdapterDecisionCacheTest.kt"
        "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialSurfaceParticleRuntimeCoordinatorTest.kt"
        "apps\native-renderer-android\native\src\lib.rs"
        "crates\rusty-quest-particle-adapter\src\lock_bound_activation.rs"
        "crates\rusty-quest-hand-adapter\src\lock_bound_activation.rs"
    )
    foreach ($relativePath in $publicEvidence) {
        $source = Join-Path $repoRootPath $relativePath
        $target = Join-Path $root $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $target -Recurse
    }
    return $root
}

function Invoke-Gate {
    param([string]$Root)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $gate -RepoRoot $Root 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ exit_code = $exitCode; output = ($output -join [Environment]::NewLine) }
}

function Assert-SelfTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Spatial workflow self-test failed: $Message" }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

    $baseline = New-TestRepo -Name "baseline"
    $baselineResult = Invoke-Gate -Root $baseline
    Assert-SelfTest ($baselineResult.exit_code -eq 0) "baseline did not pass: $($baselineResult.output)"

    $stale = New-TestRepo -Name "stale-state"
    $staleStatePath = Join-Path $stale "apps\spatial-camera-panel-android\morphospace\workspace.state.json"
    $staleState = Get-Content -Raw -LiteralPath $staleStatePath | ConvertFrom-Json
    $staleState.current_unit = "mod-003"
    $staleState.last_event_id = "mod-003-active"
    $staleState.dirty_repositories = @("spatial-app")
    Write-TestJson -Path $staleStatePath -Value $staleState
    Assert-SelfTest ((Invoke-Gate -Root $stale).exit_code -ne 0) "stale current/event/dirty state was accepted"

    $digest = New-TestRepo -Name "digest-drift"
    $digestLock = Join-Path $digest "apps\native-renderer-android\morphospace\conformance-locks\particle-adapter.feature.lock.json"
    [System.IO.File]::AppendAllText($digestLock, " ", [System.Text.Encoding]::UTF8)
    Assert-SelfTest ((Invoke-Gate -Root $digest).exit_code -ne 0) "conformance-lock digest drift was accepted"

    $marker = New-TestRepo -Name "marker-drift"
    $markerIndexPath = Join-Path $marker "apps\native-renderer-android\morphospace\conformance-locks\index.json"
    $markerIndex = Get-Content -Raw -LiteralPath $markerIndexPath | ConvertFrom-Json
    $markerIndex.locks[0].expected_effective_marker = "rusty.quest.invalid.effective"
    Write-TestJson -Path $markerIndexPath -Value $markerIndex
    Assert-SelfTest ((Invoke-Gate -Root $marker).exit_code -ne 0) "effective-marker drift was accepted"

    $missingBrokerModule = New-TestRepo -Name "missing-broker-module"
    $missingBrokerSpecPath = Join-Path $missingBrokerModule "apps\spatial-camera-panel-android\morphospace\project.spec.json"
    $missingBrokerSpec = Get-Content -Raw -LiteralPath $missingBrokerSpecPath | ConvertFrom-Json
    $missingBrokerSpec.modules = @($missingBrokerSpec.modules | Where-Object { [string]$_.module_id -ne "broker-media-client" })
    Write-TestJson -Path $missingBrokerSpecPath -Value $missingBrokerSpec
    Assert-SelfTest ((Invoke-Gate -Root $missingBrokerModule).exit_code -ne 0) "default broker feature without a project module was accepted"

    $implicitBroker = New-TestRepo -Name "implicit-broker-activation"
    $implicitBrokerLockPath = Join-Path $implicitBroker "apps\native-renderer-android\morphospace\feature.lock.json"
    $implicitBrokerLock = Get-Content -Raw -LiteralPath $implicitBrokerLockPath | ConvertFrom-Json
    ($implicitBrokerLock.features | Where-Object { [string]$_.feature_id -eq "broker-media-client" }).enabled = $true
    Write-TestJson -Path $implicitBrokerLockPath -Value $implicitBrokerLock
    Assert-SelfTest ((Invoke-Gate -Root $implicitBroker).exit_code -ne 0) "implicit default broker activation was accepted"

    $danglingBroker = New-TestRepo -Name "dangling-broker-dependency"
    $danglingBrokerLockPath = Join-Path $danglingBroker "apps\spatial-camera-panel-android\morphospace\conformance-locks\broker-media-client.feature.lock.json"
    $danglingBrokerLock = Get-Content -Raw -LiteralPath $danglingBrokerLockPath | ConvertFrom-Json
    ($danglingBrokerLock.features | Where-Object { [string]$_.feature_id -eq "broker-media-client" }).dependencies = @("missing-shell")
    Write-TestJson -Path $danglingBrokerLockPath -Value $danglingBrokerLock
    $danglingBrokerIndexPath = Join-Path $danglingBroker "apps\spatial-camera-panel-android\morphospace\conformance-locks\index.json"
    $danglingBrokerIndex = Get-Content -Raw -LiteralPath $danglingBrokerIndexPath | ConvertFrom-Json
    ($danglingBrokerIndex.locks | Where-Object { [string]$_.feature_id -eq "broker-media-client" }).sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $danglingBrokerLockPath).Hash
    Write-TestJson -Path $danglingBrokerIndexPath -Value $danglingBrokerIndex
    Assert-SelfTest ((Invoke-Gate -Root $danglingBroker).exit_code -ne 0) "dangling broker conformance dependency was accepted"

    $prematureBrokerMaturity = New-TestRepo -Name "premature-broker-maturity"
    $prematureBrokerIndexPath = Join-Path $prematureBrokerMaturity "apps\native-renderer-android\morphospace\conformance-locks\index.json"
    $prematureBrokerIndex = Get-Content -Raw -LiteralPath $prematureBrokerIndexPath | ConvertFrom-Json
    ($prematureBrokerIndex.locks | Where-Object { [string]$_.feature_id -eq "broker-media-client" }).runtime_binding = "device-promoted"
    Write-TestJson -Path $prematureBrokerIndexPath -Value $prematureBrokerIndex
    Assert-SelfTest ((Invoke-Gate -Root $prematureBrokerMaturity).exit_code -ne 0) "premature broker runtime maturity was accepted"

    $prematureBrokerUnit = New-TestRepo -Name "premature-broker-unit"
    $prematureBrokerUnitPath = Join-Path $prematureBrokerUnit "apps\spatial-camera-panel-android\morphospace\iteration-units\net-016.json"
    $prematureBrokerUnitDocument = Get-Content -Raw -LiteralPath $prematureBrokerUnitPath | ConvertFrom-Json
    $prematureBrokerUnitDocument.status = "ready"
    Write-TestJson -Path $prematureBrokerUnitPath -Value $prematureBrokerUnitDocument
    Assert-SelfTest ((Invoke-Gate -Root $prematureBrokerUnit).exit_code -ne 0) "NET-016 became ready while MOD-006 remained active"

    $promotion = New-TestRepo -Name "false-stable"
    $reviewPath = Join-Path $promotion "apps\spatial-camera-panel-android\morphospace\promotion-reviews\surface-particle-stable-readiness.json"
    New-Item -ItemType Directory -Path (Split-Path -Parent $reviewPath) -Force | Out-Null
    $review = [pscustomobject]@{ review_id = "retroactive-stable"; decision = "accepted" }
    Write-TestJson -Path $reviewPath -Value $review
    Assert-SelfTest ((Invoke-Gate -Root $promotion).exit_code -ne 0) "retroactive stable-promotion review was accepted"

    $missing = New-TestRepo -Name "missing-receipt"
    Remove-Item -LiteralPath (Join-Path $missing "apps\native-renderer-android\morphospace\receipts\mod-006-lock-bound-activation.json")
    Assert-SelfTest ((Invoke-Gate -Root $missing).exit_code -ne 0) "missing MOD-006 receipt was accepted"

    $missingEvidence = New-TestRepo -Name "missing-public-evidence"
    $evidenceReceiptPath = Join-Path $missingEvidence "apps\native-renderer-android\morphospace\receipts\mod-006-lock-bound-activation.json"
    $evidenceReceipt = Get-Content -Raw -LiteralPath $evidenceReceiptPath | ConvertFrom-Json
    $evidenceReceipt.source_evidence[0] = "fixtures/particle-adapter/missing.json"
    Write-TestJson -Path $evidenceReceiptPath -Value $evidenceReceipt
    Assert-SelfTest ((Invoke-Gate -Root $missingEvidence).exit_code -ne 0) "missing MOD-006 source evidence was accepted"

    $rewrittenHistory = New-TestRepo -Name "rewritten-historical-fixture"
    $historicalFixturePath = Join-Path $rewrittenHistory "fixtures\hand-adapter\native-hand-accepted.txt"
    [System.IO.File]::AppendAllText($historicalFixturePath, " lockBindingSchema=retrofit", [System.Text.Encoding]::UTF8)
    Assert-SelfTest ((Invoke-Gate -Root $rewrittenHistory).exit_code -ne 0) "rewritten historical accepted fixture was accepted"

    $rewrittenHistoricalUnit = New-TestRepo -Name "rewritten-historical-unit"
    $historicalUnitPath = Join-Path $rewrittenHistoricalUnit "apps\spatial-camera-panel-android\morphospace\iteration-units\mod-003.json"
    $historicalUnit = Get-Content -Raw -LiteralPath $historicalUnitPath | ConvertFrom-Json
    $historicalUnit.status = "accepted"
    Write-TestJson -Path $historicalUnitPath -Value $historicalUnit
    Assert-SelfTest ((Invoke-Gate -Root $rewrittenHistoricalUnit).exit_code -ne 0) "rewritten historical MOD-003 unit was accepted"

    $damagedSupersession = New-TestRepo -Name "damaged-supersession-event"
    $damagedEventPath = Join-Path $damagedSupersession "apps\spatial-camera-panel-android\morphospace\iteration-events.jsonl"
    $damagedLines = @(Get-Content -LiteralPath $damagedEventPath)
    $damagedIndex = -1
    for ($index = 0; $index -lt $damagedLines.Count; $index++) {
        $candidate = $damagedLines[$index] | ConvertFrom-Json
        if ([string]$candidate.event_id -eq "mod-003-superseded-by-mod-006") {
            $damagedIndex = $index
            break
        }
    }
    Assert-SelfTest ($damagedIndex -ge 0) "MOD-003 supersession event is missing from the baseline fixture"
    $damagedEvent = $damagedLines[$damagedIndex] | ConvertFrom-Json
    $damagedEvent.event_type = "validation"
    $damagedLines[$damagedIndex] = $damagedEvent | ConvertTo-Json -Compress -Depth 16
    [System.IO.File]::WriteAllLines($damagedEventPath, $damagedLines, [System.Text.UTF8Encoding]::new($false))
    Assert-SelfTest ((Invoke-Gate -Root $damagedSupersession).exit_code -ne 0) "damaged MOD-003 supersession event was accepted"

    $fabricatedReconciliation = New-TestRepo -Name "fabricated-history-reconciliation"
    $reconciliationPath = Join-Path $fabricatedReconciliation "apps\spatial-camera-panel-android\morphospace\receipts\mod-006-lock-bound-activation.json"
    $reconciliationReceipt = Get-Content -Raw -LiteralPath $reconciliationPath | ConvertFrom-Json
    $reconciliationReceipt.workflow_reconciliation.historical_local_mod004_projection = "accepted"
    $reconciliationReceipt.workflow_reconciliation.fabricated_retroactive_acceptance = $true
    Write-TestJson -Path $reconciliationPath -Value $reconciliationReceipt
    Assert-SelfTest ((Invoke-Gate -Root $fabricatedReconciliation).exit_code -ne 0) "fabricated MOD-004 history reconciliation was accepted"

    $prematureMod006 = New-TestRepo -Name "premature-mod006-review"
    $mod006ReviewPath = Join-Path $prematureMod006 "apps\native-renderer-android\morphospace\mod-006-reviews\lock-bound-activation-readiness.json"
    $mod006Review = Get-Content -Raw -LiteralPath $mod006ReviewPath | ConvertFrom-Json
    $mod006Review.decision = "accepted"
    Write-TestJson -Path $mod006ReviewPath -Value $mod006Review
    Assert-SelfTest ((Invoke-Gate -Root $prematureMod006).exit_code -ne 0) "premature MOD-006 acceptance was accepted"

    Write-Host "Spatial/Native workflow static self-test passed."
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = (Resolve-Path -LiteralPath $testRoot).Path
        if (-not $resolved.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean a workflow self-test directory outside the system temporary directory."
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

# Invoke-Gate intentionally runs many failing damaged-input probes. A passing
# self-test must not leak the final expected child-process failure to callers
# that inspect LASTEXITCODE after invoking this script.
$global:LASTEXITCODE = 0
