param([string]$RepoRoot = (Join-Path $PSScriptRoot "..\.."))

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepoRoot).Path
$manifestPath = Join-Path $root 'config\gradle-9.4.1-tool.json'
$resolverPath = Join-Path $root 'tools\Resolve-GradleTool.ps1'
$buildPath = Join-Path $root 'tools\Build-SpatialCameraPanelAndroid.ps1'
$docsPath = Join-Path $root 'docs\GRADLE_TOOL_RESOLUTION.md'
foreach ($path in @($manifestPath, $resolverPath, $buildPath, $docsPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing Gradle resolver surface: $path" }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -cne 'rusty.quest.tool_identity.v1' -or $manifest.tool_id -cne 'gradle' -or $manifest.version -cne '9.4.1') { throw 'Gradle identity schema or version drifted.' }
if ($manifest.distribution_url -cne 'https://services.gradle.org/distributions/gradle-9.4.1-bin.zip' -or $manifest.official_checksum_url -cne 'https://services.gradle.org/distributions/gradle-9.4.1-bin.zip.sha256') { throw 'Gradle identity URLs drifted.' }
if ($manifest.sha256 -cne '2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb' -or $manifest.tree_sha256 -cne '5c94e8204be25e0c18c94780cf4cf768fefa92e73f8c7b1617c483f33ca088db') { throw 'Gradle identity SHA-256 or tree digest drifted.' }
if ($manifest.expected_top_level_directory -cne 'gradle-9.4.1' -or @($manifest.required_relative_paths) -notcontains 'bin/gradle.bat' -or @($manifest.required_relative_paths) -notcontains 'lib/gradle-launcher-9.4.1.jar' -or $manifest.max_path_chars -ne 512 -or $manifest.max_path_depth -ne 20) { throw 'Gradle identity layout/path bounds drifted.' }
if (@($manifest.allowed_redirect_hosts) -notcontains 'services.gradle.org' -or @($manifest.allowed_redirect_hosts) -notcontains 'downloads.gradle.org') { throw 'Gradle identity redirect policy drifted.' }

$resolver = Get-Content -LiteralPath $resolverPath -Raw
foreach ($needle in @('VerifyCache', 'Get-ArchiveTree', 'Test-InstalledTree', 'Assert-NoHardLink', 'Get-NormalArchivePath', 'Get-ResolverMutexName', 'HttpClientHandler', 'AllowAutoRedirect=$false', 'SslProtocols', 'Threading.Mutex', 'Write-AtomicJson', 'tool_download_record.v2', 'gradle_cache_receipt.v2', 'tree_sha256', 'SelfTest')) {
    if (-not $resolver.Contains($needle)) { throw "Gradle resolver is missing required safety control: $needle" }
}
foreach ($forbidden in @('WebClient', 'Expand-Archive', 'SetEnvironmentVariable', 'ManifestPath', 'TestFixtureArchivePath', 'TestSimulateProviderFailure')) {
    if ($resolver.Contains($forbidden)) { throw "Gradle resolver contains forbidden broad acquisition or environment mutation: $forbidden" }
}

$build = Get-Content -LiteralPath $buildPath -Raw
if (-not $build.Contains('Resolve-GradleTool.ps1') -or -not $build.Contains('-Mode VerifyCache') -or -not $build.Contains('gradle_cache_receipt.v2') -or $build.Contains('function Resolve-Gradle') -or $build.Contains('services.gradle.org') -or $build.Contains('WebClient') -or $build.Contains('GradleVersion')) { throw 'Spatial Camera Panel build must consume only the shared verify-only Gradle resolver.' }

$testPath = Join-Path $root 'tools\Test-SpatialCameraPanelAndroid.ps1'
$test = Get-Content -LiteralPath $testPath -Raw
if (-not $test.Contains('-Mode VerifyCache') -or -not $test.Contains('gradle_cache_receipt.v2') -or $test.Contains('GradleVersion')) { throw 'Spatial Camera Panel test must consume only the shared verify-only Gradle resolver.' }

function Get-FunctionText {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Name)
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if($errors){throw "Cannot parse wrapper for verifier regression: $Path"}
    $function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $Name},$true)|Select-Object -First 1
    if($null -eq $function){throw "Missing wrapper verifier function: $Path"};$function.Extent.Text
}
function Test-FreshWrapperVerifier {
    param([Parameter(Mandatory)][string]$WrapperPath,[Parameter(Mandatory)][string]$Label)
    $text=Get-FunctionText $WrapperPath 'Get-VerifiedPinnedGradleBat'
    if($text.Contains('$LASTEXITCODE')){throw "$Label verifier must not use LASTEXITCODE after in-process script invocation."}
    $temp=Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-gradle-wrapper-'+[Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Directory -Path $temp -Force|Out-Null
        $harness=Join-Path $temp 'verify-wrapper.ps1'
        [IO.File]::WriteAllText($harness,"param([string]`$RepoRoot)`n`$ErrorActionPreference='Stop'`n$text`n`$value=Get-VerifiedPinnedGradleBat -RepoRoot `$RepoRoot`nif(-not (Test-Path -LiteralPath `$value -PathType Leaf)){throw 'wrapper did not return gradle.bat'}`n`$value")
        $valid=@(& pwsh -NoProfile -ExecutionPolicy Bypass -File $harness -RepoRoot $root)
        if($LASTEXITCODE -ne 0 -or $valid.Count -ne 1 -or -not (Test-Path -LiteralPath $valid[0] -PathType Leaf)){throw "$Label fresh-pwsh verifier did not accept the valid cache."}
        $badRoot=Join-Path $temp 'bad';New-Item -ItemType Directory -Path (Join-Path $badRoot 'tools') -Force|Out-Null;[IO.File]::WriteAllText((Join-Path $badRoot 'tools\Resolve-GradleTool.ps1'),'throw ''intentional verifier failure''')
        $null=& pwsh -NoProfile -ExecutionPolicy Bypass -File $harness -RepoRoot $badRoot 2>$null
        if($LASTEXITCODE -eq 0){throw "$Label fresh-pwsh verifier accepted a throwing resolver."}
        if(@(Get-ChildItem -LiteralPath $badRoot -Force -Recurse).Count -ne 2){throw "$Label verifier failure created build-side artifacts."}
    } finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Recurse -Force}}
}
Test-FreshWrapperVerifier $buildPath 'Build wrapper'
Test-FreshWrapperVerifier $testPath 'Test wrapper'

$aggregate = Get-Content -LiteralPath (Join-Path $root 'tools\check_all.ps1') -Raw
if (-not $aggregate.Contains('Gradle resolver deterministic self-test') -or -not $aggregate.Contains('Gradle resolver static gate')) { throw 'Aggregate validation does not execute the Gradle resolver gates.' }

$docs = Get-Content -LiteralPath $docsPath -Raw
foreach ($needle in @('Resolve-GradleTool.ps1', 'VerifyCache', 'local-artifacts/downloads', 'local-artifacts/tools/gradle-9.4.1', 'does not alter `PATH`', '-SelfTest', 'trusted-local-process')) {
    if (-not $docs.Contains($needle)) { throw "Gradle resolver documentation is missing: $needle" }
}

Write-Host 'Gradle tool resolver static validation passed'
