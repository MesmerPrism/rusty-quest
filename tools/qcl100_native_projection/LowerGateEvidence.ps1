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
        $Plan = $null
    )
    $gateId = "qcl041_strict_control_tcp_gate"
    $artifact = Read-Qcl100LowerGateEvidenceArtifact -GateId $gateId -Path $Path
    $issues = [System.Collections.ArrayList]::new()
    Add-Qcl100LowerGateArtifactIssues -Issues $issues -Artifact $artifact
    $fields = [ordered]@{}

    if ([bool]$artifact.metadata.parsed) {
        $summary = $artifact.object
        $fields = [ordered]@{
            status = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "status")
            matrix_focus = [string](Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "matrix_focus")
            qcl100_control_tcp_gate = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "qcl100_control_tcp_gate")
            require_tcp_tunnel_stream_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidenceProperty -Object $summary -Name "require_tcp_tunnel_stream_pass")
            preflight_infrastructure_wifi_disconnected = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.infrastructure_wifi_disconnected")
            preflight_p2p0_ipv4_cleared = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.p2p0_ipv4_cleared")
            preflight_candidate_wifi_direct_routes_clear = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "preflight.candidate_wifi_direct_prelaunch_routes_clear")
            matrix_tcp_tunnel_stream_bidirectional_bytes_pass = Get-Qcl100LowerGateEvidenceBool (Get-Qcl100LowerGateEvidencePathValue -Object $summary -Path "matrix.tcp_tunnel_stream_bidirectional_bytes_pass")
        }
        if ($fields.status -ne "pass") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_status_not_pass" -Message "QCL041 control-TCP lower gate expected status=pass." -ArtifactPath $Path
        }
        if ($fields.matrix_focus -ne "qcl100_control_tcp_gate") {
            Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_wrong_matrix_focus" -Message "QCL041 control-TCP lower gate expected matrix_focus=qcl100_control_tcp_gate." -ArtifactPath $Path
        }
        foreach ($field in @("qcl100_control_tcp_gate", "require_tcp_tunnel_stream_pass", "preflight_infrastructure_wifi_disconnected", "preflight_p2p0_ipv4_cleared", "preflight_candidate_wifi_direct_routes_clear", "matrix_tcp_tunnel_stream_bidirectional_bytes_pass")) {
            if (-not [bool]$fields[$field]) {
                Add-Qcl100LowerGateEvidenceIssue -Issues $issues -GateId $gateId -Code "qcl041_control_tcp_${field}_not_true" -Message "QCL041 control-TCP lower gate missing required true field: $field." -ArtifactPath $Path
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
        [switch]$AllowSkippedCleanup
    )
    $plan = Get-Qcl100LowerGateEvidencePlanFromSummary -Path $PlanSummaryPath
    $gates = @(
        (Test-Qcl100LowerGatePlanSummaryEvidence -Path $PlanSummaryPath),
        (Test-Qcl100LowerGateRouteClearEvidence -Path $RouteClearSummaryPath -Plan $plan),
        (Test-Qcl100LowerGateControlTcpEvidence -Path $Qcl041ControlTcpSummaryPath -Plan $plan),
        (Test-Qcl100LowerGateXrReadinessEvidence -Path $XrReadinessSummaryPath -Plan $plan),
        (Test-Qcl100LowerGateNoMediaEvidence -Path $NoMediaLaunchSummaryPath -Plan $plan -AllowSkippedCleanup:$AllowSkippedCleanup)
    )
    $issues = [System.Collections.ArrayList]::new()
    foreach ($gate in @($gates)) {
        foreach ($issue in @($gate.issues)) {
            [void]$issues.Add($issue)
        }
    }
    $firstIssue = if ($issues.Count -gt 0) { $issues[0].code } else { "" }
    [ordered]@{
        schema = "rusty.quest.qcl100_lower_gate_evidence.v1"
        generated_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        status = if ($issues.Count -eq 0) { "pass" } else { "blocked" }
        passed = [bool]($issues.Count -eq 0)
        promotion_allowed = $false
        same_group_duplex_claimed = $false
        allow_skipped_cleanup = [bool]$AllowSkippedCleanup
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
        deferred_full_promotion_reason = "QCL100 promotion remains blocked until lower-gate evidence, short control-TCP media, final-window renderer scorecards, receiver-observed bytes, cleanup, and zero native/system fatal lines all pass."
    }
}

function New-Qcl100LowerGateEvidenceSelfTestPlanSummary {
    param([string]$ArtifactDirectory = "")
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
                expected_artifacts = @((Join-Path $ArtifactDirectory "qcl041-control-tcp-summary-pass.json"))
            },
            [ordered]@{
                id = "qcl100_xr_readiness_gate"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-xr") }
                expected_artifacts = @((Join-Path $ArtifactDirectory "xr-readiness-summary-pass.json"))
            },
            [ordered]@{
                id = "qcl100_no_media_launch_gate"
                command = [ordered]@{ arguments = @("-RunId", "qcl100-lower-gate-evidence-selftest-no-media") }
                expected_artifacts = @((Join-Path $ArtifactDirectory "no-media-summary-pass.json"))
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
    [ordered]@{
        schema = "rusty.quest.qcl041_q2q_app_bound_socket_matrix_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-matrix"
        status = "pass"
        matrix_focus = "qcl100_control_tcp_gate"
        qcl100_control_tcp_gate = $true
        require_tcp_tunnel_stream_pass = $true
        preflight = [ordered]@{
            infrastructure_wifi_disconnected = $true
            p2p0_ipv4_cleared = $true
            candidate_wifi_direct_prelaunch_routes_clear = $true
        }
        matrix = [ordered]@{
            tcp_tunnel_stream_bidirectional_bytes_pass = $true
        }
    }
}

function New-Qcl100LowerGateEvidenceSelfTestXrReadinessSummary {
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-xr"
        status = "pass"
        mode = "xr_launch_readiness_only"
        launched = $false
        same_group_duplex_claimed = $false
        owner_xr_launch_readiness = [ordered]@{ xr_launch_ready = $true }
        client_xr_launch_readiness = [ordered]@{ xr_launch_ready = $true }
        freshness_acceptance = [ordered]@{
            qcl041_matrix_gate_passes_requirement = $true
            owner_xr_launch_ready = $true
            client_xr_launch_ready = $true
        }
    }
}

function New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary {
    param(
        [bool]$SameGroupDuplexClaimed = $false,
        [bool]$CleanupSkipped = $false,
        [int]$SystemFatalCount = 0
    )
    [ordered]@{
        schema = "rusty.quest.qcl100_quest_to_quest_native_stereo_projection_wifi_direct_run.v1"
        run_id = "qcl100-lower-gate-evidence-selftest-no-media"
        status = "pass"
        mode = "no_media_launch_only"
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
    $routePath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "route-clear-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestRouteSummary)
    $matrixPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "qcl041-control-tcp-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestMatrixSummary)
    $xrPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "xr-readiness-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestXrReadinessSummary)
    $noMediaPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-pass.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary)
    $duplexClaimPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-duplex-claim.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -SameGroupDuplexClaimed $true)
    $skippedCleanupPath = Write-Qcl100LowerGateEvidenceSelfTestArtifact -Path (Join-Path $OutputDirectory "no-media-summary-skipped-cleanup.json") -Value (New-Qcl100LowerGateEvidenceSelfTestNoMediaSummary -CleanupSkipped $true)

    $passCase = Get-Qcl100LowerGateEvidence `
        -PlanSummaryPath $planPath `
        -RouteClearSummaryPath $routePath `
        -Qcl041ControlTcpSummaryPath $matrixPath `
        -XrReadinessSummaryPath $xrPath `
        -NoMediaLaunchSummaryPath $noMediaPath
    if (-not [bool]$passCase.passed) {
        throw "QCL100 lower-gate evidence self-test expected pass case to pass."
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
        missing_no_media_case = $missingNoMediaCase
        premature_duplex_claim_case = $duplexClaimCase
        skipped_cleanup_case = $skippedCleanupCase
        passed = $true
    }
    Write-Qcl100LowerGateEvidenceJsonFile -Value $selfTest -Path (Join-Path $OutputDirectory "qcl100-lower-gate-evidence-self-test.json")
    return $selfTest
}
