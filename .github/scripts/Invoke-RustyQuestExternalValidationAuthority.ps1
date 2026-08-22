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
    [AllowEmptyString()][string]$EventMergeCommit = "",
    [Parameter(Mandatory = $true)][string]$PullRequestNumber,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$RunAttempt,
    [Parameter(Mandatory = $true)][string]$RunnerLabel,
    [Parameter(Mandatory = $true)][string]$RunnerOs,
    [Parameter(Mandatory = $true)][string]$RunnerArchitecture,
    [Parameter(Mandatory = $true)][string]$RunnerImageOs,
    [Parameter(Mandatory = $true)][string]$RunnerImageVersion,
    [AllowEmptyString()][string]$CommentsJsonPath = "",
    [AllowEmptyString()][string]$AuthorizationRequestPath = "",
    [switch]$AllowLocalTestRemote,
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
$ExternalOwnerPolicyPath = "config/external-owner-authorization.json"
$ExternalOwnerPolicySchemaPath = `
    "schemas/rusty.quest.external_owner_authorization_policy.v1.schema.json"
$ExternalOwnerRequestSchemaPath = `
    "schemas/rusty.quest.external_owner_authorization_request.v1.schema.json"
$ExternalOwnerAuthorizationSchemaPath = `
    "schemas/rusty.quest.external_owner_authorization.v1.schema.json"
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

function Invoke-BaseGitBytes {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $git = (Get-Command git -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $git
    $start.WorkingDirectory = $script:TrustedBase
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    $start.Environment["GIT_OPTIONAL_LOCKS"] = "0"
    $start.Environment["GIT_LFS_SKIP_SMUDGE"] = "1"
    $start.Environment["LC_ALL"] = "C"
    $start.Environment["LANG"] = "C"
    foreach ($argument in @("--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "diff.external=", "-C", $script:TrustedBase) + $Arguments) {
        [void]$start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw "Could not start bounded Git byte process." }
        $memory = [IO.MemoryStream]::new()
        try {
            $copy = $process.StandardOutput.BaseStream.CopyToAsync($memory)
            $stderrTask = $process.StandardError.ReadToEndAsync()
            if (-not $process.WaitForExit(30000)) { try { $process.Kill($true) } catch { }; throw "Git byte process exceeded the 30-second timeout." }
            $copy.GetAwaiter().GetResult()
            $stderr = $stderrTask.GetAwaiter().GetResult()
            if ($memory.Length -gt 16777216 -or $stderr.Length -gt 1048576) { throw "Git byte process output exceeded its bound." }
            if ($process.ExitCode -ne 0) { throw "Git byte process failed: $($stderr.Trim())" }
            return ,$memory.ToArray()
        } finally { $memory.Dispose() }
    } finally { $process.Dispose() }
}

function Get-ExternalOwnerArtifacts {
    param([Parameter(Mandatory = $true)][string]$Base, [Parameter(Mandatory = $true)][string]$Head)
    [byte[]]$raw = Invoke-BaseGitBytes @(
        "diff", "--name-status", "-z", "--no-renames", "--no-ext-diff", $Base, $Head, "--"
    )
    $records = @(ConvertFrom-ExternalOwnerGitNameStatusBytes $raw)
    if ($records.Count -gt 512) {
        throw "Authorization diff exceeds its path bound."
    }
    $artifacts = [Collections.Generic.List[object]]::new()
    [int64]$total = 0
    foreach ($record in $records) {
        $path = [string]$record.path
        [byte[]]$treeBytes = Invoke-BaseGitBytes @("ls-tree", "-z", $Head, "--", $path)
        if ($treeBytes.Length -eq 0) {
            $artifacts.Add([ordered]@{ path = $path; state = "absent" })
            continue
        }
        if ($treeBytes[$treeBytes.Length - 1] -ne 0) {
            throw "Authorization tree output lacks a terminal NUL delimiter."
        }
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        try {
            $entry = $utf8.GetString($treeBytes, 0, $treeBytes.Length - 1)
        } catch {
            throw "Authorization tree output contains invalid UTF-8."
        }
        if ($entry.IndexOf([char]0) -ge 0 -or
            $entry -cnotmatch "^(100644|100755) blob ([0-9a-f]{40})`t(.+)$" -or
            $Matches[3] -cne $path) {
            throw "Authorization artifact is not an exact regular blob."
        }
        $mode = $Matches[1]
        $objectId = $Matches[2]
        $size = [int64](Invoke-BaseGit @("cat-file", "-s", $objectId)).Trim()
        $total += $size
        if ($size -gt 16777216 -or $total -gt 67108864) { throw "Authorization artifact bytes exceed their bound." }
        [byte[]]$bytes = Invoke-BaseGitBytes @("cat-file", "blob", $objectId)
        if ($bytes.Length -ne $size) { throw "Authorization artifact byte size drifted while reading Git object." }
        $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
        $artifacts.Add([ordered]@{ path = $path; state = "present"; mode = $mode; size_bytes = $size; sha256 = $hash })
    }
    $result = @($artifacts)
    $paths = @($result | ForEach-Object { [string]$_.path })
    $recordPaths = @($records | ForEach-Object { [string]$_.path })
    if ($result.Count -ne $records.Count -or ($paths -join "`n") -cne ($recordPaths -join "`n")) {
        throw "Authorization artifact inventory is incomplete relative to Git name-status output."
    }
    return $result
}

function Get-PublicIssueComments {
    param([Parameter(Mandatory = $true)][int]$Number, [Parameter(Mandatory = $true)][object]$Policy)
    if (-not [string]::IsNullOrEmpty($CommentsJsonPath)) {
        if (-not $AllowLocalTestRemote) { throw "Comment fixtures are test-only." }
        [byte[]]$raw = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $CommentsJsonPath).Path)
        if ($raw.Length -gt [int]$Policy.maximum_response_bytes) { throw "Comment response exceeds its size bound." }
        return @(ConvertFrom-ExternalOwnerJsonStrict ([Text.UTF8Encoding]::new($false,$true).GetString($raw)))
    }
    if ([string]::IsNullOrEmpty($env:GITHUB_TOKEN)) { throw "Base-owned GitHub token is unavailable for public comment inspection." }
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [timespan]::FromSeconds(30)
    $client.DefaultRequestHeaders.Add("Accept", "application/vnd.github+json")
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $($env:GITHUB_TOKEN)")
    $client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28")
    $client.DefaultRequestHeaders.Add("User-Agent", "rusty-quest-static-admission")
    $comments = [Collections.Generic.List[object]]::new()
    [int64]$receivedBytes = 0
    try {
        for ($page = 1; $page -le [math]::Ceiling([int]$Policy.maximum_comments / 100.0); $page++) {
            $uri = "https://api.github.com/repos/MesmerPrism/rusty-quest/issues/$Number/comments?per_page=100&page=$page"
            $response = $client.GetAsync($uri).GetAwaiter().GetResult()
            try {
                if (-not $response.IsSuccessStatusCode) { throw "GitHub issue-comment query failed with HTTP $([int]$response.StatusCode)." }
                if ($null -ne $response.Content.Headers.ContentLength -and $response.Content.Headers.ContentLength -gt [int]$Policy.maximum_response_bytes) { throw "Comment response exceeds its configured size bound." }
                [byte[]]$bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
                $receivedBytes += $bytes.Length
                if ($receivedBytes -gt [int]$Policy.maximum_response_bytes) { throw "Comment responses exceed their configured total size bound." }
                $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
                $pageComments = @(ConvertFrom-ExternalOwnerJsonStrict $text)
                foreach ($comment in $pageComments) { $comments.Add($comment) }
                if ($comments.Count -gt [int]$Policy.maximum_comments) { throw "Comment count exceeds its configured bound." }
                if ($pageComments.Count -lt 100) { break }
            } finally { $response.Dispose() }
        }
    } finally { $client.Dispose() }
    return @($comments)
}

function Write-ExternalOwnerRequest {
    param([Parameter(Mandatory = $true)][object]$Request)
    $text = ($Request | ConvertTo-Json -Depth 30 -Compress) + "`n"
    if (-not [string]::IsNullOrEmpty($AuthorizationRequestPath)) {
        if (-not $AllowLocalTestRemote) { throw "Authorization request fixture output is test-only." }
        [IO.File]::WriteAllText([IO.Path]::GetFullPath($AuthorizationRequestPath), $text, [Text.UTF8Encoding]::new($false))
    }
    Write-Output $text
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
if (-not [string]::IsNullOrEmpty($EventMergeCommit)) {
    Assert-ObjectId $EventMergeCommit "Event merge commit observation"
}
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
if ($fetchedHead -cne $CandidateCommit) {
    throw "Fetched PR head differs from the event head object."
}
$effectiveMergeCommit = $fetchedMerge
$eventMergeRelation = if ([string]::IsNullOrEmpty($EventMergeCommit)) {
    "event-merge-observation-absent"
} elseif ($EventMergeCommit -ceq $effectiveMergeCommit) {
    "event-merge-observation-matched-fetched-ref"
} else {
    "event-merge-observation-stale-fetched-ref-authoritative"
}
$parents = @(
    ((Invoke-BaseGit @(
        "rev-list", "--parents", "-n", "1", $effectiveMergeCommit
    )).Trim()).Split(
        " ", [StringSplitOptions]::RemoveEmptyEntries
    )
)
if (
    $parents.Count -ne 3 -or
    $parents[0] -cne $effectiveMergeCommit -or
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
Import-Module (Join-Path $script:TrustedBase ".github\scripts\lib\ExternalOwnerAuthorization.psm1") -Force

$finalOutput = [IO.Path]::GetFullPath($OutPath)
$outputParent = Split-Path -Parent $finalOutput
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) {
    throw "Assessment output parent does not exist."
}
if (Test-Path -LiteralPath $finalOutput) {
    throw "Assessment output already exists."
}
$basePrefix = $script:TrustedBase.TrimEnd([char[]]@('\', '/')) + `
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
    $verifierInvocation = ({
        & $verifierScript `
            -RepositoryRoot $script:TrustedBase `
            -PolicyPath $PolicyPath `
            -Repository $ExpectedRepository `
            -BaseCommit $BaseCommit `
            -CandidateCommit $CandidateCommit `
            -OutPath $genericOutput
    }).GetNewClosure()
    try {
        $verifierResult = Invoke-ExternalOwnerFallbackVerifier `
            -Invocation $verifierInvocation -AssessmentOutputPath $genericOutput
    } catch {
        throw "Pinned external validation verifier failed without the exact protected-without-base-approval hold: $($_.Exception.Message)"
    }
    $externalOwnerHold = [bool]$verifierResult.external_owner_hold
    if ($externalOwnerHold) {
        $policyBytes = [IO.File]::ReadAllBytes((Join-Path $script:TrustedBase $PolicyPath))
        $policy = [Text.UTF8Encoding]::new($false, $true).GetString($policyBytes) | ConvertFrom-Json -Depth 30
        $artifacts = Get-ExternalOwnerArtifacts -Base $BaseCommit -Head $CandidateCommit
        $protectedArtifacts = @($artifacts | Where-Object {
            $path = [string]$_.path
            (@($policy.mandatory_protected_paths) -ccontains $path) -or @($policy.protected_rules | Where-Object {
                ($_.match -ceq "exact" -and $_.path -ceq $path) -or
                ($_.match -ceq "prefix" -and $path.StartsWith([string]$_.path, [StringComparison]::Ordinal))
            }).Count -gt 0
        })
        if ($protectedArtifacts.Count -eq 0) { throw "Exact protected-without-base-approval hold did not contain protected artifacts." }
        $generic = New-ExternalOwnerProtectedWithoutBaseApprovalAssessment `
            -Policy $policy -PolicyBytes $policyBytes -Base (Get-GitIdentity $BaseCommit) `
            -Candidate (Get-GitIdentity $CandidateCommit) -ChangedArtifacts $artifacts `
            -ProtectedArtifacts $protectedArtifacts
    } else {
        $genericJson = Get-Content -Raw -LiteralPath $genericOutput
        $generic = $genericJson | ConvertFrom-Json -Depth 30
    }
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
            merge_commit_observation = $(
                if ([string]::IsNullOrEmpty($EventMergeCommit)) {
                    $null
                } else {
                    $EventMergeCommit
                }
            )
            merge_commit_relation = $eventMergeRelation
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
        merge = Get-GitIdentity $effectiveMergeCommit
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
    if ($externalOwnerHold) {
        $ownerPolicy = Read-ExternalOwnerAuthorizationPolicy `
            -Path (Join-Path $script:TrustedBase $ExternalOwnerPolicyPath) `
            -SchemaPath (Join-Path $script:TrustedBase $ExternalOwnerPolicySchemaPath)
        $signedAssessment = $assessment | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30 -DateKind String
        $request = New-ExternalOwnerAuthorizationRequest -Policy $ownerPolicy `
            -PullRequestNumber $pullRequest -Base $assessment.base -Head $assessment.candidate `
            -ChangedArtifacts $artifacts -ProtectedArtifacts $protectedArtifacts `
            -Assessment $signedAssessment
        $requestJson = $request | ConvertTo-Json -Depth 30
        if (-not (Test-Json -Json $requestJson -SchemaFile (Join-Path $script:TrustedBase $ExternalOwnerRequestSchemaPath) -ErrorAction Stop)) {
            throw "Canonical external-owner authorization request failed its schema."
        }
        $comments = @(Get-PublicIssueComments -Number $pullRequest -Policy $ownerPolicy)
        $markerPattern = "(?m)^$([regex]::Escape([string]$ownerPolicy.comment_marker))$"
        $markers = @($comments | Where-Object { [string]$_.user.login -ceq [string]$ownerPolicy.owner_login -and [regex]::Matches([string]$_.body, $markerPattern).Count -gt 0 })
        if ($markers.Count -ne 1) {
            Write-ExternalOwnerRequest $request
            throw "External-owner authorization is required; the canonical request was emitted."
        }
        try {
            $document = ConvertFrom-ExternalOwnerJsonStrict (([string]$markers[0].body -split "\r?\n", 2)[1])
            $expected = New-ExternalOwnerAuthorizationPayload -Request $request -AuditId ([string]$document.payload.audit_id) -IssuedAt ([string]$document.payload.issued_at) -ExpiresAt ([string]$document.payload.expires_at)
            $payload = Test-ExternalOwnerAuthorizationComments -Comments $comments -ExpectedPayload $expected -Policy $ownerPolicy -SchemaPath (Join-Path $script:TrustedBase $ExternalOwnerAuthorizationSchemaPath)
            $assessment.decision = "external-owner-authorization"
            $assessment.approval_id = [string]$payload.audit_id
        } catch {
            Write-ExternalOwnerRequest $request
            throw "External-owner authorization is required; the canonical request was emitted."
        }
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
