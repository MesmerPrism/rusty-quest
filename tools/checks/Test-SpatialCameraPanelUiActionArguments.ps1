param([string]$RepoRoot)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$scriptPath = Join-Path (Resolve-Path -LiteralPath $RepoRoot).Path "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

if ($scriptText -notmatch 'function ConvertTo-RemoteShellArgument') {
    throw "The UI action helper must define a remote-shell argument encoder."
}
if ($scriptText -notmatch '"--es", "profile_title", \(ConvertTo-RemoteShellArgument \$ProfileTitle\.Trim\(\)\)') {
    throw "profile_title must be encoded as one remote-shell argument so spaced titles are not truncated."
}

$parseErrors = $null
$tokens = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "The UI action helper does not parse: $($parseErrors[0].Message)"
}
$encoder = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq "ConvertTo-RemoteShellArgument" }, $true)
$encoderBlock = [scriptblock]::Create($encoder.Extent.Text)
. $encoderBlock
if ((ConvertTo-RemoteShellArgument "Spaced Profile") -cne "'Spaced Profile'") {
    throw "The remote-shell encoder must preserve a spaced ProfileTitle as one argument."
}
if ((ConvertTo-RemoteShellArgument "Rin's Profile") -cne "'Rin'\''s Profile'") {
    throw "The remote-shell encoder must escape apostrophes with the POSIX single-quote sequence."
}

Write-Output "Spatial Camera Panel UI action profile-title argument checks passed."
