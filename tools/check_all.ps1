$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,
        [Parameter(Mandatory=$true)]
        [string]$File,
        [string[]]$Arguments = @()
    )

    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $RepoRoot
try {
    New-Item -ItemType Directory -Path "local-artifacts" -Force | Out-Null
    Invoke-Checked "cargo fmt" "cargo" @("fmt", "--all", "--check")
    Invoke-Checked "cargo test" "cargo" @("test", "--workspace")
    Invoke-Checked "runtime profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-makepad-mesh-replay.profile.json", "-DryRun")
    Invoke-Checked "remote camera runtime profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json", "-DryRun", "-Out", "local-artifacts\remote-camera-property-write-plan.json")
    Invoke-Checked "Manifold broker Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-ManifoldBrokerAndroid.ps1")
    Invoke-Checked "Quest boundary scan" "python" @("tools\check_quest_boundaries.py")
} finally {
    Pop-Location
}
