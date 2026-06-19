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
    Invoke-Checked "native renderer replay visual proof profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-replay-visual-proof.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-replay-visual-proof-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB camera quality profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-camera-quality-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB BT601/UNORM camera quality profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-camera-quality-bt601-unorm.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-camera-quality-bt601-unorm-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB low-noise 30 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-30.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-low-noise-30-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB low-noise record 30 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-noise-record-30.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-low-noise-record-30-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB low-latency 60 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-low-latency-60.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-low-latency-60-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB hold-sync profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-hold-sync-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB hold-sync reader 6 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader6.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-hold-sync-reader6-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB hold-sync reader 8 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-hold-sync-reader8.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-hold-sync-reader8-property-write-plan.json")
    Invoke-Checked "native renderer direct HWB 1280x960 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-direct-hwb-1280x960.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-direct-hwb-1280x960-property-write-plan.json")
    Invoke-Checked "native renderer hwb peripheral stretch profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-hwb-peripheral-stretch.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-hwb-peripheral-stretch-property-write-plan.json")
    Invoke-Checked "native renderer live hand visual diagnostic profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-live-hand-visual-diagnostic.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-live-hand-visual-diagnostic-property-write-plan.json")
    Invoke-Checked "native renderer native passthrough graft-only profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-graft-only.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-native-passthrough-graft-only-property-write-plan.json")
    Invoke-Checked "native renderer native passthrough hands-and-grafts profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-hands-and-grafts.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-native-passthrough-hands-and-grafts-property-write-plan.json")
    Invoke-Checked "native renderer solid black hands-and-grafts profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-solid-black-hands-and-grafts.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-solid-black-hands-and-grafts-property-write-plan.json")
    Invoke-Checked "native renderer solid black OpenXR hands anchor particles profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-solid-black-openxr-hands-anchor-particles.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-solid-black-openxr-hands-anchor-particles-property-write-plan.json")
    Invoke-Checked "native renderer environment depth status profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-environment-depth-status.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-environment-depth-status-property-write-plan.json")
    Invoke-Checked "native renderer native passthrough environment depth particles profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-environment-depth-particles.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-native-passthrough-environment-depth-particles-property-write-plan.json")
    Invoke-Checked "native renderer native passthrough Meta environment depth particles profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-property-write-plan.json")
    Invoke-Checked "native renderer native passthrough Meta environment depth particles layer-1 profile dry-run" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Apply-RuntimeProfile.ps1", "-ProfilePath", "fixtures\runtime-profiles\quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json", "-DryRun", "-Out", "local-artifacts\native-renderer-native-passthrough-meta-environment-depth-particles-layer1-property-write-plan.json")
    Invoke-Checked "Manifold broker Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-ManifoldBrokerAndroid.ps1")
    Invoke-Checked "Native renderer Android scaffold" "powershell" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools\Test-NativeRendererAndroid.ps1")
    Invoke-Checked "Quest boundary scan" "python" @("tools\check_quest_boundaries.py")
} finally {
    Pop-Location
}
