[CmdletBinding()]
param(
    [string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$Out,
    [string]$Rustc='rustc'
)
$ErrorActionPreference='Stop'
if ($PSVersionTable.PSEdition -cne 'Core' -or $PSVersionTable.PSVersion -lt [version]'7.6') {throw 'PowerShell 7.6+ required'}
$RepoRoot=[IO.Path]::GetFullPath($RepoRoot)
if (Test-Path -LiteralPath $Out) {throw 'Fresh host output directory required'}
[void](New-Item -ItemType Directory -Path $Out)
$Out=[IO.Path]::GetFullPath($Out)
$app=Join-Path $RepoRoot 'apps/native-renderer-android'
$source=Join-Path $app 'src/diagnosticMetaInput/java'
$javac=Join-Path $JavaHome 'bin/javac.exe'
$java=Join-Path $JavaHome 'bin/java.exe'
function Invoke-HostCheck([string]$Name,[string]$Executable,[string[]]$Arguments) {
    & $Executable @Arguments *> (Join-Path $Out ($Name+'.log'))
    if ($LASTEXITCODE -ne 0) {throw "$Name failed; retained original output in $Out"}
}
# Execute the actual builder's pure diagnostic ownership guard, never its build body.
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1'),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Builder parser failed'}
$guard=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-NativeMetaInputDiagnosticComposition'},$false))
if($guard.Count -ne 1){throw 'Expected exact production diagnostic composition guard'}
. ([scriptblock]::Create($guard[0].Extent.Text))
$ids=@('quest.native.openxr_vulkan_base','renderer.background.native_passthrough','input.controllers_and_hands_optional','renderer.diagnostic.meta_input_surface','renderer.diagnostic.presentation','renderer.diagnostic.buffered_stereo_full3','renderer.diagnostic.held_output_review')
$activities=@('android.app.NativeActivity','NativeHeldReviewActivity')
$services=@('NativeMetaInputSurfaceService')
Assert-NativeMetaInputDiagnosticComposition -SelectedFeatureIds $ids -Activities $activities -Services $services
foreach($damage in @('experiment','showcase','activity','service','provider','receiver')) {
    $case=@{SelectedFeatureIds=$ids;Activities=$activities;Services=$services;Providers=@();Receivers=@()}
    switch($damage){
      'experiment'{$case.SelectedFeatureIds+=@('experiment.session')}
      'showcase'{$case.SelectedFeatureIds+=@('ui.visual_showcase')}
      'activity'{$case.Activities+=@('ControlPanelActivity')}
      'service'{$case.Services+=@('OtherService')}
      'provider'{$case.Providers=@('DebugProvider')}
      'receiver'{$case.Receivers=@('Receiver')}
    }
    $rejected=$false;try{Assert-NativeMetaInputDiagnosticComposition @case}catch{$rejected=$true}
    if(-not $rejected){throw "Diagnostic composition admitted $damage"}
}
'PASS production diagnostic composition and alternate-entry rejection' | Set-Content -LiteralPath (Join-Path $Out 'diagnostic-composition.log')
foreach ($module in @('held_review_policy','fullview_passthrough_diagnostic','full_core_graph','sampled_input_adapter','meta_input_diagnostic_state','paired_witness_policy','presentation_geometry')) {
    $executable=Join-Path $Out ($module+'.exe')
    Invoke-HostCheck ($module+'-compile') $Rustc @('--edition=2021','--test',(Join-Path $app ('native/src/'+$module+'.rs')),'-o',$executable)
    Invoke-HostCheck $module $executable @()
}
$javaOut=Join-Path $Out 'java'
$sources=@(Get-ChildItem -LiteralPath $source -Recurse -Filter '*.java' | ForEach-Object FullName)
$tests=@('NativeMetaInputContractHostTest','NativeHeldReviewGateHostTest','NativeHeldReviewPixelsHostTest')
foreach ($test in $tests) {$sources+=Join-Path $app ('tests/'+$test+'.java')}
Invoke-HostCheck 'java-compile' $javac (@('-encoding','UTF-8','-cp',$AndroidJar,'-d',$javaOut)+$sources)
$cp=$javaOut+[IO.Path]::PathSeparator+$AndroidJar
foreach ($test in $tests) {Invoke-HostCheck $test $java @('-cp',$cp,('io.github.mesmerprism.rustyquest.'+$test))}
Write-Output 'PASS held-review and explicit selection policies, source geometry/control, cached Binder gate, Java cleanup/auth, pixels and legacy v2 contract; no device actions'
