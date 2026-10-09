param([Parameter(Mandatory)][string]$AndroidJar)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$panel=Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$tests=Join-Path $repo 'apps/native-renderer-android/tests/java/io/github/mesmerprism/rustyquest/native_renderer'
$javac=(Get-Command javac -ErrorAction Stop).Source
$java=(Get-Command java -ErrorAction Stop).Source
$jar=(Resolve-Path -LiteralPath $AndroidJar -ErrorAction Stop).Path
$output=Join-Path ([IO.Path]::GetTempPath()) ('rq-ble-name-'+[guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($output)
$policy=Join-Path $panel 'ExperimentSessionBleNamePolicy.java'
$server=Join-Path $panel 'ExperimentSessionBleServer.java'
$protocol=Join-Path $panel 'ExperimentSessionBleProtocol.java'
& $javac --release 8 -encoding UTF-8 -d $output $policy (Join-Path $tests 'ExperimentSessionBleNamePolicyTest.java')
if($LASTEXITCODE){throw 'Name policy host compilation failed'}
& $java -cp $output io.github.mesmerprism.rustyquest.native_renderer.ExperimentSessionBleNamePolicyTest
if($LASTEXITCODE){throw 'Name policy controls failed'}
# Resolve through the actual app/feature closure used by the build, rather than
# manually supplying a helper that an exact feature declaration might omit.
$resolutionRoot=Join-Path $output 'resolution'
$resultPath=Join-Path $resolutionRoot 'result.json'
& (Join-Path $PSHOME ('pwsh'+$(if($IsWindows){'.exe'}else{''}))) -NoProfile -File (Join-Path $repo 'tools/Resolve-NativeAppBuild.ps1') -DryRun `
    -AppSpec (Join-Path $repo 'fixtures/native-app-builds/native-breath-four-way-conformance.app.json') `
    -FeatureDir (Join-Path $repo 'fixtures/native-app-features') `
    -OutputRoot $resolutionRoot -ResultJsonPath $resultPath | Out-Null
if($LASTEXITCODE){throw 'Actual app source resolver failed'}
$result=Get-Content -LiteralPath $resultPath -Raw|ConvertFrom-Json -Depth 100
$lock=Get-Content -LiteralPath $result.feature_lock_path -Raw|ConvertFrom-Json -Depth 100
$closure=$lock.panel_source_closure
if($closure.selected_module_id-cne'breath-composition-controls'-or$closure.runtime_widening_allowed){throw 'Exact breath panel closure required'}
$consumerSources=@()
foreach($name in @('ExperimentSessionBleNamePolicy.java','ExperimentSessionBleProtocol.java','ExperimentSessionBleServer.java')){
    $records=@($closure.source_files|Where-Object{[IO.Path]::GetFileName($_.path)-ceq$name})
    if($records.Count-ne1-or$records[0].module_id-cne'breath-composition-controls'){throw "Production closure omits or duplicates BLE source: $name"}
    $file=Join-Path $repo $records[0].path
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()-cne$records[0].sha256){throw 'Resolved BLE source hash drift'}
    $consumerSources+=$file
}
# Compile only the BLE consumer subset selected and hashed by production resolution.
& $javac --release 8 -encoding UTF-8 -cp $jar -d $output @consumerSources
if($LASTEXITCODE){throw 'Actual BLE consumer Android compilation failed'}
$source=Get-Content -LiteralPath $server -Raw
if(-not$source.Contains('new AdvertiseData.Builder().setIncludeDeviceName(false)')-or
   -not$source.Contains('.addServiceUuid(new ParcelUuid(SERVICE)).build()')-or
   -not$source.Contains('startAdvertising(settings, data, scanResponse, advertiseCallback)')-or
   $source.Contains('.setName(')){throw 'Primary service or global-name boundary changed'}
"Production panel closure resolved; three exact BLE source records compiled. Evidence: $resultPath"
'Actual BLE name consumer compilation and primary-service/no-global-rename boundaries passed.'
