#Requires -Version 7.6
[CmdletBinding()]
param([string]$OutputDirectory='')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
if(-not $OutputDirectory){$OutputDirectory=Join-Path $repo ('local-artifacts/operator-backend/'+[guid]::NewGuid().ToString('N'))}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Fresh output directory required.'}
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$fixture=Join-Path $OutputDirectory 'package'
& (Join-Path $PSScriptRoot 'Test-MetaXrInputAutomation.ps1') -OutputDirectory $fixture
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
$selection=@{Enable=$true;FeatureLockPath=(Join-Path $fixture 'selected.json');FeatureLockSha256=(Hash (Join-Path $fixture 'selected.json'));
    AndroidManifestPath=(Join-Path $fixture 'AndroidManifest.xml');PackageName='io.example.native';
    LibraryPath=(Join-Path $fixture 'layer.so');LibrarySha256=(Hash (Join-Path $fixture 'layer.so'));
    LayerManifestPath=(Join-Path $fixture 'layer.json');LayerManifestSha256=(Hash (Join-Path $fixture 'layer.json'))}
$selectionPath=Join-Path $OutputDirectory 'selection.json';$selection|ConvertTo-Json|Set-Content -LiteralPath $selectionPath
$apk=Join-Path $fixture 'valid-fixture.zip'
$dumpPath=Join-Path $OutputDirectory 'manifest-dump.json'
$dump=@{schema='rusty.quest.aapt2_manifest_dump.v1';apk_sha256=(Hash $apk);exit_code=0;
    lines=@('E: manifest (line=1)','  A: package="io.example.native" (Raw: "io.example.native")','  E: application (line=2)','    A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true')}
$dump|ConvertTo-Json|Set-Content -LiteralPath $dumpPath
# Inert preparation accepts a hash-pinned local file; this test never launches it.
$proxy=(Get-Command node -CommandType Application | Select-Object -First 1).Source
$registryPath=Join-Path $OutputDirectory 'registry.json';'{"result":{"tools":[]}}'|Set-Content -LiteralPath $registryPath
$output=Join-Path $OutputDirectory 'prepared.json'
$parameters=@{SelectionArgumentsPath=$selectionPath;ApkPath=$apk;ApkSha256=(Hash $apk);ManifestDumpPath=$dumpPath;
    ProxyPath=$proxy;ProxySha256=(Hash $proxy);RegistryPath=$registryPath;RegistrySha256=(Hash $registryPath);OutputPath=$output}
$prepared=& (Join-Path $PSScriptRoot 'Prepare-MetaXrInputCapsule.ps1') @parameters
if($prepared.runtime_bound -or $prepared.device_verified -or $prepared.selection.consumer -cne 'NativeOpenXr'){throw 'Inert source package preparation failed.'}
$checks=1
function Deny([scriptblock]$Action){$rejected=$false;try{&$Action|Out-Null}catch{$rejected=$true};if(-not $rejected){throw 'Expected rejection missing.'}}
Deny { & (Join-Path $PSScriptRoot 'Prepare-MetaXrInputCapsule.ps1') @parameters };$checks++
$bad=$parameters.Clone();$bad.OutputPath=Join-Path $OutputDirectory 'bad-hash.json';$bad.ApkSha256='0'*64
Deny { & (Join-Path $PSScriptRoot 'Prepare-MetaXrInputCapsule.ps1') @bad };if(Test-Path -LiteralPath $bad.OutputPath){throw 'Invalid preparation wrote output.'};$checks++
$bad=$parameters.Clone();$bad.OutputPath=Join-Path $OutputDirectory 'bad-dump.json';$dump.exit_code=1;$dump|ConvertTo-Json|Set-Content -LiteralPath $dumpPath
Deny { & (Join-Path $PSScriptRoot 'Prepare-MetaXrInputCapsule.ps1') @bad };if(Test-Path -LiteralPath $bad.OutputPath){throw 'Failed inspector wrote output.'};$checks++
$log=Join-Path $OutputDirectory 'node-tests.txt'
& node --test (Join-Path $PSScriptRoot 'tests/meta-xr-operator-backend.test.mjs') *> $log
if($LASTEXITCODE -ne 0){Get-Content -LiteralPath $log;throw 'Operator protocol conformance failed.'}
$result=@{schema='rusty.quest.meta_xr_input.backend_host_test.v1';preparation_checks=$checks;
    protocol_log_sha256=(Hash $log);node=(& node --version);proxy_spawned=$false;device_calls=0;apk_builds=0;
    note='Protocol tests launch an independent Node stdio peer. No vendor proxy, ports or headset.'}
$result|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json')
Write-Output "Meta XR backend: $checks preparation checks and Node protocol suite passed; inert source preparation only."
