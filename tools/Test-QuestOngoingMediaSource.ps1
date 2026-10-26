param(
    [string]$RepoRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$HostJsonJar = $env:MEDIA_STREAM_HOST_JSON_JAR,
    [string]$AndroidNdkHome = $env:ANDROID_NDK_HOME,
    [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'These native/Java source composition controls require Windows.' }
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
if (-not $OutputRoot) { $OutputRoot = Join-Path $root ('target/ongoing-source-' + [guid]::NewGuid().ToString('N')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
if (-not $OutputRoot.StartsWith((Join-Path $root 'target') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $OutputRoot)) { throw 'A fresh output beneath the repository target directory is required.' }
if (-not $JavaHome) { $JavaHome = Split-Path (Split-Path (Get-Command javac -CommandType Application | Select-Object -First 1).Source -Parent) -Parent }
if (-not $HostJsonJar) {
    $cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json/20240303'
    $matches = @(Get-ChildItem -LiteralPath $cache -Filter 'json-20240303.jar' -File -Recurse -ErrorAction SilentlyContinue)
    if ($matches.Count -ne 1) { throw 'Supply HostJsonJar for the pinned org.json 20240303 host dependency.' }
    $HostJsonJar = $matches[0].FullName
}
if ((Get-FileHash -LiteralPath $HostJsonJar).Hash.ToLowerInvariant() -cne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') { throw 'Host JSON library differs from the pinned dependency.' }
if (-not $AndroidNdkHome) { throw 'Supply AndroidNdkHome for the actual Android-target source check.' }
$glslc = Join-Path $AndroidNdkHome 'shader-tools/windows-x86_64/glslc.exe'
if (-not (Test-Path -LiteralPath $glslc -PathType Leaf)) { throw 'The selected Android NDK shader compiler is unavailable.' }
$null = New-Item -ItemType Directory -Path $OutputRoot
$checks = [Collections.Generic.List[object]]::new()
function Invoke-Control([string]$Name, [string]$Executable, [string[]]$Arguments) {
    $log = Join-Path $OutputRoot ($Name + '.log')
    & $Executable @Arguments *> $log
    $exitCode = $LASTEXITCODE
    $checks.Add([ordered]@{ name = $Name; exit_code = $exitCode; log = $log })
    if ($exitCode -ne 0) { Get-Content -LiteralPath $log -Tail 40 | Write-Output; throw "$Name failed; retained output: $log" }
    Write-Host "$Name passed"
}
$python = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$rustc = (Get-Command rustc -CommandType Application | Select-Object -First 1).Source
$cargo = (Get-Command cargo -CommandType Application | Select-Object -First 1).Source
$pwsh = (Get-Command pwsh -CommandType Application | Select-Object -First 1).Source
$priorNdk = $env:ANDROID_NDK_HOME
$priorGlslc = $env:GLSLC
Push-Location -LiteralPath $root
try {
    Invoke-Control 'policy-identity' $pwsh @('-NoProfile','-File','tools/checks/Test-EmbeddedDuplexMediaPolicy.ps1','-RepoRoot',$root)
    Invoke-Control 'session-inputs' $python @('tools/checks/Test-EmbeddedDuplexMediaPolicyHost.py','--root',$root,'--output',(Join-Path $OutputRoot 'session-inputs'))
    Invoke-Control 'bootstrap-failures' $python @('tools/checks/Test-BootstrapResourceFailureHost.py','--root',$root,'--output',(Join-Path $OutputRoot 'bootstrap-failures'),'--javac',(Join-Path $JavaHome 'bin/javac.exe'),'--java',(Join-Path $JavaHome 'bin/java.exe'))
    Invoke-Control 'source-graph-readiness' $python @('tools/checks/Test-SourceGraphStartupProducerHost.py','--repo-root',$root,'--output-root',(Join-Path $OutputRoot 'source-graph'),'--rustc',$rustc)
    Invoke-Control 'whole-close' $python @('tools/checks/Test-WholeCloseActivityRolloverHost.py','--repo-root',$root,'--output-root',(Join-Path $OutputRoot 'whole-close'),'--java-home',$JavaHome,'--json-jar',$HostJsonJar)
    Invoke-Control 'original-enrollment' $pwsh @('-NoProfile','-File','tools/checks/Test-RetainedOriginalEnrollmentHost.ps1','-RepoRoot',$root,'-OutputRoot',(Join-Path $OutputRoot 'original-enrollment'),'-Rustc',$rustc)
    $cycles = Join-Path $OutputRoot 'native-cycle-ledger.exe'
    Invoke-Control 'native-cycle-compile' $rustc @('--edition','2021','--test','apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/pair_renewal_guard.rs','-o',$cycles)
    Invoke-Control 'native-cycle-controls' $cycles @('--test-threads=1')
    Invoke-Control 'finite-product-state' $cargo @('test','-p','rusty-quest-broker-authority','--locked','finite_media_construction_preserves_initial_state_and_product_scope','--','--test-threads=1')
    $env:ANDROID_NDK_HOME = $AndroidNdkHome
    $env:GLSLC = $glslc
    Invoke-Control 'actual-android-types' $cargo @('check','-p','spatial-camera-panel-native-receipt','--target','aarch64-linux-android','--locked')
    $result = [ordered]@{ status = 'passed'; scope = 'Actual selected builder, SessionInputs, bootstrap, native readiness, continuation fence and local whole-close source controls; Android native type/shader compilation.'; checks = @($checks.ToArray()); limitations = @('Renderer, OS and physical owner facts in host controls are modeled.','No APK, headset or physical streaming/cleanup claim.','Run the explicit long coupled-owner qualification separately for the reviewed candidate; it is not repeated by this short source control suite.') }
    $result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8
    Write-Host "Ongoing source controls passed: $OutputRoot"
} finally {
    $env:ANDROID_NDK_HOME = $priorNdk
    $env:GLSLC = $priorGlslc
    Pop-Location
}
