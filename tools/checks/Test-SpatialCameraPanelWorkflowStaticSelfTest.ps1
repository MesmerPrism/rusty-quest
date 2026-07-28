param([string]$RepoRoot)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path -LiteralPath $RepoRoot).Path
$gate = Join-Path $repoRootPath "tools\checks\Test-SpatialCameraPanelWorkflowStatic.ps1"
$repoDriveRoot = [System.IO.Path]::GetPathRoot($repoRootPath)
$tempBase = Join-Path $repoDriveRoot ".rq-spatial-workflow-selftest"
$testRoot = Join-Path $tempBase ("rusty-quest-spatial-workspace-selftest-" + [guid]::NewGuid().ToString("N"))

function Write-TestJson {
    param([string]$Path, [object]$Value)
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 32) + [Environment]::NewLine),
        $encoding
    )
}

function New-TestRepo {
    param([string]$Name)
    $root = Join-Path $testRoot $Name
    & git -c core.longpaths=true clone --shared --quiet -- $repoRootPath $root
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create workflow self-test repository at '$root'."
    }
    return $root
}

function Invoke-Gate {
    param([string]$Root)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& pwsh -NoProfile -ExecutionPolicy Bypass -File $gate -RepoRoot $Root 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{
        exit_code = $exitCode
        output = ($output -join [Environment]::NewLine)
    }
}

function Assert-SelfTest {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "Spatial workflow self-test failed: $Message"
    }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null

    $baseline = New-TestRepo -Name "baseline"
    $baselineResult = Invoke-Gate -Root $baseline
    Assert-SelfTest ($baselineResult.exit_code -eq 0) "baseline did not pass: $($baselineResult.output)"

    $activeLiveIndex = New-TestRepo -Name "active-live-index"
    $activeStatePath = Join-Path $activeLiveIndex "apps\spatial-camera-panel-android\morphospace\workspace.state.json"
    $activeState = Get-Content -Raw -LiteralPath $activeStatePath | ConvertFrom-Json
    $activeState.current_unit = "legacy-mixed-workspace"
    $activeState.dirty_repositories = @("quest-repo")
    Write-TestJson -Path $activeStatePath -Value $activeState
    Assert-SelfTest ((Invoke-Gate -Root $activeLiveIndex).exit_code -ne 0) "active live compatibility workspace was accepted"

    $nonInertLock = New-TestRepo -Name "non-inert-lock"
    $lockPath = Join-Path $nonInertLock "apps\spatial-camera-panel-android\morphospace\feature.lock.json"
    $lock = Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
    $lock.features = @([pscustomobject]@{ feature_id = "legacy-mixed-workspace" })
    Write-TestJson -Path $lockPath -Value $lock
    Assert-SelfTest ((Invoke-Gate -Root $nonInertLock).exit_code -ne 0) "non-inert live lock was accepted"

    $archiveDrift = New-TestRepo -Name "archive-drift"
    $archiveReadme = Join-Path $archiveDrift "apps\spatial-camera-panel-android\legacy-workspaces\mixed-integration-v1\README.md"
    [System.IO.File]::AppendAllText($archiveReadme, "archive-drift", [System.Text.Encoding]::UTF8)
    Assert-SelfTest ((Invoke-Gate -Root $archiveDrift).exit_code -ne 0) "archive byte drift was accepted"

    $archiveMetadataDrift = New-TestRepo -Name "archive-metadata-drift"
    $archiveMetadataPath = Join-Path $archiveMetadataDrift "apps\spatial-camera-panel-android\morphospace\legacy-archive.json"
    $archiveMetadata = Get-Content -Raw -LiteralPath $archiveMetadataPath | ConvertFrom-Json
    $archiveMetadata.file_count = [int]$archiveMetadata.file_count + 1
    Write-TestJson -Path $archiveMetadataPath -Value $archiveMetadata
    Assert-SelfTest ((Invoke-Gate -Root $archiveMetadataDrift).exit_code -ne 0) "archive file-count drift was accepted"

    Write-Host "Spatial Camera Panel workflow static self-test passed"
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove workflow self-test path outside the system temp directory: $resolvedTestRoot"
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
