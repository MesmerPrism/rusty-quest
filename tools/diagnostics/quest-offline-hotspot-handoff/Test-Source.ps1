[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion -lt [version]'7.6') { throw 'PowerShell 7.6 required' }

$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'QuestOfflineHotspotHandoff.java') -Raw
$checks = [ordered]@{
  uid_2000 = $source.Contains('if(Process.myUid()!=2000)')
  shell_attribution = $source.Contains('new AttributionSource.Builder(2000).setPackageName("com.android.shell")')
  correct_selection_status = $source.Contains('constant("NETWORK_SELECTION_ENABLED")')
  correct_selection_reason = $source.Contains('constant("DISABLED_NONE")')
  obsolete_label_absent = -not $source.Contains('NETWORK_SELECTION_ENABLE"')
  exact_config_path = $source.Contains('"/data/local/tmp/rqohh-"+run+".properties"')
  bounded_connect_wait = $source.Contains('CONNECT_WAIT_MS=20000L')
  bounded_probe = $source.Contains('remain>30000')
  fixed_temporary_add = $source.Contains('wifi.addNetwork(wanted)')
  fixed_original_restore = $source.Contains('wifi.enableNetwork(cfg.originalId,true)')
  owned_temporary_remove = $source.Contains('wifi.removeNetwork(b)')
  state_lock = $source.Contains('FileLock ignored=ch.lock()')
  typed_modes = @('snapshot','connect','restore','guard','watchdog-probe').Where({ -not $source.Contains('"' + $_ + '".equals(a[0])') }).Count -eq 0
}
foreach ($entry in $checks.GetEnumerator()) {
  if (-not $entry.Value) { throw "static check failed: $($entry.Key)" }
}
[ordered]@{ schema='rusty.quest.offline_hotspot_handoff_static.v1'; status='pass'; checks=$checks } | ConvertTo-Json -Depth 4
