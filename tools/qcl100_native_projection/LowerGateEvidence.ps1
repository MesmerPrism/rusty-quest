# Dot-sourced helper functions for QCL100 lower-gate evidence validation.

function Get-Qcl100LowerGateEvidenceProperty {
    param(
        $Object,
        [string]$Name
    )
    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Get-Qcl100LowerGateEvidencePathValue {
    param(
        $Object,
        [string]$Path
    )
    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }
    $current = $Object
    foreach ($part in @($Path -split "\.")) {
        if ($null -eq $current -or [string]::IsNullOrWhiteSpace($part)) {
            return $null
        }
        $current = Get-Qcl100LowerGateEvidenceProperty -Object $current -Name $part
    }
    return $current
}

function Get-Qcl100LowerGateEvidenceFirstPathValue {
    param(
        $Object,
        [string[]]$Paths
    )
    foreach ($path in $Paths) {
        $value = Get-Qcl100LowerGateEvidencePathValue -Object $Object -Path $path
        if ($null -ne $value) {
            return $value
        }
    }
    return $null
}

function Get-Qcl100LowerGateEvidenceBool {
    param($Value)
    if ($null -eq $Value) {
        return $false
    }
    if ($Value -is [bool]) {
        return [bool]$Value
    }
    $text = ([string]$Value).Trim()
    if ($text.Equals("true", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    if ($text.Equals("false", [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return [bool]$Value
}

function Get-Qcl100LowerGateEvidenceInt {
    param($Value)
    try {
        if ($null -eq $Value) {
            return 0
        }
        return [int]$Value
    } catch {
        return 0
    }
}

function Get-Qcl100LowerGateEvidenceAuthority {
    param([string]$Authority)
    $normalized = ([string]$Authority).Trim().ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return "android_connectivitymanager_network"
    }
    if ($normalized -eq "rusty-direct-p2p-socket-authority" -or
            $normalized -eq "rusty_direct_network_authority" -or
            $normalized -eq "rusty-direct-network-authority") {
        return "rusty_direct_p2p_socket_authority"
    }
    if ($normalized -ne "android_connectivitymanager_network" -and
            $normalized -ne "rusty_direct_p2p_socket_authority") {
        throw "Unknown QCL100 lower-gate authority: $Authority"
    }
    return $normalized
}

function Get-Qcl100LowerGateEvidenceComparablePath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    try {
        if (Test-Path -LiteralPath $Path) {
            return (Resolve-Path -LiteralPath $Path).Path
        }
        return [System.IO.Path]::GetFullPath($Path)
    } catch {
        return $Path
    }
}

function Get-Qcl100LowerGateEvidencePlanStep {
    param(
        $Plan,
        [string]$GateId
    )
    if ($null -eq $Plan -or [string]::IsNullOrWhiteSpace($GateId)) {
        return $null
    }
    foreach ($step in @((Get-Qcl100LowerGateEvidenceProperty -Object $Plan -Name "lower_gate_sequence"))) {
        $id = [string](Get-Qcl100LowerGateEvidenceProperty -Object $step -Name "id")
        if ($id -eq $GateId) {
            return $step
        }
    }
    return $null
}

function Get-Qcl100LowerGateEvidenceCommandArgument {
    param(
        $Step,
        [string]$Name
    )
    if ($null -eq $Step -or [string]::IsNullOrWhiteSpace($Name)) {
        return ""
    }
    $arguments = @((Get-Qcl100LowerGateEvidencePathValue -Object $Step -Path "command.arguments"))
    for ($index = 0; $index -lt $arguments.Count; $index++) {
        if ([string]$arguments[$index] -eq $Name -and ($index + 1) -lt $arguments.Count) {
            return [string]$arguments[$index + 1]
        }
    }
    return ""
}

function Get-Qcl100LowerGateEvidenceIssuePrefix {
    param([string]$GateId)
    switch ($GateId) {
        "route_clear_passive_preflight" { return "route_clear" }
        "qcl041_strict_control_tcp_gate" { return "qcl041_control_tcp" }
        "qcl100_xr_readiness_gate" { return "qcl100_xr_readiness" }
        "qcl100_no_media_launch_gate" { return "qcl100_no_media" }
        default { return $GateId }
    }
}

function Get-Qcl100LowerGateQcl041IssueCodes {
    @(
        "qcl041_client_p2p_network_callback_not_seen",
        "qcl041_client_p2p_network_not_visible_app",
        "qcl041_client_p2p_network_link_properties_missing",
        "qcl041_client_p2p_network_route_not_matching_group_owner",
        "qcl041_client_p2p_udp_network_bound_not_receiver_observed",
        "qcl041_client_p2p_network_socket_authority_not_proven",
        "qcl041_client_p2p_tcp_stream_not_bidirectional"
    )
}

function Get-Qcl100LowerGateEvidenceIssueCode {
    param($Issue)
    if ($null -eq $Issue) {
        return ""
    }
    if ($Issue -is [System.Collections.IDictionary] -and $Issue.Contains("code")) {
        return [string]$Issue["code"]
    }
    return [string](Get-Qcl100LowerGateEvidenceProperty -Object $Issue -Name "code")
}

function Get-Qcl100LowerGateQcl041Issues {
    param($Issues)
    $acceptedCodes = @{}
    foreach ($code in @(Get-Qcl100LowerGateQcl041IssueCodes)) {
        $acceptedCodes[$code] = $true
    }

    $qcl041Issues = @()
    foreach ($issue in @($Issues)) {
        $code = Get-Qcl100LowerGateEvidenceIssueCode -Issue $issue
        if (-not [string]::IsNullOrWhiteSpace($code) -and $acceptedCodes.ContainsKey($code)) {
            $qcl041Issues += $issue
        }
    }
    return $qcl041Issues
}

function Add-Qcl100LowerGateEvidencePlanIdentityChecks {
    param(
        [System.Collections.ArrayList]$Issues,
        [string]$GateId,
        $Plan,
        $Artifact,
        [string]$SummaryFileName,
        [System.Collections.Specialized.OrderedDictionary]$Fields
    )
    if ($null -eq $Plan -or -not [bool]$Artifact.metadata.parsed) {
        return
    }

    $prefix = Get-Qcl100LowerGateEvidenceIssuePrefix -GateId $GateId
    $step = Get-Qcl100LowerGateEvidencePlanStep -Plan $Plan -GateId $GateId
    $expectedArtifacts = @()
    if ($null -ne $step) {
        $expectedArtifacts = @((Get-Qcl100LowerGateEvidenceProperty -Object $step -Name "expected_artifacts") | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    }
    $expectedArtifactPath = ""
    if (-not [string]::IsNullOrWhiteSpace($SummaryFileName)) {
        $matches = @($expectedArtifacts | Where-Object { [System.IO.Path]::GetFileName([string]$_) -eq $SummaryFileName })
        if ($matches.Count -gt 0) {
            $expectedArtifactPath = [string]$matches[0]
        }
    }
    if ([string]::IsNullOrWhiteSpace($expectedArtifactPath) -and $expectedArtifacts.Count -gt 0) {
        $expectedArtifactPath = [string]$expectedArtifacts[0]
    }

    $expectedRunId = Get-Qcl100LowerGateEvidenceCommandArgument -Step $step -Name "-RunId"
    $actualRunId = [string]$Artifact.metadata.run_id
    $expectedComparablePath = Get-Qcl100LowerGateEvidenceComparablePath -Path $expectedArtifactPath
    $actualComparablePath = Get-Qcl100LowerGateEvidenceComparablePath -Path $Artifact.metadata.resolved_artifact_path
    if ([string]::IsNullOrWhiteSpace($actualComparablePath)) {
        $actualComparablePath = Get-Qcl100LowerGateEvidenceComparablePath -Path $Artifact.metadata.artifact_path
    }
    $runIdMatches = [bool](
        -not [string]::IsNullOrWhiteSpace($expectedRunId) -and
        -not [string]::IsNullOrWhiteSpace($actualRunId) -and
        $expectedRunId -eq $actualRunId)
    $pathMatches = [bool](
        -not [string]::IsNullOrWhiteSpace($expectedComparablePath) -and
        -not [string]::IsNullOrWhiteSpace($actualComparablePath) -and
        $expectedComparablePath.Equals($actualComparablePath, [System.StringComparison]::OrdinalIgnoreCase))

    $Fields["plan_step_present"] = [bool]($null -ne $step)
    $Fields["expected_run_id"] = $expectedRunId
    $Fields["run_id_matches_plan"] = $runIdMatches
    $Fields["expected_artifact_path"] = $expectedArtifactPath
    $Fields["resolved_expected_artifact_path"] = $expectedComparablePath
    $Fields["artifact_path_matches_plan"] = $pathMatches

    if ($null -eq $step) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_plan_step_missing" -Message "Lower-gate plan is missing step $GateId." -ArtifactPath $Artifact.metadata.artifact_path
        return
    }
    if ([string]::IsNullOrWhiteSpace($expectedRunId)) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_plan_run_id_missing" -Message "Lower-gate plan step $GateId does not provide a command -RunId." -ArtifactPath $Artifact.metadata.artifact_path
    } elseif ([string]::IsNullOrWhiteSpace($actualRunId) -or -not $runIdMatches) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_plan_run_id_mismatch" -Message "Lower-gate artifact run_id '$actualRunId' does not match planned run_id '$expectedRunId'." -ArtifactPath $Artifact.metadata.artifact_path
    }
    if ([string]::IsNullOrWhiteSpace($expectedArtifactPath)) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_plan_artifact_path_missing" -Message "Lower-gate plan step $GateId does not provide an expected artifact path for $SummaryFileName." -ArtifactPath $Artifact.metadata.artifact_path
    } elseif (-not $pathMatches) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_plan_artifact_path_mismatch" -Message "Lower-gate artifact path '$actualComparablePath' does not match planned artifact path '$expectedComparablePath'." -ArtifactPath $Artifact.metadata.artifact_path
    }
}

function Get-Qcl100LowerGateEvidencePlannedQcl041Reference {
    param($Plan)
    $step = Get-Qcl100LowerGateEvidencePlanStep -Plan $Plan -GateId "qcl041_strict_control_tcp_gate"
    $expectedArtifacts = @()
    if ($null -ne $step) {
        $expectedArtifacts = @((Get-Qcl100LowerGateEvidenceProperty -Object $step -Name "expected_artifacts") | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    }
    $expectedArtifactPath = ""
    $matches = @($expectedArtifacts | Where-Object { [System.IO.Path]::GetFileName([string]$_) -eq "summary.json" })
    if ($matches.Count -gt 0) {
        $expectedArtifactPath = [string]$matches[0]
    } elseif ($expectedArtifacts.Count -gt 0) {
        $expectedArtifactPath = [string]$expectedArtifacts[0]
    }

    [ordered]@{
        plan_step_present = [bool]($null -ne $step)
        expected_run_id = Get-Qcl100LowerGateEvidenceCommandArgument -Step $step -Name "-RunId"
        expected_artifact_path = $expectedArtifactPath
        resolved_expected_artifact_path = Get-Qcl100LowerGateEvidenceComparablePath -Path $expectedArtifactPath
    }
}

function Add-Qcl100LowerGateQcl041ReferenceChecks {
    param(
        [System.Collections.ArrayList]$Issues,
        [string]$GateId,
        $Plan,
        $ArtifactObject,
        [string]$ArtifactPath,
        [System.Collections.Specialized.OrderedDictionary]$Fields
    )
    if ($null -eq $Plan -or $null -eq $ArtifactObject) {
        return
    }

    $prefix = Get-Qcl100LowerGateEvidenceIssuePrefix -GateId $GateId
    $planned = Get-Qcl100LowerGateEvidencePlannedQcl041Reference -Plan $Plan
    $qcl041Gate = Get-Qcl100LowerGateEvidenceProperty -Object $ArtifactObject -Name "qcl041_matrix_gate"
    $matrixGateRequired = [bool](
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $ArtifactObject -Name "require_qcl041_matrix_gate_pass")) -or
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "freshness_acceptance.qcl041_matrix_gate_required"))
    )
    $matrixGateEvaluated = [bool](
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "freshness_acceptance.qcl041_matrix_gate_evaluated")) -or
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $qcl041Gate -Name "parsed"))
    )
    $matrixGatePassesRequirement = [bool](
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "freshness_acceptance.qcl041_matrix_gate_passes_requirement")) -or
        (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $qcl041Gate -Name "passed"))
    )
    $declaredRunId = [string](Get-Qcl100LowerGateEvidenceProperty -Object $ArtifactObject -Name "required_qcl041_matrix_run_id")
    if ([string]::IsNullOrWhiteSpace($declaredRunId)) {
        $declaredRunId = [string](Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "freshness_acceptance.required_qcl041_matrix_run_id")
    }
    $gateRunId = [string](Get-Qcl100LowerGateEvidenceProperty -Object $qcl041Gate -Name "run_id")
    if ([string]::IsNullOrWhiteSpace($gateRunId)) {
        $gateRunId = [string](Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "freshness_acceptance.qcl041_matrix_gate_run_id")
    }
    $declaredPath = [string](Get-Qcl100LowerGateEvidenceProperty -Object $ArtifactObject -Name "required_qcl041_matrix_summary_path")
    $gatePath = [string](Get-Qcl100LowerGateEvidenceProperty -Object $qcl041Gate -Name "resolved_artifact_path")
    if ([string]::IsNullOrWhiteSpace($gatePath)) {
        $gatePath = [string](Get-Qcl100LowerGateEvidenceProperty -Object $qcl041Gate -Name "artifact_path")
    }

    $declaredComparablePath = Get-Qcl100LowerGateEvidenceComparablePath -Path $declaredPath
    $gateComparablePath = Get-Qcl100LowerGateEvidenceComparablePath -Path $gatePath
    $expectedRunId = [string]$planned.expected_run_id
    $expectedComparablePath = [string]$planned.resolved_expected_artifact_path

    $Fields["qcl041_reference_plan_step_present"] = [bool]$planned.plan_step_present
    $Fields["expected_qcl041_matrix_run_id"] = $expectedRunId
    $Fields["expected_qcl041_matrix_summary_path"] = [string]$planned.expected_artifact_path
    $Fields["resolved_expected_qcl041_matrix_summary_path"] = $expectedComparablePath
    $Fields["required_qcl041_matrix_run_id"] = $declaredRunId
    $Fields["required_qcl041_matrix_summary_path"] = $declaredPath
    $Fields["resolved_required_qcl041_matrix_summary_path"] = $declaredComparablePath
    $Fields["qcl041_matrix_gate_run_id"] = $gateRunId
    $Fields["qcl041_matrix_gate_summary_path"] = $gatePath
    $Fields["resolved_qcl041_matrix_gate_summary_path"] = $gateComparablePath
    $Fields["qcl041_matrix_gate_required"] = $matrixGateRequired
    $Fields["qcl041_matrix_gate_evaluated"] = $matrixGateEvaluated
    $Fields["qcl041_matrix_gate_passes_requirement"] = $matrixGatePassesRequirement
    $Fields["qcl041_matrix_run_id_matches_plan"] = [bool](
        -not [string]::IsNullOrWhiteSpace($expectedRunId) -and
        -not [string]::IsNullOrWhiteSpace($gateRunId) -and
        $expectedRunId -eq $gateRunId)
    $Fields["qcl041_matrix_artifact_path_matches_plan"] = [bool](
        -not [string]::IsNullOrWhiteSpace($expectedComparablePath) -and
        -not [string]::IsNullOrWhiteSpace($gateComparablePath) -and
        $expectedComparablePath.Equals($gateComparablePath, [System.StringComparison]::OrdinalIgnoreCase))

    if (-not [bool]$planned.plan_step_present) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_reference_plan_step_missing" -Message "Lower-gate plan is missing the QCL041 control-TCP step required by $GateId." -ArtifactPath $ArtifactPath
        return
    }
    if (-not $matrixGateRequired) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_matrix_gate_not_required" -Message "$GateId must require the planned QCL041 matrix gate." -ArtifactPath $ArtifactPath
    }
    if (-not $matrixGateEvaluated) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_matrix_gate_not_evaluated" -Message "$GateId must record evaluated QCL041 matrix gate evidence." -ArtifactPath $ArtifactPath
    }
    if (-not $matrixGatePassesRequirement) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_matrix_gate_not_passed" -Message "$GateId must record qcl041_matrix_gate_passes_requirement=true." -ArtifactPath $ArtifactPath
    }
    if ([string]::IsNullOrWhiteSpace($declaredRunId) -or $declaredRunId -ne $expectedRunId) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_required_qcl041_matrix_run_id_mismatch" -Message "$GateId required_qcl041_matrix_run_id '$declaredRunId' does not match planned QCL041 run_id '$expectedRunId'." -ArtifactPath $ArtifactPath
    }
    if ([string]::IsNullOrWhiteSpace($gateRunId) -or $gateRunId -ne $expectedRunId) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_matrix_run_id_mismatch" -Message "$GateId QCL041 matrix gate run_id '$gateRunId' does not match planned QCL041 run_id '$expectedRunId'." -ArtifactPath $ArtifactPath
    }
    if ([string]::IsNullOrWhiteSpace($declaredComparablePath) -or -not $expectedComparablePath.Equals($declaredComparablePath, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_required_qcl041_matrix_path_mismatch" -Message "$GateId required_qcl041_matrix_summary_path '$declaredComparablePath' does not match planned QCL041 artifact '$expectedComparablePath'." -ArtifactPath $ArtifactPath
    }
    if ([string]::IsNullOrWhiteSpace($gateComparablePath) -or -not $expectedComparablePath.Equals($gateComparablePath, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-Qcl100LowerGateEvidenceIssue -Issues $Issues -GateId $GateId -Code "${prefix}_qcl041_matrix_artifact_path_mismatch" -Message "$GateId QCL041 matrix gate artifact '$gateComparablePath' does not match planned QCL041 artifact '$expectedComparablePath'." -ArtifactPath $ArtifactPath
    }
}

function Get-Qcl100LowerGateEvidencePlanFromSummary {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $summary = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
        return (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "lower_gate_plan")
    } catch {
        return $null
    }
}

function New-Qcl100LowerGateEvidenceIssue {
    param(
        [Parameter(Mandatory=$true)]
        [string]$GateId,
        [Parameter(Mandatory=$true)]
        [string]$Code,
        [Parameter(Mandatory=$true)]
        [string]$Message,
        [string]$ArtifactPath = ""
    )
    [ordered]@{
        gate_id = $GateId
        code = $Code
        message = $Message
        artifact_path = $ArtifactPath
    }
}

function Add-Qcl100LowerGateEvidenceIssue {
    param(
        [System.Collections.ArrayList]$Issues,
        [Parameter(Mandatory=$true)]
        [string]$GateId,
        [Parameter(Mandatory=$true)]
        [string]$Code,
        [Parameter(Mandatory=$true)]
        [string]$Message,
        [string]$ArtifactPath = ""
    )
    [void]$Issues.Add((New-Qcl100LowerGateEvidenceIssue -GateId $GateId -Code $Code -Message $Message -ArtifactPath $ArtifactPath))
}

function Read-Qcl100LowerGateEvidenceArtifact {
    param(
        [string]$GateId,
        [string]$Path
    )
    $issues = [System.Collections.ArrayList]::new()
    $resolvedPath = ""
    $present = $false
    $parsed = $false
    $parseError = ""
    $artifact = $null
    $status = ""
    $mode = ""
    $schema = ""
    $runId = ""

    if ([string]::IsNullOrWhiteSpace($Path)) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $issues `
            -GateId $GateId `
            -Code "${GateId}_path_missing" `
            -Message "Required lower-gate evidence path is empty."
    } elseif (-not (Test-Path -LiteralPath $Path)) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $issues `
            -GateId $GateId `
            -Code "${GateId}_artifact_missing" `
            -Message "Required lower-gate evidence artifact does not exist: $Path" `
            -ArtifactPath $Path
    } else {
        $present = $true
        $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
        try {
            $artifact = Get-Content -Raw -LiteralPath $resolvedPath | ConvertFrom-Json
            $parsed = $true
            $schema = [string](Get-Qcl100LowerGateEvidenceProperty -Object $artifact -Name "schema")
            $status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $artifact -Name "status")
            $mode = [string](Get-Qcl100LowerGateEvidenceProperty -Object $artifact -Name "mode")
            $runId = [string](Get-Qcl100LowerGateEvidenceProperty -Object $artifact -Name "run_id")
        } catch {
            $parseError = $_.Exception.Message
            Add-Qcl100LowerGateEvidenceIssue `
                -Issues $issues `
                -GateId $GateId `
                -Code "${GateId}_parse_failed" `
                -Message $parseError `
                -ArtifactPath $Path
        }
    }

    [pscustomobject]@{
        metadata = [ordered]@{
            gate_id = $GateId
            artifact_path = $Path
            resolved_artifact_path = $resolvedPath
            artifact_present = $present
            parsed = $parsed
            parse_error = $parseError
            schema = $schema
            run_id = $runId
            status = $status
            mode = $mode
        }
        object = $artifact
        issues = @($issues)
    }
}

function Add-Qcl100LowerGateArtifactIssues {
    param(
        [System.Collections.ArrayList]$Issues,
        $Artifact
    )
    foreach ($issue in @($Artifact.issues)) {
        [void]$Issues.Add($issue)
    }
}

function Add-Qcl100LowerGateNoPromotionChecks {
    param(
        [System.Collections.ArrayList]$Issues,
        [string]$GateId,
        $ArtifactObject,
        [string]$ArtifactPath
    )
    if (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "promotion_allowed")) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $Issues `
            -GateId $GateId `
            -Code "${GateId}_premature_promotion_allowed_claim" `
            -Message "Lower-gate evidence must not set promotion_allowed=true." `
            -ArtifactPath $ArtifactPath
    }
    if (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "same_group_duplex_claimed")) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $Issues `
            -GateId $GateId `
            -Code "${GateId}_premature_same_group_duplex_claim" `
            -Message "Lower-gate evidence must not claim same_group_duplex_claimed=true." `
            -ArtifactPath $ArtifactPath
    }
    if (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "transport_claims.same_group_duplex_claimed")) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $Issues `
            -GateId $GateId `
            -Code "${GateId}_premature_transport_duplex_claim" `
            -Message "Lower-gate evidence must not claim transport_claims.same_group_duplex_claimed=true." `
            -ArtifactPath $ArtifactPath
    }
    if (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $ArtifactObject -Path "transport_claims.same_group_simultaneous_duplex")) {
        Add-Qcl100LowerGateEvidenceIssue `
            -Issues $Issues `
            -GateId $GateId `
            -Code "${GateId}_premature_transport_simultaneous_duplex_claim" `
            -Message "Lower-gate evidence must not claim transport_claims.same_group_simultaneous_duplex=true." `
            -ArtifactPath $ArtifactPath
    }
}

function New-Qcl100LowerGateEvidenceGateResult {
    param(
        [string]$GateId,
        $Artifact,
        [System.Collections.ArrayList]$Issues,
        $Fields
    )
    $qcl041LowerGateIssues = @(Get-Qcl100LowerGateQcl041Issues -Issues $Issues)
    $qcl041LowerGateIssueCodes = @($qcl041LowerGateIssues | ForEach-Object { Get-Qcl100LowerGateEvidenceIssueCode -Issue $_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $firstIssue = if ($Issues.Count -gt 0) { Get-Qcl100LowerGateEvidenceIssueCode -Issue $Issues[0] } else { "" }
    $firstQcl041LowerGateIssue = if ($qcl041LowerGateIssueCodes.Count -gt 0) { [string]$qcl041LowerGateIssueCodes[0] } else { "" }
    $summaryBlockedReason = ""
    if ($null -ne $Fields -and $Fields.Contains("summary_blocked_reason")) {
        $summaryBlockedReason = [string]$Fields["summary_blocked_reason"]
    }
    $preferredSummaryBlockerCodes = @(
        "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent",
        "qcl041_connectivitymanager_other_uid_p2p_visible_client_uid_hidden"
    )
    $blockedReasonForQcl100 = if ($preferredSummaryBlockerCodes -contains $summaryBlockedReason) {
        $summaryBlockedReason
    } elseif (-not [string]::IsNullOrWhiteSpace($firstQcl041LowerGateIssue)) {
        $firstQcl041LowerGateIssue
    } else {
        $firstIssue
    }

    [ordered]@{
        id = $GateId
        artifact_path = $Artifact.metadata.artifact_path
        resolved_artifact_path = $Artifact.metadata.resolved_artifact_path
        artifact_present = [bool]$Artifact.metadata.artifact_present
        parsed = [bool]$Artifact.metadata.parsed
        schema = $Artifact.metadata.schema
        run_id = $Artifact.metadata.run_id
        status = $Artifact.metadata.status
        mode = $Artifact.metadata.mode
        passed = [bool]($Issues.Count -eq 0)
        fields = $Fields
        issues = @($Issues)
        first_issue = $firstIssue
        qcl041_lower_gate_issue_codes = $qcl041LowerGateIssueCodes
        qcl041_lower_gate_issue_count = [int]$qcl041LowerGateIssueCodes.Count
        first_qcl041_lower_gate_issue = $firstQcl041LowerGateIssue
        blocked_reason_for_qcl100 = $blockedReasonForQcl100
    }
}

function Test-Qcl100LowerGatePlanSummaryEvidence {
    param([string]$Path)
    $gateId = "qcl100_lower_gate_plan_summary"
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $plan = Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "lower_gate_plan"
        $steps = @((Get-Qcl100LowerGateEvidenceProperty -Object $plan -Name "lower_gate_sequence"))
        $stepIds = @($steps | ForEach-Object { [string](Get-Qcl100LowerGateEvidenceProperty -Object $_ -Name "id") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        $requiredStepIds = @(
            "route_clear_passive_preflight",
            "qcl041_strict_control_tcp_gate",
            "qcl100_xr_readiness_gate",
            "qcl100_no_media_launch_gate",
            "qcl100_lower_gate_evidence_validation",
            "qcl100_short_control_tcp_media_gate",
            "qcl100_full_parity_promotion_attempt"
        )
        $missingStepIds = @($requiredStepIds | Where-Object { $stepIds -notcontains $_ })

        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            mode = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "mode")
            non_live_artifact = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "non_live_artifact")
            launched = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "launched")
            device_mutation_performed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "device_mutation_performed")
            promotion_allowed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "promotion_allowed")
            same_group_duplex_claimed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "same_group_duplex_claimed")
            lower_gate_plan_schema = [string](Get-Qcl100LowerGateEvidenceProperty -Object $plan -Name "schema")
            lower_gate_step_ids = $stepIds
            missing_lower_gate_step_ids = $missingStepIds
        }
        if ($fields.status -ne "lower_gate_plan_only") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_summary_status_not_plan_only" -Message "Expected lower-gate plan summary status=lower_gate_plan_only." -ArtifactPath $Path
        }
        if ($fields.mode -ne "lower_gate_plan_only") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_summary_mode_not_plan_only" -Message "Expected lower-gate plan summary mode=lower_gate_plan_only." -ArtifactPath $Path
        }
        if (-not [bool]$fields.non_live_artifact -or [bool]$fields.launched -or [bool]$fields.device_mutation_performed) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_summary_not_non_live" -Message "Lower-gate plan summary must be non-live and must not launch or mutate devices." -ArtifactPath $Path
        }
        if ([bool]$fields.promotion_allowed -or [bool]$fields.same_group_duplex_claimed) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_summary_promotes" -Message "Lower-gate plan summary must not allow promotion or claim same-group duplex." -ArtifactPath $Path
        }
        if ($fields.lower_gate_plan_schema -ne "rusty.quest.qcl100_lower_gate_plan.v1") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_schema_missing" -Message "Expected embedded lower_gate_plan schema rusty.quest.qcl100_lower_gate_plan.v1." -ArtifactPath $Path
        }
        if (@($missingStepIds).Count -gt 0) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_lower_gate_plan_missing_required_steps" -Message "Embedded lower-gate plan is missing required gate ids: $($missingStepIds -join ', ')." -ArtifactPath $Path
        }
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $summary -ArtifactPath $Path
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $plan -ArtifactPath $Path
    }

    New-Qcl100LowerGateEvidenceGateResult -GateId $gateId -Artifact $artifact -Issues $issues -Fields $fields
}

function Test-Qcl100LowerGateRouteClearEvidence {
    param(
        [string]$Path,
        $Plan = $null
    )
    $gateId = "route_clear_passive_preflight"
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            launched = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "launched")
            preflight_infrastructure_wifi_disconnected = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.infrastructure_wifi_disconnected")
            preflight_p2p0_ipv4_cleared = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.p2p0_ipv4_cleared")
            preflight_candidate_wifi_direct_routes_clear = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.candidate_wifi_direct_prelaunch_routes_clear")
            same_group_duplex_claimed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "same_group_duplex_claimed")
        }
        if ($fields.status -ne "preflight_only") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "route_clear_status_not_preflight_only" -Message "Route-clear gate expected status=preflight_only." -ArtifactPath $Path
        }
        if ([bool]$fields.launched) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "route_clear_launched" -Message "Route-clear gate must not launch media, broker, native renderer, or QCL041 matrix." -ArtifactPath $Path
        }
        foreach ($field in @("preflight_infrastructure_wifi_disconnected", "preflight_p2p0_ipv4_cleared", "preflight_candidate_wifi_direct_routes_clear")) {
            if (-not [bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "route_clear_${field}_not_true" -Message "Route-clear gate missing required true field: $field." -ArtifactPath $Path
            }
        }
        Add-Qcl100LowerGateEvidencePlanIdentityChecks -Issues $issues -GateId $gateId -Plan $Plan -Artifact $artifact -SummaryFileName "native-stereo-projection-summary.json" -Fields $fields
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $summary -ArtifactPath $Path
    }

    New-Qcl100LowerGateEvidenceGateResult -GateId $gateId -Artifact $artifact -Issues $issues -Fields $fields
}

function Test-Qcl100LowerGateControlTcpEvidence {
    param(
        [string]$Path,
        $Plan = $null,
        [string]$Qcl100LowerGateAuthority = "android_connectivitymanager_network",
        [switch]$RequireQcl041ClientP2pNetworkCallbackSeen,
        [switch]$RequireQcl041ClientP2pNetworkSocketAuthority,
        [switch]$RequireQcl041StrictUdpDatagramEchoPass,
        [switch]$RequireQcl041TcpTunnelStreamPass
    )
    $gateId = "qcl041_strict_control_tcp_gate"
    $acceptedLowerGateAuthority = Get-Qcl100LowerGateEvidenceAuthority -Authority $Qcl100LowerGateAuthority
    $rustyDirectP2pAuthority = [bool]($acceptedLowerGateAuthority -eq "rusty_direct_p2p_socket_authority")
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            summary_blocked_reason = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "blocked_reason")
            matrix_focus = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "matrix_focus")
            qcl100_control_tcp_gate = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl100_control_tcp_gate")
            require_tcp_tunnel_stream_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "require_tcp_tunnel_stream_pass")
            preflight_infrastructure_wifi_disconnected = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.infrastructure_wifi_disconnected")
            preflight_p2p0_ipv4_cleared = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.p2p0_ipv4_cleared")
            preflight_candidate_wifi_direct_routes_clear = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.candidate_wifi_direct_prelaunch_routes_clear")
            matrix_tcp_tunnel_stream_bidirectional_bytes_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.tcp_tunnel_stream_bidirectional_bytes_pass")
            client_p2p_network_callback_seen = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_network_callback_seen")
            client_p2p_network_visible_app = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_network_visible_app")
            client_p2p_network_link_properties_present = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_network_link_properties_present")
            client_p2p_network_route_matches_group_owner = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_network_route_matches_group_owner")
            udp_network_bound_receiver_observed_packets = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.udp_network_bound_receiver_observed_packets")
            udp_network_bound_receiver_observed = [bool]((Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.udp_network_bound_receiver_observed_packets")) -gt 0)
            client_p2p_network_socket_authority_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_network_socket_authority_pass")
            client_app_network_permissions_all_granted = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_app_network_permissions_all_granted")
            client_app_network_permissions_all_declared_granted = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_app_network_permissions_all_declared_granted")
            client_sdk_int = Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_sdk_int"
            client_target_sdk_int = Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_target_sdk_int"
            client_permission_nearby_wifi_devices_applicable = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_permission_nearby_wifi_devices_applicable")
            client_permission_access_fine_location_applicable = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_permission_access_fine_location_applicable")
            client_permission_access_fine_location_manifest_max_sdk = Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_permission_access_fine_location_manifest_max_sdk"
            client_app_network_authority_restriction_hint = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_app_network_authority_restriction_hint")
            client_request_wifi_p2p_restricted_network_security_exception = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_request_wifi_p2p_restricted_network_security_exception")
            client_appop_nearby_wifi_devices_mode = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_appop_nearby_wifi_devices_mode")
            client_appop_fine_location_mode = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_appop_fine_location_mode")
            client_appop_wifi_scan_mode = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_appop_wifi_scan_mode")
            client_after_group_formation_all_network_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_after_group_formation_all_network_count")
            client_after_group_formation_p2p_candidate_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_after_group_formation_p2p_candidate_count")
            client_after_group_formation_network_interface_p2p_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_after_group_formation_network_interface_p2p_count")
            client_include_other_uid_candidate_seen = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_include_other_uid_candidate_seen")
            client_include_other_uid_on_available_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_include_other_uid_on_available_count")
            client_include_other_uid_cached_network_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_include_other_uid_cached_network_count")
            client_include_other_uid_bind_socket_result = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_include_other_uid_bind_socket_result")
            client_wifi_p2p_network_info_available = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_network_info_available")
            client_wifi_p2p_network_info_connected = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_network_info_connected")
            client_wifi_p2p_network_info_state = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_network_info_state")
            client_wifi_p2p_network_info_detailed_state = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_network_info_detailed_state")
            client_wifi_p2p_group_interface = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_group_interface")
            client_wifi_p2p_group_client_count = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_wifi_p2p_group_client_count")
            client_strict_local_p2p_app_transport_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_strict_local_p2p_app_transport_pass")
            qcl041_local_p2p_bind_stream_authority = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.qcl041_local_p2p_bind_stream_authority")
            qcl100_android_network_authority = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.qcl100_android_network_authority")
            qcl100_same_group_simultaneous_native_render = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.qcl100_same_group_simultaneous_native_render")
            local_p2p_bind_diagnostic_non_promoting = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_non_promoting")
            local_p2p_bind_socket_authority = [string](Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_socket_authority")
            local_p2p_bind_udp_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_udp_pass")
            local_p2p_bind_udp_receiver_observed_packets = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_udp_receiver_observed_packets")
            local_p2p_bind_tcp_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_tcp_pass")
            local_p2p_bind_tcp_receiver_accepts = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.client_p2p_interface_local_bind_tcp_receiver_accepts")
            local_p2p_bind_tcp_stream_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_pass", "matrix.client_p2p_interface_local_bind_tcp_stream_pass"))
            local_p2p_bind_tcp_stream_receiver_accepts = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_receiver_accepts", "matrix.client_p2p_interface_local_bind_tcp_stream_receiver_accepts"))
            local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes", "matrix.client_p2p_interface_local_bind_tcp_stream_client_to_owner_rx_bytes"))
            local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes", "matrix.client_p2p_interface_local_bind_tcp_stream_owner_to_client_rx_bytes"))
            local_p2p_bind_tcp_stream_client_to_owner_rx_bytes = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes", "matrix.client_p2p_interface_local_bind_tcp_stream_client_to_owner_rx_bytes"))
            local_p2p_bind_tcp_stream_owner_to_client_rx_bytes = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidenceFirstPathValue -Object $summary -Paths @("matrix.local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes", "matrix.client_p2p_interface_local_bind_tcp_stream_owner_to_client_rx_bytes"))
            accepted_lower_gate_authority = $acceptedLowerGateAuthority
            rusty_direct_p2p_socket_authority = $(if ($rustyDirectP2pAuthority) { "requested" } else { "not_requested" })
            require_qcl041_client_p2p_network_callback_seen = [bool]$RequireQcl041ClientP2pNetworkCallbackSeen
            require_qcl041_client_p2p_network_socket_authority = [bool]$RequireQcl041ClientP2pNetworkSocketAuthority
            require_qcl041_strict_udp_datagram_echo_pass = [bool]$RequireQcl041StrictUdpDatagramEchoPass
            require_qcl041_tcp_tunnel_stream_pass = [bool]$RequireQcl041TcpTunnelStreamPass
        }
        if ($fields.status -ne "pass") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_status_not_pass" -Message "QCL041 control-TCP lower gate expected status=pass." -ArtifactPath $Path
        }
        if ($fields.matrix_focus -ne "qcl100_control_tcp_gate") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_wrong_matrix_focus" -Message "QCL041 control-TCP lower gate expected matrix_focus=qcl100_control_tcp_gate." -ArtifactPath $Path
        }
        foreach ($field in @("qcl100_control_tcp_gate", "require_tcp_tunnel_stream_pass", "preflight_infrastructure_wifi_disconnected", "preflight_p2p0_ipv4_cleared", "preflight_candidate_wifi_direct_routes_clear")) {
            if (-not [bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_${field}_not_true" -Message "QCL041 control-TCP lower gate missing required true field: $field." -ArtifactPath $Path
            }
        }
        if ($rustyDirectP2pAuthority) {
            if (-not [bool]$fields.client_strict_local_p2p_app_transport_pass -or
                    $fields.qcl041_local_p2p_bind_stream_authority -ne "diagnostic_pass" -or
                    -not [bool]$fields.local_p2p_bind_diagnostic_non_promoting -or
                    $fields.local_p2p_bind_socket_authority -ne "network_interface_local_p2p_address_bind") {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_rusty_direct_p2p_socket_authority_not_proven" -Message "Rusty direct lower gate requires explicit network_interface_local_p2p_address_bind authority." -ArtifactPath $Path
            }
            if (-not [bool]$fields.local_p2p_bind_udp_pass -or [int]$fields.local_p2p_bind_udp_receiver_observed_packets -le 0) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_rusty_direct_p2p_udp_not_receiver_observed" -Message "Rusty direct lower gate requires receiver-observed local-p2p UDP control bytes." -ArtifactPath $Path
            }
            if (-not [bool]$fields.local_p2p_bind_tcp_pass -or [int]$fields.local_p2p_bind_tcp_receiver_accepts -le 0) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_rusty_direct_p2p_tcp_not_accepted" -Message "Rusty direct lower gate requires a receiver-accepted local-p2p TCP socket." -ArtifactPath $Path
            }
            if (-not [bool]$fields.local_p2p_bind_tcp_stream_pass -or [int]$fields.local_p2p_bind_tcp_stream_receiver_accepts -le 0) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_rusty_direct_p2p_tcp_stream_not_bidirectional" -Message "Rusty direct lower gate requires a receiver-accepted bidirectional local-p2p TCP stream." -ArtifactPath $Path
            }
            if ([int]$fields.local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes -lt 1048576 -or
                    [int]$fields.local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes -lt 1048576) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_rusty_direct_p2p_tcp_stream_bytes_below_threshold" -Message "Rusty direct lower gate requires at least 1048576 receiver-observed stream bytes in each direction." -ArtifactPath $Path
            }
            if ($issues.Count -eq 0) {
                $fields["rusty_direct_p2p_socket_authority"] = "pass"
            } else {
                $fields["rusty_direct_p2p_socket_authority"] = "blocked"
            }
        } else {
            if ([bool]$RequireQcl041ClientP2pNetworkCallbackSeen -and -not [bool]$fields.client_p2p_network_callback_seen) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_network_callback_not_seen" -Message "QCL041 lower gate requires a callback-visible client Wi-Fi Direct Network." -ArtifactPath $Path
            }
            if (-not [bool]$fields.client_p2p_network_visible_app) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_network_not_visible_app" -Message "QCL041 lower gate requires an app-visible client Wi-Fi Direct Network." -ArtifactPath $Path
            }
            if (-not [bool]$fields.client_p2p_network_link_properties_present) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_network_link_properties_missing" -Message "QCL041 lower gate requires LinkProperties for the selected client Wi-Fi Direct Network." -ArtifactPath $Path
            }
            if (-not [bool]$fields.client_p2p_network_route_matches_group_owner) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_network_route_not_matching_group_owner" -Message "QCL041 lower gate requires the selected client Wi-Fi Direct Network to route to the group owner." -ArtifactPath $Path
            }
            if ([bool]$RequireQcl041StrictUdpDatagramEchoPass -and -not [bool]$fields.udp_network_bound_receiver_observed) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_udp_network_bound_not_receiver_observed" -Message "QCL041 lower gate requires a receiver-observed network-bound UDP echo before TCP media diagnostics." -ArtifactPath $Path
            }
            if ([bool]$RequireQcl041ClientP2pNetworkSocketAuthority -and -not [bool]$fields.client_p2p_network_socket_authority_pass) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_network_socket_authority_not_proven" -Message "QCL041 lower gate requires socket authority on the selected client Wi-Fi Direct Network." -ArtifactPath $Path
            }
            if (-not [bool]$fields.matrix_tcp_tunnel_stream_bidirectional_bytes_pass) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_client_p2p_tcp_stream_not_bidirectional" -Message "QCL041 lower gate requires sustained bidirectional TCP tunnel stream bytes." -ArtifactPath $Path
            }
        }
        Add-Qcl100LowerGateEvidencePlanIdentityChecks -Issues $issues -GateId $gateId -Plan $Plan -Artifact $artifact -SummaryFileName "summary.json" -Fields $fields
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $summary -ArtifactPath $Path
    }

    New-Qcl100LowerGateEvidenceGateResult -GateId $gateId -Artifact $artifact -Issues $issues -Fields $fields
}

function Test-Qcl100LowerGateXrReadinessEvidence {
    param(
        [string]$Path,
        $Plan = $null
    )
    $gateId = "qcl100_xr_readiness_gate"
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $qcl041Pass = [bool](
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl041_matrix_gate_passes_requirement")) -or
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "freshness_acceptance.qcl041_matrix_gate_passes_requirement"))
        )
        $ownerReady = [bool](
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "owner_xr_launch_readiness.xr_launch_ready")) -or
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "freshness_acceptance.owner_xr_launch_ready"))
        )
        $clientReady = [bool](
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "client_xr_launch_readiness.xr_launch_ready")) -or
            (Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "freshness_acceptance.client_xr_launch_ready"))
        )
        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            mode = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "mode")
            qcl100_lower_gate_authority = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl100_lower_gate_authority")
            launched = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "launched")
            qcl041_matrix_gate_passes_requirement = $qcl041Pass
            owner_xr_launch_ready = $ownerReady
            client_xr_launch_ready = $clientReady
        }
        if ($fields.status -ne "pass") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_xr_readiness_status_not_pass" -Message "XR readiness lower gate expected status=pass." -ArtifactPath $Path
        }
        if ($fields.mode -ne "xr_launch_readiness_only") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_xr_readiness_wrong_mode" -Message "XR readiness lower gate expected mode=xr_launch_readiness_only." -ArtifactPath $Path
        }
        if ([bool]$fields.launched) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_xr_readiness_launched_media" -Message "XR readiness lower gate must not launch media or native renderer." -ArtifactPath $Path
        }
        foreach ($field in @("qcl041_matrix_gate_passes_requirement", "owner_xr_launch_ready", "client_xr_launch_ready")) {
            if (-not [bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_xr_readiness_${field}_not_true" -Message "XR readiness lower gate missing required true field: $field." -ArtifactPath $Path
            }
        }
        Add-Qcl100LowerGateQcl041ReferenceChecks -Issues $issues -GateId $gateId -Plan $Plan -ArtifactObject $summary -ArtifactPath $Path -Fields $fields
        Add-Qcl100LowerGateEvidencePlanIdentityChecks -Issues $issues -GateId $gateId -Plan $Plan -Artifact $artifact -SummaryFileName "native-stereo-projection-summary.json" -Fields $fields
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $summary -ArtifactPath $Path
    }

    New-Qcl100LowerGateEvidenceGateResult -GateId $gateId -Artifact $artifact -Issues $issues -Fields $fields
}

function Test-Qcl100LowerGateNoMediaEvidence {
    param(
        [string]$Path,
        $Plan = $null,
        [switch]$AllowSkippedCleanup
    )
    $gateId = "qcl100_no_media_launch_gate"
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $nativeFatalCount = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "native_log_summary.fatal_count")
        $nativeSystemFatalCount = Get-Qcl100LowerGateEvidenceInt (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "native_log_summary.system_fatal_count")
        $cleanupSkipped = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "cleanup_policy.final_force_stop_cleanup_skipped")
        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            mode = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "mode")
            qcl100_lower_gate_authority = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl100_lower_gate_authority")
            qcl041_started = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl041_started")
            qcl082_media_started = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl082_media_started")
            promotion_allowed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "promotion_allowed")
            same_group_duplex_claimed = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "same_group_duplex_claimed")
            owner_no_media_launch_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "owner_no_media_launch_pass")
            client_no_media_launch_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "client_no_media_launch_pass")
            native_log_fatal_count = $nativeFatalCount
            native_log_system_fatal_count = $nativeSystemFatalCount
            cleanup_final_force_stop_cleanup_skipped = $cleanupSkipped
            allow_skipped_cleanup = [bool]$AllowSkippedCleanup
        }
        if ($fields.status -ne "pass") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_status_not_pass" -Message "No-media lower gate expected status=pass." -ArtifactPath $Path
        }
        if ($fields.mode -ne "no_media_launch_only") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_wrong_mode" -Message "No-media lower gate expected mode=no_media_launch_only." -ArtifactPath $Path
        }
        foreach ($field in @("qcl041_started", "qcl082_media_started", "promotion_allowed", "same_group_duplex_claimed")) {
            if ([bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_${field}_not_false" -Message "No-media lower gate expected $field=false." -ArtifactPath $Path
            }
        }
        foreach ($field in @("owner_no_media_launch_pass", "client_no_media_launch_pass")) {
            if (-not [bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_${field}_not_true" -Message "No-media lower gate expected $field=true." -ArtifactPath $Path
            }
        }
        if ($nativeFatalCount -ne 0 -or $nativeSystemFatalCount -ne 0) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_native_fatal_lines_present" -Message "No-media lower gate requires native_log_summary fatal_count=0 and system_fatal_count=0." -ArtifactPath $Path
        }
        if ($cleanupSkipped -and -not [bool]$AllowSkippedCleanup) {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl100_no_media_cleanup_skipped" -Message "No-media lower gate requires final force-stop cleanup unless AllowSkippedCleanup is set." -ArtifactPath $Path
        }
        Add-Qcl100LowerGateQcl041ReferenceChecks -Issues $issues -GateId $gateId -Plan $Plan -ArtifactObject $summary -ArtifactPath $Path -Fields $fields
        Add-Qcl100LowerGateEvidencePlanIdentityChecks -Issues $issues -GateId $gateId -Plan $Plan -Artifact $artifact -SummaryFileName "native-stereo-projection-summary.json" -Fields $fields
        Add-Qcl100LowerGateNoPromotionChecks -Issues $issues -GateId $gateId -ArtifactObject $summary -ArtifactPath $Path
    }

    New-Qcl100LowerGateEvidenceGateResult -GateId $gateId -Artifact $artifact -Issues $issues -Fields $fields
}

function Get-Qcl100LowerGateEvidence {
    param(
        [string]$PlanSummaryPath,
        [string]$RouteClearSummaryPath,
        [string]$Qcl041ControlTcpSummaryPath,
        [string]$XrReadinessSummaryPath,
        [string]$NoMediaLaunchSummaryPath,
        [switch]$AllowSkippedCleanup,
        [string]$Qcl100LowerGateAuthority = "android_connectivitymanager_network",
        [switch]$RequireQcl041ClientP2pNetworkCallbackSeen,
        [switch]$RequireQcl041ClientP2pNetworkSocketAuthority,
        [switch]$RequireQcl041StrictUdpDatagramEchoPass,
        [switch]$RequireQcl041TcpTunnelStreamPass
    )
    $acceptedLowerGateAuthority = Get-Qcl100LowerGateEvidenceAuthority -Authority $Qcl100LowerGateAuthority
    $plan = Get-Qcl100LowerGateEvidencePlanFromSummary -Path $PlanSummaryPath
    $gates = @(
        (Test-Qcl100LowerGatePlanSummaryEvidence -Path $PlanSummaryPath),
        (Test-Qcl100LowerGateRouteClearEvidence -Path $RouteClearSummaryPath -Plan $plan),
        (Test-Qcl100LowerGateControlTcpEvidence `
            -Path $Qcl041ControlTcpSummaryPath `
            -Plan $plan `
            -Qcl100LowerGateAuthority $acceptedLowerGateAuthority `
            -RequireQcl041ClientP2pNetworkCallbackSeen:$RequireQcl041ClientP2pNetworkCallbackSeen `
            -RequireQcl041ClientP2pNetworkSocketAuthority:$RequireQcl041ClientP2pNetworkSocketAuthority `
            -RequireQcl041StrictUdpDatagramEchoPass:$RequireQcl041StrictUdpDatagramEchoPass `
            -RequireQcl041TcpTunnelStreamPass:$RequireQcl041TcpTunnelStreamPass),
        (Test-Qcl100LowerGateXrReadinessEvidence -Path $XrReadinessSummaryPath -Plan $plan),
        (Test-Qcl100LowerGateNoMediaEvidence -Path $NoMediaLaunchSummaryPath -Plan $plan -AllowSkippedCleanup:$AllowSkippedCleanup)
    )
    $issues = [System.Collections.ArrayList]::new()
    foreach ($gate in @($gates)) {
        foreach ($issue in @($gate.issues)) {
            [void]$issues.Add($issue)
        }
    }
    $qcl041LowerGateIssues = @(Get-Qcl100LowerGateQcl041Issues -Issues $issues)
    $qcl041LowerGateIssueCodes = @($qcl041LowerGateIssues | ForEach-Object { Get-Qcl100LowerGateEvidenceIssueCode -Issue $_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $firstIssue = if ($issues.Count -gt 0) { Get-Qcl100LowerGateEvidenceIssueCode -Issue $issues[0] } else { "" }
    $firstQcl041LowerGateIssue = if ($qcl041LowerGateIssueCodes.Count -gt 0) { [string]$qcl041LowerGateIssueCodes[0] } else { "" }
    $controlTcpGate = @($gates | Where-Object { $_.id -eq "qcl041_strict_control_tcp_gate" } | Select-Object -First 1)
    $controlTcpFields = if ($controlTcpGate.Count -gt 0) { $controlTcpGate[0].fields } else { $null }
    $controlTcpBlockedReason = if ($controlTcpGate.Count -gt 0) { [string]$controlTcpGate[0].blocked_reason_for_qcl100 } else { "" }
    $preferredSummaryBlockerCodes = @(
        "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent",
        "qcl041_connectivitymanager_other_uid_p2p_visible_client_uid_hidden"
    )
    $blockedReasonForQcl100 = if ($preferredSummaryBlockerCodes -contains $controlTcpBlockedReason) {
        $controlTcpBlockedReason
    } elseif (-not [string]::IsNullOrWhiteSpace($firstQcl041LowerGateIssue)) {
        $firstQcl041LowerGateIssue
    } else {
        $firstIssue
    }
    [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_evidence.v1"
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        status = if ($issues.Count -eq 0) { "pass" } else { "blocked" }
        passed = [bool]($issues.Count -eq 0)
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        allow_skipped_cleanup = [bool]$AllowSkippedCleanup
        accepted_lower_gate_authority = $acceptedLowerGateAuthority
        authority_labels = [ordered]@{
            qcl041_local_p2p_bind_stream_authority = if ($null -ne $controlTcpFields) { [string]$controlTcpFields.qcl041_local_p2p_bind_stream_authority } else { "" }
            rusty_direct_p2p_socket_authority = if ($null -ne $controlTcpFields) { [string]$controlTcpFields.rusty_direct_p2p_socket_authority } else { "" }
            qcl100_android_network_authority = if ($null -ne $controlTcpFields) { [string]$controlTcpFields.qcl100_android_network_authority } else { "" }
            qcl100_same_group_simultaneous_native_render = "not_promoted"
        }
        required_qcl041_client_p2p_network_callback_seen = [bool]$RequireQcl041ClientP2pNetworkCallbackSeen
        required_qcl041_client_p2p_network_socket_authority = [bool]$RequireQcl041ClientP2pNetworkSocketAuthority
        required_qcl041_strict_udp_datagram_echo_pass = [bool]$RequireQcl041StrictUdpDatagramEchoPass
        required_qcl041_tcp_tunnel_stream_pass = [bool]$RequireQcl041TcpTunnelStreamPass
        required_artifacts = [ordered]@{
            lower_gate_plan_summary = $PlanSummaryPath
            route_clear_passive_preflight = $RouteClearSummaryPath
            qcl041_strict_control_tcp_gate = $Qcl041ControlTcpSummaryPath
            qcl100_xr_readiness_gate = $XrReadinessSummaryPath
            qcl100_no_media_launch_gate = $NoMediaLaunchSummaryPath
        }
        gates = $gates
        issues = @($issues)
        first_issue = $firstIssue
        qcl041_lower_gate_issue_codes = $qcl041LowerGateIssueCodes
        qcl041_lower_gate_issue_count = [int]$qcl041LowerGateIssueCodes.Count
        first_qcl041_lower_gate_issue = $firstQcl041LowerGateIssue
        blocked_reason_for_qcl100 = $blockedReasonForQcl100
        deferred_full_promotion_reason = "QCL100 promotion remains blocked until lower-gate evidence, short control-TCP media, final-window renderer scorecards, receiver-observed bytes, cleanup, and zero native/system fatal lines all pass."
    }
}

function New-Qcl100LowerGateEvidenceSelfTestPlanSummary {
    param(
        [string]$ArtifactDirectory = "",
        [string]$MatrixSummaryFile = "qcl041-control-tcp-summary-pass.json",
        [string]$XrSummaryFile = "xr-readiness-summary-pass.json",
        [string]$NoMediaSummaryFile = "no-media-summary-pass.json"
    )
    if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
        $ArtifactDirectory = Join-Path $env:TEMP "qcl100-lower-gate-evidence-selftest"
    }
    $plan = [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_plan.v1"
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        lower_gate_sequence = @(
            [ordered]@{
                id = "route_clear_passive_preflight"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-route") }
                expected_artifacts = @((Join-Path $ArtifactDirectory "route-clear-summary-pass.json"))
            },
            [ordered]@{
                id = "qcl041_strict_control_tcp_gate"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-matrix") }
                expected_artifacts = @((Join-Path $ArtifactDirectory $MatrixSummaryFile))
            },
            [ordered]@{
                id = "qcl100_xr_readiness_gate"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-xr") }
                expected_artifacts = @((Join-Path $ArtifactDirectory $XrSummaryFile))
            },
            [ordered]@{
                id = "qcl100_no_media_launch_gate"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-no-media") }
                expected_artifacts = @((Join-Path $ArtifactDirectory $NoMediaSummaryFile))
            },
            [ordered]@{ id = "qcl100_lower_gate_evidence_validation" },
            [ordered]@{ id = "qcl100_short_control_tcp_media_gate" },
            [ordered]@{ id = "qcl100_full_parity_promotion_attempt" }
        )
    }
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-plan"
        status = "lower_gate_plan_only"
        mode = "lower_gate_plan_only"
        non_live_artifact = $true
        launched = $false
        device_mutation_performed = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        lower_gate_plan = $plan
    }
}

function New-Qcl100LowerGateEvidenceSelfTestRouteSummary {
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-route"
        status = "preflight_only"
        launched = $false
        same_group_duplex_claimed = $false
        preflight = [ordered]@{
            infrastructure_wifi_disconnected = $true
            p2p0_ipv4_cleared = $true
            candidate_wifi_direct_prelaunch_routes_clear = $true
        }
    }
}

function New-Qcl100LowerGateEvidenceSelfTestMatrixSummary {
    param(
        [bool]$Pass = $true,
        [bool]$ClientP2pNetworkCallbackSeen = $Pass,
        [bool]$ClientP2pNetworkVisibleApp = $Pass,
        [bool]$ClientP2pNetworkLinkPropertiesPresent = $Pass,
        [bool]$ClientP2pNetworkRouteMatchesGroupOwner = $Pass,
        [bool]$ClientP2pNetworkSocketAuthorityPass = $Pass,
        [int]$UdpNetworkBoundReceiverObservedPackets = $(if ($Pass) { 1 } else { 0 }),
        [bool]$TcpTunnelStreamBidirectionalBytesPass = $Pass,
        [string[]]$ReceiverObservedUdpModes = $(if ($Pass) { @("udp_network_bound") } else { @() }),
        [string[]]$ReceiverObservedTcpModes = $(if ($Pass) { @("tcp_tunnel_stream_socket") } else { @() }),
        [bool]$LocalP2pBindNonPromoting = $false,
        [bool]$LocalP2pBindUdpPass = $false,
        [int]$LocalP2pBindUdpReceiverObservedPackets = 0,
        [bool]$LocalP2pBindTcpPass = $false,
        [int]$LocalP2pBindTcpReceiverAccepts = 0,
        [bool]$LocalP2pBindTcpStreamPass = $false,
        [int]$LocalP2pBindTcpStreamReceiverAccepts = 0,
        [int]$LocalP2pBindTcpStreamBytesPerDirection = 0
    )
    [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-matrix"
        status = "pass"
        matrix_focus = "qcl100_control_tcp_gate"
        qcl100_control_tcp_gate = $true
        require_tcp_tunnel_stream_pass = $true
        blocked_reason = $(if ($LocalP2pBindTcpStreamPass -and -not $Pass) { "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent" } else { "" })
        preflight = [ordered]@{
            infrastructure_wifi_disconnected = $true
            p2p0_ipv4_cleared = $true
            candidate_wifi_direct_prelaunch_routes_clear = $true
        }
        matrix = [ordered]@{
            client_p2p_network_callback_seen = $ClientP2pNetworkCallbackSeen
            client_p2p_network_visible_app = $ClientP2pNetworkVisibleApp
            client_p2p_network_selected_handle = $(if ($ClientP2pNetworkVisibleApp) { 123456 } else { $null })
            client_p2p_network_selected_interface = $(if ($ClientP2pNetworkVisibleApp) { "p2p0" } else { "" })
            client_p2p_network_link_properties_present = $ClientP2pNetworkLinkPropertiesPresent
            client_p2p_network_route_matches_group_owner = $ClientP2pNetworkRouteMatchesGroupOwner
            client_p2p_network_capability_wifi_p2p = $ClientP2pNetworkVisibleApp
            client_p2p_network_capability_local_network = $false
            client_p2p_network_socket_authority_attempted = $ClientP2pNetworkSocketAuthorityPass
            client_p2p_network_socket_authority_pass = $ClientP2pNetworkSocketAuthorityPass
            udp_network_bound_receiver_observed_packets = $UdpNetworkBoundReceiverObservedPackets
            udp_network_bound_receiver_observed_source_address = $(if ($UdpNetworkBoundReceiverObservedPackets -gt 0) { "192.168.49.46" } else { "" })
            udp_network_bound_receiver_observed_source_matches_client_p2p = [bool]($UdpNetworkBoundReceiverObservedPackets -gt 0)
            udp_network_bound_network_handle = $(if ($UdpNetworkBoundReceiverObservedPackets -gt 0) { 123456 } else { $null })
            client_p2p_interface_local_bind_non_promoting = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_socket_authority = $(if ($LocalP2pBindNonPromoting) { "network_interface_local_p2p_address_bind" } else { "" })
            client_p2p_interface_local_bind_udp_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_udp_pass = $LocalP2pBindUdpPass
            client_p2p_interface_local_bind_udp_receiver_observed_packets = $LocalP2pBindUdpReceiverObservedPackets
            client_p2p_interface_local_bind_udp_receiver_observed_source_address = $(if ($LocalP2pBindUdpReceiverObservedPackets -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_tcp_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_tcp_pass = $LocalP2pBindTcpPass
            client_p2p_interface_local_bind_tcp_receiver_accepts = $LocalP2pBindTcpReceiverAccepts
            client_p2p_interface_local_bind_tcp_receiver_accepted_source = $(if ($LocalP2pBindTcpReceiverAccepts -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_tcp_stream_attempted = $LocalP2pBindNonPromoting
            client_p2p_interface_local_bind_tcp_stream_pass = $LocalP2pBindTcpStreamPass
            client_p2p_interface_local_bind_tcp_stream_receiver_accepts = $LocalP2pBindTcpStreamReceiverAccepts
            client_p2p_interface_local_bind_tcp_stream_receiver_accepted_source = $(if ($LocalP2pBindTcpStreamReceiverAccepts -gt 0) { "192.168.49.46" } else { "" })
            client_p2p_interface_local_bind_tcp_stream_client_to_owner_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            client_p2p_interface_local_bind_tcp_stream_owner_to_client_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            local_p2p_bind_tcp_stream_attempted = $LocalP2pBindNonPromoting
            local_p2p_bind_tcp_stream_pass = $LocalP2pBindTcpStreamPass
            local_p2p_bind_tcp_stream_receiver_accepts = $LocalP2pBindTcpStreamReceiverAccepts
            local_p2p_bind_tcp_stream_receiver_accepted_source = $(if ($LocalP2pBindTcpStreamReceiverAccepts -gt 0) { "192.168.49.46" } else { "" })
            local_p2p_bind_tcp_stream_sender_to_receiver_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            local_p2p_bind_tcp_stream_receiver_to_sender_rx_bytes = $(if ($LocalP2pBindTcpStreamPass) { $LocalP2pBindTcpStreamBytesPerDirection } else { 0 })
            client_strict_local_p2p_app_transport_pass = $LocalP2pBindTcpStreamPass
            qcl041_local_p2p_bind_stream_authority = $(if ($LocalP2pBindTcpStreamPass) { "diagnostic_pass" } else { "not_proven" })
            qcl100_android_network_authority = $(if ($Pass) { "pass" } else { "blocked" })
            qcl100_same_group_simultaneous_native_render = "not_promoted"
            receiver_observed_udp_modes = @($ReceiverObservedUdpModes)
            receiver_observed_tcp_modes = @($ReceiverObservedTcpModes)
            tcp_tunnel_stream_bidirectional_bytes_pass = $TcpTunnelStreamBidirectionalBytesPass
        }
    }
}

function New-Qcl100LowerGateEvidenceSelfTestXrReadinessSummary {
    param(
        [string]$MatrixSummaryPath = "",
        [string]$MatrixRunId = "qcl100-lower-gate-evidence-selftest-matrix",
        [bool]$MatrixGatePassed = $true,
        [string]$Qcl100LowerGateAuthority = "android_connectivitymanager_network"
    )
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-xr"
        status = "pass"
        mode = "xr_launch_readiness_only"
        qcl100_lower_gate_authority = $Qcl100LowerGateAuthority
        require_qcl041_matrix_gate_pass = $true
        required_qcl041_matrix_summary_path = $MatrixSummaryPath
        required_qcl041_matrix_run_id = $MatrixRunId
        qcl041_matrix_gate = [ordered]@{
            artifact_path = $MatrixSummaryPath
            resolved_artifact_path = $MatrixSummaryPath
            parsed = $true
            run_id = $MatrixRunId
            passed = $MatrixGatePassed
        }
        launched = $false
        same_group_duplex_claimed = $false
        owner_xr_launch_readiness = [ordered]@{ xr_launch_ready = $true }
        client_xr_launch_readiness = [ordered]@{ xr_launch_ready = $true }
        freshness_acceptance = [ordered]@{
            qcl041_matrix_gate_required = $true
            qcl041_matrix_gate_evaluated = $true
            qcl041_matrix_gate_artifact = $MatrixSummaryPath
            qcl041_matrix_gate_passed = $MatrixGatePassed
            qcl041_matrix_gate_passes_requirement = $true
            required_qcl041_matrix_run_id = $MatrixRunId
            qcl041_matrix_gate_run_id = $MatrixRunId
            owner_xr_launch_ready = $true
            client_xr_launch_ready = $true
        }
    }
}

function New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary {
    param(
        [bool]$SameGroupDuplexClaimed = $false,
        [bool]$CleanupSkipped = $false,
        [int]$SystemFatalCount = 0,
        [string]$MatrixSummaryPath = "",
        [string]$MatrixRunId = "qcl100-lower-gate-evidence-selftest-matrix",
        [bool]$MatrixGatePassed = $true,
        [string]$Qcl100LowerGateAuthority = "android_connectivitymanager_network"
    )
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-no-media"
        status = "pass"
        mode = "no_media_launch_only"
        qcl100_lower_gate_authority = $Qcl100LowerGateAuthority
        require_qcl041_matrix_gate_pass = $true
        required_qcl041_matrix_summary_path = $MatrixSummaryPath
        required_qcl041_matrix_run_id = $MatrixRunId
        qcl041_matrix_gate = [ordered]@{
            artifact_path = $MatrixSummaryPath
            resolved_artifact_path = $MatrixSummaryPath
            parsed = $true
            run_id = $MatrixRunId
            passed = $MatrixGatePassed
        }
        qcl041_started = $false
        qcl082_media_started = $false
        promotion_allowed = $false
        same_group_duplex_claimed = $SameGroupDuplexClaimed
        owner_no_media_launch_pass = $true
        client_no_media_launch_pass = $true
        native_log_summary = [ordered]@{
            fatal_count = 0
            system_fatal_count = $SystemFatalCount
        }
        cleanup_policy = [ordered]@{
            final_force_stop_cleanup_skipped = $CleanupSkipped
        }
        freshness_acceptance = [ordered]@{
            qcl041_matrix_gate_required = $true
            qcl041_matrix_gate_evaluated = $true
            qcl041_matrix_gate_artifact = $MatrixSummaryPath
            qcl041_matrix_gate_passed = $MatrixGatePassed
            qcl041_matrix_gate_passes_requirement = $true
            required_qcl041_matrix_run_id = $MatrixRunId
            qcl041_matrix_gate_run_id = $MatrixRunId
            native_log_system_fatal_count = $SystemFatalCount
            native_log_fatal_count = 0
        }
    }
}

function Write-Qcl100LowerGateEvidenceJsonFile {
    param(
        [object]$Value,
        [string]$Path
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $Value | ConvertTo-Json -Depth 24 | Set-Content -Path $Path -Encoding UTF8
}

function Write-Qcl100LowerGateEvidenceSelfTestArtifact {
    param(
        [string]$Path,
        [object]$Value
    )
    Write-Qcl100LowerGateEvidenceJsonFile -Value $Value -Path $Path
    return $Path
}

function Invoke-Qcl100LowerGateEvidenceSelfTest {
    param([string]$OutputDirectory = $OutDir)
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $OutputDirectory = Join-Path $env:TEMP "qcl100-lower-gate-evidence-selftest"
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

    $planPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "lower-gate-plan-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestPlanSummary -ArtifactDirectory $OutputDirectory)
    $rustyDirectPlanPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "lower-gate-plan-summary-rusty-direct.json") -Value (New-Qcl100LowerGateEvidenceSelfTestPlanSummary `
            -ArtifactDirectory $OutputDirectory `
            -MatrixSummaryFile "qcl041-control-tcp-summary-local-p2p-bind-only.json" `
            -XrSummaryFile "xr-readiness-summary-local-p2p-bind-only.json" `
            -NoMediaSummaryFile "no-media-summary-local-p2p-bind-only.json")
    $routePath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "route-clear-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestRouteSummary)
    $matrixPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "qcl041-control-tcp-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestMatrixSummary)
    $localP2pBindOnlyMatrixPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "qcl041-control-tcp-summary-local-p2p-bind-only.json") -Value (New-Qcl100LowerGateEvidenceSelfTestMatrixSummary `
            -Pass $false `
            -ReceiverObservedUdpModes @("udp_local_p2p_bind_echo") `
            -ReceiverObservedTcpModes @("tcp_local_p2p_bind_socket", "tcp_local_p2p_bind_stream_socket") `
            -LocalP2pBindNonPromoting $true `
            -LocalP2pBindUdpPass $true `
            -LocalP2pBindUdpReceiverObservedPackets 4 `
            -LocalP2pBindTcpPass $true `
            -LocalP2pBindTcpReceiverAccepts 1 `
            -LocalP2pBindTcpStreamPass $true `
            -LocalP2pBindTcpStreamReceiverAccepts 1 `
            -LocalP2pBindTcpStreamBytesPerDirection 4194304)
    $xrPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "xr-readiness-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestXrReadinessSummary -MatrixSummaryPath $matrixPath)
    $noMediaPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -MatrixSummaryPath $matrixPath)
    $xrLocalP2pPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "xr-readiness-summary-local-p2p-bind-only.json") -Value (New-Qcl100LowerGateEvidenceSelfTestXrReadinessSummary -MatrixSummaryPath $localP2pBindOnlyMatrixPath -Qcl100LowerGateAuthority "rusty_direct_p2p_socket_authority")
    $noMediaLocalP2pPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-local-p2p-bind-only.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -MatrixSummaryPath $localP2pBindOnlyMatrixPath -Qcl100LowerGateAuthority "rusty_direct_p2p_socket_authority")
    $duplexClaimPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-duplex-claim.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -SameGroupDuplexClaimed $true -MatrixSummaryPath $matrixPath)
    $skippedCleanupPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-skipped-cleanup.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -CleanupSkipped $true -MatrixSummaryPath $matrixPath)

    $passCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrPath `
        -NoMediaLaunchSummaryPath $noMediaPath `
        -RequireQcl041ClientP2pNetworkCallbackSeen `
        -RequireQcl041ClientP2pNetworkSocketAuthority `
        -RequireQcl041StrictUdpDatagramEchoPass `
        -RequireQcl041TcpTunnelStreamPass
    if (-not [bool]$passCase.passed) {
        throw "QCL100 lower-gate evidence self-test expected pass case to pass."
    }

    $localP2pBindOnlyCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $rustyDirectPlanPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $localP2pBindOnlyMatrixPath `
        -XrReadinessSummaryPath $xrLocalP2pPath `
        -NoMediaLaunchSummaryPath $noMediaLocalP2pPath `
        -RequireQcl041ClientP2pNetworkCallbackSeen `
        -RequireQcl041ClientP2pNetworkSocketAuthority `
        -RequireQcl041StrictUdpDatagramEchoPass `
        -RequireQcl041TcpTunnelStreamPass
    if ([bool]$localP2pBindOnlyCase.passed -or $localP2pBindOnlyCase.blocked_reason_for_qcl100 -ne "qcl041_strict_local_p2p_app_transport_pass_connectivitymanager_network_absent") {
        throw "QCL100 lower-gate evidence self-test expected local p2p bind-only diagnostics to stay blocked on absent ConnectivityManager.Network authority."
    }
    $localP2pBindOnlyGate = @($localP2pBindOnlyCase.gates | Where-Object { $_.id -eq "qcl041_strict_control_tcp_gate" } | Select-Object -First 1)
    if ($localP2pBindOnlyGate.first_qcl041_lower_gate_issue -ne "qcl041_client_p2p_network_callback_not_seen") {
        throw "QCL100 lower-gate evidence self-test expected local p2p bind-only granular issue to remain callback-visible Network absence."
    }
    if (-not [bool]$localP2pBindOnlyGate.fields.local_p2p_bind_diagnostic_non_promoting -or -not [bool]$localP2pBindOnlyGate.fields.local_p2p_bind_udp_pass -or -not [bool]$localP2pBindOnlyGate.fields.local_p2p_bind_tcp_pass -or -not [bool]$localP2pBindOnlyGate.fields.local_p2p_bind_tcp_stream_pass) {
        throw "QCL100 lower-gate evidence self-test expected local p2p bind diagnostic fields to be preserved in the control-TCP gate."
    }
    if ($localP2pBindOnlyGate.fields.qcl041_local_p2p_bind_stream_authority -ne "diagnostic_pass" -or
            $localP2pBindOnlyGate.fields.qcl100_android_network_authority -ne "blocked" -or
            $localP2pBindOnlyCase.authority_labels.qcl100_same_group_simultaneous_native_render -ne "not_promoted") {
        throw "QCL100 lower-gate evidence self-test expected local p2p bind authority labels to stay non-promoting."
    }
    $localP2pRustyDirectCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $rustyDirectPlanPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $localP2pBindOnlyMatrixPath `
        -XrReadinessSummaryPath $xrLocalP2pPath `
        -NoMediaLaunchSummaryPath $noMediaLocalP2pPath `
        -Qcl100LowerGateAuthority "rusty_direct_p2p_socket_authority" `
        -RequireQcl041TcpTunnelStreamPass
    if (-not [bool]$localP2pRustyDirectCase.passed) {
        throw "QCL100 lower-gate evidence self-test expected local p2p bind-only evidence to pass under rusty_direct_p2p_socket_authority."
    }
    if ($localP2pRustyDirectCase.accepted_lower_gate_authority -ne "rusty_direct_p2p_socket_authority" -or
            $localP2pRustyDirectCase.authority_labels.rusty_direct_p2p_socket_authority -ne "pass" -or
            [bool]$localP2pRustyDirectCase.promotion_allowed -or
            [bool]$localP2pRustyDirectCase.same_group_duplex_claimed) {
        throw "QCL100 lower-gate evidence self-test expected Rusty direct authority to pass without promotion."
    }

    $mismatchedXrReferenceCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrLocalP2pPath `
        -NoMediaLaunchSummaryPath $noMediaPath `
        -RequireQcl041ClientP2pNetworkCallbackSeen `
        -RequireQcl041ClientP2pNetworkSocketAuthority `
        -RequireQcl041StrictUdpDatagramEchoPass `
        -RequireQcl041TcpTunnelStreamPass
    if ([bool]$mismatchedXrReferenceCase.passed -or @($mismatchedXrReferenceCase.issues.code) -notcontains "qcl100_xr_readiness_qcl041_matrix_artifact_path_mismatch") {
        throw "QCL100 lower-gate evidence self-test expected XR/QCL041 provenance mismatch to fail."
    }

    $missingNoMediaCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrPath `
        -NoMediaLaunchSummaryPath (Join-Path $OutputDirectory "missing-no-media-summary.json")
    if ([bool]$missingNoMediaCase.passed -or $missingNoMediaCase.first_issue -ne "qcl100_no_media_launch_gate_artifact_missing") {
        throw "QCL100 lower-gate evidence self-test expected missing no-media artifact to fail first."
    }

    $duplexClaimCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrPath `
        -NoMediaLaunchSummaryPath $duplexClaimPath
    if ([bool]$duplexClaimCase.passed -or @($duplexClaimCase.issues.code) -notcontains "qcl100_no_media_launch_gate_premature_same_group_duplex_claim") {
        throw "QCL100 lower-gate evidence self-test expected premature duplex claim to fail."
    }

    $skippedCleanupCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrPath `
        -NoMediaLaunchSummaryPath $skippedCleanupPath
    if ([bool]$skippedCleanupCase.passed -or @($skippedCleanupCase.issues.code) -notcontains "qcl100_no_media_cleanup_skipped") {
        throw "QCL100 lower-gate evidence self-test expected skipped cleanup to fail by default."
    }

    $selfTest = [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_evidence_self_test.v1"
        pass_case = $passCase
        local_p2p_bind_only_case = $localP2pBindOnlyCase
        local_p2p_rusty_direct_case = $localP2pRustyDirectCase
        mismatched_xr_reference_case = $mismatchedXrReferenceCase
        missing_no_media_case = $missingNoMediaCase
        premature_duplex_claim_case = $duplexClaimCase
        skipped_cleanup_case = $skippedCleanupCase
        passed = $true
    }
    Write-Qcl100LowerGateEvidenceJsonFile -Value $selfTest -Path (Join-Path $OutputDirectory "qcl100-lower-gate-evidence-self-test.json")
    return $selfTest
}
