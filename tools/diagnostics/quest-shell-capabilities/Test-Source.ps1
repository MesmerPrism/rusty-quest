[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion -lt [version]'7.6') { throw 'PowerShell 7.6 required' }

$source = $PSScriptRoot
$javaRoot = Join-Path $source 'src/io/github/mesmerprism/rustyquest/shellcapabilities'
$required = @(
  'AndroidManifest.xml', 'Build.ps1', 'Build-Shell.ps1', 'ble_wifi_client.py',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/BinderProtocol.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/BleProtocol.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/BleProtocolTest.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/CapabilityProvider.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/LeaseShellRole.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/WifiShellRole.java',
  'src/io/github/mesmerprism/rustyquest/shellcapabilities/WifiRestoreGuardian.java'
)
foreach ($relative in $required) {
  if (-not (Test-Path -LiteralPath (Join-Path $source $relative))) { throw "missing $relative" }
}

$manifest = Get-Content -LiteralPath (Join-Path $source 'AndroidManifest.xml') -Raw
$provider = Get-Content -LiteralPath (Join-Path $javaRoot 'CapabilityProvider.java') -Raw
$lease = Get-Content -LiteralPath (Join-Path $javaRoot 'LeaseShellRole.java') -Raw
$wifi = Get-Content -LiteralPath (Join-Path $javaRoot 'WifiShellRole.java') -Raw
$guardian = Get-Content -LiteralPath (Join-Path $javaRoot 'WifiRestoreGuardian.java') -Raw
$checks = [ordered]@{
  package = $manifest.Contains('package="io.github.mesmerprism.rustyquest.shellcapabilities"')
  provider_dump_permission = $manifest.Contains('android:permission="android.permission.DUMP"')
  provider_uid_2000 = $provider.Contains('if(caller!=2000)throw new SecurityException("shell_uid_required")')
  callback_uid_and_token = $lease.Contains('!token.equals(got)||caller!=appUid')
  exact_external_provider = $lease.Contains('getContentProviderExternal') -and $lease.Contains('removeContentProviderExternalAsUser')
  exact_shell_attribution = $lease.Contains('new AttributionSource.Builder(2000).setPackageName("com.android.shell")')
  fixed_pipe_sizes = $lease.Contains('BinderProtocol.SMALL') -and $lease.Contains('BinderProtocol.LARGE')
  fixed_wifi_commands = $wifi.Contains('runSet("disabled")') -and $wifi.Contains('runSet("enabled")') -and $wifi.Contains('runCmd("set-wifi-enabled",value)')
  bounded_wifi_ttl = $wifi.Contains('ttl<1000||ttl>120000')
  fixed_guardian = $guardian.Contains('!"30000".equals(a[1])') -and $guardian.Contains('"set-wifi-enabled","enabled"')
}
foreach ($entry in $checks.GetEnumerator()) { if (-not $entry.Value) { throw "static check failed: $($entry.Key)" } }

$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$temp = Join-Path ([IO.Path]::GetTempPath()) ('rqsc-static-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
  & $javac -encoding UTF-8 -source 8 -target 8 -d $temp (Join-Path $javaRoot 'BleProtocol.java') (Join-Path $javaRoot 'BleProtocolTest.java')
  if ($LASTEXITCODE -ne 0) { throw 'javac BLE protocol test failed' }
  $protocol = & $java -cp $temp io.github.mesmerprism.rustyquest.shellcapabilities.BleProtocolTest
  if ($LASTEXITCODE -ne 0 -or $protocol -notcontains 'ble_protocol_test=pass') { throw 'BLE protocol test failed' }
  python -m py_compile (Join-Path $source 'ble_wifi_client.py')
  if ($LASTEXITCODE -ne 0) { throw 'Python syntax check failed' }
} finally {
  Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath (Join-Path $source '__pycache__') -Recurse -Force -ErrorAction SilentlyContinue
}

[ordered]@{ schema='rusty.quest.shell_capabilities_static.v1'; status='pass'; checks=$checks } | ConvertTo-Json -Depth 4
