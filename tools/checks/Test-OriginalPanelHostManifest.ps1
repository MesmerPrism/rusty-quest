param([Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $OutputRoot){throw 'Create-new output required.'}
New-Item -ItemType Directory -Path $OutputRoot|Out-Null
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$repo/tools/Resolve-NativeAppBuild.ps1",[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Resolver parse failed.'}
$definition=$ast.Find({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -ceq 'New-GeneratedAndroidManifestText'},$true)
if($null -eq $definition){throw 'Actual manifest function missing.'}
Invoke-Expression $definition.Extent.Text
$native='io.github.mesmerprism.rustyquest.native_renderer.NativeProductPanelActivity'
$stock='android.app.NativeActivity'
$ns='http://schemas.android.com/apk/res/android'
$checks=0
foreach($variant in @('product','original2d','neutral')){
    $selected=@('ui.native_mesh_panel')
    if($variant -ne 'neutral'){$selected=@('ui.breath_composition_control_panel')}
    if($variant -eq 'product'){$selected+=@('ui.native_mesh_panel')}
    $activities=@($(if($variant -eq 'product'){$native}else{$stock}),'ControlPanelActivity')
    $text=New-GeneratedAndroidManifestText -PackageName 'io.github.mesmerprism.rustyquest.test_panel' -ApplicationLabel 'Original panel fixture' -Activities $activities -SelectedFeatureIds $selected
    [IO.File]::WriteAllText("$OutputRoot/$variant.xml",$text)
    $xml=[xml]$text
    $names=@($xml.manifest.application.activity|ForEach-Object{$_.GetAttribute('name',$ns)})
    $expected=if($variant -eq 'product'){$native}else{$stock}
    if($names -cnotcontains $expected -or $names -ccontains $(if($variant -eq 'product'){$stock}else{$native})){throw 'Physical Activity selection mismatch.'};$checks++
    if($xml.manifest.application.GetAttribute('theme',$ns) -cne '@android:style/Theme.Material.NoActionBar'){throw 'Original application theme changed.'};$checks++
    if(@($xml.manifest.application.activity|Where-Object{$_.HasAttribute('theme',$ns)}).Count){throw 'Activity theme override changed original dialog/widget theme.'};$checks++
    if($xml.manifest.SelectNodes('uses-permission').Count){throw 'Presentation route added permissions.'};$checks++
    $meta=@($xml.manifest.application.'meta-data'|Where-Object{$_.GetAttribute('name',$ns) -ceq 'rusty.quest.native_renderer.original_panel_native_host'})
    if(($variant -eq 'product') -ne ($meta.Count -eq 1)){throw 'Selected physical native host metadata mismatch.'};$checks++
}
foreach($bad in @(@{features=@('ui.native_mesh_panel','ui.breath_composition_control_panel');activities=@($stock,'ControlPanelActivity')},@{features=@('ui.native_mesh_panel');activities=@($native,'ControlPanelActivity')})){
    $rejected=$false
    try{$null=New-GeneratedAndroidManifestText -PackageName 'io.github.mesmerprism.rustyquest.test_panel' -ApplicationLabel 'Original panel fixture' -Activities $bad.activities -SelectedFeatureIds $bad.features}catch{$rejected=$true}
    if(-not$rejected){throw 'Mismatched feature/activity closure admitted.'};$checks++
}
@{passed=$true;checks=$checks;actual_manifest_function=$true;device_effects=0;compilers=0}|ConvertTo-Json|Set-Content "$OutputRoot/RESULT.json"
Write-Output "Original panel manifest checks=$checks passed"
