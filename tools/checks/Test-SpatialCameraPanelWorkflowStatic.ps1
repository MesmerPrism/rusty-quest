param(
    [string]$RepoRoot,
    [string]$RoadmapPath = $env:RUSTY_MORPHOSPACE_ROADMAP
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$spatialRoot = Join-Path $repoRootPath "apps\spatial-camera-panel-android\morphospace"
$nativeRoot = Join-Path $repoRootPath "apps\native-renderer-android\morphospace"

function Assert-Workflow {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Read-JsonDocument {
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$RelativePath)

    $path = Join-Path $Root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing project workflow file: $path"
    }
    try {
        return Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    } catch {
        throw "Invalid project workflow JSON '$path': $($_.Exception.Message)"
    }
}

function Read-JsonFiles {
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$RelativeDirectory)

    $path = Join-Path $Root $RelativeDirectory
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { return @() }
    return @(Get-ChildItem -LiteralPath $path -Filter "*.json" -File | Sort-Object Name | ForEach-Object {
        try { Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json }
        catch { throw "Invalid project workflow JSON '$($_.FullName)': $($_.Exception.Message)" }
    })
}

function Get-WorkflowSha256 {
    param([Parameter(Mandatory=$true)][string]$Path)

    $stream = [System.IO.FileStream]::new($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::Read)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace("-", "")
    } finally {
        $sha.Dispose()
        $stream.Dispose()
    }
}

function Read-ProjectionReceipt {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][string]$ReceiptName,
        [Parameter(Mandatory=$true)][string]$ExpectedProjectId,
        [Parameter(Mandatory=$true)][string]$ExpectedCanonicalUnit
    )

    $receipt = Read-JsonDocument -Root $Root -RelativePath "receipts\$ReceiptName"
    Assert-Workflow ([string]$receipt.schema -eq "rusty.quest.morphospace_projection_receipt.v1") "Projection receipt '$ReceiptName' has the wrong schema."
    Assert-Workflow ([string]$receipt.project_id -eq $ExpectedProjectId) "Projection receipt '$ReceiptName' has the wrong project_id."
    Assert-Workflow ([string]$receipt.canonical_unit_id -eq $ExpectedCanonicalUnit) "Projection receipt '$ReceiptName' has the wrong canonical unit."
    Assert-Workflow ([string]$receipt.unit_id -eq $ExpectedCanonicalUnit.ToLowerInvariant()) "Projection receipt '$ReceiptName' has the wrong local unit."
    Assert-Workflow ([string]$receipt.status -eq "accepted") "Projection receipt '$ReceiptName' is not accepted."
    Assert-Workflow ([string]$receipt.canonical_receipt -like "<planning-root>/*") "Projection receipt '$ReceiptName' must keep its private canonical receipt behind the planning-root placeholder."
    Assert-Workflow (@($receipt.public_evidence).Count -gt 0) "Projection receipt '$ReceiptName' has no public evidence."

    $rootPrefix = $repoRootPath.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    foreach ($evidence in @($receipt.public_evidence)) {
        $evidenceText = [string]$evidence
        Assert-Workflow (-not [string]::IsNullOrWhiteSpace($evidenceText)) "Projection receipt '$ReceiptName' contains empty public evidence."
        if ($evidenceText -match "[\\/]") {
            Assert-Workflow (-not [System.IO.Path]::IsPathRooted($evidenceText)) "Projection receipt '$ReceiptName' contains rooted public evidence '$evidenceText'."
            $evidencePath = [System.IO.Path]::GetFullPath((Join-Path $repoRootPath ($evidenceText -replace "/", "\")))
            Assert-Workflow ($evidencePath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) "Projection receipt '$ReceiptName' public evidence escapes the repository: '$evidenceText'."
            Assert-Workflow (Test-Path -LiteralPath $evidencePath) "Projection receipt '$ReceiptName' references missing public evidence '$evidenceText'."
        }
    }
    return $receipt
}

function Assert-EqualSet {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [object[]]$Actual = @(),
        [object[]]$Expected = @()
    )

    $actualValue = @($Actual | ForEach-Object { [string]$_ } | Sort-Object) -join "|"
    $expectedValue = @($Expected | ForEach-Object { [string]$_ } | Sort-Object) -join "|"
    if ($actualValue -ne $expectedValue) {
        throw "$Label mismatch. Expected '$expectedValue', got '$actualValue'."
    }
}

function Get-ById {
    param([object[]]$Items, [string]$Property, [string]$Id, [string]$Label)

    $matches = @($Items | Where-Object { [string]$_.$Property -eq $Id })
    if ($matches.Count -ne 1) { throw "$Label expects one '$Id' entry; found $($matches.Count)." }
    return $matches[0]
}

function Test-ProjectFeatureLockClosure {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][object]$Spec,
        [Parameter(Mandatory=$true)][object]$Lock
    )

    Assert-Workflow ([string]$Lock.schema -eq "rusty.morphospace.workflow.feature_lock.v1") "$Label default feature lock uses an unsupported schema."
    Assert-Workflow ([string]$Lock.project_id -eq [string]$Spec.project_id) "$Label default feature lock has the wrong project_id."
    Assert-Workflow ([string]$Lock.default_activation -eq "disabled") "$Label default feature lock must fail closed."
    Assert-EqualSet -Label "$Label project/lock feature projection" -Actual @($Lock.features.feature_id) -Expected @($Spec.modules.feature_id)

    $moduleIds = @($Spec.modules | ForEach-Object { [string]$_.module_id })
    $featureIds = @($Spec.modules | ForEach-Object { [string]$_.feature_id })
    Assert-Workflow (@($moduleIds | Sort-Object -Unique).Count -eq $moduleIds.Count) "$Label project spec repeats a module_id."
    Assert-Workflow (@($featureIds | Sort-Object -Unique).Count -eq $featureIds.Count) "$Label project spec repeats a feature_id."
    $lockModuleIds = @($Lock.features | ForEach-Object { [string]$_.module_id })
    $lockFeatureIds = @($Lock.features | ForEach-Object { [string]$_.feature_id })
    Assert-Workflow (@($lockModuleIds | Sort-Object -Unique).Count -eq $lockModuleIds.Count) "$Label default feature lock repeats a module_id."
    Assert-Workflow (@($lockFeatureIds | Sort-Object -Unique).Count -eq $lockFeatureIds.Count) "$Label default feature lock repeats a feature_id."

    foreach ($module in @($Spec.modules)) {
        $feature = Get-ById -Items @($Lock.features) -Property "feature_id" -Id ([string]$module.feature_id) -Label "$Label default feature lock"
        Assert-Workflow ([string]$feature.module_id -eq [string]$module.module_id) "$Label feature '$($module.feature_id)' points at the wrong module."
        Assert-EqualSet -Label "$Label module dependency projection for '$($module.module_id)'" -Actual @($feature.dependencies) -Expected @($module.dependencies)
        foreach ($dependency in @($module.dependencies)) {
            Assert-Workflow ($moduleIds -contains [string]$dependency) "$Label module '$($module.module_id)' has an unknown dependency '$dependency'."
        }
    }

    foreach ($feature in @($Lock.features | Where-Object { $_.enabled -eq $true })) {
        foreach ($dependency in @($feature.dependencies)) {
            $selectedDependency = Get-ById -Items @($Lock.features) -Property "module_id" -Id ([string]$dependency) -Label "$Label enabled dependency closure"
            Assert-Workflow ($selectedDependency.enabled -eq $true) "$Label enabled feature '$($feature.feature_id)' has disabled dependency '$dependency'."
        }
        foreach ($conflict in @($feature.conflicts)) {
            $selectedConflict = @($Lock.features | Where-Object { [string]$_.feature_id -eq [string]$conflict -and $_.enabled -eq $true })
            Assert-Workflow ($selectedConflict.Count -eq 0) "$Label enabled feature '$($feature.feature_id)' conflicts with enabled feature '$conflict'."
        }
    }
}

function Read-EventLog {
    param([Parameter(Mandatory=$true)][string]$Root)

    $path = Join-Path $Root "iteration-events.jsonl"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing event log: $path" }
    $events = New-Object System.Collections.Generic.List[object]
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $path) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $events.Add(($line | ConvertFrom-Json)) | Out-Null }
        catch { throw "Invalid event JSON at $path line $lineNumber`: $($_.Exception.Message)" }
    }
    return @($events.ToArray())
}

function Get-GitBlobSha256 {
    param(
        [Parameter(Mandatory=$true)][string]$GitRoot,
        [Parameter(Mandatory=$true)][string]$Blob
    )

    if ($Blob -notmatch '^[0-9a-fA-F]{40,64}$') {
        throw "Invalid historical Git blob id '$Blob'."
    }
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = "git"
    $escapedRoot = $GitRoot.Replace('"', '\"')
    $startInfo.Arguments = "-C `"$escapedRoot`" cat-file blob $Blob"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw "Unable to start git while reading historical blob '$Blob'." }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha.ComputeHash($process.StandardOutput.BaseStream)
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "Historical Git blob '$Blob' is unavailable: $errorText"
        }
        return [BitConverter]::ToString($hashBytes).Replace("-", "")
    } finally {
        $sha.Dispose()
        $process.Dispose()
    }
}

function Test-ProjectStateAndEvents {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][object]$Spec,
        [Parameter(Mandatory=$true)][object]$State,
        [Parameter(Mandatory=$true)][object[]]$Units
    )

    $events = @(Read-EventLog -Root $Root)
    Assert-Workflow ($events.Count -gt 0) "$($Spec.project_id) needs at least one event."
    Assert-Workflow ([string]$State.project_id -eq [string]$Spec.project_id) "$($Spec.project_id) compact state has the wrong project_id."
    Assert-Workflow ([int]$State.plan_revision -eq [int]$Spec.revision) "$($Spec.project_id) compact state plan_revision does not match the project spec."
    $unitIds = @($Units | ForEach-Object { [string]$_.unit_id })
    $seenEvents = @{}
    $previousSequence = 0
    $previousTimestamp = [DateTimeOffset]::MinValue
    foreach ($event in $events) {
        $eventId = [string]$event.event_id
        Assert-Workflow (-not [string]::IsNullOrWhiteSpace($eventId)) "$($Spec.project_id) event needs event_id."
        Assert-Workflow (-not $seenEvents.ContainsKey($eventId)) "$($Spec.project_id) repeats event '$eventId'."
        $seenEvents[$eventId] = $true
        Assert-Workflow ([int]$event.sequence -eq ($previousSequence + 1)) "$($Spec.project_id) event sequence is not contiguous at '$eventId'."
        $previousSequence = [int]$event.sequence
        if ($event.timestamp -is [DateTime]) {
            $timestamp = [DateTimeOffset]::new([DateTime]$event.timestamp)
        } else {
            $timestamp = [DateTimeOffset]::Parse(
                [string]$event.timestamp,
                [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::RoundtripKind
            )
        }
        Assert-Workflow ($timestamp -ge $previousTimestamp) "$($Spec.project_id) event timestamps move backward at '$eventId'."
        $previousTimestamp = $timestamp
        Assert-Workflow ([string]$event.project_id -eq [string]$Spec.project_id) "$($Spec.project_id) event '$eventId' has wrong project_id."
        if ($null -ne $event.unit_id) {
            Assert-Workflow ($unitIds -contains [string]$event.unit_id) "$($Spec.project_id) event '$eventId' references missing unit '$($event.unit_id)'."
        }
        foreach ($receipt in @($event.receipts)) {
            $receiptText = [string]$receipt
            if ($receiptText.StartsWith("receipts/") -or $receiptText.StartsWith("promotion-reviews/") -or $receiptText.StartsWith("mod-006-reviews/")) {
                Assert-Workflow (Test-Path -LiteralPath (Join-Path $Root ($receiptText -replace "/", "\")) -PathType Leaf) "$($Spec.project_id) event '$eventId' references missing local evidence '$receiptText'."
            }
        }
    }

    Assert-Workflow ([string]$State.last_event_id -eq [string]$events[-1].event_id) "$($Spec.project_id) state last_event_id is not the actual event tail."
    $terminalSpatialMod006 = (
        [string]$Spec.project_id -eq "spatial-camera-panel" -and
        [string]$State.last_event_id -eq "MOD-006-accepted-0016" -and
        $null -eq $State.current_unit
    )
    $inFlight = @($Units | Where-Object { [string]$_.status -in @("active", "validating") })
    if ($terminalSpatialMod006) {
        $inFlight = @($inFlight | Where-Object { [string]$_.unit_id -ne "mod-003" })
    }
    if ($null -eq $State.current_unit) {
        Assert-Workflow ($inFlight.Count -eq 0) "$($Spec.project_id) has an in-flight unit but no current_unit."
    } else {
        $current = @($inFlight | Where-Object { [string]$_.unit_id -eq [string]$State.current_unit })
        $historicalActiveExceptions = @()
        if (
            [string]$Spec.project_id -eq "spatial-camera-panel" -and
            @("mod-006", "mod-007") -contains [string]$State.current_unit
        ) {
            $historicalActiveExceptions = @("mod-003")
        }
        $unexpected = @($inFlight | Where-Object {
            [string]$_.unit_id -ne [string]$State.current_unit -and
            $historicalActiveExceptions -notcontains [string]$_.unit_id
        })
        Assert-Workflow ($current.Count -eq 1 -and $unexpected.Count -eq 0) "$($Spec.project_id) current_unit does not match its in-flight unit after immutable-history exceptions."
    }
    $readyUnits = @($Units | Where-Object { [string]$_.status -eq "ready" })
    if ($null -eq $State.next_ready_unit) {
        Assert-Workflow ($readyUnits.Count -eq 0) "$($Spec.project_id) has a ready unit but no next_ready_unit."
    } else {
        Assert-Workflow ($readyUnits.Count -eq 1 -and [string]$readyUnits[0].unit_id -eq [string]$State.next_ready_unit) "$($Spec.project_id) next_ready_unit does not match its sole ready unit."
    }
    if ($null -eq $State.current_unit) {
        if ($terminalSpatialMod006) {
            Assert-EqualSet -Label "$($Spec.project_id) accepted MOD-006 dirty repository preservation" -Actual @($State.dirty_repositories) -Expected @("native-renderer-app", "quest-repo", "spatial-app")
        } else {
            Assert-Workflow (@($State.dirty_repositories).Count -eq 0) "$($Spec.project_id) terminal compact state still claims dirty repositories."
        }
    } else {
        Assert-Workflow (@($State.dirty_repositories).Count -gt 0) "$($Spec.project_id) in-flight compact state erased its dirty repository projection."
    }
    Assert-Workflow (@($State.blockers).Count -eq 0) "$($Spec.project_id) compact state still carries a blocker after accepted projections."
    Assert-Workflow ($null -eq $State.pending_push_bundle) "$($Spec.project_id) compact state still carries a pending push bundle after its pushed projections."
    Assert-Workflow ([string]$State.validation_checkpoint.result -eq "pass") "$($Spec.project_id) terminal validation checkpoint is not pass."
    $checkpointReceipt = [string]$State.validation_checkpoint.receipt
    if ($checkpointReceipt.StartsWith("receipts/")) {
        Assert-Workflow (Test-Path -LiteralPath (Join-Path $Root ($checkpointReceipt -replace "/", "\")) -PathType Leaf) "$($Spec.project_id) validation checkpoint references missing '$checkpointReceipt'."
    }

    foreach ($unit in @($Units | Where-Object { [string]$_.status -eq "accepted" })) {
        $acceptedEvent = @($events | Where-Object {
            [string]$_.unit_id -eq [string]$unit.unit_id -and ([string]$_.event_id -match "(?i)^$([regex]::Escape([string]$unit.unit_id))-accepted($|-)")
        })
        Assert-Workflow ($acceptedEvent.Count -eq 1) "$($Spec.project_id) accepted unit '$($unit.unit_id)' needs exactly one accepted projection event."
    }

    return $events
}

function Test-Mod006Projection {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][string]$ExpectedProjectId,
        [Parameter(Mandatory=$true)][object[]]$Units
    )

    $unit = Get-ById -Items $Units -Property "unit_id" -Id "mod-006" -Label "$ExpectedProjectId MOD-006"
    if ($ExpectedProjectId -eq "spatial-camera-panel") {
        Assert-Workflow ([string]$unit.status -eq "accepted") "$ExpectedProjectId MOD-006 must be accepted after fresh two-Quest validation evidence."
        $workflowReceipt = Read-JsonDocument -Root $Root -RelativePath "receipts\mod-006-workflow-validation-twoquest-20260713.json"
        Assert-Workflow ([string]$workflowReceipt.result -eq "pass") "$ExpectedProjectId MOD-006 workflow validation receipt is not pass."
        $deviceReceipt = Read-JsonDocument -Root $Root -RelativePath "receipts\mod-006-twoquest-device-validation-20260713.json"
        Assert-Workflow ([string]$deviceReceipt.status -eq "passed") "$ExpectedProjectId MOD-006 two-Quest device receipt is not passed."
    } else {
        Assert-Workflow ([string]$unit.status -eq "validating") "$ExpectedProjectId MOD-006 must remain validating until fresh device evidence exists."
    }
    Assert-Workflow ([string]$unit.device_requirement -eq "required") "$ExpectedProjectId MOD-006 must retain its device gate."

    $receipt = Read-JsonDocument -Root $Root -RelativePath "receipts\mod-006-lock-bound-activation.json"
    Assert-Workflow ([string]$receipt.schema -eq "rusty.quest.mod006_lock_bound_activation_receipt.v1") "$ExpectedProjectId MOD-006 receipt has the wrong schema."
    Assert-Workflow ([string]$receipt.project_id -eq $ExpectedProjectId) "$ExpectedProjectId MOD-006 receipt has the wrong project id."
    Assert-Workflow ([string]$receipt.status -eq "source-validated-device-pending") "$ExpectedProjectId MOD-006 receipt must distinguish source validation from device acceptance."
    Assert-Workflow ([string]$receipt.device_gate.status -eq "pending") "$ExpectedProjectId MOD-006 receipt prematurely closed the device gate."
    Assert-Workflow (@($receipt.does_not_prove).Count -gt 0) "$ExpectedProjectId MOD-006 receipt lacks limitations."
    foreach ($evidence in @($receipt.source_evidence)) {
        $path = Join-Path $repoRootPath (([string]$evidence) -replace "/", "\")
        Assert-Workflow (Test-Path -LiteralPath $path) "$ExpectedProjectId MOD-006 references missing source evidence '$evidence'."
    }
    foreach ($entry in @($receipt.historical_fixture_hashes)) {
        $path = Join-Path $repoRootPath (([string]$entry.path) -replace "/", "\")
        Assert-Workflow (Test-Path -LiteralPath $path -PathType Leaf) "$ExpectedProjectId MOD-006 references missing historical fixture '$($entry.path)'."
        $actualHash = Get-WorkflowSha256 -Path $path
        Assert-Workflow ($actualHash -eq [string]$entry.sha256) "$ExpectedProjectId MOD-006 historical fixture '$($entry.path)' was rewritten."
        $historicalText = Get-Content -Raw -LiteralPath $path
        Assert-Workflow (-not $historicalText.Contains("lockBindingSchema=")) "$ExpectedProjectId MOD-006 retrofitted current lock evidence into historical fixture '$($entry.path)'."
    }
    if ($receipt.PSObject.Properties.Name -contains "historical_tracked_artifact_hashes") {
        foreach ($entry in @($receipt.historical_tracked_artifact_hashes)) {
            $path = Join-Path $repoRootPath (([string]$entry.path) -replace "/", "\")
            Assert-Workflow (Test-Path -LiteralPath $path -PathType Leaf) "$ExpectedProjectId MOD-006 historical tracked artifact '$($entry.path)' is missing."
            $actualHash = Get-WorkflowSha256 -Path $path
            if ($actualHash -ne [string]$entry.sha256) {
                Assert-Workflow (-not [string]::IsNullOrWhiteSpace([string]$entry.head_blob)) "$ExpectedProjectId MOD-006 historical tracked artifact '$($entry.path)' evolved without an immutable Git blob reference."
                $historicalHash = Get-GitBlobSha256 -GitRoot $repoRootPath -Blob ([string]$entry.head_blob)
                Assert-Workflow ($historicalHash -eq [string]$entry.sha256) "$ExpectedProjectId MOD-006 historical Git blob for '$($entry.path)' does not match its receipt digest."
            }
        }
    }
    if ($receipt.PSObject.Properties.Name -contains "historical_event_prefix") {
        $prefix = $receipt.historical_event_prefix
        $path = Join-Path $repoRootPath (([string]$prefix.path) -replace "/", "\")
        $lines = @(Get-Content -LiteralPath $path)
        $prefixCount = [int]$prefix.line_count
        Assert-Workflow ($lines.Count -ge $prefixCount) "$ExpectedProjectId MOD-006 historical event prefix was truncated."
        $sha = [System.Security.Cryptography.SHA256]::Create()
        for ($index = 0; $index -lt $prefixCount; $index++) {
            $actualHash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($lines[$index]))).Replace("-", "")
            Assert-Workflow ($actualHash -eq [string]$prefix.line_sha256[$index]) "$ExpectedProjectId MOD-006 historical event line $($index + 1) was rewritten."
        }
        foreach ($line in @($lines | Select-Object -Skip $prefixCount)) {
            $event = $line | ConvertFrom-Json
            $isCorrectiveEvent = [string]$event.unit_id -eq "mod-006" -and [string]$event.event_id -like "mod-006-*"
            $isMod007Event = [string]$event.unit_id -eq "mod-007" -and [string]$event.event_id -like "mod-007-*"
            $isExactSupersession = (
                [string]$event.event_id -eq "mod-003-superseded-by-mod-006" -and
                [string]$event.unit_id -eq "mod-003" -and
                [string]$event.event_type -eq "state-transition"
            )
            $isExactNet016Proposal = (
                [string]$event.event_id -eq "net-016-proposed" -and
                [string]$event.unit_id -eq "net-016" -and
                [string]$event.event_type -eq "planning" -and
                @($event.receipts).Count -eq 0
            )
            Assert-Workflow ($isCorrectiveEvent -or $isMod007Event -or $isExactSupersession -or $isExactNet016Proposal) "$ExpectedProjectId appended an event outside MOD-006/MOD-007, the exact additive MOD-003 supersession, or the inert NET-016 proposal."
        }
    }

    $reconciliation = $receipt.workflow_reconciliation
    Assert-Workflow ($null -ne $reconciliation) "$ExpectedProjectId MOD-006 lacks the additive WF-005 history reconciliation."
    Assert-Workflow ([string]$reconciliation.historical_spatial_state -eq "mod-003-active-not-accepted") "$ExpectedProjectId MOD-006 rewrote the historical MOD-003 state."
    Assert-Workflow ([string]$reconciliation.historical_local_mod004_projection -eq "absent" -and [string]$reconciliation.historical_local_mod005_projection -eq "absent") "$ExpectedProjectId MOD-006 fabricated historical MOD-004/MOD-005 local projections."
    Assert-Workflow ([string]$reconciliation.corrective_unit -eq "mod-006" -and [string]$reconciliation.history_policy -eq "preserve-exact-prefix-and-add-corrective-evidence") "$ExpectedProjectId MOD-006 history policy drifted."
    Assert-Workflow ($reconciliation.fabricated_retroactive_acceptance -eq $false) "$ExpectedProjectId MOD-006 claims a fabricated retroactive acceptance."
    foreach ($candidateId in @("surface-particle-substrate", "tracked-hand-substrate")) {
        $projection = Get-ById -Items @($reconciliation.current_candidate_projections) -Property "candidate_id" -Id $candidateId -Label "$ExpectedProjectId current candidate projection"
        Assert-Workflow ([string]$projection.maturity -eq "cross-consumer-source-validated-device-pending") "$ExpectedProjectId current candidate '$candidateId' maturity is not source-validated/device-pending."
        Assert-Workflow ([string]$projection.independent_consumer -eq "native-renderer") "$ExpectedProjectId current candidate '$candidateId' lacks the independent Native Renderer consumer."
    }

    $review = Read-JsonDocument -Root $Root -RelativePath "mod-006-reviews\lock-bound-activation-readiness.json"
    Assert-Workflow ([string]$review.schema -eq "rusty.quest.mod006_lock_bound_activation_review.v1") "$ExpectedProjectId MOD-006 review has the wrong schema."
    if ($ExpectedProjectId -eq "spatial-camera-panel") {
        Assert-Workflow ([string]$review.decision -eq "rework") "$ExpectedProjectId MOD-006 source-readiness review must remain the immutable pre-device review."
        Assert-Workflow ([string]$review.device_result -eq "pending") "$ExpectedProjectId MOD-006 source-readiness review was rewritten instead of preserving the later device receipt separately."
    } else {
        Assert-Workflow ([string]$review.decision -eq "rework") "$ExpectedProjectId MOD-006 review must remain rework until device validation."
        Assert-Workflow ([string]$review.device_result -eq "pending") "$ExpectedProjectId MOD-006 review prematurely claims device acceptance."
    }
}

function Test-ConformanceLock {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][object]$DefaultLock,
        [Parameter(Mandatory=$true)][string]$RelativePath,
        [Parameter(Mandatory=$true)][string]$ShellFeature,
        [Parameter(Mandatory=$true)][string]$TargetFeature
    )

    $lock = Read-JsonDocument -Root $Root -RelativePath $RelativePath
    Assert-Workflow ([string]$lock.schema -eq "rusty.morphospace.workflow.feature_lock.v1") "$RelativePath has wrong lock schema."
    Assert-Workflow ([string]$lock.project_id -eq [string]$DefaultLock.project_id) "$RelativePath has wrong project_id."
    $enabled = @($lock.features | Where-Object { $_.enabled -eq $true } | ForEach-Object { [string]$_.feature_id })
    Assert-EqualSet -Label "$RelativePath enabled features" -Actual $enabled -Expected @($ShellFeature, $TargetFeature)
    $defaultTarget = Get-ById -Items @($DefaultLock.features) -Property "feature_id" -Id $TargetFeature -Label "$RelativePath default target"
    $selectedTarget = Get-ById -Items @($lock.features) -Property "feature_id" -Id $TargetFeature -Label "$RelativePath selected target"
    Assert-Workflow ($defaultTarget.enabled -eq $false) "$RelativePath target '$TargetFeature' is not disabled in the default lock."
    Assert-Workflow ($selectedTarget.enabled -eq $true -and -not [string]::IsNullOrWhiteSpace([string]$selectedTarget.requested_by)) "$RelativePath target '$TargetFeature' is not explicitly requested."
    Assert-EqualSet -Label "$RelativePath target dependencies" -Actual @($selectedTarget.dependencies) -Expected @($defaultTarget.dependencies)
    Assert-EqualSet -Label "$RelativePath target permissions" -Actual @($selectedTarget.permissions) -Expected @($defaultTarget.permissions)
    Assert-EqualSet -Label "$RelativePath target routes" -Actual @($selectedTarget.routes) -Expected @($defaultTarget.routes)
    Assert-EqualSet -Label "$RelativePath target assets" -Actual @($selectedTarget.assets) -Expected @($defaultTarget.assets)
    foreach ($feature in @($lock.features | Where-Object { $_.enabled -eq $true })) {
        foreach ($dependency in @($feature.dependencies)) {
            $selectedDependency = Get-ById -Items @($lock.features) -Property "module_id" -Id ([string]$dependency) -Label "$RelativePath enabled dependency closure"
            Assert-Workflow ($selectedDependency.enabled -eq $true) "$RelativePath enabled feature '$($feature.feature_id)' has disabled dependency '$dependency'."
        }
        foreach ($conflict in @($feature.conflicts)) {
            $selectedConflict = @($lock.features | Where-Object { [string]$_.feature_id -eq [string]$conflict -and $_.enabled -eq $true })
            Assert-Workflow ($selectedConflict.Count -eq 0) "$RelativePath enables conflicting features '$($feature.feature_id)' and '$conflict'."
        }
    }
    return $lock
}

function Test-ConformanceLockIndex {
    param(
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][object]$DefaultLock,
        [Parameter(Mandatory=$true)][string[]]$ExpectedFeatures,
        [hashtable]$RuntimeBindingOverrides = @{}
    )

    $index = Read-JsonDocument -Root $Root -RelativePath "conformance-locks\index.json"
    Assert-Workflow ([string]$index.schema -eq "rusty.quest.morphospace_lock_index.v1") "$($index.project_id) has the wrong conformance-lock index schema."
    Assert-Workflow ([string]$index.project_id -eq [string]$DefaultLock.project_id) "$($index.project_id) conformance-lock index does not match the default project lock."
    Assert-EqualSet -Label "$($index.project_id) indexed conformance features" -Actual @($index.locks.feature_id) -Expected $ExpectedFeatures
    foreach ($entry in @($index.locks)) {
        $path = Join-Path (Join-Path $Root "conformance-locks") ([string]$entry.path)
        Assert-Workflow (Test-Path -LiteralPath $path -PathType Leaf) "$($index.project_id) lock index references missing '$($entry.path)'."
        $actualHash = Get-WorkflowSha256 -Path $path
        Assert-Workflow ($actualHash -eq [string]$entry.sha256) "$($index.project_id) conformance lock '$($entry.path)' digest drifted."
        $lock = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
        Assert-Workflow ([int]$lock.revision -eq [int]$entry.revision) "$($index.project_id) conformance lock '$($entry.path)' revision drifted."
        $selectedFeature = Get-ById -Items @($lock.features) -Property "feature_id" -Id ([string]$entry.feature_id) -Label "$($index.project_id) indexed conformance lock"
        Assert-Workflow ([string]$entry.expected_effective_marker -eq [string]$selectedFeature.activation_receipt.effective_marker) "$($index.project_id) indexed marker for '$($entry.feature_id)' drifted from the selected lock."
        $expectedRuntimeBinding = "source-validated-device-pending"
        if ($RuntimeBindingOverrides.ContainsKey([string]$entry.feature_id)) {
            $expectedRuntimeBinding = [string]$RuntimeBindingOverrides[[string]$entry.feature_id]
        }
        Assert-Workflow ([string]$entry.runtime_binding -eq $expectedRuntimeBinding) "$($index.project_id) runtime binding for '$($entry.feature_id)' must be '$expectedRuntimeBinding'."
    }
}

function Test-BrokerMediaWorkflowProjection {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Root,
        [Parameter(Mandatory=$true)][object]$Spec,
        [Parameter(Mandatory=$true)][object]$DefaultLock,
        [Parameter(Mandatory=$true)][object]$State,
        [Parameter(Mandatory=$true)][object[]]$Units,
        [Parameter(Mandatory=$true)][string]$ShellModule,
        [Parameter(Mandatory=$true)][string]$RenderOwner
    )

    $module = Get-ById -Items @($Spec.modules) -Property "module_id" -Id "broker-media-client" -Label "$Label project module"
    Assert-Workflow ([string]$module.feature_id -eq "broker-media-client") "$Label broker module and feature identities drifted."
    Assert-Workflow ([string]$module.source_repo -eq "quest-repo") "$Label broker-media-client must remain owned by the reusable Quest adapter lane."
    Assert-Workflow ([string]$module.maturity -eq "candidate") "$Label broker-media-client must remain a candidate until central NET-016 device acceptance."
    Assert-Workflow ([string]$module.contract -match '^rusty\.quest\.broker_media_lifecycle_receipt\.v[1-9][0-9]*$') "$Label broker-media-client has an unsupported lifecycle contract."
    Assert-EqualSet -Label "$Label broker-media-client module dependencies" -Actual @($module.dependencies) -Expected @($ShellModule)

    $streamAuthority = Get-ById -Items @($Spec.authority_map) -Property "parameter" -Id "stream.session" -Label "$Label authority map"
    $renderAuthority = Get-ById -Items @($Spec.authority_map) -Property "parameter" -Id "render.adoption" -Label "$Label authority map"
    Assert-Workflow ([string]$streamAuthority.owner -eq "manifold") "$Label stream.session authority escaped Manifold."
    Assert-Workflow ([string]$renderAuthority.owner -eq $RenderOwner) "$Label render.adoption authority escaped the application."

    $defaultFeature = Get-ById -Items @($DefaultLock.features) -Property "feature_id" -Id "broker-media-client" -Label "$Label default feature lock"
    Assert-Workflow ($defaultFeature.enabled -eq $false -and [string]::IsNullOrWhiteSpace([string]$defaultFeature.requested_by)) "$Label default broker-media-client must remain inert and unrequested."
    Assert-Workflow ([string]$defaultFeature.module_id -eq "broker-media-client") "$Label default broker feature points at the wrong module."
    Assert-EqualSet -Label "$Label default broker dependencies" -Actual @($defaultFeature.dependencies) -Expected @($ShellModule)
    $defaultParameterOwners = @($defaultFeature.parameter_authorities | ForEach-Object { "$($_.parameter)=$($_.owner)" })
    Assert-EqualSet -Label "$Label broker parameter authorities" -Actual $defaultParameterOwners -Expected @("stream.session=manifold", "render.adoption=$RenderOwner")
    Assert-Workflow ([string]$defaultFeature.activation_receipt.effective_marker -match '^rusty\.[a-z0-9_]+(?:\.[a-z0-9_]+)+\.effective$') "$Label broker activation marker is not a canonical dotted marker."

    $selected = Read-JsonDocument -Root $Root -RelativePath "conformance-locks\broker-media-client.feature.lock.json"
    $selectedFeature = Get-ById -Items @($selected.features) -Property "feature_id" -Id "broker-media-client" -Label "$Label broker conformance lock"
    Assert-Workflow ([string]$selectedFeature.requested_by -eq "iteration-unit:net-016") "$Label broker conformance lock is not requested by local NET-016."
    Assert-Workflow ([string]$selectedFeature.activation_receipt.effective_marker -eq [string]$defaultFeature.activation_receipt.effective_marker) "$Label broker conformance marker drifted from the inert default descriptor."

    $unit = Get-ById -Items $Units -Property "unit_id" -Id "net-016" -Label "$Label iteration unit"
    Assert-Workflow ([string]$unit.status -eq "proposed") "$Label local NET-016 must remain proposed until explicitly claimed."
    Assert-EqualSet -Label "$Label NET-016 prerequisites" -Actual @($unit.prerequisites) -Expected @("mod-006")
    Assert-Workflow ([string]$unit.device_requirement -eq "required") "$Label local NET-016 must retain its device gate."
    if ($Label -eq "Spatial") {
        $mod007 = @($Units | Where-Object { [string]$_.unit_id -eq "mod-007" })
        $terminalAfterMod006 =
            $null -eq $State.current_unit -and
            [string]$State.last_event_id -eq "MOD-006-accepted-0016"
        $mod007InFlight =
            $mod007.Count -eq 1 -and
            @("active", "validating") -contains [string]$mod007[0].status -and
            [string]$State.current_unit -eq "mod-007" -and
            [string]$State.last_event_id -like "mod-007-*"
        Assert-Workflow (($terminalAfterMod006 -or $mod007InFlight) -and $null -eq $State.next_ready_unit) "$Label must remain terminal after MOD-006 or identify MOD-007 as the sole in-flight unit before NET-016 claim."
    } else {
        Assert-Workflow ([string]$State.current_unit -eq "mod-006" -and $null -eq $State.next_ready_unit) "$Label must keep MOD-006 as the sole current unit before NET-016 claim."
        Assert-Workflow ([string]$State.last_event_id -eq "net-016-proposed") "$Label compact state does not terminate at the additive NET-016 proposal event."
    }
}

$spatialSpec = Read-JsonDocument -Root $spatialRoot -RelativePath "project.spec.json"
$spatialLock = Read-JsonDocument -Root $spatialRoot -RelativePath "feature.lock.json"
$spatialState = Read-JsonDocument -Root $spatialRoot -RelativePath "workspace.state.json"
$spatialCandidates = @(Read-JsonFiles -Root $spatialRoot -RelativeDirectory "module-candidates")
$spatialUnits = @(Read-JsonFiles -Root $spatialRoot -RelativeDirectory "iteration-units")
$spatialReviews = @(Read-JsonFiles -Root $spatialRoot -RelativeDirectory "promotion-reviews")

Assert-Workflow ([string]$spatialSpec.schema -eq "rusty.morphospace.workflow.project_spec.v1") "Spatial project spec uses an unsupported schema."
Assert-Workflow ([string]$spatialSpec.project_id -eq "spatial-camera-panel") "Spatial project id drifted."
Assert-Workflow ($spatialSpec.activation_model.default -eq "disabled" -and $spatialSpec.activation_model.unlisted_modules -eq "inert") "Spatial composition must fail closed."
Test-ProjectFeatureLockClosure -Label "Spatial" -Spec $spatialSpec -Lock $spatialLock
Assert-EqualSet -Label "Spatial default enabled features" -Actual @($spatialLock.features | Where-Object enabled -eq $true | ForEach-Object feature_id) -Expected @("spatial-panel-shell")
Assert-EqualSet -Label "Spatial default disabled features" -Actual @($spatialLock.features | Where-Object enabled -eq $false | ForEach-Object feature_id) -Expected @("camera-hwb-projection", "surface-particle-runtime", "tracked-hand-surface", "spatial-stereo-video", "spatial-asset-model", "spatial-virtual-room", "broker-media-client")
Assert-Workflow (@($spatialSpec.modules | Where-Object { $_.module_id -eq "remote-peer-media" }).Count -eq 0) "Remote peer media must remain absent from Spatial composition."

$particleCandidate = Get-ById -Items $spatialCandidates -Property "candidate_id" -Id "surface-particle-substrate" -Label "Spatial candidate"
$handCandidate = Get-ById -Items $spatialCandidates -Property "candidate_id" -Id "tracked-hand-substrate" -Label "Spatial candidate"
foreach ($candidate in @($particleCandidate, $handCandidate)) {
    Assert-Workflow ([string]$candidate.maturity -eq "candidate") "Historical candidate '$($candidate.candidate_id)' was retroactively promoted."
    Assert-Workflow ([string]$candidate.promotion_target -eq "contract-ready") "Historical candidate '$($candidate.candidate_id)' changed its original promotion edge."
    $module = Get-ById -Items @($spatialSpec.modules) -Property "module_id" -Id ([string]$candidate.module_id) -Label "Spatial module"
    Assert-Workflow ([string]$module.maturity -eq [string]$candidate.maturity) "Candidate '$($candidate.candidate_id)' and project module maturity disagree."
}
Assert-Workflow ($spatialReviews.Count -eq 0) "Historical candidates must not gain retroactive stable-promotion reviews."

$null = Test-ConformanceLock -Root $spatialRoot -DefaultLock $spatialLock -RelativePath "conformance-locks\particle-adapter.feature.lock.json" -ShellFeature "spatial-panel-shell" -TargetFeature "surface-particle-runtime"
$null = Test-ConformanceLock -Root $spatialRoot -DefaultLock $spatialLock -RelativePath "conformance-locks\hand-adapter.feature.lock.json" -ShellFeature "spatial-panel-shell" -TargetFeature "tracked-hand-surface"
$null = Test-ConformanceLock -Root $spatialRoot -DefaultLock $spatialLock -RelativePath "conformance-locks\broker-media-client.feature.lock.json" -ShellFeature "spatial-panel-shell" -TargetFeature "broker-media-client"
$spatialAssetLock = Test-ConformanceLock -Root $spatialRoot -DefaultLock $spatialLock -RelativePath "conformance-locks\spatial-asset-model.feature.lock.json" -ShellFeature "spatial-panel-shell" -TargetFeature "spatial-asset-model"
$spatialAssetFeature = Get-ById -Items @($spatialAssetLock.features) -Property "feature_id" -Id "spatial-asset-model" -Label "Spatial asset conformance lock"
Assert-Workflow ([string]$spatialAssetFeature.requested_by -eq "conformance-profile:spatial-asset-model") "Spatial asset conformance lock has the wrong selector."
Assert-Workflow ([string]$spatialAssetFeature.activation_receipt.schema -eq "rusty.quest.spatial_asset_model.activation_receipt.v1") "Spatial asset conformance lock has the wrong receipt schema."
Assert-Workflow ([string]$spatialAssetFeature.activation_receipt.effective_marker -eq "rusty.quest.spatial_asset_model.effective") "Spatial asset conformance lock has the wrong effective marker."
$null = Test-ConformanceLockIndex -Root $spatialRoot -DefaultLock $spatialLock -ExpectedFeatures @("surface-particle-runtime", "tracked-hand-surface", "broker-media-client", "spatial-asset-model") -RuntimeBindingOverrides @{"broker-media-client"="project-declared-source-wip-not-promotional"}
$spatialEvents = @(Test-ProjectStateAndEvents -Root $spatialRoot -Spec $spatialSpec -State $spatialState -Units $spatialUnits)

Test-Mod006Projection -Root $spatialRoot -ExpectedProjectId "spatial-camera-panel" -Units $spatialUnits
Test-BrokerMediaWorkflowProjection -Label "Spatial" -Root $spatialRoot -Spec $spatialSpec -DefaultLock $spatialLock -State $spatialState -Units $spatialUnits -ShellModule "spatial-panel-shell" -RenderOwner "spatial-app"

$nativeSpec = Read-JsonDocument -Root $nativeRoot -RelativePath "project.spec.json"
$nativeLock = Read-JsonDocument -Root $nativeRoot -RelativePath "feature.lock.json"
$nativeState = Read-JsonDocument -Root $nativeRoot -RelativePath "workspace.state.json"
$nativeUnits = @(Read-JsonFiles -Root $nativeRoot -RelativeDirectory "iteration-units")
Assert-Workflow ([string]$nativeSpec.project_id -eq "native-renderer") "Native Renderer project id drifted."
Assert-Workflow ($nativeSpec.activation_model.default -eq "disabled" -and $nativeSpec.activation_model.unlisted_modules -eq "inert") "Native Renderer composition must fail closed."
Test-ProjectFeatureLockClosure -Label "Native Renderer" -Spec $nativeSpec -Lock $nativeLock
Assert-EqualSet -Label "Native default enabled features" -Actual @($nativeLock.features | Where-Object enabled -eq $true | ForEach-Object feature_id) -Expected @("native-renderer-shell")
Assert-EqualSet -Label "Native default disabled features" -Actual @($nativeLock.features | Where-Object enabled -eq $false | ForEach-Object feature_id) -Expected @("particle-adapter-consumer", "hand-adapter-consumer", "broker-media-client")
$null = Test-ConformanceLock -Root $nativeRoot -DefaultLock $nativeLock -RelativePath "conformance-locks\particle-adapter.feature.lock.json" -ShellFeature "native-renderer-shell" -TargetFeature "particle-adapter-consumer"
$null = Test-ConformanceLock -Root $nativeRoot -DefaultLock $nativeLock -RelativePath "conformance-locks\hand-adapter.feature.lock.json" -ShellFeature "native-renderer-shell" -TargetFeature "hand-adapter-consumer"
$null = Test-ConformanceLock -Root $nativeRoot -DefaultLock $nativeLock -RelativePath "conformance-locks\broker-media-client.feature.lock.json" -ShellFeature "native-renderer-shell" -TargetFeature "broker-media-client"
$null = Test-ConformanceLockIndex -Root $nativeRoot -DefaultLock $nativeLock -ExpectedFeatures @("particle-adapter-consumer", "hand-adapter-consumer", "broker-media-client") -RuntimeBindingOverrides @{"broker-media-client"="project-declared-source-wip-not-promotional"}
$nativeEvents = @(Test-ProjectStateAndEvents -Root $nativeRoot -Spec $nativeSpec -State $nativeState -Units $nativeUnits)

Test-Mod006Projection -Root $nativeRoot -ExpectedProjectId "native-renderer" -Units $nativeUnits
Test-BrokerMediaWorkflowProjection -Label "Native Renderer" -Root $nativeRoot -Spec $nativeSpec -DefaultLock $nativeLock -State $nativeState -Units $nativeUnits -ShellModule "native-renderer-shell" -RenderOwner "native-renderer-app"

if (-not [string]::IsNullOrWhiteSpace($RoadmapPath)) {
    if (-not (Test-Path -LiteralPath $RoadmapPath -PathType Leaf)) { throw "RoadmapPath does not exist: $RoadmapPath" }
    $roadmap = Get-Content -Raw -LiteralPath $RoadmapPath | ConvertFrom-Json
    foreach ($unitId in @("MOD-003", "MOD-004", "MOD-005")) {
        $unit = Get-ById -Items @($roadmap.units) -Property "unit_id" -Id $unitId -Label "Canonical roadmap"
        Assert-Workflow ([string]$unit.status -eq "accepted") "Canonical roadmap unit '$unitId' is not accepted."
    }
    Write-Host "Spatial/Native workflow cross-ledger check passed: $RoadmapPath"
} else {
    Write-Host "Spatial/Native workflow cross-ledger check skipped: pass -RoadmapPath or set RUSTY_MORPHOSPACE_ROADMAP"
}

Write-Host "Spatial and Native Renderer Morphospace workflow static gate passed"
