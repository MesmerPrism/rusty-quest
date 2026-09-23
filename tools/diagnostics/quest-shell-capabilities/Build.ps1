[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$AndroidHome,
  [Parameter(Mandatory)][string]$JavaHome,
  [Parameter(Mandatory)][string]$BuildLeaseId
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [version]'7.6'){throw 'PowerShell 7.6 required'}
$source=$PSScriptRoot
$repo=(Resolve-Path (Join-Path $source '../../..')).Path
$platform=Join-Path $AndroidHome 'platforms/android-35/android.jar'
$bt=Join-Path $AndroidHome 'build-tools/35.0.0'
$aapt=Join-Path $bt 'aapt2.exe';$d8=Join-Path $bt 'd8.bat';$align=Join-Path $bt 'zipalign.exe';$signer=Join-Path $bt 'apksigner.bat'
$javac=Join-Path $JavaHome 'bin/javac.exe';$jar=Join-Path $JavaHome 'bin/jar.exe';$keytool=Join-Path $JavaHome 'bin/keytool.exe'
foreach($p in @($platform,$aapt,$d8,$align,$signer,$javac,$jar,$keytool)){if(-not(Test-Path -LiteralPath $p)){throw "missing $p"}}
$inputs=@(Get-ChildItem -LiteralPath $source -Recurse -File|Where-Object{$_.FullName -notlike '*\target\*'}|Sort-Object FullName|ForEach-Object{[ordered]@{name=$_.FullName.Substring($source.Length+1).Replace('\','/');sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
$identity=[ordered]@{schema='rusty.quest.shell_capabilities_inputs.v1';package='io.github.mesmerprism.rustyquest.shellcapabilities';min_sdk=29;target_sdk=34;sources=$inputs}
$compact=$identity|ConvertTo-Json -Depth 10 -Compress
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($compact))).ToLowerInvariant()
$out=Join-Path $repo "target/quest-shell-capabilities/$hash"
if(Test-Path -LiteralPath $out){throw "immutable output exists $out"}
New-Item -ItemType Directory -Path $out,(Join-Path $out classes),(Join-Path $out dex),(Join-Path $out stage)|Out-Null
[IO.File]::WriteAllText((Join-Path $out 'inputs.json'),($identity|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
function Run([string]$exe,[string[]]$arguments,[string]$log){$v=& $exe @arguments 2>&1;$code=$LASTEXITCODE;$v|Set-Content -LiteralPath (Join-Path $out $log);if($code-ne0){$v|Write-Output;throw "$log failed $code"}}
$java=@(Get-ChildItem -LiteralPath (Join-Path $source src) -Recurse -Filter *.java|ForEach-Object FullName)
Run $javac (@('-encoding','UTF-8','-source','8','-target','8','-cp',$platform,'-d',(Join-Path $out classes))+$java) 'javac.txt'
Run (Join-Path $JavaHome 'bin/java.exe') @('-cp',((Join-Path $out classes)+[IO.Path]::PathSeparator+$platform),'io.github.mesmerprism.rustyquest.shellcapabilities.BleProtocolTest') 'ble-protocol-test.txt'
Run $jar @('cf',(Join-Path $out classes.jar),'-C',(Join-Path $out classes),'.') 'jar.txt'
Run $d8 @('--min-api','29','--lib',$platform,'--output',(Join-Path $out dex),(Join-Path $out classes.jar)) 'd8.txt'
Run $aapt @('link','-o',(Join-Path $out base.apk),'--debug-mode','--manifest',(Join-Path $source AndroidManifest.xml),'-I',$platform,'--min-sdk-version','29','--target-sdk-version','34','--version-code','1','--version-name','1.0') 'aapt.txt'
Copy-Item (Join-Path $out dex/classes.dex) (Join-Path $out stage/classes.dex)
$unaligned=Join-Path $out unaligned.apk;Copy-Item (Join-Path $out base.apk) $unaligned
Push-Location (Join-Path $out stage);try{Run $jar @('uf',$unaligned,'classes.dex') 'apk-add.txt'}finally{Pop-Location}
Run $align @('-f','4',$unaligned,(Join-Path $out aligned.apk)) 'zipalign.txt'
$key=Join-Path $repo target/quest-shell-capabilities/debug.keystore
if(-not(Test-Path $key)){New-Item -ItemType Directory -Force (Split-Path $key)|Out-Null;Run $keytool @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Rusty Quest Shell Capabilities,O=Rusty Quest,C=US') 'keytool.txt'}
$apk=Join-Path $out quest-shell-capabilities.apk
Run $signer @('sign','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,(Join-Path $out aligned.apk)) 'sign.txt'
Run $signer @('verify','--verbose','--print-certs',$apk) 'verify.txt';Run $aapt @('dump','badging',$apk) 'badging.txt';Run $aapt @('dump','permissions',$apk) 'permissions.txt'
$verify=Get-Content (Join-Path $out verify.txt)-Raw;if($verify-notmatch 'certificate SHA-256 digest:\s*([0-9a-fA-F]{64})'){throw 'signer missing'}
$result=[ordered]@{schema='rusty.quest.shell_capabilities_build.v1';status='built_device_unverified';input_sha256=$hash;build_lease_id=$BuildLeaseId;package='io.github.mesmerprism.rustyquest.shellcapabilities';apk=$apk;apk_sha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant();apk_bytes=(Get-Item $apk).Length;signer_sha256=$Matches[1].ToLowerInvariant();shell_main='io.github.mesmerprism.rustyquest.shellcapabilities.LeaseShellRole';wifi_shell_main='io.github.mesmerprism.rustyquest.shellcapabilities.WifiShellRole';guardian_main='io.github.mesmerprism.rustyquest.shellcapabilities.WifiRestoreGuardian';result_file='files/capability-<run_token>.json';ble_result_file='files/ble-<run_token>.json';log_tag='RQShellCaps';ble_service_uuid='b11c0001-7a2b-4c3d-9e0f-112233445566';ble_rx_uuid='b11c0002-7a2b-4c3d-9e0f-112233445566';ble_tx_uuid='b11c0003-7a2b-4c3d-9e0f-112233445566';ble_frame='20 bytes: version/op/seq-be16 plus HMAC-SHA256(token,direction,header) first 16';probes=@('app_to_shell_tcp_loopback','app_to_shell_abstract_uds','shell_to_app_tcp_loopback','shell_to_app_abstract_uds','binder_handoff','binder_lease','binder_pipe_fd_4096','binder_pipe_fd_4194304','binder_death','authenticated_ble_noop','ble_wifi_off_resume');omitted=@('udp','ble_notifications')}
[IO.File]::WriteAllText((Join-Path $out RESULT.json),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false));$result|ConvertTo-Json -Depth 8
