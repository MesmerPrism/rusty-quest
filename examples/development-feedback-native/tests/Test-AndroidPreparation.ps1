[CmdletBinding()]
param([Parameter(Mandatory)][string]$Rustc, [string]$AndroidNdk = '')
$ErrorActionPreference = 'Stop'
$example = Split-Path $PSScriptRoot -Parent
$output = Join-Path $example ('.local/negative-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$run = Join-Path $example 'Run-Conformance.ps1'
function Rejects-BeforeCompilation([string]$Name, [string[]]$Arguments) {
    $case = Join-Path $output $Name
    New-Item -ItemType Directory -Path $case | Out-Null
    # Missing preflight would compile/run the host and incorrectly report success.
    & pwsh -NoProfile -File $run -Rustc $Rustc -OutputRoot $case @Arguments 2>&1 |
        Set-Content (Join-Path $case 'output.txt')
    if ($LASTEXITCODE -eq 0) { throw "Invalid preparation accepted: $Name" }
    $receipts = @(Get-ChildItem -LiteralPath $case -Filter receipt.json -Recurse)
    if ($receipts.Count -ne 1) { throw 'Failed Android preflight must retain one failure receipt' }
    $receipt = Get-Content -LiteralPath $receipts[0].FullName -Raw | ConvertFrom-Json
    if ($receipt.passed -or $receipt.android_preparation.passed -or $receipt.timings.Count -ne 0 -or -not $receipt.failure) {
        throw "Invalid preparation reached compilation or claimed pass: $Name"
    }
}
Rejects-BeforeCompilation 'invalid-ndk' @('-AndroidNdk',(Join-Path $output 'missing-ndk'))
Rejects-BeforeCompilation 'api-without-ndk' @('-AndroidApi','26')
if ($AndroidNdk) {
    Rejects-BeforeCompilation 'api-below-contract' @('-AndroidNdk',$AndroidNdk,'-AndroidApi','25')
    Rejects-BeforeCompilation 'api-outside-ndk' @('-AndroidNdk',$AndroidNdk,'-AndroidApi','999')
}
Write-Output "PASS Android negative preflights fail before compile with retained receipts: $output"
