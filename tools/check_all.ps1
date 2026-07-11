param(
    [switch]$IncludeLegacyMakepad
)

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
    Invoke-Checked "cargo test" "cargo" @("test", "--workspace", "--exclude", "spatial-camera-panel-native-receipt")
    if ($IncludeLegacyMakepad) {
        Invoke-Checked "legacy Makepad runtime profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-makepad-mesh-replay.profile.json", "-DryRun")
    } else {
        Write-Host "Skipping legacy Makepad runtime profile validation (pass -IncludeLegacyMakepad to opt in)."
    }
    Invoke-Checked "remote camera runtime profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-remote-camera-q2q-diagnostic.profile.json", "-DryRun", "-Out", "local-artifacts\remote-camera-property-write-plan.json")
    Invoke-Checked "native renderer runtime profile matrix" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-NativeRendererProfileMatrix.ps1")
    Invoke-Checked "native renderer property parity" "python" @("tools\check_native_renderer_property_parity.py", "--out", "local-artifacts\native-renderer-property-parity.json")
    Invoke-Checked "native app-build static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-NativeAppBuildStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "QCL-041 Wi-Fi Direct Android harness" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-Qcl041WifiDirectHarnessAndroid.ps1")
    if ($IncludeLegacyMakepad) {
        Invoke-Checked "legacy QCL-099 stereo projection runner" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-Qcl099StereoProjectionRunner.ps1")
    } else {
        Write-Host "Skipping legacy QCL-099/Makepad validation (pass -IncludeLegacyMakepad to opt in)."
    }
    Invoke-Checked "QCL-100 crash/relaunch watch static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-Qcl100CrashRelaunchWatchStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "QCL-100 native stereo promotion candidate static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-Qcl100NativeStereoPromotionCandidateStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "QCL-100 packed stereo static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-Qcl100PackedStereoStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "Quest particle adapter static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-QuestParticleAdapterStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "Quest hand adapter static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-QuestHandAdapterStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "Quest broker product static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-QuestBrokerProductStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "Quest broker authority static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\checks\Test-QuestBrokerAuthorityStatic.ps1", "-RepoRoot", ".")
    Invoke-Checked "Spatial Camera Panel Android static gate" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-SpatialCameraPanelAndroid.ps1", "-RepoRoot", ".")
    Invoke-Checked "Manifold broker Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-ManifoldBrokerAndroid.ps1")
    Invoke-Checked "Peer rendezvous Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-PeerRendezvousAndroid.ps1")
    Invoke-Checked "Native renderer Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-NativeRendererAndroid.ps1", "-SkipProfileMatrix")
    Invoke-Checked "Quest boundary scan" "python" @("tools\check_quest_boundaries.py")
} finally {
    Pop-Location
}
