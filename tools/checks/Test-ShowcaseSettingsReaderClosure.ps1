param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string]$ClassesDir,
    [string]$JavaHome,
    [switch]$Vulkan
)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Builder AST contains syntax errors.' }
$selection = @($ast.EndBlock.Statements | Where-Object {
    $_ -is [System.Management.Automation.Language.IfStatementAst] -and
    $_.Extent.Text.StartsWith('if ($showcaseSelected) {') -and
    $_.Extent.Text.Contains('Showcase base Java source closure is incomplete.')
})
if ($selection.Count -ne 1) { throw 'Expected one exact production showcase base Java selection.' }
$showcaseSelected = $true
$base = Join-Path $RepoRoot 'apps/native-renderer-android/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
$sourceFiles = @('PanelModule.java','NativeAppSettingsReader.java','QuestionnairePanelActivity.java') | ForEach-Object { Join-Path $base $_ }
. ([scriptblock]::Create($selection[0].Extent.Text))
$names = @($sourceFiles | ForEach-Object { [IO.Path]::GetFileName($_) } | Sort-Object)
if ([string]::Join('|',$names) -cne 'NativeAppSettingsReader.java|PanelModule.java') { throw "Production showcase base closure lost the native loader reader or admitted an unrelated source: $($names -join ',')" }
$sourceFiles = @(Join-Path $base 'PanelModule.java')
$missingRejected = $false
try { . ([scriptblock]::Create($selection[0].Extent.Text)) } catch { $missingRejected = $true }
if (-not $missingRejected) { throw 'Showcase source selection admitted a missing mandatory reader.' }
$showcaseSelected = $false
$sourceFiles = @('PanelModule.java','NativeAppSettingsReader.java','QuestionnairePanelActivity.java') | ForEach-Object { Join-Path $base $_ }
. ([scriptblock]::Create($selection[0].Extent.Text))
if ($sourceFiles.Count -ne 3) { throw 'Showcase selection changed an unselected product source closure.' }
$allowlist = @($ast.FindAll({param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$allowedShowcaseJava'}, $true))
if ($allowlist.Count -ne 1) { throw 'Expected exact production selected Java allowlist.' }
. ([scriptblock]::Create($allowlist[0].Extent.Text))
$showcaseVulkanSelected = [bool]$Vulkan
. ([scriptblock]::Create($allowlist[0].Extent.Text))
$expectedCount=if($Vulkan){23}else{16}
if ($allowedShowcaseJava.Count -ne $expectedCount -or $allowedShowcaseJava -cnotcontains 'NativeAppSettingsReader.java') { throw 'Selected Java allowlist omits the settings reader.' }
if (@($allowedShowcaseJava | Where-Object { $_ -match 'ExperimentSession|ConditionAudio|Questionnaire|Recording|Kiosk' }).Count -ne 0) { throw 'Showcase reader fix widened the capability allowlist.' }
if ($ClassesDir) {
    if (-not $JavaHome) { throw 'Compiled signature inspection requires JavaHome.' }
    $reader = Join-Path $ClassesDir 'io/github/mesmerprism/rustyquest/native_renderer/NativeAppSettingsReader.class'
    if (-not (Test-Path -LiteralPath $reader -PathType Leaf)) { throw 'Actual compiled showcase closure is missing NativeAppSettingsReader.class.' }
    $descriptor = (& (Join-Path $JavaHome 'bin/javap.exe') -classpath $ClassesDir -public -s io.github.mesmerprism.rustyquest.native_renderer.NativeAppSettingsReader) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Compiled settings reader javap inspection failed.' }
    if ($descriptor -notmatch 'public static java\.lang\.String readAsset\(android\.app\.Activity, java\.lang\.String\)[^\r\n]*\r?\n\s*descriptor: \(Landroid/app/Activity;Ljava/lang/String;\)Ljava/lang/String;') { throw 'Compiled native settings reader class/method descriptor does not match the native loader.' }
    Write-Output 'PASS actual compiled NativeAppSettingsReader.readAsset(Activity,String)->String signature.'
}
Write-Output "PASS production AST base selection and exact $expectedCount-source capability allowlist retain the native settings reader."
