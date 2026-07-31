[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[A-Za-z0-9._:-]+$")]
    [string]$Serial,
    [Parameter(Mandatory = $true)]
    [ValidateSet("Check", "Status", "Cancel")]
    [string]$Command,
    [string]$AdbPath = "adb",
    [string]$PackageName =
        "io.github.mesmerprism.rustyquest.packageupdater.alpha.e2ecli"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSEdition -ne "Core" -or
    $PSVersionTable.PSVersion -lt [version]"7.6") {
    throw "Package Updater E2E CLI requires PowerShell 7.6 Core or newer."
}
if ($PackageName -notmatch
    "^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$") {
    throw "Invalid Package Updater E2E package identity."
}

$deviceState = (& $AdbPath -s $Serial get-state 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne "device") {
    throw "ADB target $Serial is not ready: $deviceState"
}

if ($Command -eq "Check") {
    $component = (
        "$PackageName/" +
        "io.github.mesmerprism.rustyquest.packageupdater.PackageUpdaterActivity"
    )
    $launch = (& $AdbPath -s $Serial shell am start -W -n $component 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $launch -notmatch "Status:\s+ok") {
        throw "Could not foreground the E2E updater before check: $launch"
    }
}

$method = $Command.ToLowerInvariant()
$authority = "$PackageName.cli"
$raw = (
    & $AdbPath -s $Serial shell content call `
        --uri "content://$authority" `
        --method $method 2>&1
) -join "`n"
if ($LASTEXITCODE -ne 0) {
    throw "Package Updater E2E CLI call failed: $raw"
}

$match = [regex]::Match($raw, "result_b64=([A-Za-z0-9_-]+)")
if (-not $match.Success) {
    throw "Package Updater E2E CLI returned no machine payload: $raw"
}
$encoded = $match.Groups[1].Value.Replace("-", "+").Replace("_", "/")
switch ($encoded.Length % 4) {
    2 { $encoded += "==" }
    3 { $encoded += "=" }
    1 { throw "Package Updater E2E CLI returned invalid Base64url." }
}
$json = [System.Text.Encoding]::UTF8.GetString(
    [System.Convert]::FromBase64String($encoded)
)
$response = $json | ConvertFrom-Json -Depth 16
if ($response.schema -ne
    "rusty.quest.package_update.e2e_cli_response.v1") {
    throw "Package Updater E2E CLI response schema changed."
}

$response
if (-not $response.accepted) {
    exit 2
}
