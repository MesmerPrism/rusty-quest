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
foreach ($deadlineCall in @('ResponseHeadersRead,$deadline.Token', 'ReadAsStreamAsync($deadline.Token)', 'Copy-GradleDownloadBody $inputStream $out ([int64]$Identity.max_archive_bytes) $deadline.Token', 'ReadAsync($buffer,0,$buffer.Length,$CancellationToken)')) {
    if (-not $resolver.Contains($deadlineCall)) { throw 'HTTP headers and async body reads must share the download deadline.' }
}
foreach ($needle in @('VerifyCache', 'Get-ArchiveTree', 'Test-InstalledTree', 'Assert-NoHardLink', 'Get-NormalArchivePath', 'Get-ResolverMutexName', 'HttpClientHandler', 'AllowAutoRedirect=$false', 'SslProtocols', 'Threading.Mutex', 'Write-AtomicJson', 'tool_download_record.v2', 'gradle_cache_receipt.v2', 'tree_sha256', 'SelfTest')) {
    if (-not $resolver.Contains($needle)) { throw "Gradle resolver is missing required safety control: $needle" }
}
foreach ($forbidden in @('WebClient', 'Expand-Archive', 'SetEnvironmentVariable', 'ManifestPath', 'TestFixtureArchivePath', 'TestSimulateProviderFailure')) {
    if ($resolver.Contains($forbidden)) { throw "Gradle resolver contains forbidden broad acquisition or environment mutation: $forbidden" }
}

$build = Get-Content -LiteralPath $buildPath -Raw
if (-not $build.Contains('Resolve-GradleTool.ps1') -or -not $build.Contains('-Mode VerifyCache') -or -not $build.Contains('gradle_cache_receipt.v2') -or $build.Contains('function Resolve-Gradle') -or $build.Contains('services.gradle.org') -or $build.Contains('WebClient')) { throw 'Spatial Camera Panel build must consume only the shared verify-only Gradle resolver.' }

$testPath = Join-Path $root 'tools\Test-SpatialCameraPanelAndroid.ps1'
$test = Get-Content -LiteralPath $testPath -Raw
if (-not $test.Contains('-Mode VerifyCache') -or -not $test.Contains('gradle_cache_receipt.v2')) { throw 'Spatial Camera Panel test must consume only the shared verify-only Gradle resolver.' }

foreach ($wrapperText in @($build,$test)) {
    if (-not $wrapperText.Contains('[ValidateSet("9.4.1")][string]$GradleVersion = "9.4.1"')) {
        throw 'Existing GradleVersion argument must remain compatible while rejecting unpinned versions.'
    }
}
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
        $sharedArgument=if($text.Contains('$SharedGradleToolRoot')){' -SharedGradleToolRoot $SharedRoot'}else{''}
        [IO.File]::WriteAllText($harness,"param([string]`$RepoRoot,[string]`$SharedRoot)`n`$ErrorActionPreference='Stop'`n$text`n`$value=Get-VerifiedPinnedGradleBat -RepoRoot `$RepoRoot$sharedArgument`nif(-not (Test-Path -LiteralPath `$value -PathType Leaf)){throw 'wrapper did not return gradle.bat'}`n`$value")
        # Wrapper protocol fixtures are inert. Real archive/tree verification is exercised by resolver -SelfTest.
        $protocolRoot=Join-Path $temp 'protocol'
        foreach($relative in @('tools','config','local-artifacts/tools/gradle-9.4.1/bin')){[void][IO.Directory]::CreateDirectory((Join-Path $protocolRoot $relative))}
        Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $protocolRoot 'config/gradle-9.4.1-tool.json')
        [IO.File]::WriteAllText((Join-Path $protocolRoot 'local-artifacts/tools/gradle-9.4.1/bin/gradle.bat'),'@echo inert-wrapper-fixture')
        $protocolResolver=@'
param([string]$RepoRoot,[string]$Mode,[int]$TimeoutSeconds,[string]$SharedGradleToolRoot)
$fixtureHome=if($SharedGradleToolRoot){Join-Path $SharedGradleToolRoot 'tools/gradle-9.4.1'}else{Join-Path $RepoRoot 'local-artifacts/tools/gradle-9.4.1'}
$r=[ordered]@{schema='rusty.quest.gradle_cache_receipt.v2';mode='VerifyCache';tool_id='gradle';version='9.4.1';archive_sha256='2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb';tree_sha256='5c94e8204be25e0c18c94780cf4cf768fefa92e73f8c7b1617c483f33ca088db';gradle_home=$fixtureHome;gradle_bat=(Join-Path $fixtureHome 'bin/gradle.bat')}
if($SharedGradleToolRoot){$r.shared_tool_root=$SharedGradleToolRoot;$r.tool_distribution=@{schema='rusty.quest.gradle_tool_distribution.v1';storage_kind='explicit-shared';tool_id=$r.tool_id;version=$r.version;archive_sha256=$r.archive_sha256;tree_sha256=$r.tree_sha256;resolver_sha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant();config_sha256=(Get-FileHash -LiteralPath (Join-Path $RepoRoot 'config/gradle-9.4.1-tool.json')).Hash.ToLowerInvariant();marker_sha256=(Get-FileHash -LiteralPath (Join-Path $SharedGradleToolRoot 'tool-identity.json')).Hash.ToLowerInvariant();paths_recorded=$false}}
$r|ConvertTo-Json -Depth 5
'@
        [IO.File]::WriteAllText((Join-Path $protocolRoot 'tools/Resolve-GradleTool.ps1'),$protocolResolver)
        $valid=@(& pwsh -NoProfile -ExecutionPolicy Bypass -File $harness -RepoRoot $protocolRoot)
        if($LASTEXITCODE -ne 0 -or $valid.Count -ne 1 -or -not (Test-Path -LiteralPath $valid[0] -PathType Leaf)){throw "$Label fresh-pwsh verifier did not accept the valid cache."}
        if($sharedArgument){
            $sharedRoot=Join-Path $temp 'shared';[void][IO.Directory]::CreateDirectory((Join-Path $sharedRoot 'tools/gradle-9.4.1/bin'))
            [IO.File]::WriteAllText((Join-Path $sharedRoot 'tools/gradle-9.4.1/bin/gradle.bat'),'@echo inert-shared-wrapper-fixture')
            [IO.File]::WriteAllText((Join-Path $sharedRoot 'tool-identity.json'),'inert-wrapper-marker')
            $valid=@(& pwsh -NoProfile -File $harness -RepoRoot $protocolRoot -SharedRoot $sharedRoot)
            if($LASTEXITCODE -ne 0 -or $valid.Count -ne 1 -or $valid[0] -cne (Join-Path $sharedRoot 'tools/gradle-9.4.1/bin/gradle.bat')){throw "$Label did not consume its explicitly selected shared tool root."}
            [IO.File]::WriteAllText((Join-Path $protocolRoot 'tools/Resolve-GradleTool.ps1'),$protocolResolver.Replace("storage_kind='explicit-shared'","storage_kind='ambient'"))
            $null=& pwsh -NoProfile -File $harness -RepoRoot $protocolRoot -SharedRoot $sharedRoot 2>$null
            if($LASTEXITCODE -eq 0){throw "$Label accepted a changed shared identity receipt."}
        }
        $badRoot=Join-Path $temp 'bad';New-Item -ItemType Directory -Path (Join-Path $badRoot 'tools') -Force|Out-Null;[IO.File]::WriteAllText((Join-Path $badRoot 'tools\Resolve-GradleTool.ps1'),'throw ''intentional verifier failure''')
        $null=& pwsh -NoProfile -ExecutionPolicy Bypass -File $harness -RepoRoot $badRoot 2>$null
        if($LASTEXITCODE -eq 0){throw "$Label fresh-pwsh verifier accepted a throwing resolver."}
        if(@(Get-ChildItem -LiteralPath $badRoot -Force -Recurse).Count -ne 2){throw "$Label verifier failure created build-side artifacts."}
        $unverifiedRoot=Join-Path $temp 'unverified'
        foreach($relative in @('tools','config','local-artifacts/downloads','local-artifacts/tools/gradle-9.4.1/bin')) {
            [void][IO.Directory]::CreateDirectory((Join-Path $unverifiedRoot $relative))
        }
        Copy-Item -LiteralPath $resolverPath -Destination (Join-Path $unverifiedRoot 'tools/Resolve-GradleTool.ps1')
        Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $unverifiedRoot 'config/gradle-9.4.1-tool.json')
        [IO.File]::WriteAllText((Join-Path $unverifiedRoot 'local-artifacts/tools/gradle-9.4.1/bin/gradle.bat'),'@echo untrusted')
        $before=@(Get-ChildItem -LiteralPath $unverifiedRoot -Recurse -File).Count
        $null=& pwsh -NoProfile -ExecutionPolicy Bypass -File $harness -RepoRoot $unverifiedRoot 2>$null
        if($LASTEXITCODE -eq 0 -or @(Get-ChildItem -LiteralPath $unverifiedRoot -Recurse -File).Count -ne $before) {
            throw "$Label accepted or repaired an executable-only unverified cache."
        }
        $null=& pwsh -NoProfile -ExecutionPolicy Bypass -File $WrapperPath -GradleVersion 9.4.0 2>$null
        if($LASTEXITCODE -eq 0){throw "$Label accepted an unpinned GradleVersion argument."}
    } finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Recurse -Force}}
}
Test-FreshWrapperVerifier $buildPath 'Build wrapper'
Test-FreshWrapperVerifier $testPath 'Test wrapper'

function Test-ExternalArtifactOutput {
    $definition=Get-FunctionText $buildPath 'New-ExternalArtifactDirectory'
    . ([scriptblock]::Create($definition))
    $temp=Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-artifact-output-'+[Guid]::NewGuid().ToString('N'))
    function Assert-Rejected {param([scriptblock]$Action,[string]$Label) $failed=$false;try{& $Action|Out-Null}catch{$failed=$true};if(-not $failed){throw "External artifact fixture accepted $Label"}}
    try{
        foreach($part in @('attempt','source','cache','shared')){[void][IO.Directory]::CreateDirectory((Join-Path $temp $part))}
        $parent=Join-Path $temp 'attempt';$excluded=@('source','cache','shared'|ForEach-Object{Join-Path $temp $_})
        foreach($mode in @('DevFast','Candidate')){
            $output=Join-Path $parent ("public-build-$mode")
            [void](New-ExternalArtifactDirectory -Parent $parent -Output $output -ExcludedRoots $excluded -Mode $mode)
            if(Test-Path -LiteralPath $output){throw 'External artifact validation created output early.'}
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output $output -ExcludedRoots $excluded -Mode $mode -Replace -Create} "$mode replacement"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output $parent -ExcludedRoots $excluded -Mode $mode} "$mode parent equality"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output (Join-Path $temp 'escape') -ExcludedRoots $excluded -Mode $mode} "$mode outside output"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output (Join-Path $parent 'CON.txt') -ExcludedRoots $excluded -Mode $mode} "$mode reserved output name"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output (Join-Path $parent 'build:stream') -ExcludedRoots $excluded -Mode $mode} "$mode output alternate stream"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output 'relative-build' -ExcludedRoots $excluded -Mode $mode} "$mode relative output"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent ([IO.Path]::GetPathRoot($parent)) -Output $output -ExcludedRoots $excluded -Mode $mode} "$mode filesystem root parent"
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $temp -Output $output -ExcludedRoots $excluded -Mode $mode} "$mode ancestor overlap"
            foreach($path in $excluded){Assert-Rejected {New-ExternalArtifactDirectory -Parent $path -Output (Join-Path $path 'build') -ExcludedRoots $excluded -Mode $mode} "$mode source/cache/tool overlap"}
            $link=Join-Path $parent 'linked';New-Item -ItemType Junction -Path $link -Target (Join-Path $temp 'cache')|Out-Null
            try{Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output (Join-Path $link 'build') -ExcludedRoots $excluded -Mode $mode} "$mode linked ancestor"}finally{Remove-Item -LiteralPath $link -Force}
            [void](New-ExternalArtifactDirectory -Parent $parent -Output $output -ExcludedRoots $excluded -Mode $mode -Create)
            if(-not(Test-Path -LiteralPath $output -PathType Container)){throw "External artifact create-new failed in $mode mode."}
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output $output -ExcludedRoots $excluded -Mode $mode -Create} "$mode existing output"
            Remove-Item -LiteralPath $output
            [IO.File]::WriteAllText($output,'collision')
            Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output $output -ExcludedRoots $excluded -Mode $mode -Create} "$mode file collision"
        }
        Assert-Rejected {New-ExternalArtifactDirectory -Parent $parent -Output (Join-Path $parent 'unsupported') -ExcludedRoots $excluded -Mode Unsupported} 'unsupported mode'
    }finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Recurse -Force}}
}
Test-ExternalArtifactOutput

function Test-HostCargoTargetInheritance {
    # The private caller scopes host Cargo to its external h lane. Exercise the
    # actual digest entrypoint with an inert command rather than compiling it.
    $nativeBuild=Join-Path $root 'tools/Build-NativeRendererAndroid.ps1'
    . ([scriptblock]::Create((Get-FunctionText $nativeBuild 'Get-RuntimeConfigDigest')))
    $priorTarget=$env:CARGO_TARGET_DIR
    $priorPath=$env:PATH
    $fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-inert-host-'+[Guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($fixtureRoot)
    $hostTarget=Join-Path $fixtureRoot 'h';$observation=Join-Path $fixtureRoot 'observed.txt'
    $commandBody="@echo off`r`n> `"$observation`" echo %CARGO_TARGET_DIR%`r`n>> `"$observation`" echo %*`r`necho $('a'*64)`r`n"
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'cargo.cmd'),$commandBody)
    try{
        $env:CARGO_TARGET_DIR=$hostTarget
        $env:PATH=$fixtureRoot+[IO.Path]::PathSeparator+$priorPath
        $digest=Get-RuntimeConfigDigest -RepoRoot $root -RuntimeConfigPath 'inert-runtime-config.json'
        $observed=@(Get-Content -LiteralPath $observation)
        if($digest -cne ('a'*64) -or $observed.Count -ne 2 -or $observed[0] -cne $hostTarget -or
            $observed[1] -cne 'run --quiet -p rusty-quest-broker-authority --bin runtime_config_digest -- inert-runtime-config.json' -or
            (Test-Path -LiteralPath $hostTarget)){throw 'Host runtime digest did not retain the caller-scoped external Cargo target/argv.'}
    }finally{
        $env:PATH=$priorPath
        if($null -eq $priorTarget){Remove-Item Env:\CARGO_TARGET_DIR -ErrorAction SilentlyContinue}else{$env:CARGO_TARGET_DIR=$priorTarget}
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
Test-HostCargoTargetInheritance

$docs = Get-Content -LiteralPath $docsPath -Raw
foreach ($needle in @('Resolve-GradleTool.ps1', 'VerifyCache', 'local-artifacts/downloads', 'local-artifacts/tools/gradle-9.4.1', 'does not alter `PATH`', '-SelfTest', 'trusted-local-process')) {
    if (-not $docs.Contains($needle)) { throw "Gradle resolver documentation is missing: $needle" }
}

Write-Host 'Gradle tool resolver static validation passed'
