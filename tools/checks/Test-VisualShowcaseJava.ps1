param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$JsonJar,
    [Parameter(Mandatory)][string]$OutputRoot,
    [switch]$Vulkan,
    [string]$VisualSchema,
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $OutputRoot) { throw 'Use a fresh immutable Java check output root.' }
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$generated = Join-Path $OutputRoot 'generated'
$projected = Join-Path $OutputRoot 'projected'
$classes = Join-Path $OutputRoot 'classes'
New-Item -ItemType Directory -Path $generated,$classes | Out-Null
& python -B (Join-Path $RepoRoot 'tools/produce_showcase_java.py') --repo $RepoRoot --output $projected
if ($LASTEXITCODE -ne 0) { throw 'Showcase Java projection failed.' }
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererShowcaseFacade.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAppStatusBridge.psm1') -Force
[IO.File]::WriteAllText((Join-Path $generated 'ControlPanelActivity.java'),(New-NativeRendererShowcaseFacadeSource -Vulkan:$Vulkan))
[IO.File]::WriteAllText((Join-Path $generated 'NativeRendererAppStatusBridge.java'),(New-NativeRendererAppStatusBridgeSource))
$package = 'io/github/mesmerprism/rustyquest/native_renderer'
$descriptor = Get-Content -LiteralPath (Join-Path $RepoRoot 'fixtures/native-app-features/ui/visual-showcase/ui.visual_showcase.feature.json') -Raw | ConvertFrom-Json
$moduleSources = @($descriptor.panel_composition.modules[0].source_files | ForEach-Object { Join-Path $RepoRoot $_ })
if ($Vulkan) {
    $presentation = Get-Content -LiteralPath (Join-Path $RepoRoot 'fixtures/native-app-features/ui/native-mesh-panel-showcase/ui.native_mesh_panel.showcase.feature.json') -Raw | ConvertFrom-Json
    $moduleSources += @($presentation.panel_source_module.source_files | ForEach-Object { Join-Path $RepoRoot $_ })
}
$projection = Get-Content -LiteralPath (Join-Path $projected 'projection.json') -Raw | ConvertFrom-Json
foreach ($record in $projection.sources) { $canonical = Join-Path $RepoRoot $record.input; if (@($moduleSources | Where-Object { $_ -ceq $canonical }).Count -ne 1) { throw 'Unbound Java projection input.' }; $moduleSources = @($moduleSources | Where-Object { $_ -cne $canonical }) + @(Join-Path $projected $record.output) }
$sources = $moduleSources + @(Get-ChildItem -LiteralPath $generated -Filter *.java | ForEach-Object FullName) +
    @(Join-Path $RepoRoot "apps/native-renderer-android/src/main/java/$package/PanelModule.java") +
    @(Join-Path $RepoRoot "apps/native-renderer-android/src/main/java/$package/NativeAppSettingsReader.java")
$expectedCount = if ($Vulkan) { 23 } else { 16 }
if ($sources.Count -ne $expectedCount) { throw "Showcase exact Java source closure must have $expectedCount files." }
$sourceRecords = @()
foreach ($source in $sources) {
    $body = [IO.File]::ReadAllText($source)
    if ($body -match 'ExperimentSession|ConditionAudio|Questionnaire|RecordingExport|nativeStartParallelBreathCapture|nativeStopParallelBreathCapture|nativeReadParallelBreathCaptureStatus|StereoVideo|EmbeddedManifold') { throw "Forbidden source capability: $source" }
    $sourceRecords += [ordered]@{ name = [IO.Path]::GetFileName($source); sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$response = Join-Path $OutputRoot 'sources.rsp'
$sources | Set-Content -LiteralPath $response -Encoding ascii
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $AndroidJar -d $classes "@$response"
if ($LASTEXITCODE -ne 0) { throw 'Showcase exact Java compilation failed.' }
& (Join-Path $PSScriptRoot 'Test-ShowcaseSettingsReaderClosure.ps1') -RepoRoot $RepoRoot -ClassesDir $classes -JavaHome $JavaHome -Vulkan:$Vulkan
if ($Vulkan) { & (Join-Path $PSScriptRoot 'Test-ShowcaseVulkanJavaContract.ps1') -ClassesDir $classes -JavaHome $JavaHome }
$compiled = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter *.class | ForEach-Object Name)
if (@($compiled | Where-Object { $_ -match 'ExperimentSession|ConditionAudio|Questionnaire|Recording|StereoVideo|EmbeddedManifold|Kiosk' }).Count -gt 0) { throw 'Compiled Java closure contains excluded classes.' }
$hostClasses = Join-Path $OutputRoot 'host-classes'
New-Item -ItemType Directory -Path $hostClasses | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $JsonJar -d $hostClasses `
    (Join-Path $projected 'ShowcaseGatewayProtocol.java') (Join-Path $projected 'ShowcaseGuestRegistry.java') `
    (Join-Path $RepoRoot "apps/native-renderer-android/panel-modules/showcase/src/test/java/$package/ShowcaseGatewayProtocolTest.java")
if ($LASTEXITCODE -ne 0) { throw 'Showcase host protocol test compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$hostClasses$([IO.Path]::PathSeparator)$JsonJar" io.github.mesmerprism.rustyquest.native_renderer.ShowcaseGatewayProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'Showcase host protocol tests failed.' }
# Execute the production panel's pure payload adapter without constructing an
# Android Activity. This catches a correctly compiled but rejected UI command.
$panelTestClasspath = "$classes$([IO.Path]::PathSeparator)$JsonJar$([IO.Path]::PathSeparator)$AndroidJar"
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $panelTestClasspath -d $hostClasses `
    (Join-Path $RepoRoot "apps/native-renderer-android/panel-modules/showcase/src/test/java/$package/ShowcaseVisualPayloadTest.java")
if ($LASTEXITCODE -ne 0) { throw 'Showcase production visual payload test compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$hostClasses$([IO.Path]::PathSeparator)$panelTestClasspath" io.github.mesmerprism.rustyquest.native_renderer.ShowcaseVisualPayloadTest
if ($LASTEXITCODE -ne 0) { throw 'Showcase production visual payload test failed.' }
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $panelTestClasspath -d $hostClasses `
    (Join-Path $RepoRoot "apps/native-renderer-android/panel-modules/showcase/src/test/java/$package/ShowcasePanelKeyTest.java")
if ($LASTEXITCODE -ne 0) { throw 'Showcase production key counter test compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp "$hostClasses$([IO.Path]::PathSeparator)$panelTestClasspath" io.github.mesmerprism.rustyquest.native_renderer.ShowcasePanelKeyTest
if ($LASTEXITCODE -ne 0) { throw 'Showcase production key counter test failed.' }
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $panelTestClasspath -d $hostClasses `
    (Join-Path $RepoRoot "apps/native-renderer-android/panel-modules/showcase/src/test/java/$package/ShowcaseControlsModelTest.java")
if ($LASTEXITCODE -ne 0) { throw 'Showcase controls model tests compilation failed.' }
$modelArguments=@('-cp',"$hostClasses$([IO.Path]::PathSeparator)$panelTestClasspath",'io.github.mesmerprism.rustyquest.native_renderer.ShowcaseControlsModelTest')
if ($VisualSchema) { $modelArguments += $VisualSchema }
& (Join-Path $JavaHome 'bin/java.exe') @modelArguments
if ($LASTEXITCODE -ne 0) { throw 'Showcase controls model tests failed.' }
if ($Vulkan) {
    & (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -cp $panelTestClasspath -d $hostClasses `
        (Join-Path $RepoRoot "apps/native-renderer-android/panel-modules/showcase/src/test/java/$package/ShowcaseAttachmentAdmissionTest.java")
    if ($LASTEXITCODE -ne 0) { throw 'Showcase attachment admission test compilation failed.' }
    & (Join-Path $JavaHome 'bin/java.exe') -cp "$hostClasses$([IO.Path]::PathSeparator)$panelTestClasspath" io.github.mesmerprism.rustyquest.native_renderer.ShowcaseAttachmentAdmissionTest
    if ($LASTEXITCODE -ne 0) { throw 'Showcase attachment admission test failed.' }
}
$receipt = [ordered]@{
    schema = 'rusty.quest.visual_showcase.java_check.v1'
    source_count = $sources.Count
    sources = @($sourceRecords | Sort-Object name)
    java_compilation = 'passed'
    vulkan_selected = [bool]$Vulkan
    native_settings_reader_class_and_descriptor = 'passed'
    host_protocol_and_guest_tests = 'passed'
    host_visual_apply_payload_test = 'passed'
    host_triple_b_counter_test = 'passed'
    host_controls_model_test = 'passed'
    compiled_class_count = $compiled.Count
    compiled_classes = @($compiled | Sort-Object)
    toolchain = [ordered]@{
        javac_sha256 = (Get-FileHash -LiteralPath (Join-Path $JavaHome 'bin/javac.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
        android_jar_sha256 = (Get-FileHash -LiteralPath $AndroidJar -Algorithm SHA256).Hash.ToLowerInvariant()
        json_jar_sha256 = (Get-FileHash -LiteralPath $JsonJar -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    apk_assembly_invoked = $false
    device_validation = $false
}
$receipt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $OutputRoot 'RESULT.json') -Encoding utf8
Write-Output "PASS exact $expectedCount-source showcase Java compilation and excluded-class inventory. Result: $(Join-Path $OutputRoot 'RESULT.json')"
