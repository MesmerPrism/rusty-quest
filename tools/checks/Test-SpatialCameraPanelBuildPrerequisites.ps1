param([Parameter(Mandatory)][string]$RepoRoot,[Parameter(Mandatory)][string]$InputSpecPath,[Parameter(Mandatory)][string]$OutRoot)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
if(Test-Path -LiteralPath $OutRoot){throw 'Create-new control evidence required.'}
$null=New-Item -ItemType Directory -Path $OutRoot
Import-Module (Join-Path $RepoRoot 'tools/lib/SpatialCameraPanelBuildPrerequisites.psm1') -Force
# This spec names existing platform installations/shared Gradle only; it contains no product key.
$spec=Get-Content -LiteralPath $InputSpecPath -Raw|ConvertFrom-Json -AsHashtable
foreach($key in $spec.Keys){if($key-notin@('AndroidHome','JavaHome','NdkHome','NdkVersion','BuildToolsVersion','SharedGradleToolRoot')){throw 'Unexpected platform fixture field.'}}
$keytool=Join-Path $spec.JavaHome 'bin/keytool.exe';$fixture=Join-Path $OutRoot 'fixture.p12'
$parent=@{};Get-ChildItem Env:|ForEach-Object{$parent[$_.Name]=$_.Value}
$checks=[Collections.Generic.List[object]]::new()
try{
    # Fresh test-only signer; never product signing or APK evidence.
    $env:RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD='fixture-only-password'
    $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD='fixture-only-password'
    $env:RUSTY_QUEST_SPATIAL_SIGNING_KEY_ALIAS='fixture'
    $null=& $keytool -genkeypair -storetype PKCS12 -keystore $fixture -storepass:env RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -keypass:env RUSTY_QUEST_SPATIAL_SIGNING_KEY_PASSWORD -alias fixture -dname 'CN=Local Fixture' -keyalg RSA -validity 1 2>&1
    if($LASTEXITCODE-ne0){throw 'Fixture-only signer creation failed.'}
    $cert=Join-Path $OutRoot 'fixture.der'
    $null=& $keytool -exportcert -keystore $fixture -storepass:env RUSTY_QUEST_SPATIAL_SIGNING_STORE_PASSWORD -alias fixture -file $cert 2>&1
    if($LASTEXITCODE-ne0){throw 'Fixture-only certificate read failed.'}
    $expected=(Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash.ToLowerInvariant()
    function Check([string]$Name,[hashtable]$Changes,[string]$ExpectedReason=''){
        $a=@{};foreach($key in $spec.Keys){$a[$key]=$spec[$key]}
        $a.RepoRoot=$RepoRoot;$a.EvidenceDirectory=Join-Path $OutRoot $Name;$a.Keystore=$fixture
        $a.BuildCacheRoot=Join-Path $OutRoot 'compiler-cache'
        $a.ExpectedSignerSha256=$expected;$a.AppId='example.invalid.quest.fixture';$a.BuildMode='Candidate'
        foreach($key in $Changes.Keys){$a[$key]=$Changes[$key]}
        $denied=$false;$reason='';$result=$null
        try{$result=Get-QuestSpatialBuildPrerequisites @a}catch{$denied=$true;$reason=$_.Exception.Message}
        if([bool]$ExpectedReason-ne$denied-or($ExpectedReason-and-not$reason.Contains($ExpectedReason))){throw "Unexpected shared prerequisite outcome: $Name ($reason)"}
        if(-not$denied-and($result.CertificateSha256-cne$expected-or$result.Evidence.compiler_invoked-or$result.Evidence.compiler_cache_written)){throw 'Prerequisite observation exceeded scope.'}
        if($ExpectedReason-like'Prerequisite evidence overlaps*'-and(Test-Path -LiteralPath $a.EvidenceDirectory)){throw 'Evidence overlap refusal created the denied directory.'}
        foreach($cacheLeaf in @('t','h','gu','gp','n','target')){if(Test-Path -LiteralPath (Join-Path $OutRoot $cacheLeaf)){throw 'Prerequisite observation created compiler/cache stage.'}}
        $checks.Add([ordered]@{name=$Name;passed=$true;denied=$denied;reason=$reason})
    }
    $nativePreference=$PSNativeCommandUseErrorActionPreference
    try{
        $PSNativeCommandUseErrorActionPreference=$true
        Check 'actual-platform-fixture-signer-positive' @{}
        if(-not$PSNativeCommandUseErrorActionPreference){throw 'Prerequisite observer changed caller native-command preference.'}
    }finally{$PSNativeCommandUseErrorActionPreference=$nativePreference}
    # Exit 2 alone is not readiness: the real tool's invalid option also exits 2.
    $zipalign=Join-Path $spec.AndroidHome ('build-tools/'+$spec.BuildToolsVersion+'/zipalign.exe')
    $badProbe=& {
        $PSNativeCommandUseErrorActionPreference=$false
        $text=@(& $zipalign -h 2>&1|ForEach-Object{[string]$_})-join"`n"
        [pscustomobject]@{text=$text;code=$LASTEXITCODE}
    }
    $badDenied=$false
    try{& (Get-Module SpatialCameraPanelBuildPrerequisites) {param($text) Assert-QuestSpatialZipalignUsage -Text $text} $badProbe.text}catch{$badDenied=$true}
    if($badProbe.code-ne2-or-not$badDenied){throw 'Actual zipalign invalid-option exit 2 was accepted.'}
    $checks.Add([ordered]@{name='actual-zipalign-exit2-invalid-option';passed=$true;denied=$true;reason='recognized usage required'})
    Check 'missing-sdk' @{AndroidHome=(Join-Path $OutRoot 'absent-sdk')} 'tool directory is absent'
    Check 'wrong-ndk-version' @{NdkVersion='0.0.0'} 'NDK differs'
    Check 'missing-signer' @{Keystore=(Join-Path $OutRoot 'absent.p12')} 'keystore not found'
    Check 'wrong-public-signer' @{ExpectedSignerSha256=('0'*64)} 'signer fingerprint mismatch'
    Check 'cache-evidence-overlap' @{EvidenceDirectory=(Join-Path $OutRoot 'compiler-cache/readiness')} 'Prerequisite evidence overlaps'
    Check 'declared-output-evidence-overlap' @{EvidenceDirectory=(Join-Path $OutRoot 'attempt/readiness');ExcludedRoots=@((Join-Path $OutRoot 'attempt'))} 'Prerequisite evidence overlaps'
    $record=[ordered]@{passed=$true;controls=$checks.ToArray();product_signer_used=$false;compiler_invoked=$false;device_effects=0;apk_proof=$false}
    [IO.File]::WriteAllText((Join-Path $OutRoot 'RESULT.json'),($record|ConvertTo-Json -Depth 16),[Text.UTF8Encoding]::new($false))
    $record|ConvertTo-Json -Depth 16
}finally{
    [Environment]::GetEnvironmentVariables('Process').Keys|ForEach-Object{[Environment]::SetEnvironmentVariable([string]$_,$null,'Process')}
    foreach($name in $parent.Keys){[Environment]::SetEnvironmentVariable($name,$parent[$name],'Process')}
}
