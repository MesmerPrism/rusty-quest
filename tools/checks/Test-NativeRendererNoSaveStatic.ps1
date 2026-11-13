param([string]$RepoRoot='')
$ErrorActionPreference='Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot=Join-Path $PSScriptRoot '../..' }
$RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
$src=Join-Path $RepoRoot 'apps/native-renderer-android/native/src'
$meta=Get-Content -Raw -LiteralPath (Join-Path $src 'meta_input_diagnostic.rs')
$presentation=Get-Content -Raw -LiteralPath (Join-Path $src 'presentation_diagnostic.rs')
$feedback=Get-Content -Raw -LiteralPath (Join-Path $src 'display_composite_feedback.rs')
$state=Get-Content -Raw -LiteralPath (Join-Path $src 'meta_input_diagnostic_state.rs')
$xr=Get-Content -Raw -LiteralPath (Join-Path $src 'xr_vulkan.rs')
$options=Get-Content -Raw -LiteralPath (Join-Path $src 'native_renderer_display_composite_options.rs')
function Require([bool]$value,[string]$message) { if (-not $value) { throw $message } }
Require ($presentation.Contains('pub(crate) save_witnesses: bool') -and $presentation.Contains('#[serde(default = "save_witnesses_default")]')) 'Closed boolean/default missing'
Require ($presentation.Contains('saveWitnesses={} noPixelWitnesses={}')) 'Effective no-save marker missing'
Require ($meta.Contains('witness_timing_if_enabled(crate::presentation_diagnostic::witnesses_enabled(), || {')) 'Central actual timing must use startup policy before cadence'
Require ($feedback.Contains('meta_input_diagnostic::witness_timing(generation, sequence)') -and $feedback.Contains('meta_input_diagnostic::witness_timing(')) 'Both renderer scheduling routes must keep central timing'
$rawGate=$feedback.IndexOf('meta_input_diagnostic::witness_timing(generation, sequence)')
$rawSchedule=$feedback.IndexOf('self.meta_witness_sequences.push(sequence)',$rawGate)
Require ($rawGate -ge 0 -and $rawSchedule -gt $rawGate) 'Raw witness scheduling must remain behind timing admission'
$eyeStart=$feedback.IndexOf('unsafe fn record_presentation_witnesses(')
$eyeGate=$feedback.IndexOf('meta_input_diagnostic::witness_timing(',$eyeStart)
$eyeAllocation=$feedback.IndexOf('// Do not destroy resources on resize',$eyeStart)
Require ($eyeStart -ge 0 -and $eyeGate -gt $eyeStart -and $eyeAllocation -gt $eyeGate) 'Processed-eye readback allocation must stay after timing gate'
foreach ($name in @('write_full_core3_paired_raw','write_witness')) {
 $start=$meta.IndexOf(('pub(crate) fn '+$name+'('));$gate=$meta.IndexOf('require_witness_saving(crate::presentation_diagnostic::witnesses_enabled())?;',$start)
 $directory=$meta.IndexOf('let dir = WITNESS_DIR',$start)
 Require ($start -ge 0 -and $gate -gt $start -and $directory -gt $gate) ('Defensive raw writer guard missing: '+$name)
}
$start=$presentation.IndexOf('pub(crate) fn write_witness(')
Require ($presentation.IndexOf('require_witness_saving(witnesses_enabled())?;',$start) -lt $presentation.IndexOf('let dir = DIRECTORY',$start)) 'Defensive presentation writer guard missing'
Require ($meta.Contains('pub(crate) fn completed(value: Completion)') -and $meta.Contains('i64::from(prior.is_some())')) 'Actual completed status must remain independent of witness mask'
Require ($feedback.Contains('crate::meta_input_diagnostic::completed(completion);')) 'Actual fence retirement completion path missing'
Require ($xr.Contains('mode: NativeDisplayCompositeMode::GpuFeedbackDiagnostic') -and $options.Contains('NativeDisplayCompositeMode::GpuReadbackDiagnostic') -and $options.Contains('| NativeDisplayCompositeMode::GpuRecursiveFeedbackDiagnostic')) 'Selected external route/export separation changed'
'PASS no-save config, scheduling/writer joins, effective marker and unchanged completion route (source only)'
