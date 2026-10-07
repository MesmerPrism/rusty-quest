param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$OutDir,
    [Parameter(Mandatory)][string]$CarrierCapsule,
    [string]$BuildToolsDir,
    [string]$Keystore,
    [string]$KeyAlias = 'androiddebugkey',
    [string]$StorePasswordEnvironment = 'RUSTY_QUEST_HUB_BRIDGE_STORE_PASS',
    [string]$KeyPasswordEnvironment = 'RUSTY_QUEST_HUB_BRIDGE_KEY_PASS',
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$app=Join-Path $repoRoot 'apps/connection-hub-ble-bridge-android'
$carrier=Join-Path $CarrierCapsule 'quest-ble-control.jar'
$carrierReceipt=Join-Path $CarrierCapsule 'artifact.json'
$receipt=Get-Content -LiteralPath $carrierReceipt -Raw | ConvertFrom-Json
if($receipt.schema -cne 'rusty.quest.ble_carrier.artifact.v1' -or $receipt.artifact -cne 'quest-ble-control.jar' -or (Get-FileHash -LiteralPath $carrier -Algorithm SHA256).Hash.ToLowerInvariant() -cne $receipt.sha256){throw 'Exact carrier artifact receipt required.'}
if(Test-Path -LiteralPath $OutDir){throw 'Output must be create-new.'}
function RunTool([string]$File,[string[]]$Arguments){& $File @Arguments;if($LASTEXITCODE-ne 0){throw "Owned tool failed: $([IO.Path]::GetFileName($File)) exit $LASTEXITCODE"}}
$javac=Join-Path $JavaHome 'bin/javac.exe';$jar=Join-Path $JavaHome 'bin/jar.exe'
$tools=@($AndroidJar,$javac,$jar)
if(-not $CompileOnly){
    if([string]::IsNullOrEmpty($BuildToolsDir)-or[string]::IsNullOrEmpty($Keystore)){throw 'Exact build tools and existing matching-Hub-signature keystore are required.'}
    foreach($name in @($StorePasswordEnvironment,$KeyPasswordEnvironment)){if($name-notmatch '^[A-Z][A-Z0-9_]{2,79}$'-or[string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name,'Process'))){throw 'Explicit signing environment prerequisite absent.'}}
    $tools+=@($Keystore,(Join-Path $BuildToolsDir 'd8.bat'),(Join-Path $BuildToolsDir 'aapt2.exe'),(Join-Path $BuildToolsDir 'zipalign.exe'),(Join-Path $BuildToolsDir 'apksigner.bat'))
}
foreach($file in $tools){if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw "Missing exact tool/input: $file"}}
$sources=@(Get-ChildItem -LiteralPath (Join-Path $app 'src/main/java') -Recurse -Filter *.java|ForEach-Object FullName)
$sources+=@('Rfc6455Codec.java','DeadlineInputStream.java'|ForEach-Object {Join-Path $repoRoot "crates/rusty-quest-broker-transport/android/io/github/mesmerprism/rustyquest/broker_transport/$_"})
$manifest=Join-Path $app 'AndroidManifest.xml'
$bound=@($PSCommandPath,$manifest,$carrier,$carrierReceipt)+$sources+$tools
$pins=@($bound|Sort-Object -Unique|ForEach-Object {[ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant();size_bytes=(Get-Item -LiteralPath $_).Length}})
New-Item -ItemType Directory -Path $OutDir -ErrorAction Stop|Out-Null
$classes=Join-Path $OutDir 'classes';New-Item -ItemType Directory -Path $classes|Out-Null
$arguments=Join-Path $OutDir 'sources.args';$sources|ForEach-Object {'"'+$_.Replace('\','/')+'"'}|Set-Content -LiteralPath $arguments -Encoding utf8
RunTool $javac @('-encoding','UTF-8','-source','1.8','-target','1.8','-bootclasspath',$AndroidJar,'-classpath',$carrier,'-d',$classes,"@$arguments")
$apk=$null;$signerSha=$null
if(-not $CompileOnly){
    $classesJar=Join-Path $OutDir 'classes.jar';RunTool $jar @('cf',$classesJar,'-C',$classes,'.')
    $dex=Join-Path $OutDir 'dex';New-Item -ItemType Directory -Path $dex|Out-Null
    RunTool (Join-Path $BuildToolsDir 'd8.bat') @('--min-api','29','--lib',$AndroidJar,'--output',$dex,$classesJar,$carrier)
    $unsigned=Join-Path $OutDir 'unsigned.apk';RunTool (Join-Path $BuildToolsDir 'aapt2.exe') @('link','-o',$unsigned,'--manifest',$manifest,'-I',$AndroidJar,'--min-sdk-version','29','--target-sdk-version','34','--version-code','1','--version-name','0.1.0')
    RunTool $jar @('uf',$unsigned,'-C',$dex,'classes.dex')
    $aligned=Join-Path $OutDir 'aligned.apk';RunTool (Join-Path $BuildToolsDir 'zipalign.exe') @('4',$unsigned,$aligned)
    $apk=Join-Path $OutDir 'rusty-quest-hub-ble-bridge.apk'
    RunTool (Join-Path $BuildToolsDir 'apksigner.bat') @('sign','--ks',$Keystore,'--ks-key-alias',$KeyAlias,'--ks-pass',"env:$StorePasswordEnvironment",'--key-pass',"env:$KeyPasswordEnvironment",'--out',$apk,$aligned)
    $certificateOutput=Join-Path $OutDir 'signer-verification.txt'
    & (Join-Path $BuildToolsDir 'apksigner.bat') verify --verbose --print-certs $apk *> $certificateOutput
    if($LASTEXITCODE-ne 0){throw 'Owned APK signer verification failed.'}
    $certificateMatches=[regex]::Matches([IO.File]::ReadAllText($certificateOutput),'(?m)^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$')
    if($certificateMatches.Count-ne 1){throw 'Exactly one observed APK signing certificate required.'}
    $signerSha=$certificateMatches[0].Groups[1].Value.ToLowerInvariant()
}
foreach($pin in $pins){if((Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash.ToLowerInvariant()-cne$pin.sha256){throw 'Source/tool changed during owned operation.'}}
$receipt=[ordered]@{schema='rusty.quest.hub_ble_bridge_build.v1';status=if($CompileOnly){'source_compiled'}else{'apk_built'};compile_only=[bool]$CompileOnly;authority_admission=$false;physical_device_proof=$false;source_tools=$pins;apk_path=$apk;apk_sha256=if($apk){(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()}else{$null};apk_signer_sha256=$signerSha;default_activation='inert_launcher';hub_authority='existing_native_grants_and_listener_required';carrier='bluetooth_gatt_to_fixed_loopback_websocket';confidentiality='not_proven';production_eligible=$false}
$receipt|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $OutDir 'build-receipt.json') -Encoding utf8
