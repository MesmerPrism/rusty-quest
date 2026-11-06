param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop';$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Resolve-NativeAppBuild.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Resolver AST invalid.'}
foreach($function in @($ast.EndBlock.Statements | Where-Object {$_ -is [Management.Automation.Language.FunctionDefinitionAst]})){. ([scriptblock]::Create($function.Extent.Text))}
$ids=@('ui.visual_showcase','ui.native_mesh_panel.showcase')
$activities=@(Get-SelectedNativeHostActivities -Activities @('android.app.NativeActivity','ControlPanelActivity') -SelectedFeatureIds $ids)
if($activities.Count -ne 1 -or $activities[0] -cne 'io.github.mesmerprism.rustyquest.native_renderer.ShowcaseNativeActivity'){throw 'Vulkan showcase did not select sole genuine host.'}
$legacy=@(Get-SelectedNativeHostActivities -Activities @('android.app.NativeActivity','ControlPanelActivity') -SelectedFeatureIds @('ui.visual_showcase'))
if(($legacy -join '|') -cne 'android.app.NativeActivity|ControlPanelActivity'){throw 'Default-off host mapping changed.'}
$features=@{}
foreach($path in @('fixtures/native-app-features/ui/visual-showcase/ui.visual_showcase.feature.json','fixtures/native-app-features/ui/native-mesh-panel-showcase/ui.native_mesh_panel.showcase.feature.json')){$descriptor=Get-Content -LiteralPath (Join-Path $RepoRoot $path) -Raw | ConvertFrom-Json;$features[$descriptor.feature_id]=@{descriptor=$descriptor}}
$closure=Resolve-NativePanelComposition -RepoRoot $RepoRoot -SelectedFeatureIds $ids -Features $features -Activities $activities
if($closure.source_files.Count -ne 19 -or $closure.modules.Count -ne 2){throw 'Exact retained form and neutral source module closure missing.'}
foreach($source in $closure.source_files){if($source.path -match 'ConditionAudio|Questionnaire|RecordingExport|ExperimentSessionRuntime'){throw 'Presentation imported excluded capability.'}}
$manifest=New-GeneratedAndroidManifestText -PackageName 'example.showcase' -ApplicationLabel 'Showcase' -Activities $activities -Permissions @() -UsesFeatures @() -Receivers @() -Services @() -Queries @() -SelectedFeatureIds $ids
$xml=[xml]$manifest
if($xml.SelectNodes('/manifest/application/activity').Count -ne 1 -or $xml.SelectNodes('/manifest/application/provider|/manifest/application/receiver|/manifest/application/service').Count -ne 0){throw 'Unexpected showcase manifest components.'}
if($manifest -notmatch 'showcase_panel_native_host' -or $manifest -match 'android:name="[^\"]*ControlPanelActivity"'){throw 'Manifest missed selected role or exposed old Activity.'}
$features['ui.native_mesh_panel.showcase'].descriptor.panel_source_module.source_files+=@('apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer/QuestionnairePanelActivity.java')
$rejected=$false;try{Resolve-NativePanelComposition -RepoRoot $RepoRoot -SelectedFeatureIds $ids -Features $features -Activities $activities|Out-Null}catch{$rejected=$true}
if(-not $rejected){throw 'Selected presentation admitted an additional source.'}
Write-Output 'PASS production resolver: default-off mapping, genuine sole host/no standalone component, exact source extension, extra-source rejection and closed role metadata. No compiler invoked.'
