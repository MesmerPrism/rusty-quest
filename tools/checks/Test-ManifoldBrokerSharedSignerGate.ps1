param(
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
}
$buildPath = Join-Path $RepoRoot 'tools\Build-ManifoldBrokerAndroid.ps1'
$deployPath = Join-Path $RepoRoot 'tools\Invoke-ConnectionHubQuest.ps1'
if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
    throw "Missing broker build script: $buildPath"
}
$build = Get-Content -Raw -LiteralPath $buildPath
$deploy = Get-Content -Raw -LiteralPath $deployPath

function Require([string]$Pattern, [string]$Failure) {
    if ($build -cnotmatch $Pattern) {
        throw $Failure
    }
}

Require '\[switch\]\$RequireSharedSigner' `
    'Shared-package builds do not expose an explicit signer gate.'
Require '\$keystoreWasExplicit = -not \[string\]::IsNullOrWhiteSpace\(\$Keystore\)' `
    'The signer gate does not distinguish an explicit local binding from the broker default.'
Require '\$RequireSharedSigner -and -not \$keystoreWasExplicit' `
    'The broker default signer can silently enter the shared-package path.'
Require 'Shared client package builds require an explicit local -Keystore binding\.' `
    'The missing explicit signer rejection is absent.'
Require 'Assert-SharedSignerExpectedIdentity -Required' `
    'The public shared client certificate fingerprint is not pinned.'
Require 'Assert-SharedSignerCertificate -Required .* -ExpectedSha256 \$ExpectedSignerSha256 -ActualSha256 \$certificateSha256' `
    'The selected certificate is not compared to the pinned shared fingerprint.'
Require 'Explicit shared signer fingerprint mismatch\.' `
    'An explicit mismatched signer is not rejected.'
Require 'artifact_signer_sha256 = \$certificateSha256' `
    'The public build receipt does not record the actual signer fingerprint.'
Require 'RUSTY_QUEST_SHARED_SIGNING_ALIAS' `
    'The shared signer alias is not supplied through a local environment binding.'
Require 'RUSTY_QUEST_SHARED_SIGNING_STORE_PASSWORD' `
    'The shared signer store password is not supplied through a local environment binding.'
Require 'RUSTY_QUEST_SHARED_SIGNING_KEY_PASSWORD' `
    'The shared signer key password is not supplied through a local environment binding.'
if ($deploy -cnotmatch '-RequireSharedSigner') {
    throw 'The normal Hub build/deploy path does not force the shared signer gate.'
}
if ($deploy -cnotmatch '\$sharedSigner -ne \$ExpectedSignerSha256') {
    throw 'The deploy path does not reject an inspected mismatched signer before install.'
}
if ($deploy -cmatch 'keystore_sha256\s*=') {
    throw 'The deploy receipt records keystore identity instead of only public certificate readback.'
}

$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($build,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Broker builder parser failed.'}
if($ast.ParamBlock.Attributes.TypeName.Name-cnotcontains'CmdletBinding'){throw 'Builder must reject unrecognized or removed parameter names before effects.'}
$unknownOutput=& (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File $buildPath -UnrecognizedSharedSigner 2>&1
if($LASTEXITCODE-eq0-or-not(($unknownOutput-join "`n").Contains('UnrecognizedSharedSigner'))){throw 'Unknown signer parameter did not fail closed.'}
foreach($name in @('Assert-SharedSignerExpectedIdentity','Assert-SharedSignerCertificate')){
    $functions=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.FunctionDefinitionAst]-and$n.Name-ceq$name},$true))
    if($functions.Count-ne1){throw "Unique production signer function required: $name"}
    Invoke-Expression $functions[0].Extent.Text
}
function Reject([scriptblock]$Body){$rejected=$false;try{&$Body}catch{$rejected=$true};if(-not$rejected){throw 'Damaged shared signer input admitted.'}}
$expected='a'*64
Assert-SharedSignerCertificate $true $expected $expected
Assert-SharedSignerCertificate $false '' ('b'*64)
Reject {Assert-SharedSignerCertificate $true '' $expected}
Reject {Assert-SharedSignerCertificate $true 'invalid' $expected}
Reject {Assert-SharedSignerCertificate $true ('A'*64) $expected}
Reject {Assert-SharedSignerCertificate $true $expected ('b'*64)}
Reject {Assert-SharedSignerCertificate $true $expected 'malformed-export'}
Reject {Assert-SharedSignerCertificate $false $expected $expected}
# Execute the real pre-compilation binding block. No keytool, build or device call.
$start=$build.IndexOf('$keystoreWasExplicit =');$end=$build.IndexOf('$resolvedOutParent =',$start)
if($start-lt0-or$end-le$start){throw 'Unique pre-compilation binding block required.'}
$binding=[scriptblock]::Create($build.Substring($start,$end-$start))
$names=@('RUSTY_QUEST_SHARED_SIGNING_ALIAS','RUSTY_QUEST_SHARED_SIGNING_STORE_PASSWORD','RUSTY_QUEST_SHARED_SIGNING_KEY_PASSWORD');$prior=@{}
foreach($name in $names){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,'modeled-host-value','Process')}
    $RequireSharedSigner=$true;$ExpectedSignerSha256=$expected;$Keystore='explicit-modeled-keystore'
    & $binding
    $Keystore='';Reject {&$binding};$Keystore='explicit-modeled-keystore'
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$null,'Process');Reject {&$binding};[Environment]::SetEnvironmentVariable($name,'modeled-host-value','Process')}
    $ExpectedSignerSha256='';Reject {&$binding}
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}}
$pathGate=@($ast.FindAll({param($n)$n-is[Management.Automation.Language.IfStatementAst]-and$n.Extent.Text.Contains('The explicit shared client signing keystore does not exist.')},$true))
if($pathGate.Count-ne1){throw 'Unique real shared-keystore existence gate required.'}
$RequireSharedSigner=$true;$Keystore=Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString('N')+'.missing-keystore')
Reject {&([scriptblock]::Create($pathGate[0].Extent.Text))}
$Keystore=[IO.Path]::GetTempPath();Reject {&([scriptblock]::Create($pathGate[0].Extent.Text))}
if($build.IndexOf('keytool certificate export')-gt$build.IndexOf('Assert-SharedSignerCertificate -Required')){throw 'Exported certificate verification order changed.'}
if($deploy-cnotmatch '-ExpectedSignerSha256 \$ExpectedSignerSha256' -or $deploy-cnotmatch 'build_manifest.v3'){throw 'Hub did not migrate its explicit expected identity/versioned receipt.'}
Write-Host 'Manifold broker generic shared signer gate: PASS (production expected/certificate and binding controls; no build or device)'
