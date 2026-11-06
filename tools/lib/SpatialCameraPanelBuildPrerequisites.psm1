Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Wait-QuestSpatialOperation {
    [CmdletBinding(DefaultParameterSetName='Task')]
    param(
        [Parameter(Mandatory,ParameterSetName='Mutex')][Threading.Mutex]$Mutex,
        [Parameter(Mandatory,ParameterSetName='Task')][Threading.Tasks.Task]$Task,
        [ValidateSet('Bounded','Retained',IgnoreCase=$false)][string]$WaitPolicy='Bounded',
        [TimeSpan]$Estimate=([TimeSpan]::FromSeconds(55)),
        [Parameter(Mandatory,ParameterSetName='Mutex')][ref]$Abandoned,
        [string]$Label='Quest platform operation'
    )
    if($PSCmdlet.ParameterSetName-ceq'Mutex'){
        $Abandoned.Value=$false
        try{
            if($Mutex.WaitOne($Estimate)){return $true}
            if($WaitPolicy-ceq'Bounded'){return $false}
            try{Write-Warning "$Label remains pending past its estimate; retaining the same mutex wait." -WarningAction Continue}catch{}
            [void]$Mutex.WaitOne();return $true
        }catch [Threading.AbandonedMutexException]{$Abandoned.Value=$true;return $true}
    }
    if($WaitPolicy-ceq'Retained'){
        try{$completed=$Task.Wait($Estimate)}catch [AggregateException]{return $Task.GetAwaiter().GetResult()}
        if(-not$completed){try{Write-Warning "$Label remains pending past its estimate; retaining the same task." -WarningAction Continue}catch{}}
    }
    return $Task.GetAwaiter().GetResult()
}

function Get-VerifiedPinnedGradleBat {
    param([Parameter(Mandatory=$true)][string]$RepoRoot,[string]$SharedGradleToolRoot = "",[ref]$ToolDistribution,[ValidateSet("Bounded","Retained",IgnoreCase=$false)][string]$WaitPolicy="Bounded")
    $resolver = Join-Path $RepoRoot "tools\Resolve-GradleTool.ps1"
    if (-not (Test-Path -LiteralPath $resolver -PathType Leaf)) { throw "Pinned Gradle resolver is missing: $resolver" }
    try {
        $output = @(& $resolver -RepoRoot $RepoRoot -Mode VerifyCache -TimeoutSeconds 55 -SharedGradleToolRoot $SharedGradleToolRoot -WaitPolicy $WaitPolicy)
    } catch {
        throw "Pinned Gradle cache verification failed: $($_.Exception.Message)"
    }
    if ($output.Count -eq 0) { throw "Pinned Gradle cache verification returned no receipt." }
    try { $receipt = (($output -join "`n") | ConvertFrom-Json -ErrorAction Stop) } catch { throw "Pinned Gradle cache verification returned an invalid receipt: $($_.Exception.Message)" }
    $shared = -not [string]::IsNullOrWhiteSpace($SharedGradleToolRoot)
    $expectedHome = if ($shared) { [IO.Path]::GetFullPath((Join-Path $SharedGradleToolRoot "tools\gradle-9.4.1")).TrimEnd('\') } else { [IO.Path]::GetFullPath((Join-Path $RepoRoot "local-artifacts\tools\gradle-9.4.1")).TrimEnd('\') }
    $actualHome = [IO.Path]::GetFullPath([string]$receipt.gradle_home).TrimEnd('\')
    $actualBat = [IO.Path]::GetFullPath([string]$receipt.gradle_bat)
    if ($receipt.schema -cne "rusty.quest.gradle_cache_receipt.v2" -or $receipt.mode -cne "VerifyCache" -or
        $receipt.tool_id -cne "gradle" -or $receipt.version -cne "9.4.1" -or
        $receipt.archive_sha256 -cne "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb" -or
        $receipt.tree_sha256 -cne "5c94e8204be25e0c18c94780cf4cf768fefa92e73f8c7b1617c483f33ca088db" -or
        $actualHome -cne $expectedHome -or
        -not $actualBat.StartsWith($expectedHome + "\bin\", [StringComparison]::OrdinalIgnoreCase) -or
        $actualBat -cnotlike "*\bin\gradle.bat" -or
        -not (Test-Path -LiteralPath $actualBat -PathType Leaf)) {
        throw "Pinned Gradle cache receipt is invalid or escapes the explicitly selected tool root."
    }
    if ($shared) {
        $distribution = $receipt.tool_distribution
        if ($receipt.shared_tool_root -cne [IO.Path]::GetFullPath($SharedGradleToolRoot).TrimEnd('\') -or
            $distribution.schema -cne 'rusty.quest.gradle_tool_distribution.v1' -or $distribution.storage_kind -cne 'explicit-shared' -or
            $distribution.tool_id -cne $receipt.tool_id -or $distribution.version -cne $receipt.version -or
            $distribution.archive_sha256 -cne $receipt.archive_sha256 -or $distribution.tree_sha256 -cne $receipt.tree_sha256 -or
            $distribution.resolver_sha256 -cne (Get-FileHash -LiteralPath $resolver -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $distribution.config_sha256 -cne (Get-FileHash -LiteralPath (Join-Path $RepoRoot 'config\gradle-9.4.1-tool.json') -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $distribution.marker_sha256 -cne (Get-FileHash -LiteralPath (Join-Path $SharedGradleToolRoot 'tool-identity.json') -Algorithm SHA256).Hash.ToLowerInvariant() -or
            $distribution.paths_recorded -ne $false) { throw 'Pinned shared Gradle distribution receipt identity is invalid.' }
        if ($null -ne $ToolDistribution) { $ToolDistribution.Value = $distribution }
    }
    return $actualBat
}


function Assert-QuestSpatialPrerequisiteEvidencePath {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][AllowNull()][AllowEmptyString()][AllowEmptyCollection()][string[]]$ExcludedRoots)
    function Literal([string]$Value){
        if([string]::IsNullOrWhiteSpace($Value)-or$Value-cnotmatch'^[A-Za-z]:[\\/]'-or$Value.Substring(2).Contains(':')-or$Value-match'[*?]'){throw 'Prerequisite evidence geometry requires literal local drive paths.'}
        $full=[IO.Path]::GetFullPath($Value).TrimEnd([char[]]@('\','/'))
        if($full.Length-le3){throw 'A volume root is not scoped prerequisite evidence.'}
        $current=[IO.Path]::GetPathRoot($full)
        foreach($part in $full.Substring($current.Length).Split([char[]]@('\','/'),[StringSplitOptions]::RemoveEmptyEntries)){
            $current=Join-Path $current $part;$item=$null
            try{$item=Get-Item -LiteralPath $current -Force -ErrorAction Stop}catch{if($_.CategoryInfo.Category-ne[Management.Automation.ErrorCategory]::ObjectNotFound){throw}}
            if($null-ne$item-and($item.Attributes-band[IO.FileAttributes]::ReparsePoint)){throw 'Prerequisite evidence geometry contains a reparse point.'}
            if($null-ne$item-and-not$item.PSIsContainer-and$current-ine$full){throw 'Prerequisite evidence geometry has a file ancestor.'}
        }
        return $full
    }
    $selected=Literal $Path
    foreach($root in $ExcludedRoots|Where-Object{$_}){
        $other=Literal $root
        if($selected.Equals($other,[StringComparison]::OrdinalIgnoreCase)-or$selected.StartsWith($other+'\',[StringComparison]::OrdinalIgnoreCase)-or$other.StartsWith($selected+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Prerequisite evidence overlaps a declared cache, tool, source forest or output root.'}
    }
    if(Test-Path -LiteralPath $selected){throw 'Prerequisite evidence directory must be create-new.'}
    return $selected
}

function Resolve-QuestSpatialApplication {
    param([Parameter(Mandatory)][string]$Name,[string]$Path='', [switch]$Optional)
    if($Path){
        if(-not[IO.Path]::IsPathFullyQualified($Path)-or-not(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'Selected platform application path is invalid.'}
        $commands=@(Get-Command -Name ([IO.Path]::GetFullPath($Path)) -CommandType Application -ErrorAction Stop)
    }else{$commands=@(Get-Command -Name $Name -CommandType Application -All -ErrorAction SilentlyContinue)}
    $commands=@($commands|Group-Object { [IO.Path]::GetFullPath($_.Source).ToLowerInvariant() }|ForEach-Object{$_.Group[0]})
    if($commands.Count-eq0-and$Optional){return $null}
    if($commands.Count-ne1){throw 'Platform application selection is missing or ambiguous; select one absolute path.'}
    return $commands[0]
}

function Assert-QuestSpatialZipalignUsage {
    param([string]$Text)
    if($Text-notmatch'(?m)^Zip alignment utility\s*$'-or$Text-notmatch'(?m)^Usage: zipalign '-or$Text-match'(?im)(unknown option|unknown flag|^ERROR:)'){
        throw 'Selected zipalign did not return its recognized no-argument usage.'
    }
}

function Get-QuestSpatialBuildPrerequisites {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string]$AndroidHome,
        [Parameter(Mandatory)][string]$JavaHome,
        [string]$NdkHome='',
        [string]$NdkVersion='27.2.12479018',
        [string]$BuildToolsVersion='36.0.0',
        [string]$SharedGradleToolRoot='',
        [Parameter(Mandatory)][string]$BuildCacheRoot,
        [string[]]$ExcludedRoots=@(),
        [string]$Keystore='',
        [string]$ExpectedSignerSha256='',
        [Parameter(Mandatory)][string]$AppId,
        [ValidateSet('DevFast','Candidate')][string]$BuildMode='Candidate',
        [switch]$PublicationBuild,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [ValidateSet('Bounded','Retained',IgnoreCase=$false)][string]$WaitPolicy='Bounded'
    )
    # These are observations and fresh evidence only. Compiler/cache staging remains in the builder.
    if(-not$NdkHome){$NdkHome=Join-Path $AndroidHome "ndk/$NdkVersion"}
    $EvidenceDirectory=Assert-QuestSpatialPrerequisiteEvidencePath -Path $EvidenceDirectory `
        -ExcludedRoots (@($BuildCacheRoot,$SharedGradleToolRoot,$AndroidHome,$JavaHome,$NdkHome)+$ExcludedRoots)
    foreach($root in @($RepoRoot,$AndroidHome,$JavaHome,$NdkHome)){
        if(-not[IO.Path]::IsPathFullyQualified($root)-or-not(Test-Path -LiteralPath $root -PathType Container)){throw 'Required explicit platform tool directory is absent.'}
    }
    $RepoRoot=(Resolve-Path -LiteralPath $RepoRoot).Path
    $AndroidHome=(Resolve-Path -LiteralPath $AndroidHome).Path
    $JavaHome=(Resolve-Path -LiteralPath $JavaHome).Path
    $NdkHome=(Resolve-Path -LiteralPath $NdkHome).Path
    if((Split-Path -Leaf $NdkHome)-cne$NdkVersion){throw 'Resolved Android NDK differs from the pinned version.'}
    $buildTools=Join-Path $AndroidHome "build-tools/$BuildToolsVersion"
    $properties=Join-Path $buildTools 'source.properties'
    if(-not(Test-Path -LiteralPath $properties -PathType Leaf)){throw 'Pinned Android build-tools source.properties is missing.'}
    if((Get-Content -LiteralPath $properties -Raw)-notmatch('(?m)^Pkg\.Revision\s*=\s*'+[regex]::Escape($BuildToolsVersion)+'\s*$')){throw 'Android build-tools revision differs.'}
    $tools=@{
        aapt2=Join-Path $buildTools 'aapt2.exe';zipalign=Join-Path $buildTools 'zipalign.exe';apksigner=Join-Path $buildTools 'apksigner.bat'
        keytool=Join-Path $JavaHome 'bin/keytool.exe';java=Join-Path $JavaHome 'bin/java.exe'
        linker=Join-Path $NdkHome 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang.cmd'
        readelf=Join-Path $NdkHome 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
        nm=Join-Path $NdkHome 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-nm.exe'
    }
    foreach($file in $tools.Values){if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Pinned Android build tool is missing.'}}
    $probes=[Collections.Generic.List[object]]::new()
    function Observe([string]$Name,[string]$File,[string[]]$Arguments,[int[]]$Accepted=@(0)){
        # Inspect the actual exit ourselves, including recognized nonzero usage exits.
        $PSNativeCommandUseErrorActionPreference=$false
        # Drain the finite version observation completely before retaining a bounded diagnostic.
        $lines=@(& $File @Arguments 2>&1|ForEach-Object{[string]$_});$code=$LASTEXITCODE
        $text=$lines-join"`n"
        if([Text.Encoding]::UTF8.GetByteCount($text)-gt65536){throw 'Platform version observation exceeded its bound.'}
        if($code-notin$Accepted){throw "Platform version observation failed: $Name (exit $code)."}
        $raw=[Text.Encoding]::UTF8.GetBytes($text)
        $probes.Add([ordered]@{tool=$Name;exit_code=$code;output_sha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($raw)).ToLowerInvariant()})
        return $text
    }
    [void](Observe 'aapt2' $tools.aapt2 @('version'))
    [void](Observe 'android-clang' $tools.linker @('--version'))
    $javaVersion=Observe 'java' $tools.java @('-version')
    $keytoolVersion=Observe 'keytool' $tools.keytool @('-J-version')
    foreach($version in @($javaVersion,$keytoolVersion)){
        if($version-notmatch'(?m)^(?:openjdk|java) version "17\.'-or$version-notmatch'(?i)Temurin'){throw 'Selected Java/keytool is not backed by pinned Temurin JDK17.'}
    }
    $zipalignUsage=Observe 'zipalign' $tools.zipalign @() @(0,1,2)
    Assert-QuestSpatialZipalignUsage -Text $zipalignUsage
    [void](Observe 'apksigner' $tools.apksigner @('version'))
    $distribution=$null
    $gradle=Get-VerifiedPinnedGradleBat -RepoRoot $RepoRoot -SharedGradleToolRoot $SharedGradleToolRoot -ToolDistribution ([ref]$distribution) -WaitPolicy $WaitPolicy
    $explicit=-not[string]::IsNullOrWhiteSpace($Keystore)
    if(-not$explicit-and$env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE){$Keystore=$env:RUSTY_QUEST_SPATIAL_SIGNING_KEYSTORE;$explicit=$true}
    $shared=$AppId-ceq'io.github.mesmerprism.rustyquest.spatial_camera_panel'
    $required=$shared-or$BuildMode-ceq'Candidate'-or[bool]$PublicationBuild
    if($required-and-not$explicit){throw 'Shared-package and Candidate builds require explicit local signer input.'}
    $expected=$ExpectedSignerSha256.Trim().ToLowerInvariant()
    if($shared){
        $sharedSigner='722f1f3dcb921918d2e02f39f1b1bd8f9ff2812e07757c5fc665f6b8f7ee32a8'
        if($expected-and$expected-cne$sharedSigner){throw 'Shared package expected signer differs.'};$expected=$sharedSigner
    }
    if($required-and$expected-cnotmatch'^[0-9a-f]{64}$'){throw 'Candidate requires the exact expected signer fingerprint.'}
    if(-not[IO.Path]::IsPathFullyQualified($EvidenceDirectory)-or(Test-Path -LiteralPath $EvidenceDirectory)){throw 'Prerequisite evidence directory must be an absolute create-new path.'}
    if($explicit){
        if(-not(Test-Path -LiteralPath $Keystore -PathType Leaf)){throw 'Explicit signing keystore not found.'}
        if([string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS)-or[string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD)-or[string]::IsNullOrWhiteSpace($env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD)){throw 'Explicit signer requires declared local environment values.'}
        $Keystore=(Resolve-Path -LiteralPath $Keystore).Path
    }
    # Recheck the same literal geometry immediately before the first evidence write.
    $EvidenceDirectory=Assert-QuestSpatialPrerequisiteEvidencePath -Path $EvidenceDirectory `
        -ExcludedRoots (@($BuildCacheRoot,$SharedGradleToolRoot,$AndroidHome,$JavaHome,$NdkHome)+$ExcludedRoots)
    $null=New-Item -ItemType Directory -Path $EvidenceDirectory
    $certificate=''
    if($explicit){
        $path=Join-Path $EvidenceDirectory 'selected-signer.der'
        # Never write argv or secret values. Fresh attempt evidence replaces the old shared-cache probe.
        $null=& $tools.keytool -exportcert -keystore $Keystore -storepass:env RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -alias $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS -file $path 2>&1
        if($LASTEXITCODE-ne0-or-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'Selected signer certificate observation failed.'}
        $certificate=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if($expected-and$certificate-cne$expected){throw 'Explicit Spatial Camera Panel signer fingerprint mismatch before compilation.'}
    $evidence=[ordered]@{status='passed';platform_tool_probes=$probes.ToArray();build_tools_properties_sha256=(Get-FileHash $properties -Algorithm SHA256).Hash.ToLowerInvariant();signer_sha256=$certificate;compiler_invoked=$false;source_exported=$false;compiler_cache_written=$false;device_effects=0}
    [IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'prerequisites.json'),($evidence|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
    [pscustomobject]@{AndroidHome=$AndroidHome;JavaHome=$JavaHome;NdkHome=$NdkHome;BuildTools=$buildTools;BuildToolsSourceProperties=$properties;Tools=$tools;GradleBat=$gradle;GradleToolDistribution=$distribution;Keystore=$Keystore;KeystoreWasExplicit=$explicit;CertificateSha256=$certificate;ExpectedSignerSha256=$expected;Evidence=$evidence}
}
Export-ModuleMember -Function Get-QuestSpatialBuildPrerequisites,Resolve-QuestSpatialApplication,Assert-QuestSpatialPrerequisiteEvidencePath
