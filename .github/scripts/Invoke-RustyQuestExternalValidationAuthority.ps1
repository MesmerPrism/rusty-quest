[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseRoot,
    [Parameter(Mandatory = $true)][string]$VerifierRoot,
    [Parameter(Mandatory = $true)][string]$Repository,
    [Parameter(Mandatory = $true)][string]$BaseRepository,
    [Parameter(Mandatory = $true)][string]$BaseRef,
    [Parameter(Mandatory = $true)][string]$HeadRepository,
    [Parameter(Mandatory = $true)][string]$EventName,
    [Parameter(Mandatory = $true)][string]$BaseCommit,
    [Parameter(Mandatory = $true)][string]$CandidateCommit,
    [Parameter(Mandatory = $true)][string]$MergeCommit,
    [Parameter(Mandatory = $true)][string]$PullRequestNumber,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$RunAttempt,
    [Parameter(Mandatory = $true)][string]$RunnerLabel,
    [Parameter(Mandatory = $true)][string]$RunnerOs,
    [Parameter(Mandatory = $true)][string]$RunnerArchitecture,
    [Parameter(Mandatory = $true)][string]$RunnerImageOs,
    [Parameter(Mandatory = $true)][string]$RunnerImageVersion,
    [Parameter(Mandatory = $true)][string]$OutPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ExpectedRepository = "MesmerPrism/rusty-quest"
$ExpectedVerifierCommit = "50a4c5222c9d6c4567bac09405e43049c61b126f"
$ExpectedVerifierTree = "ead3855a2ecc5e1240e271d81a938985457f10e8"
$ExpectedVerifierBytes = 35452
$ExpectedVerifierSha256 = `
    "fcab9717b53bee594949d3d7ffc6126d91db0a4b7592241efab9f9cefcd5a5be"
$PolicyPath = "config/external-validation-authority.json"
$AssessmentSchemaPath = `
    "schemas/rusty.quest.external_validation_authority_assessment.v1.schema.json"
$ForbiddenGitEnvironmentVariables = @(
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CEILING_DIRECTORIES",
    "GIT_COMMON_DIR",
    "GIT_CONFIG_COUNT",
    "GIT_CONFIG_GLOBAL",
    "GIT_CONFIG_NOSYSTEM",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_SYSTEM",
    "GIT_DIR",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
    "GIT_INDEX_FILE",
    "GIT_NAMESPACE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_QUARANTINE_PATH",
    "GIT_REPLACE_REF_BASE",
    "GIT_SHALLOW_FILE",
    "GIT_WORK_TREE"
)

function Assert-ObjectId {
    param([Parameter(Mandatory = $true)][string]$Value, [string]$Label)
    if ($Value -cnotmatch "^[0-9a-f]{40}$") {
        throw "$Label is not a full lowercase SHA-1 object ID."
    }
}

function Assert-Decimal {
    param([Parameter(Mandatory = $true)][string]$Value, [string]$Label)
    if ($Value -cnotmatch "^[1-9][0-9]{0,29}$") {
        throw "$Label is not a positive canonical decimal."
    }
}

function Assert-RepositoryIdentity {
    param([Parameter(Mandatory = $true)][string]$Value, [string]$Label)
    if ($Value -cnotmatch `
        "^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$") {
        throw "$Label is not a canonical repository identity."
    }
}

function Assert-RunnerIdentityValue {
    param([Parameter(Mandatory = $true)][string]$Value, [string]$Label)
    if (
        [string]::IsNullOrWhiteSpace($Value) -or
        $Value.Length -gt 128 -or
        $Value -notmatch "^[A-Za-z0-9._+()-]+$"
    ) {
        throw "$Label is not a bounded runner identity value."
    }
}

function Assert-NoLink {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$Label)
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (
        ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        (
            $item.PSObject.Properties.Name -contains "LinkType" -and
            -not [string]::IsNullOrEmpty([string]$item.LinkType)
        )
    ) {
        throw "$Label must not be a link or reparse point."
    }
}

function Invoke-SafeGit {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $git = (Get-Command git -CommandType Application -ErrorAction Stop | `
        Select-Object -First 1).Source
    $nullDevice = if ($IsWindows) { "NUL" } else { "/dev/null" }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $git
    $start.WorkingDirectory = $Root
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    $start.Environment["GIT_OPTIONAL_LOCKS"] = "0"
    $start.Environment["GIT_LFS_SKIP_SMUDGE"] = "1"
    $start.Environment["LC_ALL"] = "C"
    $start.Environment["LANG"] = "C"
    foreach ($name in @($start.Environment.Keys)) {
        if (
            $ForbiddenGitEnvironmentVariables -icontains $name -or
            $name.StartsWith(
                "GIT_CONFIG_KEY_", [StringComparison]::OrdinalIgnoreCase
            ) -or
            $name.StartsWith(
                "GIT_CONFIG_VALUE_", [StringComparison]::OrdinalIgnoreCase
            )
        ) {
            [void]$start.Environment.Remove($name)
        }
    }
    foreach ($argument in @(
        "--no-optional-locks",
        "-c", "core.fsmonitor=false",
        "-c", "core.hooksPath=$nullDevice",
        "-c", "diff.external=",
        "-c", "protocol.version=2",
        "-C", $Root
    ) + $Arguments) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) {
            throw "Could not start bounded Git process."
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            try { $process.Kill($true) } catch { }
            throw "Git process exceeded the 30-second timeout."
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($stdout.Length + $stderr.Length -gt 1048576) {
            throw "Git process output exceeded 1 MiB."
        }
        if ($process.ExitCode -ne 0) {
            throw "Git process failed: $($stderr.Trim())"
        }
        return $stdout
    } finally {
        $process.Dispose()
    }
}

function Invoke-BaseGit {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return Invoke-SafeGit -Root $script:TrustedBase -Arguments $Arguments
}

function Get-GitIdentity {
    param([Parameter(Mandatory = $true)][string]$Commit)
    $resolvedCommit = (Invoke-BaseGit @(
        "rev-parse", "--verify", "$Commit`^{commit}"
    )).Trim()
    $resolvedTree = (Invoke-BaseGit @(
        "rev-parse", "--verify", "$Commit`^{tree}"
    )).Trim()
    if ($resolvedCommit -cne $Commit) {
        throw "Resolved commit differs from the declared object ID."
    }
    return [pscustomobject][ordered]@{
        commit = $resolvedCommit
        tree = $resolvedTree
    }
}

if ($Repository -cne $ExpectedRepository) {
    throw "Workflow repository differs from the fixed Quest repository."
}
if ($BaseRepository -cne $ExpectedRepository) {
    throw "Event base repository differs from MesmerPrism/rusty-quest."
}
if ($BaseRef -cne "main") {
    throw "Event base ref differs from main."
}
Assert-RepositoryIdentity $HeadRepository "Event head repository"
if ($EventName -cne "pull_request_target") {
    throw "Only pull_request_target is accepted."
}
if ($RunnerLabel -cne "windows-2025") {
    throw "Runner label differs from windows-2025."
}
if ($RunnerOs -cne "Windows") {
    throw "Runner OS differs from Windows."
}
if ($RunnerArchitecture -cne "X64") {
    throw "Runner architecture differs from X64."
}
Assert-RunnerIdentityValue $RunnerImageOs "Runner image OS"
Assert-RunnerIdentityValue $RunnerImageVersion "Runner image version"
Assert-ObjectId $BaseCommit "Base commit"
Assert-ObjectId $CandidateCommit "Candidate commit"
Assert-ObjectId $MergeCommit "Merge commit"
Assert-Decimal $PullRequestNumber "Pull-request number"
Assert-Decimal $RunId "Workflow run ID"
Assert-Decimal $RunAttempt "Workflow run attempt"
[int]$pullRequest = 0
[int]$attempt = 0
if (
    -not [int]::TryParse($PullRequestNumber, [ref]$pullRequest) -or
    -not [int]::TryParse($RunAttempt, [ref]$attempt)
) {
    throw "Pull-request number or workflow run attempt exceeds Int32."
}

foreach ($name in $ForbiddenGitEnvironmentVariables) {
    if (Test-Path -LiteralPath "Env:$name") {
        throw "Ambient Git selector is forbidden: $name"
    }
}
foreach ($entry in @(Get-ChildItem Env:)) {
    if (
        $entry.Name.StartsWith(
            "GIT_CONFIG_KEY_", [StringComparison]::OrdinalIgnoreCase
        ) -or
        $entry.Name.StartsWith(
            "GIT_CONFIG_VALUE_", [StringComparison]::OrdinalIgnoreCase
        )
    ) {
        throw "Ambient injected Git configuration is forbidden: $($entry.Name)"
    }
}

$script:TrustedBase = [IO.Path]::GetFullPath($BaseRoot)
$trustedVerifier = [IO.Path]::GetFullPath($VerifierRoot)
Assert-NoLink $script:TrustedBase "Trusted base root"
Assert-NoLink $trustedVerifier "Pinned verifier root"

$powerShellExecutable = (Get-Process -Id $PID -ErrorAction Stop).Path
Assert-NoLink $powerShellExecutable "PowerShell executable"
$powerShellItem = Get-Item -LiteralPath $powerShellExecutable
$powerShellHash = (Get-FileHash -LiteralPath $powerShellExecutable `
    -Algorithm SHA256).Hash.ToLowerInvariant()
$gitExecutable = (Get-Command git -CommandType Application -ErrorAction Stop | `
    Select-Object -First 1).Source
Assert-NoLink $gitExecutable "Git executable"
$gitItem = Get-Item -LiteralPath $gitExecutable
$gitHash = (Get-FileHash -LiteralPath $gitExecutable `
    -Algorithm SHA256).Hash.ToLowerInvariant()
$gitVersion = (Invoke-BaseGit @("--version")).Trim()
if ($gitVersion -notmatch '^git version [ -~]{1,100}$') {
    throw "Git runtime version output is not bounded canonical text."
}

$baseHead = (Invoke-BaseGit @("rev-parse", "HEAD")).Trim()
if ($baseHead -cne $BaseCommit) {
    throw "Trusted base HEAD differs from the event base SHA."
}
$dirty = Invoke-BaseGit @(
    "status", "--porcelain=v1", "--untracked-files=all"
)
if ($dirty.Length -ne 0) {
    throw "Trusted base checkout is dirty before candidate fetch."
}
$shallow = (Invoke-BaseGit @(
    "rev-parse", "--is-shallow-repository"
)).Trim()
if ($shallow -cne "false") {
    throw "Trusted base checkout is shallow."
}
$commonDir = [IO.Path]::GetFullPath((Invoke-BaseGit @(
    "rev-parse", "--path-format=absolute", "--git-common-dir"
)).Trim())
$expectedCommonDir = [IO.Path]::GetFullPath(
    (Join-Path $script:TrustedBase ".git")
)
if (-not $commonDir.Equals(
    $expectedCommonDir, [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Trusted base must be a standalone initialized checkout."
}
Assert-NoLink $commonDir "Trusted base Git directory"
$replaceRefs = (Invoke-BaseGit @(
    "for-each-ref", "--format=%(refname)", "refs/replace"
)).Trim()
if ($replaceRefs.Length -ne 0) {
    throw "Trusted base contains replacement refs."
}
foreach ($metadataPath in @(
    (Join-Path $commonDir "objects/info/alternates"),
    (Join-Path $commonDir "info/grafts")
)) {
    if (Test-Path -LiteralPath $metadataPath) {
        throw "Trusted base contains external or legacy object metadata."
    }
}
$origin = (Invoke-BaseGit @("remote", "get-url", "origin")).Trim()
if ($origin -cnotmatch `
    "^https://github\.com/MesmerPrism/rusty-quest(?:\.git)?$") {
    throw "Trusted base origin is not the fixed public Quest repository."
}

$privatePrefix = "refs/external-validation/pr-$PullRequestNumber"
$privateHead = "$privatePrefix/head"
$privateMerge = "$privatePrefix/merge"
Invoke-BaseGit @(
    "fetch",
    "--force",
    "--no-tags",
    "--no-write-fetch-head",
    "origin",
    "+refs/pull/$PullRequestNumber/head`:$privateHead",
    "+refs/pull/$PullRequestNumber/merge`:$privateMerge"
) | Out-Null
$fetchedHead = (Invoke-BaseGit @(
    "rev-parse", "--verify", "$privateHead`^{commit}"
)).Trim()
$fetchedMerge = (Invoke-BaseGit @(
    "rev-parse", "--verify", "$privateMerge`^{commit}"
)).Trim()
if ($fetchedHead -cne $CandidateCommit -or $fetchedMerge -cne $MergeCommit) {
    throw "Fetched PR head or merge object differs from the event object."
}
$parents = @(
    ((Invoke-BaseGit @(
        "rev-list", "--parents", "-n", "1", $MergeCommit
    )).Trim()).Split(
        " ", [StringSplitOptions]::RemoveEmptyEntries
    )
)
if (
    $parents.Count -ne 3 -or
    $parents[0] -cne $MergeCommit -or
    $parents[1] -cne $BaseCommit -or
    $parents[2] -cne $CandidateCommit
) {
    throw "PR merge object does not have the exact event base and head parents."
}
if ((Invoke-BaseGit @("rev-parse", "HEAD")).Trim() -cne $BaseCommit) {
    throw "Candidate fetch changed trusted base HEAD."
}
if ((Invoke-BaseGit @(
    "status", "--porcelain=v1", "--untracked-files=all"
)).Length -ne 0) {
    throw "Candidate fetch changed the trusted base worktree."
}

$verifierHead = (Invoke-SafeGit -Root $trustedVerifier `
    -Arguments @("rev-parse", "HEAD")).Trim()
if ($verifierHead -cne $ExpectedVerifierCommit) {
    throw "Pinned verifier checkout commit differs."
}
$verifierTree = (Invoke-SafeGit -Root $trustedVerifier `
    -Arguments @("rev-parse", "HEAD^{tree}")).Trim()
if ($verifierTree -cne $ExpectedVerifierTree) {
    throw "Pinned verifier checkout tree differs."
}
if ((Invoke-SafeGit -Root $trustedVerifier `
    -Arguments @("status", "--porcelain=v1", "--untracked-files=all")
).Length -ne 0) {
    throw "Pinned verifier checkout is dirty."
}
$verifierScript = Join-Path $trustedVerifier `
    "scripts/Test-ExternalValidationAuthority.ps1"
Assert-NoLink $verifierScript "Pinned verifier entrypoint"
$verifierItem = Get-Item -LiteralPath $verifierScript
$verifierHash = (Get-FileHash -LiteralPath $verifierScript `
    -Algorithm SHA256).Hash.ToLowerInvariant()
if (
    $verifierItem.Length -ne $ExpectedVerifierBytes -or
    $verifierHash -cne $ExpectedVerifierSha256
) {
    throw "Pinned verifier entrypoint byte identity differs."
}

$finalOutput = [IO.Path]::GetFullPath($OutPath)
$outputParent = Split-Path -Parent $finalOutput
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "Assessment output parent does not exist."
}
if (Test-Path -LiteralPath $finalOutput) {
    throw "Assessment output already exists."
}
$basePrefix = $script:TrustedBase.TrimEnd("\\", "/") + `
    [IO.Path]::DirectorySeparatorChar
if (
    $finalOutput.Equals(
        $script:TrustedBase, [StringComparison]::OrdinalIgnoreCase
    ) -or
    $finalOutput.StartsWith(
        $basePrefix, [StringComparison]::OrdinalIgnoreCase
    )
) {
    throw "Assessment output must remain outside the trusted checkout."
}

$genericOutput = Join-Path $outputParent (
    ".external-validation-generic-" + [Guid]::NewGuid().ToString("N") + ".json"
)
try {
    $pwsh = (Get-Command pwsh -CommandType Application -ErrorAction Stop | `
        Select-Object -First 1).Source
    & $pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
        -File $verifierScript `
        -RepositoryRoot $script:TrustedBase `
        -PolicyPath $PolicyPath `
        -Repository $ExpectedRepository `
        -BaseCommit $BaseCommit `
        -CandidateCommit $CandidateCommit `
        -OutPath $genericOutput | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Pinned external validation verifier failed."
    }
    $genericJson = Get-Content -Raw -LiteralPath $genericOutput
    $generic = $genericJson | ConvertFrom-Json -Depth 30
    if (
        $generic.repository -cne $ExpectedRepository -or
        $generic.base.commit -cne $BaseCommit -or
        $generic.candidate.commit -cne $CandidateCommit -or
        $generic.candidate_code_executed -ne $false -or
        $generic.execution_attested -ne $false -or
        $generic.publication_authority -ne $false
    ) {
        throw "Pinned verifier assessment identity or authority boundary differs."
    }
    $assessment = [pscustomobject][ordered]@{
        schema = "rusty.quest.external_validation_authority_assessment.v1"
        policy_id = [string]$generic.policy_id
        policy_sha256 = [string]$generic.policy_sha256
        repository = $ExpectedRepository
        pull_request_number = $pullRequest
        event_identity = [pscustomobject][ordered]@{
            base_repository = $BaseRepository
            base_ref = $BaseRef
            head_repository = $HeadRepository
        }
        workflow = [pscustomobject][ordered]@{
            event = "pull_request_target"
            run_id = $RunId
            run_attempt = $attempt
        }
        runtime = [pscustomobject][ordered]@{
            powershell = [pscustomobject][ordered]@{
                edition = [string]$PSVersionTable.PSEdition
                version = $PSVersionTable.PSVersion.ToString()
                executable_bytes = [int64]$powerShellItem.Length
                executable_sha256 = $powerShellHash
            }
            git = [pscustomobject][ordered]@{
                version = $gitVersion
                executable_bytes = [int64]$gitItem.Length
                executable_sha256 = $gitHash
            }
            runner = [pscustomobject][ordered]@{
                label = $RunnerLabel
                os = $RunnerOs
                architecture = $RunnerArchitecture
                image_os = $RunnerImageOs
                image_version = $RunnerImageVersion
                image_allowlist_enforced = $false
                drift_status = "observed-unpinned"
            }
        }
        base = $generic.base
        candidate = $generic.candidate
        merge = Get-GitIdentity $MergeCommit
        changed_paths = @($generic.changed_paths)
        protected_paths = @($generic.protected_paths)
        decision = [string]$generic.decision
        approval_id = $generic.approval_id
        candidate_code_executed = $false
        execution_attested = $false
        publication_authority = $false
        limitations = @($generic.limitations) + @(
            "Runner image and tool identities are observed exactly but not allowlisted."
        )
    }
    $assessmentJson = $assessment | ConvertTo-Json -Depth 30
    $assessmentSchema = Join-Path $script:TrustedBase $AssessmentSchemaPath
    if (-not (Test-Json -Json $assessmentJson `
        -SchemaFile $assessmentSchema -ErrorAction Stop)) {
        throw "Rusty Quest assessment failed its base-owned schema."
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(
        $assessmentJson + "`n"
    )
    $stream = [IO.FileStream]::new(
        $finalOutput,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write,
        [IO.FileShare]::None
    )
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
    Write-Output $assessment
} finally {
    if (Test-Path -LiteralPath $genericOutput -PathType Leaf) {
        Remove-Item -LiteralPath $genericOutput -Force
    }
}
