param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)), [string]$FeatureLock)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$builder = Join-Path $RepoRoot 'tools/Build-NativeRendererAndroid.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($builder, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Builder AST contains syntax errors.' }
$functions = @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Assert-ShowcaseManifestComponentClosure' }, $false))
if ($functions.Count -ne 1) { throw 'Expected the exact production manifest guard.' }
# Execute only the production pure guard, never the APK/Cargo builder.
. ([scriptblock]::Create($functions[0].Extent.Text))
$script:cases = 0
function Test-ClosureCase {
    param([string]$Json, [bool]$Accept)
    $rejected = $false
    try { Assert-ShowcaseManifestComponentClosure -Manifest (ConvertFrom-Json -InputObject $Json) }
    catch { $rejected = $true }
    if ($rejected -eq $Accept) { throw "Unexpected guard outcome accept=$Accept for $Json" }
    $script:cases++
}
Test-ClosureCase '{}' $true
Test-ClosureCase '{"providers":null,"receivers":null,"services":null}' $true
Test-ClosureCase '{"providers":[],"receivers":[],"services":[]}' $true
foreach ($name in @('providers', 'receivers', 'services')) {
    Test-ClosureCase ('{"' + $name + '":[]}') $true
    Test-ClosureCase ('{"' + $name + '":null}') $true
    foreach ($value in @('["DeclaredComponent"]', '[null]', '"DeclaredComponent"', 'false', '0', '{}')) {
        Test-ClosureCase ('{"' + $name + '":' + $value + '}') $false
    }
}
foreach ($value in @('null', 'false', '0', '"manifest"', '[]')) { Test-ClosureCase $value $false }
if ($FeatureLock) {
    $lock = Get-Content -LiteralPath $FeatureLock -Raw | ConvertFrom-Json
    Assert-ShowcaseManifestComponentClosure -Manifest $lock.android_manifest
    $script:cases++
}
Write-Output "PASS $script:cases executable optional-component guard cases; production builder AST valid; no compiler or APK invocation."
