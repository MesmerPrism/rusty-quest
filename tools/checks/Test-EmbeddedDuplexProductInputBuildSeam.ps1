param(
    [string]$QuestRepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [Parameter(Mandatory = $true)][string]$FixtureRoot,
    [Parameter(Mandatory = $true)][string]$OutDir
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$builder = Join-Path $QuestRepoRoot "tools/Build-SpatialCameraPanelAndroid.ps1"
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Resolve-Path -LiteralPath $builder).Path, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw "Spatial Camera Panel builder does not parse." }
foreach ($name in @("Get-FileSha256", "Get-StringSha256", "Resolve-OptionalDirectoryPath", "Test-ExactJsonProperties", "Test-EmbeddedDuplexProductInputRoot")) {
    $definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $true)
    if ($null -eq $definition) { throw "Spatial Camera Panel builder lacks '$name'." }
    Invoke-Expression $definition.Extent.Text
}

$source = (Resolve-Path -LiteralPath $FixtureRoot).Path
$leaf = Split-Path -Leaf $source
if ($leaf -cnotmatch '^[0-9a-f]{64}$' -or (Test-Path -LiteralPath $OutDir)) {
    throw "Fixture must be a product-input closure and OutDir must be new."
}
$out = [IO.Path]::GetFullPath($OutDir)
$fixture = Join-Path $out $leaf
New-Item -ItemType Directory -Path $fixture | Out-Null
Copy-Item -Path (Join-Path $source '*') -Destination $fixture

$manifestPath = Join-Path $fixture "product-input-manifest.json"
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -Depth 64
$feature = Get-Content -LiteralPath (Join-Path $fixture "planning-feature-lock.json") -Raw | ConvertFrom-Json -Depth 64
$rows = @($feature.features | Where-Object { [string]$_.feature_id -ceq "morphovision-embedded-peer-input" })
if ($rows.Count -ne 1) { throw "Fixture feature lock lacks the exact embedded peer input row." }
$row = $rows[0]
$applicationId = [string]$manifest.package.application_id
$signer = [string]$manifest.package.signing_certificate_sha256

function Write-FixtureManifest {
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 64), [Text.UTF8Encoding]::new($false))
}
function Assert-Rejected([string]$Name, [string]$ExpectedMessage) {
    try {
        $null = Test-EmbeddedDuplexProductInputRoot -Path $fixture -ApplicationId $applicationId -SigningCertificateSha256 $signer
    } catch {
        if ($_.Exception.Message -cne $ExpectedMessage) { throw "'$Name' failed at an unexpected gate: $($_.Exception.Message)" }
        Write-Output "rejected $Name"
        return
    }
    throw "'$Name' was accepted."
}

foreach ($name in @("planning_feature_id", "planning_feature_module_id", "planning_feature_activation_receipt_schema", "planning_feature_resolver_fingerprint")) {
    $manifest.source_authorities.PSObject.Properties.Remove($name)
}
Write-FixtureManifest
Assert-Rejected "old five-field manifest" "Embedded duplex product input manifest identity is invalid."

$manifest.source_authorities | Add-Member -NotePropertyName planning_feature_id -NotePropertyValue ([string]$row.feature_id)
$manifest.source_authorities | Add-Member -NotePropertyName planning_feature_module_id -NotePropertyValue ([string]$row.module_id)
$manifest.source_authorities | Add-Member -NotePropertyName planning_feature_activation_receipt_schema -NotePropertyValue ([string]$row.activation.receipt_schema)
$manifest.source_authorities | Add-Member -NotePropertyName planning_feature_resolver_fingerprint -NotePropertyValue ([string]$feature.lock_fingerprint)
Write-FixtureManifest
$accepted = Test-EmbeddedDuplexProductInputRoot -Path $fixture -ApplicationId $applicationId -SigningCertificateSha256 $signer
if ([string]$accepted.closure_sha256 -cne $leaf) { throw "The exact nine-field manifest returned a different closure." }
Write-Output "accepted exact nine-field manifest"

$manifest.source_authorities.planning_feature_resolver_fingerprint = '0' * 64
Write-FixtureManifest
Assert-Rejected "tampered resolver fingerprint" "Embedded duplex product input source authority differs from the packaged feature lock."
$manifest.source_authorities.planning_feature_resolver_fingerprint = [string]$feature.lock_fingerprint
$manifest.source_authorities | Add-Member -NotePropertyName unexpected -NotePropertyValue "x"
Write-FixtureManifest
Assert-Rejected "extra authority field" "Embedded duplex product input manifest identity is invalid."
$manifest.source_authorities.PSObject.Properties.Remove("unexpected")
$manifest.source_authorities.PSObject.Properties.Remove("planning_feature_module_id")
Write-FixtureManifest
Assert-Rejected "missing authority field" "Embedded duplex product input manifest identity is invalid."
