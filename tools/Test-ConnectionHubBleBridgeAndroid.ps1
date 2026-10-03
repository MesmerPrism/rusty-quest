param([Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$JsonJar,[Parameter(Mandatory)][string]$JsonJarSha256,[Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutDir){throw 'Evidence must be create-new.'}
if($JsonJarSha256-notmatch'^[a-f0-9]{64}$'-or(Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash.ToLowerInvariant()-cne$JsonJarSha256){throw 'Exact JSON runtime pin required.'}
$root=Split-Path -Parent $PSScriptRoot
New-Item -ItemType Directory -Path $OutDir -ErrorAction Stop|Out-Null
& (Join-Path $PSScriptRoot 'Build-ConnectionHubBleBridgeAndroid.ps1') -AndroidJar $AndroidJar -JavaHome $JavaHome -OutDir (Join-Path $OutDir 'android-compile') -CompileOnly
$sources=@('HubBleFrames.java','HubLoopbackClient.java','HubGattBridge.java','HubReadiness.java'|ForEach-Object {Join-Path $root "apps/connection-hub-ble-bridge-android/src/main/java/io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/$_"})
$sources+=@(Get-ChildItem (Join-Path $root 'apps/connection-hub-ble-bridge-android/tests/java') -Recurse -Filter *.java|ForEach-Object FullName)
$sources+=@('Rfc6455Codec.java','DeadlineInputStream.java','BoundedWebSocketSession.java'|ForEach-Object {Join-Path $root "crates/rusty-quest-broker-transport/android/io/github/mesmerprism/rustyquest/broker_transport/$_"})
$hub=Join-Path $root 'apps/manifold-broker-android/src/main/java'
$sources+=@('ConnectionHubHttpServer','ConnectionHubRuntime','ConnectionHubProtocol','ConnectionHubAuthorityPort','ConnectionHubStateStore','HubSurfaceRegistry','HubSurfaceDescriptor','HubProviderIdentity'|ForEach-Object {Join-Path $hub "io/github/mesmerprism/rustymanifold/broker/$_.java"})
$classes=Join-Path $OutDir 'host-classes';New-Item -ItemType Directory -Path $classes|Out-Null
$arguments=Join-Path $OutDir 'host-sources.args';$sources|ForEach-Object {'"'+$_.Replace('\','/')+'"'}|Set-Content -LiteralPath $arguments -Encoding utf8
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
$servicePath=Join-Path $root 'apps/connection-hub-ble-bridge-android/src/main/java/io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/BridgeService.java'
$hostInputs=@($PSCommandPath,$servicePath,$JsonJar,$AndroidJar,$javac,$java)+$sources+@(Get-ChildItem -LiteralPath $hub -Recurse -Filter *.java|ForEach-Object FullName)
$hostPins=@($hostInputs|Sort-Object -Unique|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant();size_bytes=(Get-Item -LiteralPath $_).Length}})
& $javac -encoding UTF-8 -source 17 -target 17 -cp "$JsonJar;$AndroidJar" -sourcepath $hub -d $classes "@$arguments" *> (Join-Path $OutDir 'host-compile.log')
if($LASTEXITCODE-ne 0){throw 'Actual host source compile failed.'}
$cases=@();foreach($test in @('HubBleFramesTest','HubLoopbackClientTest')){
    $log=Join-Path $OutDir "$test.log";& $java -cp "$classes;$JsonJar;$AndroidJar" "io.github.mesmerprism.rustyquest.connection_hub_ble_bridge.$test" *> $log
    if($LASTEXITCODE-ne 0){throw "Production host check failed: $test"}
    $match=[regex]::Match([IO.File]::ReadAllText($log),'PASS (\d+) production cases');if(-not $match.Success){throw 'No actual focused completion.'}
    $cases+=@{test=$test;cases=[int]$match.Groups[1].Value;log_path=$log;log_sha256=(Get-FileHash $log -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$servicePath=Join-Path $root 'apps/connection-hub-ble-bridge-android/src/main/java/io/github/mesmerprism/rustyquest/connection_hub_ble_bridge/BridgeService.java'
$service=[IO.File]::ReadAllText($servicePath)
if($service-notmatch'handler\.postDelayed\(expiry,Math\.max\(0,deadline-SystemClock\.elapsedRealtime\(\)\)\);bridge\.start\(\);'-or$service-notmatch'catch\(Exception denied\)\{stopCarrier\(\);stopSelf\(\);\}'-or$service-notmatch'handler\.removeCallbacks\(expiry\)'){throw 'Absolute expiry must be scheduled before potentially blocking startup and cancelled on failure/destruction.'}
foreach($pin in $hostPins){if((Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash.ToLowerInvariant()-cne$pin.sha256-or(Get-Item -LiteralPath $pin.path).Length-ne$pin.size_bytes){throw 'Host source/tool changed during focused check.'}}
@{schema='local.quest.hub_ble_bridge_host_check.v1';status='passed';physical_device_proof=$false;native_authority_modeled=$true;source_tools=$hostPins;service_expiry_static_order_check=$true;android_service_runtime_tested=$false;checks=$cases}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $OutDir 'result.json') -Encoding utf8
