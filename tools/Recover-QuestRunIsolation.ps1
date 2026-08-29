[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$ExpectedSerial,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._]+$')][string]$ExpectedPackageName,
    [Parameter(Mandatory=$true)][string]$EnteredReceiptPath,
    [Parameter(Mandatory=$true)][string]$TerminalReceiptPath,
    [Parameter(Mandatory=$true)][string]$Adb,
    [string]$AdbServerPort,
    [ValidateRange(0, 120)][int]$MutexTimeoutSeconds = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not [IO.Path]::IsPathFullyQualified($Adb) -or -not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw '-Adb must be an explicit existing executable path for receipt-bound recovery.'
}
Import-Module (Join-Path $PSScriptRoot 'lib\QuestRunIsolation.psm1') -Force

$receipt = Recover-QuestRunIsolation `
    -Adb (Resolve-Path -LiteralPath $Adb).Path `
    -ExpectedSerial $ExpectedSerial `
    -AdbServerPort $AdbServerPort `
    -ExpectedPackageName $ExpectedPackageName `
    -EnteredReceiptPath $EnteredReceiptPath `
    -TerminalReceiptPath $TerminalReceiptPath `
    -MutexTimeoutSeconds $MutexTimeoutSeconds
$receipt | ConvertTo-Json -Depth 12
