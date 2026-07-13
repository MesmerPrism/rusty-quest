param(
    [Parameter(Mandatory=$true)][string[]]$Serial,
    [string]$BrokerApk = "",
    [string]$NativeApk = "",
    [string]$SpatialApk = "",
    [string]$NativeLifecycleEvidence = "",
    [string]$SpatialLifecycleEvidence = "",
    [switch]$CollectLifecycleArtifactsFromApps,
    [switch]$GenerateLifecycleRecoveryEvidence,
    [string]$NativeLifecycleRecoveryEvidence = "",
    [string]$SpatialLifecycleRecoveryEvidence = "",
    [string]$EvidenceDir = "",
    [string]$Adb = "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
if (@($Serial | Select-Object -Unique).Count -ne 2) { throw "Exactly two distinct Quest serials are required." }
if ([string]::IsNullOrWhiteSpace($BrokerApk)) { $BrokerApk = Join-Path $repo "target\manifold-broker-android\rusty-manifold-broker.apk" }
if ([string]::IsNullOrWhiteSpace($NativeApk)) { $NativeApk = Join-Path $repo "target\native-renderer-android\rusty-quest-native-renderer.apk" }
if ([string]::IsNullOrWhiteSpace($SpatialApk)) { $SpatialApk = Join-Path $repo "target\spatial-camera-panel-android\rusty-quest-spatial-camera-panel.apk" }
foreach($path in @($Adb,$BrokerApk,$NativeApk,$SpatialApk)){ if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing suite input: $path"} }
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path "S:\Work\tmp" ("morphospace-net016-multi-app-broker-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$nativeLifecycleEvidenceSupplied = -not [string]::IsNullOrWhiteSpace($NativeLifecycleEvidence)
$spatialLifecycleEvidenceSupplied = -not [string]::IsNullOrWhiteSpace($SpatialLifecycleEvidence)
$nativeRecoveryEvidenceSupplied = -not [string]::IsNullOrWhiteSpace($NativeLifecycleRecoveryEvidence)
$spatialRecoveryEvidenceSupplied = -not [string]::IsNullOrWhiteSpace($SpatialLifecycleRecoveryEvidence)
$hasFullLifecycleEvidence = $nativeLifecycleEvidenceSupplied -and $spatialLifecycleEvidenceSupplied
$hasRecoveryEvidenceTemplates = $nativeRecoveryEvidenceSupplied -and $spatialRecoveryEvidenceSupplied
if ($nativeLifecycleEvidenceSupplied -xor $spatialLifecycleEvidenceSupplied) {
  throw "Native and Spatial lifecycle evidence must be supplied together."
}
if ($nativeRecoveryEvidenceSupplied -xor $spatialRecoveryEvidenceSupplied) {
  throw "Native and Spatial lifecycle recovery evidence must be supplied together."
}
if ($hasRecoveryEvidenceTemplates -and -not $CollectLifecycleArtifactsFromApps) {
  throw "Lifecycle recovery evidence can only be used with -CollectLifecycleArtifactsFromApps because start/stop completion artifacts must be pulled from app-private storage."
}
if ($GenerateLifecycleRecoveryEvidence -and -not $CollectLifecycleArtifactsFromApps) {
  throw "Generated lifecycle recovery evidence requires -CollectLifecycleArtifactsFromApps because it binds pulled start/stop and manifest artifacts."
}
if ($GenerateLifecycleRecoveryEvidence -and $hasRecoveryEvidenceTemplates) {
  throw "Use either -GenerateLifecycleRecoveryEvidence or explicit lifecycle recovery evidence templates, not both."
}
if (-not $hasFullLifecycleEvidence -and -not $CollectLifecycleArtifactsFromApps) {
  throw "NET-016 media lifecycle evidence is required before the two-Quest wrapper may install apps. Provide -NativeLifecycleEvidence and -SpatialLifecycleEvidence generated from the app runtime start/stop/apply/release/restart path, or pass -CollectLifecycleArtifactsFromApps for a non-promotional partial artifact collection run."
}
if (-not $hasFullLifecycleEvidence -and $CollectLifecycleArtifactsFromApps) {
  Write-Warning "CollectLifecycleArtifactsFromApps is non-promotional: pulled app-private artifacts do not prove app death, provider restart, old-epoch rejection, cleanup, or bounded fatal absence."
  if($hasRecoveryEvidenceTemplates){
    Write-Warning "Lifecycle recovery evidence templates are explicit operator/runtime inputs. The wrapper will assemble and validate them, but it will not infer recovery from partial app artifacts."
  }
  if($GenerateLifecycleRecoveryEvidence){
    Write-Warning "GenerateLifecycleRecoveryEvidence is promotional only if every observed recovery gate passes: app death, fresh provider epoch, old-epoch rejection, cleanup, and zero bounded fatals."
  }
}
if ($hasFullLifecycleEvidence) {
  foreach($path in @($NativeLifecycleEvidence,$SpatialLifecycleEvidence)){ if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing lifecycle evidence: $path"} }
}
$broker = "io.github.mesmerprism.rustymanifold.broker"
$native = "io.github.mesmerprism.rustyquest.native_renderer"
$spatial = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
$activity = "io.github.mesmerprism.rustyquest.broker_client.BrokerClientProbeActivity"

function Write-Text([string]$Path,[string]$Text){[IO.File]::WriteAllText($Path,$Text,$utf8NoBom)}
function Adb([string]$Device,[string[]]$AdbArgs,[switch]$AllowFailure){$o=& $Adb -s $Device @AdbArgs 2>&1;$c=$LASTEXITCODE;if($c-ne 0-and-not$AllowFailure){throw "adb -s $Device $($AdbArgs -join ' ') failed: $($o -join ' ')"};@($o)}
function Get-SafeDeviceDirectoryName([string]$Device){$Device -replace '[^A-Za-z0-9_.-]','_'}
function Wait-Marker([string]$Device,[string]$ClientId,[int]$Seconds){$deadline=(Get-Date).AddSeconds($Seconds);do{$log=(Adb $Device @("logcat","-d","-s","RustyBrokerClient:V","RustyManifoldAdmission:V","AndroidRuntime:E","*:S"))-join"`n";if($log-match("RUSTY_QUEST_BROKER_CLIENT clientId="+[regex]::Escape($ClientId)+"[^`n]*status=accepted")){return $log};if($log-match("RUSTY_QUEST_BROKER_CLIENT clientId="+[regex]::Escape($ClientId)+"[^`n]*status=rejected")){throw "Client rejected on $Device for $ClientId`n$log"};if($log-match"FATAL EXCEPTION"){throw "Client fatal on $Device`n$log"};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline);throw "Timed out waiting for $ClientId on $Device`n$log"}
function Wait-OldEpochMarker([string]$Device,[string]$ClientId,[int]$Seconds){$deadline=(Get-Date).AddSeconds($Seconds);do{$log=(Adb $Device @("logcat","-d","-s","RustyBrokerClient:V","RustyManifoldAdmission:V","AndroidRuntime:E","*:S"))-join"`n";if($log-match("RUSTY_QUEST_BROKER_CLIENT clientId="+[regex]::Escape($ClientId)+"[^`n]*status=old_epoch_rejected")){return $log};if($log-match"FATAL EXCEPTION"){throw "Client fatal during old-epoch probe on $Device`n$log"};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline);throw "Timed out waiting for old-epoch rejection from $ClientId on $Device"}
function Sha256([string]$Path){
  $stream = [IO.File]::OpenRead($Path)
  try {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
      (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
      $sha.Dispose()
    }
  } finally {
    $stream.Dispose()
  }
}
function Get-FinalAdmissionRevision([string]$Log,[string]$ClientId){
  $matches = [regex]::Matches($Log, "RUSTY_QUEST_BROKER_CLIENT clientId=" + [regex]::Escape($ClientId) + "[^`n]*status=accepted[^`n]*admissionRevision=(\d+)")
  if($matches.Count -eq 0){throw "Missing final admission revision marker for $ClientId"}
  [int64]$matches[$matches.Count - 1].Groups[1].Value
}
function Pull-AppPrivateTextFile([string]$Device,[string]$Package,[string]$RelativePath,[string]$OutPath){
  $psi = [Diagnostics.ProcessStartInfo]::new()
  $psi.FileName = $Adb
  $psi.Arguments = (@("-s",$Device,"exec-out","run-as",$Package,"cat",$RelativePath) -join " ")
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $process = [Diagnostics.Process]::Start($psi)
  try {
    $stream = [IO.File]::Open($OutPath,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
      $process.StandardOutput.BaseStream.CopyTo($stream)
    } finally {
      $stream.Dispose()
    }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if($process.ExitCode -ne 0){
      throw "adb -s $Device exec-out run-as $Package cat $RelativePath failed: $stderr"
    }
  } finally {
    $process.Dispose()
  }
  [ordered]@{relative_path=$RelativePath;path=$OutPath;sha256="sha256:$(Sha256 $OutPath)"}
}
function Pull-LifecycleArtifacts([string]$Device,[string]$Package,[string]$Label,[string]$OutDir){
  New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
  $files = @(
    "files/broker-media-lifecycle/manifest.json",
    "files/broker-media-lifecycle/start-completion.json",
    "files/broker-media-lifecycle/stop-completion.json",
    "files/broker-media-lifecycle/frame-evidence.txt",
    "files/broker-media-lifecycle/marker-evidence.txt"
  )
  $pulled = @()
  $issues = @()
  foreach($file in $files){
    try {
      $outPath = Join-Path $OutDir (($file -replace '[\\/:]','_'))
      $pulled += Pull-AppPrivateTextFile -Device $Device -Package $Package -RelativePath $file -OutPath $outPath
    } catch {
      $detail = ([string]$_.Exception.Message).Replace("`r"," ").Replace("`n"," ").Trim()
      if($detail.Length -gt 320){ $detail = $detail.Substring(0,320) }
      $issues += "missing_or_unreadable:$file detail=$detail"
    }
  }
  $manifest = $null
  $manifestPath = Join-Path $OutDir "files_broker-media-lifecycle_manifest.json"
  if(Test-Path -LiteralPath $manifestPath -PathType Leaf){
    try { $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json } catch { $issues += "manifest_json_invalid" }
  }
  if($null -eq $manifest){
    $issues += "manifest_missing"
  } else {
    if($manifest.recovery_complete -ne $false){ $issues += "manifest_must_remain_partial_recovery_complete_false" }
    if(-not (($manifest.does_not_prove -join " ") -match "app death" -and ($manifest.does_not_prove -join " ") -match "cleanup")) {
      $issues += "manifest_missing_does_not_prove_recovery_cleanup"
    }
  }
  [pscustomobject][ordered]@{
    label=$Label
    package=$Package
    status=if($issues.Count -eq 0){"partial_collected"}else{"partial_failed"}
    artifact_dir=$OutDir
    manifest_path=$manifestPath
    start_completion_path=(Join-Path $OutDir "files_broker-media-lifecycle_start-completion.json")
    stop_completion_path=(Join-Path $OutDir "files_broker-media-lifecycle_stop-completion.json")
    frame_evidence_path=(Join-Path $OutDir "files_broker-media-lifecycle_frame-evidence.txt")
    marker_evidence_path=(Join-Path $OutDir "files_broker-media-lifecycle_marker-evidence.txt")
    files=$pulled
    manifest=$manifest
    recovery_complete=if($null -eq $manifest){$null}else{$manifest.recovery_complete}
    does_not_prove=if($null -eq $manifest){@()}else{@($manifest.does_not_prove)}
    issues=@($issues)
  }
}
function Pull-OldEpochProbe([string]$Device,[string]$Package,[string]$Label,[string]$OutDir){
  New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
  $outPath = Join-Path $OutDir "old-epoch-rejection.json"
  Pull-AppPrivateTextFile -Device $Device -Package $Package -RelativePath "files/broker-media-lifecycle-recovery/old-epoch-rejection.json" -OutPath $outPath | Out-Null
  $probe = Get-Content -Raw -LiteralPath $outPath | ConvertFrom-Json
  if($probe.'$schema' -ne "rusty.quest.broker_client.old_epoch_rejection_probe.v1"){throw "Old-epoch probe for $Label has wrong schema."}
  if($probe.old_epoch_rejected -ne $true -or $probe.local_acceptance_rules -ne $false){throw "Old-epoch probe for $Label was not an authority rejection."}
  if([string]::IsNullOrWhiteSpace([string]$probe.current_provider_epoch_id) -or [string]$probe.current_provider_epoch_id -eq [string]$probe.old_provider_epoch_id){
    throw "Old-epoch probe for $Label did not prove a fresh provider epoch."
  }
  [pscustomobject][ordered]@{
    label=$Label
    path=$outPath
    sha256="sha256:$(Sha256 $outPath)"
    probe=$probe
  }
}
function Resolve-TemplatePath([string]$Template,[string]$Device,[string]$Label){
  $client = if($Label -eq "native-renderer"){"native-renderer"}else{"spatial-camera-panel"}
  $Template.Replace("{serial}",$Device).Replace("{client}",$client)
}
function Test-AppDeathObserved([string]$Device,[string]$Package){
  Adb $Device @("shell","am","force-stop",$Package)-AllowFailure | Out-Null
  Start-Sleep -Milliseconds 500
  $appPid = (Adb $Device @("shell","pidof",$Package)-AllowFailure) -join ""
  [string]::IsNullOrWhiteSpace($appPid.Trim())
}
function Get-FatalCounts([string]$Log){
  [pscustomobject][ordered]@{
    package = ([regex]::Matches($Log,'FATAL EXCEPTION:[\s\S]{0,1200}(native_renderer|spatial_camera_panel|rustymanifold)')).Count
    system = ([regex]::Matches($Log,'FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog.*system_server|Fatal signal.*system_server')).Count
  }
}
function Test-StopCompletionReleased([object]$Artifacts){
  if(-not(Test-Path -LiteralPath $Artifacts.stop_completion_path -PathType Leaf)){return $false}
  try {
    $stop = Get-Content -Raw -LiteralPath $Artifacts.stop_completion_path | ConvertFrom-Json
    return ($stop.platform_effect_completed -eq $true -and $stop.decision_owner_id -eq "module.runtime.host")
  } catch {
    return $false
  }
}
function Write-ObservedRecoveryEvidence([object]$Artifacts,[object]$OldEpochProbe,[bool]$AppDeath,[bool]$Cleanup,[object]$FatalCounts,[string]$OutPath){
  $manifest = $Artifacts.manifest
  $leaseReleased = Test-StopCompletionReleased -Artifacts $Artifacts
  $clientSuffix = ([string]$manifest.client_id).Replace("-","_").Replace(".","_")
  $evidence = [ordered]@{
    '$schema' = "rusty.quest.broker_client.lifecycle_recovery_evidence.v1"
    client_id = [string]$manifest.client_id
    package_name = [string]$manifest.package_name
    recovery_source = "wrapper_observed_device_sequence"
    release_receipt_id = if($leaseReleased){"receipt.release.derived_from_stop_completion.$clientSuffix"}else{""}
    lease_released = [bool]$leaseReleased
    app_death_observed = [bool]$AppDeath
    rebind_provider_epoch_id = [string]$manifest.provider_epoch_id
    pre_rebind_authority_revision = [uint64]$manifest.runtime_authority_revision
    post_rebind_authority_revision = [uint64]$manifest.runtime_authority_revision
    restarted_provider_epoch_id = [string]$OldEpochProbe.probe.current_provider_epoch_id
    old_epoch_rejected = [bool]$OldEpochProbe.probe.old_epoch_rejected
    old_epoch_probe_path = [string]$OldEpochProbe.path
    old_epoch_probe_sha256 = [string]$OldEpochProbe.sha256
    cleanup_complete = [bool]$Cleanup
    package_fatal_count = [uint32]$FatalCounts.package
    system_fatal_count = [uint32]$FatalCounts.system
  }
  Write-Text $OutPath ($evidence | ConvertTo-Json -Depth 12)
  $OutPath
}
function Invoke-ObservedLifecycleRecoveryPair([string]$Device,[string]$DeviceDir,[object]$NativeArtifacts,[object]$SpatialArtifacts){
  $recoveryDir = Join-Path $DeviceDir "generated-lifecycle-recovery"
  New-Item -ItemType Directory -Force -Path $recoveryDir | Out-Null
  $nativeDeath = Test-AppDeathObserved -Device $Device -Package $native
  $spatialDeath = Test-AppDeathObserved -Device $Device -Package $spatial
  Adb $Device @("shell","am","force-stop",$broker)-AllowFailure | Out-Null
  Start-Sleep -Milliseconds 500
  $oldNativeEpoch = [string]$NativeArtifacts.manifest.provider_epoch_id
  $oldSpatialEpoch = [string]$SpatialArtifacts.manifest.provider_epoch_id
  Adb $Device @("shell","am","start","-n","$native/$activity","--el","expected_authority_revision","1","--es","recovery_probe_mode","old_epoch_rejection","--es","override_provider_epoch_id",$oldNativeEpoch) | Out-Null
  $nativeOldLog = Wait-OldEpochMarker $Device "client.quest.native-renderer" 20
  $nativeOldProbe = Pull-OldEpochProbe -Device $Device -Package $native -Label "native-renderer" -OutDir (Join-Path $recoveryDir "native-renderer")
  $spatialExpectedRevision = [int64]$nativeOldProbe.probe.admission_authority_revision
  if($spatialExpectedRevision -le 0){throw "Native old-epoch probe did not report a usable admission revision for Spatial recovery probe."}
  Adb $Device @("shell","am","start","-n","$spatial/$activity","--el","expected_authority_revision",$spatialExpectedRevision.ToString(),"--es","recovery_probe_mode","old_epoch_rejection","--es","override_provider_epoch_id",$oldSpatialEpoch) | Out-Null
  $spatialOldLog = Wait-OldEpochMarker $Device "client.quest.spatial-camera-panel" 20
  $spatialOldProbe = Pull-OldEpochProbe -Device $Device -Package $spatial -Label "spatial-camera-panel" -OutDir (Join-Path $recoveryDir "spatial-camera-panel")
  Write-Text (Join-Path $recoveryDir "old-epoch-logcat.txt") (($nativeOldLog,$spatialOldLog) -join "`n")
  foreach($pkg in @($native,$spatial,$broker)){
    Adb $Device @("shell","am","force-stop",$pkg)-AllowFailure | Out-Null
    Adb $Device @("uninstall",$pkg)-AllowFailure | Out-Null
  }
  $listed=(Adb $Device @("shell","pm","list","packages"))-join"`n"
  $cleanup=@(@($native,$spatial,$broker)|Where-Object{$listed-match[regex]::Escape($_)}).Count-eq0
  $fullLog=(Adb $Device @("logcat","-d"))-join"`n"
  Write-Text (Join-Path $recoveryDir "recovery-logcat.txt") $fullLog
  $fatalCounts = Get-FatalCounts -Log $fullLog
  $nativeRecoveryPath = Join-Path $recoveryDir "native-renderer-recovery.json"
  $spatialRecoveryPath = Join-Path $recoveryDir "spatial-camera-panel-recovery.json"
  Write-ObservedRecoveryEvidence -Artifacts $NativeArtifacts -OldEpochProbe $nativeOldProbe -AppDeath $nativeDeath -Cleanup $cleanup -FatalCounts $fatalCounts -OutPath $nativeRecoveryPath | Out-Null
  Write-ObservedRecoveryEvidence -Artifacts $SpatialArtifacts -OldEpochProbe $spatialOldProbe -AppDeath $spatialDeath -Cleanup $cleanup -FatalCounts $fatalCounts -OutPath $spatialRecoveryPath | Out-Null
  [pscustomobject][ordered]@{
    native_recovery_path=$nativeRecoveryPath
    spatial_recovery_path=$spatialRecoveryPath
    native_app_death_observed=$nativeDeath
    spatial_app_death_observed=$spatialDeath
    cleanup_complete=$cleanup
    package_fatal_count=$fatalCounts.package
    system_fatal_count=$fatalCounts.system
  }
}
function Read-RecoveryEvidence([string]$Path,[string]$Label){
  if(-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw "Missing lifecycle recovery evidence for ${Label}: $Path"}
  $evidence = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
  if($evidence.'$schema' -ne "rusty.quest.broker_client.lifecycle_recovery_evidence.v1"){
    throw "Lifecycle recovery evidence for ${Label} has wrong schema."
  }
  foreach($field in @("release_receipt_id","lease_released","app_death_observed","rebind_provider_epoch_id","pre_rebind_authority_revision","post_rebind_authority_revision","restarted_provider_epoch_id","old_epoch_rejected","cleanup_complete","package_fatal_count","system_fatal_count")){
    if(-not ($evidence.PSObject.Properties.Name -contains $field)){ throw "Lifecycle recovery evidence for ${Label} missing $field" }
  }
  if($evidence.lease_released -ne $true -or
     $evidence.app_death_observed -ne $true -or
     $evidence.old_epoch_rejected -ne $true -or
     $evidence.cleanup_complete -ne $true -or
     [int]$evidence.package_fatal_count -ne 0 -or
     [int]$evidence.system_fatal_count -ne 0){
    throw "Lifecycle recovery evidence for ${Label} is not acceptance-complete."
  }
  $evidence
}
function Build-AssemblyEvidence([object]$Artifacts,[string]$RecoveryPath,[string]$OutPath){
  if($Artifacts.status -ne "partial_collected"){throw "Cannot assemble lifecycle evidence from incomplete partial artifacts for $($Artifacts.label)"}
  $manifest = $Artifacts.manifest
  if($null -eq $manifest){throw "Cannot assemble lifecycle evidence without manifest for $($Artifacts.label)"}
  foreach($field in @("provider_epoch_id","session_id","stream_id","render_sink_id","marker_namespace","status_receipt_id","subscription_receipt_id","receiver_observed_bytes","rendered_frame_count")){
    if(-not ($manifest.PSObject.Properties.Name -contains $field)){ throw "Lifecycle artifact manifest for $($Artifacts.label) missing $field" }
  }
  $recovery = Read-RecoveryEvidence -Path $RecoveryPath -Label $Artifacts.label
  $assembly = [ordered]@{
    status_receipt_id = [string]$manifest.status_receipt_id
    subscription_receipt_id = [string]$manifest.subscription_receipt_id
    render = [ordered]@{
      provider_epoch_id = [string]$manifest.provider_epoch_id
      session_id = [string]$manifest.session_id
      stream_id = [string]$manifest.stream_id
      render_sink_id = [string]$manifest.render_sink_id
      marker_namespace = [string]$manifest.marker_namespace
      frame_evidence_path = [string]$Artifacts.frame_evidence_path
      frame_evidence_sha256 = "sha256:$(Sha256 $Artifacts.frame_evidence_path)"
      marker_evidence_path = [string]$Artifacts.marker_evidence_path
      marker_evidence_sha256 = "sha256:$(Sha256 $Artifacts.marker_evidence_path)"
      receiver_observed_bytes = [uint64]$manifest.receiver_observed_bytes
      rendered_frame_count = [uint64]$manifest.rendered_frame_count
    }
    release_receipt_id = [string]$recovery.release_receipt_id
    lease_released = [bool]$recovery.lease_released
    app_death_observed = [bool]$recovery.app_death_observed
    rebind_provider_epoch_id = [string]$recovery.rebind_provider_epoch_id
    pre_rebind_authority_revision = [uint64]$recovery.pre_rebind_authority_revision
    post_rebind_authority_revision = [uint64]$recovery.post_rebind_authority_revision
    restarted_provider_epoch_id = [string]$recovery.restarted_provider_epoch_id
    old_epoch_rejected = [bool]$recovery.old_epoch_rejected
    cleanup_complete = [bool]$recovery.cleanup_complete
    package_fatal_count = [uint32]$recovery.package_fatal_count
    system_fatal_count = [uint32]$recovery.system_fatal_count
  }
  Write-Text $OutPath ($assembly | ConvertTo-Json -Depth 12)
  $OutPath
}
function Invoke-AssembleLifecycleEvidence([string]$PackagePath,[object]$Artifacts,[string]$RecoveryPath,[string]$OutEvidencePath){
  $assemblyPath = Join-Path $Artifacts.artifact_dir "assembly-evidence.json"
  Build-AssemblyEvidence -Artifacts $Artifacts -RecoveryPath $RecoveryPath -OutPath $assemblyPath | Out-Null
  Push-Location $repo
  try {
    $output = @(& cargo run --quiet -p rusty-quest-broker-client --bin assemble_media_lifecycle_evidence -- $PackagePath $Artifacts.start_completion_path $Artifacts.stop_completion_path $assemblyPath 2>&1)
    if($LASTEXITCODE -ne 0){throw "NET-016 lifecycle evidence assembly failed for $($Artifacts.label): $($output -join ' ')"}
    Write-Text $OutEvidencePath ($output -join "`n")
  } finally {
    Pop-Location
  }
  $OutEvidencePath
}
function Make-MediaLifecyclePackage([string]$Client,[string]$Lifecycle,[string]$Feature,[string]$Binding,[string]$OutPath){
  $product = Join-Path $repo "..\rusty-manifold\fixtures\broker-product\media-session-standalone.lock.json"
  $package = [ordered]@{
    '$schema' = "rusty.quest.broker_media_lifecycle_package.v1"
    product_lock_json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $product).Path)
    product_lock_sha256 = "sha256:$(Sha256 $product)"
    client_lock_json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Client).Path)
    client_lock_sha256 = "sha256:$(Sha256 $Client)"
    media_lifecycle_lock_json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Lifecycle).Path)
    media_lifecycle_lock_sha256 = "sha256:$(Sha256 $Lifecycle)"
    app_feature_lock_json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Feature).Path)
    app_feature_lock_sha256 = "sha256:$(Sha256 $Feature)"
    media_binding_json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Binding).Path)
    media_binding_sha256 = "sha256:$(Sha256 $Binding)"
  }
  Write-Text $OutPath ($package | ConvertTo-Json -Depth 20)
}

$nativePackage = Join-Path $EvidenceDir "native-media-lifecycle-package.json"
$spatialPackage = Join-Path $EvidenceDir "spatial-media-lifecycle-package.json"
Make-MediaLifecyclePackage `
  -Client (Join-Path $repo "fixtures\broker-clients\native-renderer.client.json") `
  -Lifecycle (Join-Path $repo "fixtures\broker-clients\native-renderer.media-lifecycle.json") `
  -Feature (Join-Path $repo "apps\native-renderer-android\morphospace\conformance-locks\broker-media-client.feature.lock.json") `
  -Binding (Join-Path $repo "fixtures\media-runtime-products\native-renderer-display.binding.json") `
  -OutPath $nativePackage
Make-MediaLifecyclePackage `
  -Client (Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.client.json") `
  -Lifecycle (Join-Path $repo "fixtures\broker-clients\spatial-camera-panel.media-lifecycle.json") `
  -Feature (Join-Path $repo "apps\spatial-camera-panel-android\morphospace\conformance-locks\broker-media-client.feature.lock.json") `
  -Binding (Join-Path $repo "fixtures\media-runtime-products\spatial-camera-panel-display.binding.json") `
  -OutPath $spatialPackage
$pairReceipt = Join-Path $EvidenceDir "media-lifecycle-pair-receipt.json"
if ($hasFullLifecycleEvidence) {
  Push-Location $repo
  try {
    $validateOutput = @(& cargo run --quiet -p rusty-quest-broker-client --bin validate_media_lifecycle_evidence -- $nativePackage $NativeLifecycleEvidence $spatialPackage $SpatialLifecycleEvidence 2>&1)
    if($LASTEXITCODE -ne 0){throw "NET-016 lifecycle evidence validation failed: $($validateOutput -join ' ')"}
    Write-Text $pairReceipt ($validateOutput -join "`n")
  } finally {
    Pop-Location
  }
} else {
  $pairReceipt = $null
}

$rows=@()
$runtimePairReceipts=@()
foreach($device in $Serial){
  $deviceDir=Join-Path $EvidenceDir (Get-SafeDeviceDirectoryName $device);New-Item -ItemType Directory -Force -Path $deviceDir|Out-Null
  $issues=@();$cleanup=$false
  $partialLifecycleArtifacts=@()
  $devicePairReceipt=$null
  $preRecoveryChecksDone=$false
  try{
    if(((Adb $device @("get-state"))-join"").Trim()-ne"device"){throw "Quest $device not ready"}
    foreach($pkg in @($native,$spatial,$broker)){Adb $device @("uninstall",$pkg)-AllowFailure|Out-Null}
    foreach($item in @(@($BrokerApk,"broker"),@($NativeApk,"native"),@($SpatialApk,"spatial"))){Adb $device @("install","-r",$item[0])|Out-Null}
    Adb $device @("logcat","-c")|Out-Null
    Adb $device @("shell","am","start","-n","$native/$activity","--el","expected_authority_revision","1")|Out-Null
    $nativeLog=Wait-Marker $device "client.quest.native-renderer" 20
    $spatialExpectedRevision = Get-FinalAdmissionRevision -Log $nativeLog -ClientId "client.quest.native-renderer"
    Adb $device @("shell","am","start","-n","$spatial/$activity","--el","expected_authority_revision",$spatialExpectedRevision.ToString())|Out-Null
    $spatialLog=Wait-Marker $device "client.quest.spatial-camera-panel" 20
    $log=(Adb $device @("logcat","-d"))-join"`n";Write-Text (Join-Path $deviceDir "logcat.txt") $log
    if($CollectLifecycleArtifactsFromApps){
      $partialRoot = Join-Path $deviceDir "partial-lifecycle-artifacts"
      $partialLifecycleArtifacts += Pull-LifecycleArtifacts -Device $device -Package $native -Label "native-renderer" -OutDir (Join-Path $partialRoot "native-renderer")
      $partialLifecycleArtifacts += Pull-LifecycleArtifacts -Device $device -Package $spatial -Label "spatial-camera-panel" -OutDir (Join-Path $partialRoot "spatial-camera-panel")
      foreach($artifact in $partialLifecycleArtifacts){
        if($artifact.status -ne "partial_collected"){ $issues += "partial_lifecycle_artifact_collection_failed:$($artifact.label)" }
      }
      if($GenerateLifecycleRecoveryEvidence){
        foreach($expect in @(
          "clientId=client.quest.native-renderer[^`n]*featureLockId=lock.broker-client.native-renderer.v1[^`n]*markerNamespace=RUSTY_QUEST_NATIVE_BROKER_CLIENT[^`n]*contractFamilies=peer-session,media-session",
          "clientId=client.quest.spatial-camera-panel[^`n]*featureLockId=lock.broker-client.spatial-camera-panel.v1[^`n]*markerNamespace=RUSTY_QUEST_SPATIAL_BROKER_CLIENT[^`n]*contractFamilies=peer-session,media-session")){
          if($log-notmatch$expect){$issues+="client_marker_or_lock_mismatch"}
        }
        if($log-match"clientId=client.quest.native-renderer[^`n]*RUSTY_QUEST_SPATIAL_BROKER_CLIENT"-or$log-match"clientId=client.quest.spatial-camera-panel[^`n]*RUSTY_QUEST_NATIVE_BROKER_CLIENT"){$issues+="marker_bleed"}
        $nativeDump=(Adb $device @("shell","dumpsys","package",$native))-join"`n";$spatialDump=(Adb $device @("shell","dumpsys","package",$spatial))-join"`n"
        Write-Text (Join-Path $deviceDir "native-package.txt") $nativeDump;Write-Text (Join-Path $deviceDir "spatial-package.txt") $spatialDump
        $nativeUid=[regex]::Match($nativeDump,'(?m)^\s*appId=(\d+)').Groups[1].Value;$spatialUid=[regex]::Match($spatialDump,'(?m)^\s*appId=(\d+)').Groups[1].Value
        if([string]::IsNullOrWhiteSpace($nativeUid)-or[string]::IsNullOrWhiteSpace($spatialUid)-or$nativeUid-eq$spatialUid){$issues+="client_uid_not_distinct"}
        $initialFatalCounts = Get-FatalCounts -Log $log
        if($initialFatalCounts.package-ne0){$issues+="package_fatal_present"};if($initialFatalCounts.system-ne0){$issues+="system_fatal_present"}
        $preRecoveryChecksDone=$true
      }
      if(($hasRecoveryEvidenceTemplates -or $GenerateLifecycleRecoveryEvidence) -and $issues.Count -eq 0){
        $nativeArtifacts = @($partialLifecycleArtifacts | Where-Object {$_.label -eq "native-renderer"} | Select-Object -First 1)[0]
        $spatialArtifacts = @($partialLifecycleArtifacts | Where-Object {$_.label -eq "spatial-camera-panel"} | Select-Object -First 1)[0]
        if($GenerateLifecycleRecoveryEvidence){
          $generatedRecovery = Invoke-ObservedLifecycleRecoveryPair -Device $device -DeviceDir $deviceDir -NativeArtifacts $nativeArtifacts -SpatialArtifacts $spatialArtifacts
          $nativeRecoveryPath = $generatedRecovery.native_recovery_path
          $spatialRecoveryPath = $generatedRecovery.spatial_recovery_path
          if(-not $generatedRecovery.native_app_death_observed){$issues += "native_app_death_not_observed"}
          if(-not $generatedRecovery.spatial_app_death_observed){$issues += "spatial_app_death_not_observed"}
          if(-not $generatedRecovery.cleanup_complete){$issues += "cleanup_packages_remain"}
          if($generatedRecovery.package_fatal_count -ne 0){$issues += "package_fatal_present"}
          if($generatedRecovery.system_fatal_count -ne 0){$issues += "system_fatal_present"}
        } else {
          $nativeRecoveryPath = Resolve-TemplatePath -Template $NativeLifecycleRecoveryEvidence -Device $device -Label "native-renderer"
          $spatialRecoveryPath = Resolve-TemplatePath -Template $SpatialLifecycleRecoveryEvidence -Device $device -Label "spatial-camera-panel"
        }
        $nativeAssembledEvidence = Join-Path $deviceDir "native-media-lifecycle-evidence.json"
        $spatialAssembledEvidence = Join-Path $deviceDir "spatial-media-lifecycle-evidence.json"
        Invoke-AssembleLifecycleEvidence -PackagePath $nativePackage -Artifacts $nativeArtifacts -RecoveryPath $nativeRecoveryPath -OutEvidencePath $nativeAssembledEvidence | Out-Null
        Invoke-AssembleLifecycleEvidence -PackagePath $spatialPackage -Artifacts $spatialArtifacts -RecoveryPath $spatialRecoveryPath -OutEvidencePath $spatialAssembledEvidence | Out-Null
        $devicePairReceipt = Join-Path $deviceDir "media-lifecycle-pair-receipt.json"
        Push-Location $repo
        try {
          $validateOutput = @(& cargo run --quiet -p rusty-quest-broker-client --bin validate_media_lifecycle_evidence -- $nativePackage $nativeAssembledEvidence $spatialPackage $spatialAssembledEvidence 2>&1)
          if($LASTEXITCODE -ne 0){throw "NET-016 assembled lifecycle evidence validation failed on ${device}: $($validateOutput -join ' ')"}
          Write-Text $devicePairReceipt ($validateOutput -join "`n")
          $runtimePairReceipts += [pscustomobject][ordered]@{serial=$device;path=$devicePairReceipt;sha256="sha256:$(Sha256 $devicePairReceipt)"}
        } finally {
          Pop-Location
        }
      }
    }
    if(-not $preRecoveryChecksDone){
      foreach($expect in @(
        "clientId=client.quest.native-renderer[^`n]*featureLockId=lock.broker-client.native-renderer.v1[^`n]*markerNamespace=RUSTY_QUEST_NATIVE_BROKER_CLIENT[^`n]*contractFamilies=peer-session,media-session",
        "clientId=client.quest.spatial-camera-panel[^`n]*featureLockId=lock.broker-client.spatial-camera-panel.v1[^`n]*markerNamespace=RUSTY_QUEST_SPATIAL_BROKER_CLIENT[^`n]*contractFamilies=peer-session,media-session")){
        if($log-notmatch$expect){$issues+="client_marker_or_lock_mismatch"}
      }
      if($log-match"clientId=client.quest.native-renderer[^`n]*RUSTY_QUEST_SPATIAL_BROKER_CLIENT"-or$log-match"clientId=client.quest.spatial-camera-panel[^`n]*RUSTY_QUEST_NATIVE_BROKER_CLIENT"){$issues+="marker_bleed"}
      $nativeDump=(Adb $device @("shell","dumpsys","package",$native))-join"`n";$spatialDump=(Adb $device @("shell","dumpsys","package",$spatial))-join"`n"
      Write-Text (Join-Path $deviceDir "native-package.txt") $nativeDump;Write-Text (Join-Path $deviceDir "spatial-package.txt") $spatialDump
      $nativeUid=[regex]::Match($nativeDump,'(?m)^\s*appId=(\d+)').Groups[1].Value;$spatialUid=[regex]::Match($spatialDump,'(?m)^\s*appId=(\d+)').Groups[1].Value
      if([string]::IsNullOrWhiteSpace($nativeUid)-or[string]::IsNullOrWhiteSpace($spatialUid)-or$nativeUid-eq$spatialUid){$issues+="client_uid_not_distinct"}
      $fatalCounts=Get-FatalCounts -Log $log
      if($fatalCounts.package-ne0){$issues+="package_fatal_present"};if($fatalCounts.system-ne0){$issues+="system_fatal_present"}
    }
  } finally {
    foreach($pkg in @($native,$spatial,$broker)){Adb $device @("shell","am","force-stop",$pkg)-AllowFailure|Out-Null;Adb $device @("uninstall",$pkg)-AllowFailure|Out-Null}
    $listed=(Adb $device @("shell","pm","list","packages"))-join"`n";$cleanup=@(@($native,$spatial,$broker)|Where-Object{$listed-match[regex]::Escape($_)}).Count-eq0
    if(-not$cleanup){$issues+="cleanup_packages_remain"}
  }
  $rows += [pscustomobject][ordered]@{serial=$device;status=if($issues.Count-eq0){"pass"}else{"fail"};native_client="accepted";spatial_client="accepted";distinct_uid=$issues-notcontains"client_uid_not_distinct";shared_contract_parity=$issues-notcontains"client_marker_or_lock_mismatch";marker_bleed_absent=$issues-notcontains"marker_bleed";package_fatal_count=@($issues|Where-Object{$_-eq"package_fatal_present"}).Count;system_fatal_count=@($issues|Where-Object{$_-eq"system_fatal_present"}).Count;cleanup_complete=$cleanup;partial_lifecycle_artifacts=@($partialLifecycleArtifacts);media_lifecycle_pair_receipt=$devicePairReceipt;issues=@($issues);evidence_dir=$deviceDir}
}
$failedRows=@($rows|Where-Object{$_.status-ne"pass"})
$hasRuntimePairReceipts = @($runtimePairReceipts).Count -eq @($Serial).Count
$lifecycleEvidenceMode = if($hasFullLifecycleEvidence){"prevalidated_full"}elseif($hasRuntimePairReceipts){"assembled_from_app_artifacts_and_explicit_recovery"}elseif($CollectLifecycleArtifactsFromApps){"collected_partial_not_promotional"}else{"missing"}
$summary=[ordered]@{schema="rusty.quest.multi_app_broker_two_quest_evidence.v1";status=if($failedRows.Count-eq0 -and ($hasFullLifecycleEvidence -or $hasRuntimePairReceipts)){"pass"}else{"fail"};coordination_mode="user_authorized_serial_scoped";device_count=2;client_count=2;client_lifecycle_artifact_collection_enabled=[bool]$CollectLifecycleArtifactsFromApps;lifecycle_evidence_mode=$lifecycleEvidenceMode;does_not_prove_without_full_lifecycle_evidence=@("app death","provider restart","old-epoch rejection","package cleanup","bounded fatal absence","NET-016 media lifecycle promotion");shared_contracts=@("rusty.manifold.peer.session_descriptor.v1","rusty.manifold.media.session_descriptor.v1");media_lifecycle_pair_receipt=$pairReceipt;media_lifecycle_pair_receipts=@($runtimePairReceipts);qcl100_generic_fold=[ordered]@{status="not_promoted";reason="fresh QCL100 artifact path/hash/epoch/bytes/frames/render evidence is required before generic multi-app media promotion";artifact_path=$null;artifact_sha256=$null;epoch=$null;bytes=$null;frames=$null;render_evidence=$null};rows=$rows}
$summaryPath=Join-Path $EvidenceDir "summary.json";Write-Text $summaryPath ($summary|ConvertTo-Json -Depth 12);Write-Output $summaryPath;if($summary.status-ne"pass"){exit 1}
