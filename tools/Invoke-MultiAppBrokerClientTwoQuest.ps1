param(
    [Parameter(Mandatory=$true)][string[]]$Serial,
    [string]$BrokerApk = "",
    [string]$NativeApk = "",
    [string]$SpatialApk = "",
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
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) { $EvidenceDir = Join-Path "S:\Work\tmp" ("morphospace-net011-multi-app-broker-" + (Get-Date -Format "yyyyMMdd-HHmmss")) }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$broker = "io.github.mesmerprism.rustymanifold.broker"
$native = "io.github.mesmerprism.rustyquest.native_renderer"
$spatial = "io.github.mesmerprism.rustyquest.spatial_camera_panel"
$activity = "io.github.mesmerprism.rustyquest.broker_client.BrokerClientProbeActivity"

function Write-Text([string]$Path,[string]$Text){[IO.File]::WriteAllText($Path,$Text,$utf8NoBom)}
function Adb([string]$Device,[string[]]$AdbArgs,[switch]$AllowFailure){$o=& $Adb -s $Device @AdbArgs 2>&1;$c=$LASTEXITCODE;if($c-ne 0-and-not$AllowFailure){throw "adb -s $Device $($AdbArgs -join ' ') failed: $($o -join ' ')"};@($o)}
function Wait-Marker([string]$Device,[string]$ClientId,[int]$Seconds){$deadline=(Get-Date).AddSeconds($Seconds);do{$log=(Adb $Device @("logcat","-d","-s","RustyBrokerClient:V","RustyManifoldAdmission:V","AndroidRuntime:E","*:S"))-join"`n";if($log-match("RUSTY_QUEST_BROKER_CLIENT clientId="+[regex]::Escape($ClientId)+"[^`n]*status=accepted")){return $log};if($log-match"FATAL EXCEPTION"){throw "Client fatal on $Device`n$log"};Start-Sleep -Milliseconds 500}while((Get-Date)-lt$deadline);throw "Timed out waiting for $ClientId on $Device"}

$foldPath = Join-Path $EvidenceDir "qcl100-generic-media-receipt.json"
& cargo run --quiet -p rusty-quest-broker-client --bin fold_qcl100_media -- (Join-Path $repo "fixtures\broker-clients\qcl100-generic-media.pass.json") $foldPath
if($LASTEXITCODE-ne 0){throw "QCL100 generic evidence fold failed"}
$fold = Get-Content -Raw -LiteralPath $foldPath | ConvertFrom-Json
if($fold.status-ne"pass"-or$fold.media_session_contract-ne"rusty.manifold.media.session_descriptor.v1"){throw "Generic QCL100 fold drifted"}

$rows=@()
foreach($device in $Serial){
  $deviceDir=Join-Path $EvidenceDir $device;New-Item -ItemType Directory -Force -Path $deviceDir|Out-Null
  $issues=@();$cleanup=$false
  try{
    if(((Adb $device @("get-state"))-join"").Trim()-ne"device"){throw "Quest $device not ready"}
    foreach($pkg in @($native,$spatial,$broker)){Adb $device @("uninstall",$pkg)-AllowFailure|Out-Null}
    foreach($item in @(@($BrokerApk,"broker"),@($NativeApk,"native"),@($SpatialApk,"spatial"))){Adb $device @("install","-r",$item[0])|Out-Null}
    Adb $device @("logcat","-c")|Out-Null
    Adb $device @("shell","am","start","-n","$native/$activity","--el","expected_authority_revision","1")|Out-Null
    $nativeLog=Wait-Marker $device "client.quest.native-renderer" 20
    Adb $device @("shell","am","start","-n","$spatial/$activity","--el","expected_authority_revision","4")|Out-Null
    $spatialLog=Wait-Marker $device "client.quest.spatial-camera-panel" 20
    $log=(Adb $device @("logcat","-d"))-join"`n";Write-Text (Join-Path $deviceDir "logcat.txt") $log
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
    $packageFatals=([regex]::Matches($log,'FATAL EXCEPTION:[\s\S]{0,1200}(native_renderer|spatial_camera_panel|rustymanifold)')).Count
    $systemFatals=([regex]::Matches($log,'FATAL EXCEPTION IN SYSTEM PROCESS|Watchdog.*system_server|Fatal signal.*system_server')).Count
    if($packageFatals-ne0){$issues+="package_fatal_present"};if($systemFatals-ne0){$issues+="system_fatal_present"}
  } finally {
    foreach($pkg in @($native,$spatial,$broker)){Adb $device @("shell","am","force-stop",$pkg)-AllowFailure|Out-Null;Adb $device @("uninstall",$pkg)-AllowFailure|Out-Null}
    $listed=(Adb $device @("shell","pm","list","packages"))-join"`n";$cleanup=@(@($native,$spatial,$broker)|Where-Object{$listed-match[regex]::Escape($_)}).Count-eq0
    if(-not$cleanup){$issues+="cleanup_packages_remain"}
  }
  $rows += [pscustomobject][ordered]@{serial=$device;status=if($issues.Count-eq0){"pass"}else{"fail"};native_client="accepted";spatial_client="accepted";distinct_uid=$issues-notcontains"client_uid_not_distinct";shared_contract_parity=$issues-notcontains"client_marker_or_lock_mismatch";marker_bleed_absent=$issues-notcontains"marker_bleed";package_fatal_count=@($issues|Where-Object{$_-eq"package_fatal_present"}).Count;system_fatal_count=@($issues|Where-Object{$_-eq"system_fatal_present"}).Count;cleanup_complete=$cleanup;issues=@($issues);evidence_dir=$deviceDir}
}
$failedRows=@($rows|Where-Object{$_.status-ne"pass"})
$summary=[ordered]@{schema="rusty.quest.multi_app_broker_two_quest_evidence.v1";status=if($failedRows.Count-eq0){"pass"}else{"fail"};coordination_mode="user_authorized_serial_scoped";device_count=2;client_count=2;shared_contracts=@("rusty.manifold.peer.session_descriptor.v1","rusty.manifold.media.session_descriptor.v1");qcl100_generic_fold=$foldPath;rows=$rows}
$summaryPath=Join-Path $EvidenceDir "summary.json";Write-Text $summaryPath ($summary|ConvertTo-Json -Depth 12);Write-Output $summaryPath;if($summary.status-ne"pass"){exit 1}
