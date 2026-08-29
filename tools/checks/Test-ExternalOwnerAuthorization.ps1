[CmdletBinding()]
param([string]$RepoRoot = "")
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path } else { $RepoRoot = (Resolve-Path $RepoRoot).Path }
Import-Module (Join-Path $RepoRoot ".github\scripts\lib\ExternalOwnerAuthorization.psm1") -Force
$authorizationSchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization.v1.schema.json"
$policySchema = Join-Path $RepoRoot "schemas\rusty.quest.external_owner_authorization_policy.v1.schema.json"
$policy = Read-ExternalOwnerAuthorizationPolicy (Join-Path $RepoRoot "config\external-owner-authorization.json") $policySchema

function Assert-DamageRejected {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Action)
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "External-owner damage was accepted: $Name" }
}

function Test-CanonicalEqual {
    param(
        [Parameter(Mandatory)][object]$Left,
        [Parameter(Mandatory)][object]$Right
    )
    return [Security.Cryptography.CryptographicOperations]::FixedTimeEquals(
        (Get-CanonicalAuthorizationBytes $Left),
        (Get-CanonicalAuthorizationBytes $Right)
    )
}

function Assert-ExternalOwnerClosedProfileDamageRejected {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Action)
    try {
        & $Action
    } catch {
        if ([string]$_.Exception.Message -cne "Pinned verifier result is not the exact protected-without-base-approval hold.") {
            throw "ANSI closed-profile damage rejected outside its verifier gate: $Name"
        }
        return
    }
    throw "ANSI closed-profile damage was accepted: $Name"
}

function Invoke-ExternalOwnerChildFailureFixture {
    param([Parameter(Mandatory)][string]$ScriptText)
    $pwsh = (Get-Command pwsh -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
    $encoded = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($ScriptText)
    )
    $output = @(& $pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
        -EncodedCommand $encoded 2>&1)
    return [pscustomobject]@{
        exit_code = [int]$LASTEXITCODE
        output = $output
    }
}

function New-ExternalOwnerChildFailureRecord {
    param(
        [Parameter(Mandatory)][string]$Message,
        [string]$FullyQualifiedErrorId = "NativeCommandError",
        [Management.Automation.ErrorCategory]$Category = [Management.Automation.ErrorCategory]::NotSpecified,
        [AllowEmptyString()][string]$Target = $Message
    )
    return [Management.Automation.ErrorRecord]::new(
        [Management.Automation.RemoteException]::new($Message),
        $FullyQualifiedErrorId, $Category, $Target
    )
}

$rsa = [Security.Cryptography.RSA]::Create(3072)
try {
    $now = [datetimeoffset]::Parse("2026-08-22T16:00:00Z")
    $artifacts = @([ordered]@{ path = "config/external-validation-authority.json"; state = "present"; mode = "100644"; size_bytes = 3; sha256 = ("a" * 64) })
    $baseIdentity = [ordered]@{ commit = ("1" * 40); tree = ("2" * 40) }
    $headIdentity = [ordered]@{ commit = ("3" * 40); tree = ("4" * 40) }
    $mergeIdentity = [ordered]@{ commit = ("5" * 40); tree = ("4" * 40) }
    $assessment = [ordered]@{
        schema = "rusty.quest.external_validation_authority_assessment.v1"
        policy_id = "rusty-quest-external-validation-authority-v1"
        policy_sha256 = ("b" * 64)
        repository = "MesmerPrism/rusty-quest"
        pull_request_number = 53
        event_identity = [ordered]@{
            base_repository = "MesmerPrism/rusty-quest"
            base_ref = "main"
            head_repository = "MesmerPrism/rusty-quest"
            merge_commit_observation = $null
            merge_commit_relation = "event-merge-observation-absent"
        }
        workflow = [ordered]@{
            event = "pull_request_target"
            run_id = "33151222801"
            run_attempt = 1
        }
        runtime = [ordered]@{
            powershell = [ordered]@{
                edition = "Core"
                version = "7.6.5"
                executable_bytes = 301368
                executable_sha256 = ("c" * 64)
            }
            git = [ordered]@{
                version = "git version 2.55.0.windows.5"
                executable_bytes = 43352
                executable_sha256 = ("d" * 64)
            }
            runner = [ordered]@{
                label = "windows-2025"
                os = "Windows"
                architecture = "X64"
                image_os = "win25-vs2026"
                image_version = "20260824.214.3"
                image_allowlist_enforced = $false
                drift_status = "observed-unpinned"
            }
        }
        base = $baseIdentity
        candidate = $headIdentity
        merge = $mergeIdentity
        changed_paths = @($artifacts[0].path)
        protected_paths = @($artifacts[0].path)
        decision = "protected-without-base-approval"
        approval_id = $null
        candidate_code_executed = $false
        execution_attested = $false
        publication_authority = $false
        limitations = @(
            "Static admission only; no candidate code was executed.",
            "Execution, tests, and owner-effect evidence require separate trusted validation.",
            "This assessment does not authorize publication.",
            "Runner image and tool identities are observed exactly but not allowlisted."
        )
    }
    $policyPath = Join-Path $RepoRoot "config\external-validation-authority.json"
    [byte[]]$policyBytes = [IO.File]::ReadAllBytes($policyPath)
    $authorityPolicy = [Text.UTF8Encoding]::new($false, $true).GetString($policyBytes) |
        ConvertFrom-Json -Depth 30
    $hold = New-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
        -Policy $authorityPolicy -PolicyBytes $policyBytes -Base $baseIdentity `
        -Candidate $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts
    $holdDecisionDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdDecisionDamage.decision = "unprotected"
    $holdApprovalDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdApprovalDamage.approval_id = "unexpected-approval"
    $holdInventoryDamage = $hold | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $holdInventoryDamage.changed_paths = @()
    foreach ($damage in @(
        [pscustomobject]@{ name = "hold-decision"; value = $holdDecisionDamage },
        [pscustomobject]@{ name = "hold-approval"; value = $holdApprovalDamage },
        [pscustomobject]@{ name = "hold-inventory"; value = $holdInventoryDamage }
    )) {
        Assert-DamageRejected $damage.name {
            Assert-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
                -Assessment $damage.value -Policy $authorityPolicy -PolicyBytes $policyBytes `
                -Base $baseIdentity -Candidate $headIdentity `
                -ChangedArtifacts $artifacts -ProtectedArtifacts $artifacts
        }
    }
    $holdMessage = "Protected changes do not match an exact base-approved change set."
    $transportMessage = "Exception: $holdMessage`r`n"
    $directFailure = $null
    try {
        throw [Management.Automation.RuntimeException]::new($holdMessage)
    } catch {
        $directFailure = $_
    }
    if (
        $directFailure -isnot [Management.Automation.ErrorRecord] -or
        $directFailure.Exception.GetType() -ne [Management.Automation.RuntimeException] -or
        [string]$directFailure.Exception.Message -cne $holdMessage
    ) {
        throw "Direct verifier hold behavior changed."
    }
    $serializedHold = Invoke-ExternalOwnerChildFailureFixture "throw '$holdMessage'"
    if (
        $serializedHold.exit_code -ne 1 -or
        $serializedHold.output.Count -ne 1 -or
        $serializedHold.output[0] -isnot [Management.Automation.ErrorRecord] -or
        $serializedHold.output[0].Exception.GetType() -ne [Management.Automation.RemoteException] -or
        [string]$serializedHold.output[0].FullyQualifiedErrorId -cne "NativeCommandError" -or
        [string]$serializedHold.output[0].CategoryInfo.Category -cne "NotSpecified" -or
        [string]$serializedHold.output[0].TargetObject -cne $transportMessage -or
        [string]$serializedHold.output[0].Exception.Message -cne $transportMessage
    ) {
        throw "Exact Windows child verifier hold transport changed."
    }
    Assert-ExternalOwnerFallbackVerifierFailure `
        -ExitCode $serializedHold.exit_code -Output $serializedHold.output
    $fileTransportVerifier = "C:\trusted-verifier\scripts\Test-ExternalValidationAuthority.ps1"
    $fileTransportLine = 969
    $fileTransportMessages = @(
        "Exception: $fileTransportVerifier`:$fileTransportLine",
        "Line |",
        (" {0,3} |          throw `"Protected changes do not match an exact base-approved  …" -f $fileTransportLine),
        "     |          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~",
        "     | $holdMessage"
    )
    $fileTransportIds = @(
        "NativeCommandError",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage",
        "NativeCommandErrorMessage"
    )
    function Get-ExternalOwnerAnsiFileTransportMessages {
        param(
            [Parameter(Mandatory)][string]$VerifierScript,
            [Parameter(Mandatory)][int]$VerifierLine,
            [Parameter(Mandatory)][string]$HoldMessage
        )
        $escape = [char]27
        $red = $escape + "[31;1m"
        $reset = $escape + "[0m"
        $cyan = $escape + "[36;1m"
        return [string[]]@(
            ($red + "Exception: " + $reset + $VerifierScript + ":" + $VerifierLine + $reset),
            ($red + $reset + $cyan + "Line |" + $reset),
            ($red + $reset + $cyan + $cyan + (" {0,3} | " -f $VerifierLine) +
                $reset + "         " + $cyan +
                "throw `"Protected changes do not match an exact base-approved " +
                $reset + " …" + $reset),
            ($red + $reset + $cyan + $cyan + $reset + $cyan + $reset + $cyan +
                "     | " + $red +
                "         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~" + $reset),
            ($red + $reset + $cyan + $cyan + $reset + $cyan + $reset + $cyan +
                $red + $red + $cyan + "     | " + $red + $HoldMessage + $reset
            )
        )
    }
    function Get-ExternalOwnerUtf8Sha256 {
        param([Parameter(Mandatory)][string]$Text)
        [byte[]]$bytes = [Text.UTF8Encoding]::new($false, $true).GetBytes($Text)
        return [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)
        ).ToLowerInvariant()
    }
    function New-ExternalOwnerFileTransportFixture {
        param(
            [Parameter(Mandatory)][string[]]$Messages,
            [int]$MessageDamageIndex = -1,
            [int]$FullyQualifiedErrorIdDamageIndex = -1,
            [int]$CategoryDamageIndex = -1,
            [int]$TargetDamageIndex = -1,
            [int]$ExceptionDamageIndex = -1
        )
        return @(
            for ($index = 0; $index -lt 5; $index++) {
                $message = if ($index -eq $MessageDamageIndex) {
                    $Messages[$index] + " damage"
                } else { $Messages[$index] }
                $id = if ($index -eq $FullyQualifiedErrorIdDamageIndex) {
                    "PinnedVerifier"
                } else { $fileTransportIds[$index] }
                $category = if ($index -eq $CategoryDamageIndex) {
                    [Management.Automation.ErrorCategory]::InvalidData
                } else { [Management.Automation.ErrorCategory]::NotSpecified }
                $target = if ($index -eq $TargetDamageIndex) {
                    "wrong-target"
                } elseif ($index -eq 0) { $message } else { "" }
                $exception = if ($index -eq $ExceptionDamageIndex) {
                    [Management.Automation.RuntimeException]::new($message)
                } else { [Management.Automation.RemoteException]::new($message) }
                [Management.Automation.ErrorRecord]::new(
                    $exception, $id, $category, $target
                )
            }
        )
    }
    $fileTransportOutput = New-ExternalOwnerFileTransportFixture `
        -Messages $fileTransportMessages
    Assert-ExternalOwnerFallbackVerifierFailure `
        -ExitCode 1 -Output $fileTransportOutput `
        -VerifierScript $fileTransportVerifier -VerifierHoldLine $fileTransportLine
    $fileTransportDamageCases = @(
        [pscustomobject]@{ name = "wrong-script"; line = $fileTransportLine; output = $fileTransportOutput; script = "C:\wrong\Test-ExternalValidationAuthority.ps1"; exit = 1 },
        [pscustomobject]@{ name = "wrong-line"; line = ($fileTransportLine - 1); output = $fileTransportOutput; script = $fileTransportVerifier; exit = 1 },
        [pscustomobject]@{ name = "record-count-short"; line = $fileTransportLine; output = @($fileTransportOutput | Select-Object -First 4); script = $fileTransportVerifier; exit = 1 },
        [pscustomobject]@{ name = "record-count-extra"; line = $fileTransportLine; output = @($fileTransportOutput + $fileTransportOutput[4]); script = $fileTransportVerifier; exit = 1 },
        [pscustomobject]@{ name = "exit"; line = $fileTransportLine; output = $fileTransportOutput; script = $fileTransportVerifier; exit = 2 },
        [pscustomobject]@{ name = "fqid"; line = $fileTransportLine; output = (New-ExternalOwnerFileTransportFixture -Messages $fileTransportMessages -FullyQualifiedErrorIdDamageIndex 1); script = $fileTransportVerifier; exit = 1 },
        [pscustomobject]@{ name = "category"; line = $fileTransportLine; output = (New-ExternalOwnerFileTransportFixture -Messages $fileTransportMessages -CategoryDamageIndex 2); script = $fileTransportVerifier; exit = 1 },
        [pscustomobject]@{ name = "target"; line = $fileTransportLine; output = (New-ExternalOwnerFileTransportFixture -Messages $fileTransportMessages -TargetDamageIndex 0); script = $fileTransportVerifier; exit = 1 }
    )
    foreach ($index in 0..4) {
        $fileTransportDamageCases += [pscustomobject]@{
            name = "renderer-record-$index"; line = $fileTransportLine
            output = (New-ExternalOwnerFileTransportFixture -Messages $fileTransportMessages -MessageDamageIndex $index)
            script = $fileTransportVerifier; exit = 1
        }
    }
    foreach ($damage in $fileTransportDamageCases) {
        Assert-DamageRejected ("file-transport-" + $damage.name) {
            Assert-ExternalOwnerFallbackVerifierFailure `
                -ExitCode $damage.exit -Output $damage.output `
            -VerifierScript $damage.script -VerifierHoldLine $damage.line
        }
    }
    # This is the exact hosted windows-2025 renderer observation from the
    # non-authoritative diagnostic.  The permanent adapter still builds its
    # expected text from the live hash-verified verifier path; this fixed
    # template protects the captured ANSI byte shape from accidental drift.
    $hostedAnsiVerifier = "D:\a\rusty-quest\rusty-quest\pinned-verifier\scripts\Test-ExternalValidationAuthority.ps1"
    $hostedAnsiMessages = Get-ExternalOwnerAnsiFileTransportMessages `
        -VerifierScript $hostedAnsiVerifier -VerifierLine 969 `
        -HoldMessage $holdMessage
    $hostedAnsiExpected = @(
        [pscustomobject]@{ bytes = 119; sha256 = "24ade1f4eb1d7c360c21d6f6302a8cf685673577732235dd4b4c51b09cb99a93" },
        [pscustomobject]@{ bytes = 28; sha256 = "eab5810c4d6310caf3407134ffd9a1d2a6d2a80fbf7801e32c56b8bc0243872e" },
        [pscustomobject]@{ bytes = 125; sha256 = "8977d37fccc7351da85f28e1d99d20fe1545fb2b680dec7290b237b8991c7ef1" },
        [pscustomobject]@{ bytes = 135; sha256 = "5b55dd13ad3587247bb68a05e5df28df6ce4d2ec1769750a8d7c91df447a9615" },
        [pscustomobject]@{ bytes = 151; sha256 = "96371b12b6cfd7db954297523533338fd3f5720e4d279c2619dc99a67cda7a29" }
    )
    for ($index = 0; $index -lt 5; $index++) {
        $bytes = [Text.UTF8Encoding]::new($false, $true).GetByteCount($hostedAnsiMessages[$index])
        if ($bytes -ne $hostedAnsiExpected[$index].bytes -or
            (Get-ExternalOwnerUtf8Sha256 $hostedAnsiMessages[$index]) -cne $hostedAnsiExpected[$index].sha256) {
            throw "Hosted ANSI renderer template changed at record $index."
        }
    }
    $ansiFileTransportMessages = Get-ExternalOwnerAnsiFileTransportMessages `
        -VerifierScript $fileTransportVerifier -VerifierLine $fileTransportLine `
        -HoldMessage $holdMessage
    $ansiFileTransportOutput = New-ExternalOwnerFileTransportFixture `
        -Messages $ansiFileTransportMessages
    Assert-ExternalOwnerFallbackVerifierFailure `
        -ExitCode 1 -Output $ansiFileTransportOutput `
        -VerifierScript $fileTransportVerifier -VerifierHoldLine $fileTransportLine
    $ansiEsc = [char]27
    [string[]]$ansiEscDamageMessages = @($ansiFileTransportMessages)
    $ansiEscDamageMessages[0] = $ansiEscDamageMessages[0].Replace($ansiEsc, [char]26)
    [string[]]$ansiColorDamageMessages = @($ansiFileTransportMessages)
    $ansiColorDamageMessages[0] = $ansiColorDamageMessages[0].Replace("[31;1m", "[32;1m")
    [string[]]$ansiResetDamageMessages = @($ansiFileTransportMessages)
    $ansiResetDamageMessages[0] = $ansiResetDamageMessages[0].Replace("[0m", "[1m")
    [string[]]$ansiSourceDamageMessages = @($ansiFileTransportMessages)
    $ansiSourceDamageMessages[2] = $ansiSourceDamageMessages[2].Replace("throw", "write")
    [string[]]$ansiEllipsisDamageMessages = @($ansiFileTransportMessages)
    $ansiEllipsisDamageMessages[2] = $ansiEllipsisDamageMessages[2].Replace("…", "...")
    [string[]]$ansiMixedDamageMessages = @($ansiFileTransportMessages)
    $ansiMixedDamageMessages[4] = $fileTransportMessages[4]
    $ansiDamageCases = @(
        [pscustomobject]@{ name = "esc"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiEscDamageMessages) },
        [pscustomobject]@{ name = "color"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiColorDamageMessages) },
        [pscustomobject]@{ name = "reset"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiResetDamageMessages) },
        [pscustomobject]@{ name = "source"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiSourceDamageMessages) },
        [pscustomobject]@{ name = "ellipsis"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiEllipsisDamageMessages) },
        [pscustomobject]@{ name = "path"; line = $fileTransportLine; script = "C:\wrong\Test-ExternalValidationAuthority.ps1"; exit = 1; output = $ansiFileTransportOutput },
        [pscustomobject]@{ name = "line"; line = ($fileTransportLine - 1); script = $fileTransportVerifier; exit = 1; output = $ansiFileTransportOutput },
        [pscustomobject]@{ name = "record-identity"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -ExceptionDamageIndex 4) },
        [pscustomobject]@{ name = "fqid-first"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -FullyQualifiedErrorIdDamageIndex 0) },
        [pscustomobject]@{ name = "fqid-later"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -FullyQualifiedErrorIdDamageIndex 3) },
        [pscustomobject]@{ name = "category"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -CategoryDamageIndex 2) },
        [pscustomobject]@{ name = "target-first"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -TargetDamageIndex 0) },
        [pscustomobject]@{ name = "target-later"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiFileTransportMessages -TargetDamageIndex 3) },
        [pscustomobject]@{ name = "record-count-short"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = @($ansiFileTransportOutput | Select-Object -First 4) },
        [pscustomobject]@{ name = "record-count-extra"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = @($ansiFileTransportOutput + $ansiFileTransportOutput[4]) },
        [pscustomobject]@{ name = "output"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = @($ansiFileTransportOutput + "extra") },
        [pscustomobject]@{ name = "exit"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 2; output = $ansiFileTransportOutput },
        [pscustomobject]@{ name = "mixed-plain"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = (New-ExternalOwnerFileTransportFixture -Messages $ansiMixedDamageMessages) },
        [pscustomobject]@{ name = "partial"; line = $fileTransportLine; script = $fileTransportVerifier; exit = 1; output = @($ansiFileTransportOutput | Select-Object -First 2) }
    )
    foreach ($damage in $ansiDamageCases) {
        Assert-ExternalOwnerClosedProfileDamageRejected ("ansi-file-transport-" + $damage.name) {
            Assert-ExternalOwnerFallbackVerifierFailure `
                -ExitCode $damage.exit -Output $damage.output `
                -VerifierScript $damage.script -VerifierHoldLine $damage.line
        }
    }
    foreach ($damage in @(
        [pscustomobject]@{ name = "hold-prefix"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord "prefix $transportMessage") },
        [pscustomobject]@{ name = "hold-suffix"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord ($transportMessage + "suffix")) },
        [pscustomobject]@{ name = "hold-space"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord ($transportMessage + " ")) },
        [pscustomobject]@{ name = "hold-lf"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord "Exception: $holdMessage`n") },
        [pscustomobject]@{ name = "hold-double-crlf"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord ($transportMessage + "`r`n")) },
        [pscustomobject]@{ name = "hold-target"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord $transportMessage -Target "wrong-target") },
        [pscustomobject]@{ name = "hold-category"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord $transportMessage -Category ([Management.Automation.ErrorCategory]::InvalidData)) },
        [pscustomobject]@{ name = "hold-fqid"; exit = 1; output = @(New-ExternalOwnerChildFailureRecord $transportMessage -FullyQualifiedErrorId "PinnedVerifier") },
        [pscustomobject]@{ name = "hold-runtime-exception"; exit = 1; output = @([Management.Automation.ErrorRecord]::new([Management.Automation.RuntimeException]::new($transportMessage), "NativeCommandError", [Management.Automation.ErrorCategory]::NotSpecified, $transportMessage)) },
        [pscustomobject]@{ name = "hold-extra-output"; exit = 1; output = @($serializedHold.output[0], "extra") },
        [pscustomobject]@{ name = "hold-exit"; exit = 2; output = @($serializedHold.output[0]) },
        [pscustomobject]@{ name = "hold-lookalike"; exit = 1; output = @($transportMessage) }
    )) {
        Assert-DamageRejected $damage.name {
            Assert-ExternalOwnerFallbackVerifierFailure -ExitCode $damage.exit -Output $damage.output
        }
    }
    $stdoutContamination = Invoke-ExternalOwnerChildFailureFixture (
        "Write-Output 'unexpected stdout'; throw '$holdMessage'"
    )
    if ($stdoutContamination.exit_code -ne 1 -or $stdoutContamination.output.Count -ne 2) {
        throw "Child stdout-contamination fixture changed."
    }
    Assert-DamageRejected "hold-stdout-contamination" {
        Assert-ExternalOwnerFallbackVerifierFailure `
            -ExitCode $stdoutContamination.exit_code -Output $stdoutContamination.output
    }
    $null = ConvertFrom-ExternalOwnerGitNameStatusBytes ([Text.Encoding]::UTF8.GetBytes(
        "A" + [char]0 + "config/external-validation-authority.json" + [char]0
    ))
    $ordinalBeforeCulturePaths = @(
        "Cargo.lock",
        "apps/native-renderer-android/AndroidManifest.xml"
    )
    $ordinalBeforeCultureBytes = [Text.Encoding]::UTF8.GetBytes(
        "M" + [char]0 + $ordinalBeforeCulturePaths[0] + [char]0 +
        "M" + [char]0 + $ordinalBeforeCulturePaths[1] + [char]0
    )
    $ordinalBeforeCultureRecords = @(
        ConvertFrom-ExternalOwnerGitNameStatusBytes $ordinalBeforeCultureBytes
    )
    if (
        $ordinalBeforeCultureRecords.Count -ne 2 -or
        [string]$ordinalBeforeCultureRecords[0].path -cne $ordinalBeforeCulturePaths[0] -or
        [string]$ordinalBeforeCultureRecords[1].path -cne $ordinalBeforeCulturePaths[1]
    ) {
        throw "The Git name-status parser did not preserve ordinal path order."
    }
    foreach ($damage in @(
        [pscustomobject]@{ name = "invalid-utf8"; bytes = [byte[]]@(65, 0, 255, 0) },
        [pscustomobject]@{ name = "missing-terminal-delimiter"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "config/external-validation-authority.json") },
        [pscustomobject]@{ name = "backslash-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "dir" + [char]92 + "file.ps1" + [char]0) },
        [pscustomobject]@{ name = "duplicate-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "config/external-validation-authority.json" + [char]0 + "M" + [char]0 + "config/external-validation-authority.json" + [char]0) },
        [pscustomobject]@{ name = "case-colliding-path"; bytes = [Text.Encoding]::UTF8.GetBytes("A" + [char]0 + "Config/external-validation-authority.json" + [char]0 + "M" + [char]0 + "config/external-validation-authority.json" + [char]0) },
        [pscustomobject]@{ name = "culture-sorted-not-ordinal"; bytes = [Text.Encoding]::UTF8.GetBytes("M" + [char]0 + $ordinalBeforeCulturePaths[1] + [char]0 + "M" + [char]0 + $ordinalBeforeCulturePaths[0] + [char]0) }
    )) {
        Assert-DamageRejected $damage.name {
            $null = ConvertFrom-ExternalOwnerGitNameStatusBytes $damage.bytes
        }
    }
    $secondArtifact = [ordered]@{ path = "tools/other.ps1"; state = "absent" }
    Assert-DamageRejected "incomplete-changed-inventory" {
        Assert-ExternalOwnerArtifactInventory `
            -ChangedPaths @($artifacts[0].path, $secondArtifact.path) `
            -ChangedArtifacts $artifacts -ProtectedPaths @($artifacts[0].path) `
            -ProtectedArtifacts $artifacts
    }
    Assert-DamageRejected "incomplete-protected-inventory" {
        Assert-ExternalOwnerArtifactInventory `
            -ChangedPaths @($artifacts[0].path, $secondArtifact.path) `
            -ChangedArtifacts @($artifacts[0], $secondArtifact) `
            -ProtectedPaths @($secondArtifact.path) -ProtectedArtifacts $artifacts
    }
    $ordinalBeforeCultureArtifacts = @($ordinalBeforeCulturePaths | ForEach-Object {
        [ordered]@{
            path = $_
            state = "present"
            mode = "100644"
            size_bytes = 3
            sha256 = ("a" * 64)
        }
    })
    $ordinalBeforeCultureAssessment = $assessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $ordinalBeforeCultureAssessment.changed_paths = $ordinalBeforeCulturePaths
    $ordinalBeforeCultureAssessment.protected_paths = $ordinalBeforeCulturePaths
    $null = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity -Head $headIdentity `
        -ChangedArtifacts $ordinalBeforeCultureArtifacts `
        -ProtectedArtifacts $ordinalBeforeCultureArtifacts `
        -Assessment $ordinalBeforeCultureAssessment
    Assert-DamageRejected "culture-sorted-authorization-artifacts" {
        $null = New-ExternalOwnerAuthorizationRequest `
            -Policy $policy -PullRequestNumber 53 -Base $baseIdentity -Head $headIdentity `
            -ChangedArtifacts @($ordinalBeforeCultureArtifacts[1], $ordinalBeforeCultureArtifacts[0]) `
            -ProtectedArtifacts @($ordinalBeforeCultureArtifacts[1], $ordinalBeforeCultureArtifacts[0]) `
            -Assessment $ordinalBeforeCultureAssessment
    }
    $request = New-ExternalOwnerAuthorizationRequest -Policy $policy -PullRequestNumber 53 -Base ([ordered]@{commit=("1"*40);tree=("2"*40)}) -Head ([ordered]@{commit=("3"*40);tree=("4"*40)}) -ChangedArtifacts $artifacts -ProtectedArtifacts $artifacts -Assessment $assessment
    $payload = New-ExternalOwnerAuthorizationPayload -Request $request -AuditId "external-owner-pr53-00000000000000000000000000000000" -IssuedAt "2026-08-22T15:59:00Z" -ExpiresAt "2026-08-22T16:59:00Z"
    $attemptTwoAssessment = $assessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $attemptTwoAssessment.workflow.run_attempt = 2
    $attemptTwoRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity `
        -Head $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts -Assessment $attemptTwoAssessment
    $attemptTwoPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $attemptTwoRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (
        [int]$request.assessment.workflow.run_attempt -ne 1 -or
        [int]$attemptTwoRequest.assessment.workflow.run_attempt -ne 2 -or
        (Test-CanonicalEqual $request $attemptTwoRequest) -or
        -not (Test-CanonicalEqual $payload $attemptTwoPayload)
    ) {
        throw "Cross-attempt transport provenance or stable challenge behavior changed."
    }

    $transportDriftAssessment = $attemptTwoAssessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $transportDriftAssessment.workflow.run_id = "33159999999"
    $transportDriftAssessment.event_identity.merge_commit_observation = ("6" * 40)
    $transportDriftAssessment.event_identity.merge_commit_relation =
        "event-merge-observation-matched-fetched-ref"
    $transportDriftAssessment.runtime.powershell.version = "7.6.6"
    $transportDriftAssessment.runtime.powershell.executable_sha256 = ("e" * 64)
    $transportDriftAssessment.runtime.git.version = "git version 2.55.1.windows.1"
    $transportDriftAssessment.runtime.git.executable_sha256 = ("f" * 64)
    $transportDriftAssessment.runtime.runner.image_version = "20260825.1"
    $transportDriftRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity `
        -Head $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts -Assessment $transportDriftAssessment
    $transportDriftPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $transportDriftRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (
        (Test-CanonicalEqual $attemptTwoRequest $transportDriftRequest) -or
        -not (Test-CanonicalEqual $payload $transportDriftPayload)
    ) {
        throw "Transport diagnostics incorrectly changed the stable challenge."
    }

    $changedHeadIdentity = [ordered]@{
        commit = ("7" * 40)
        tree = ("8" * 40)
    }
    $changedHeadAssessment = $assessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $changedHeadAssessment.candidate = $changedHeadIdentity
    $changedHeadAssessment.merge = [ordered]@{
        commit = ("9" * 40)
        tree = ("8" * 40)
    }
    $changedHeadRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity `
        -Head $changedHeadIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts -Assessment $changedHeadAssessment
    $changedHeadPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $changedHeadRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (Test-CanonicalEqual $payload $changedHeadPayload) {
        throw "A changed head identity did not change the signed challenge."
    }

    $changedBaseIdentity = [ordered]@{
        commit = ("a" * 40)
        tree = ("b" * 40)
    }
    $changedBaseAssessment = $assessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $changedBaseAssessment.base = $changedBaseIdentity
    $changedBaseAssessment.merge = [ordered]@{
        commit = ("c" * 40)
        tree = ("4" * 40)
    }
    $changedBaseRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $changedBaseIdentity `
        -Head $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts -Assessment $changedBaseAssessment
    $changedBasePayload = New-ExternalOwnerAuthorizationPayload `
        -Request $changedBaseRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (Test-CanonicalEqual $payload $changedBasePayload) {
        throw "A changed base identity did not change the signed challenge."
    }

    $changedArtifacts = @([ordered]@{
        path = "config/external-validation-authority.json"
        state = "present"
        mode = "100644"
        size_bytes = 4
        sha256 = ("0" * 64)
    })
    $changedArtifactRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity `
        -Head $headIdentity -ChangedArtifacts $changedArtifacts `
        -ProtectedArtifacts $changedArtifacts -Assessment $assessment
    $changedArtifactPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $changedArtifactRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (Test-CanonicalEqual $payload $changedArtifactPayload) {
        throw "Changed artifact evidence did not change the signed challenge."
    }

    $changedPolicyAssessment = $assessment | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $changedPolicyAssessment.policy_sha256 = ("1" * 64)
    $changedPolicyRequest = New-ExternalOwnerAuthorizationRequest `
        -Policy $policy -PullRequestNumber 53 -Base $baseIdentity `
        -Head $headIdentity -ChangedArtifacts $artifacts `
        -ProtectedArtifacts $artifacts -Assessment $changedPolicyAssessment
    $changedPolicyPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $changedPolicyRequest `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (Test-CanonicalEqual $payload $changedPolicyPayload) {
        throw "Changed authority policy evidence did not change the signed challenge."
    }

    $postRequestAssessmentDamage = $request | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $postRequestAssessmentDamage.assessment.candidate.commit = ("0" * 40)
    Assert-DamageRejected "post-request-authoritative-assessment" {
        $null = New-ExternalOwnerAuthorizationPayload `
            -Request $postRequestAssessmentDamage `
            -AuditId "external-owner-pr53-00000000000000000000000000000000" `
            -IssuedAt "2026-08-22T15:59:00Z" `
            -ExpiresAt "2026-08-22T16:59:00Z"
    }
    $postRequestHashDamage = $request | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $postRequestHashDamage.assessment_sha256 = ("0" * 64)
    Assert-DamageRejected "post-request-assessment-challenge-hash" {
        $null = New-ExternalOwnerAuthorizationPayload `
            -Request $postRequestHashDamage `
            -AuditId "external-owner-pr53-00000000000000000000000000000000" `
            -IssuedAt "2026-08-22T15:59:00Z" `
            -ExpiresAt "2026-08-22T16:59:00Z"
    }
    $changedRequestSchema = $request | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30 -DateKind String
    $changedRequestSchema.schema =
        "rusty.quest.external_owner_authorization_request.v999"
    $changedRequestSchemaPayload = New-ExternalOwnerAuthorizationPayload `
        -Request $changedRequestSchema `
        -AuditId "external-owner-pr53-00000000000000000000000000000000" `
        -IssuedAt "2026-08-22T15:59:00Z" `
        -ExpiresAt "2026-08-22T16:59:00Z"
    if (Test-CanonicalEqual $payload $changedRequestSchemaPayload) {
        throw "A changed authorization request schema did not change the signed challenge."
    }
    [byte[]]$canonical = Get-CanonicalAuthorizationBytes $payload
    $signature = $rsa.SignData($canonical, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pss)
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant()
    $testPolicy = $policy | ConvertTo-Json -Depth 20 | ConvertFrom-Json -Depth 20
    $testPolicy.public_key_pem = $rsa.ExportSubjectPublicKeyInfoPem().Replace("`r", "")
    $testPolicy.public_key_spki_sha256 = $fingerprint
    $document = [ordered]@{ schema = "rusty.quest.external_owner_authorization.v1"; payload = $payload; signature = [ordered]@{ algorithm = "RSA-PSS-SHA256"; public_key_spki_sha256 = $fingerprint; value_base64 = [Convert]::ToBase64String($signature) } }
    $comment = [ordered]@{ id = 1; created_at = "2026-08-22T15:59:00Z"; updated_at = "2026-08-22T15:59:00Z"; user = [ordered]@{ login = "MesmerPrism" }; body = $testPolicy.comment_marker + "`n" + ($document | ConvertTo-Json -Depth 30 -Compress) }
    $null = Test-ExternalOwnerAuthorizationComments -Comments @($comment) -ExpectedPayload $payload -Policy $testPolicy -Now $now -SchemaPath $authorizationSchema
    $null = Test-ExternalOwnerAuthorizationComments -Comments @($comment) -ExpectedPayload $attemptTwoPayload -Policy $testPolicy -Now $now -SchemaPath $authorizationSchema
    $bootstrapComment = $comment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $bootstrapComment.body = $testPolicy.bootstrap_comment_marker + "`n{}"
    Assert-DamageRejected "bootstrap-marker-normal-fallback" {
        $null = Test-ExternalOwnerAuthorizationComments -Comments @($bootstrapComment) `
            -ExpectedPayload $payload -Policy $testPolicy -Now $now `
            -SchemaPath $authorizationSchema
    }
    $changed = $payload | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $changed.head.commit = ("0" * 40)
    $duplicate = @($comment, $comment)
    [byte[]]$originalSignatureBytes = [Convert]::FromBase64String(
        [string]$document.signature.value_base64
    )
    [byte[]]$wrongSignatureBytes = [byte[]]$originalSignatureBytes.Clone()
    $wrongSignatureBytes[0] = $wrongSignatureBytes[0] -bxor 1
    if ([Security.Cryptography.CryptographicOperations]::FixedTimeEquals(
        $originalSignatureBytes, $wrongSignatureBytes
    )) {
        throw "Wrong-signature damage did not mutate the signature bytes."
    }
    $wrongSignatureDocument = $document | ConvertTo-Json -Depth 30 |
        ConvertFrom-Json -Depth 30
    $wrongSignatureDocument.signature.value_base64 =
        [Convert]::ToBase64String($wrongSignatureBytes)
    $wrongSignature = $comment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30
    $wrongSignature.body = $testPolicy.comment_marker + "`n" +
        ($wrongSignatureDocument | ConvertTo-Json -Depth 30 -Compress)
    foreach ($case in @(
        [pscustomobject]@{ name = "duplicate"; comments = $duplicate; expected = $payload; at = $now },
        [pscustomobject]@{ name = "changed-evidence"; comments = @($comment); expected = $changed; at = $now },
        [pscustomobject]@{ name = "stale"; comments = @($comment); expected = $payload; at = $now.AddDays(2) },
        [pscustomobject]@{ name = "future"; comments = @($comment); expected = $payload; at = $now.AddDays(-1) },
        [pscustomobject]@{ name = "wrong-signature"; comments = @($wrongSignature); expected = $payload; at = $now }
    )) {
        $rejected = $false
        try { $null = Test-ExternalOwnerAuthorizationComments -Comments $case.comments -ExpectedPayload $case.expected -Policy $testPolicy -Now $case.at -SchemaPath $authorizationSchema } catch { $rejected = $true }
        if (-not $rejected) { throw "External-owner authorization damage was accepted: $($case.name)" }
    }
    Write-Output "External-owner authorization crypto and damage tests passed."
} finally { $rsa.Dispose() }
