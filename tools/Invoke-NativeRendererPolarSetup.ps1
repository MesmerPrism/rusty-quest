[CmdletBinding()]
param(
 [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
 [Parameter(Mandatory)][ValidatePattern('^io\.github\.mesmerprism\.rustyquest\.native_renderer\.[a-z0-9_.]+$')][string]$PackageName,
 [string]$RequestPath
)
$ErrorActionPreference='Stop'
$adb=(Get-Command adb -ErrorAction Stop).Source
$body=if($RequestPath){[IO.File]::ReadAllText((Resolve-Path -LiteralPath $RequestPath).Path)}else{'{"v":4,"op":"status"}'}
$request=$body|ConvertFrom-Json -AsHashtable
if($RequestPath){
 $fields=@('v','id','op','s','g','r','d','n');if($request.ContainsKey('mac')){$fields+='mac'}
 if($request.Count -ne $fields.Count -or @($fields|Where-Object{-not $request.ContainsKey($_)}).Count){throw 'Closed v4 setup fields required'}
 if($request.v -isnot [long] -and $request.v -isnot [int]){throw 'Integer version required'}
 if($request.v -ne 4 -or $request.op -notin @('scan','select','connect','start-acc','start-ecg')){throw 'Closed setup operation required'}
 foreach($field in @('id','n')){if($request[$field] -isnot [string] -or $request[$field] -cnotmatch '^[a-f0-9]{16}$'){throw "Invalid $field"}}
 if($request.s -isnot [string] -or $request.s -cnotmatch '^[a-f0-9]{32}$'){throw 'Invalid reference'}
 foreach($field in @('g','r')){if(($request[$field] -isnot [long] -and $request[$field] -isnot [int]) -or $request[$field] -lt 0 -or $request[$field] -gt 9007199254740991){throw 'Safe integer fence required'}}
 if($request.g -le 0 -or $request.d -isnot [string] -or ($request.op -eq 'select' -and $request.d -cnotmatch '^[a-f0-9]{12}$') -or ($request.op -ne 'select' -and $request.d -ne '')){throw 'Invalid selection/epoch'}
 if($request.ContainsKey('mac') -and ($request.mac -isnot [string] -or $request.mac -cnotmatch '^[a-f0-9]{32}$')){throw 'Invalid proof'}
}
$bytes=[Text.Encoding]::UTF8.GetBytes(($request|ConvertTo-Json -Compress -Depth 12))
if($bytes.Length -gt 240){throw 'Setup command exceeds carrier bound'}
$encoded=[Convert]::ToBase64String($bytes)
$component="$PackageName/io.github.mesmerprism.rustyquest.native_renderer.PolarSensorCommandReceiver"
# One command dispatch only. A later status invocation observes the original ID;
# it cannot replay a sensor operation or launch a headset Activity.
$output=& $adb -s $Serial shell am broadcast -n $component -a 'io.github.mesmerprism.rustyquest.native_renderer.action.POLAR_SENSOR_RUNTIME_COMMAND' --es polar_sensor_setup_request_b64 $encoded 2>&1
if($LASTEXITCODE -ne 0){throw "Setup broadcast failed: $output"}
$raw=(& $adb -s $Serial exec-out run-as $PackageName cat files/polar_sensor_setup_operator_status.json 2>$null|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or -not $raw){throw 'Setup outcome unavailable; observe status and do not replay'}
$result=$raw|ConvertFrom-Json -Depth 16
if($RequestPath -and $result.receipt.id -cne $request.id){throw 'Original setup receipt not yet visible; observe status and do not replay'}
$result|ConvertTo-Json -Depth 16
