#requires -Version 7.6
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$CanonicalSerial,
    [Parameter(Mandatory)][string]$BootId,
    [Parameter(Mandatory)][string]$Package,
    [Parameter(Mandatory)][string]$Adb,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$AdbSha256,
    [Parameter(Mandatory)][string]$RequestPath,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'This private-receipt CLI currently requires Windows ACL support.' }
if ($Package -cnotmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$') { throw 'Exact app package required.' }
$binary = (Resolve-Path -LiteralPath $Adb).Path
$requestBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $RequestPath).Path)
if ($requestBytes.Length -eq 0 -or $requestBytes.Length -gt 16384) { throw 'Closed request size exceeded.' }
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$request = $utf8.GetString($requestBytes) | ConvertFrom-Json -Depth 24
if ($request.schema -cne 'rusty.quest.product_panel.command.v1' -or $request.operation -cnotin @('status', 'result', 'invoke')) {
    throw 'Unsupported product panel request.'
}
# The app validates the closed action/parameter shape and current owner token.
# The exact request bytes are retained by its bounded no-replay registry.
$directory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $directory) { throw 'OutputDirectory must be create-new.' }
$parent = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName($directory))
while ($null -ne $parent) {
    if (-not $parent.Exists -or ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Output parents must exist and contain no reparse points.'
    }
    $parent = $parent.Parent
}
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$security = [Security.AccessControl.DirectorySecurity]::new()
$security.SetOwner($sid)
$security.SetAccessRuleProtection($true, $false)
$security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
[IO.FileSystemAclExtensions]::Create([IO.DirectoryInfo]::new($directory), $security) | Out-Null
$actualAcl = Get-Acl -LiteralPath $directory
if (-not $actualAcl.AreAccessRulesProtected -or @($actualAcl.Access).Count -ne 1 -or
    @($actualAcl.Access | Where-Object { $_.AccessControlType -ne 'Allow' -or $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value }).Count) {
    throw 'Private receipt directory ACL verification failed.'
}
function Write-PrivateBytes([string]$Name, [byte[]]$Bytes) {
    $stream = [IO.File]::Open((Join-Path $directory $Name), 'CreateNew', 'Write', 'None')
    try { $stream.Write($Bytes, 0, $Bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
}
function Invoke-FixedRead([string[]]$ReadArguments) {
    $output = @(& $binary '-s' $Device @ReadArguments 2>&1)
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw 'Fixed target identity read failed; no mutation replay.' }
    return ($output -join "`n").Trim()
}
$binaryLock = [IO.File]::Open($binary, 'Open', 'Read', 'Read')
$sent = $false
$status = 'not-dispatched'
try {
    if ((Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant() -cne $AdbSha256) { throw 'ADB byte mismatch.' }
    $serial = Invoke-FixedRead -ReadArguments @('shell', 'getprop', 'ro.serialno')
    $boot = Invoke-FixedRead -ReadArguments @('shell', 'cat', '/proc/sys/kernel/random/boot_id')
    if ($serial -cne $CanonicalSerial -or $boot -cne $BootId) { throw 'Exact target/boot mismatch.' }
    $intent = [ordered]@{
        schema = 'rusty.quest.product_panel.host_invocation.v1'
        started_at_utc = [DateTime]::UtcNow.ToString('o')
        canonical_serial = $serial
        boot_id = $boot
        transport = $Device
        package = $Package
        adb_sha256 = $AdbSha256
        request_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($requestBytes)).ToLowerInvariant()
        operation = [string]$request.operation
        device_effect_possible = $request.operation -ceq 'invoke'
        retry = 'forbidden; query this exact request id before any next action'
    }
    Write-PrivateBytes 'intent.json' ($utf8.GetBytes(($intent | ConvertTo-Json -Depth 8)))
    $encoded = [Convert]::ToBase64String($requestBytes).TrimEnd('=')
    $component = $Package + '/io.github.mesmerprism.rustyquest.native_renderer.BreathCompositionCommandReceiver'
    # One exact-package DUMP-protected ordered broadcast. No launch, shell command,
    # daemon lifecycle, automatic retry or generic intent is exposed by this CLI.
    $sent = $true
    $status = 'unconfirmed'
    $output = @(& $binary '-s' $Device 'shell' 'am' 'broadcast' '-a' 'io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_COMMAND' '-n' $component '--es' 'command_b64' $encoded 2>&1)
    $code = $LASTEXITCODE
    $wire = $output -join "`n"
    Write-PrivateBytes 'broadcast.txt' ($utf8.GetBytes($wire))
    if ($code -ne 0) { throw 'Typed dispatch failed or outcome unknown; do not replay.' }
    $match = [regex]::Match($wire, 'data="(?<body>[A-Za-z0-9+/=]+)"')
    if (-not $match.Success) { throw 'App receipt missing; do not replay.' }
    $body = $match.Groups['body'].Value
    $body = $body.PadRight($body.Length + (4 - $body.Length % 4) % 4, '=')
    $receiptBytes = [Convert]::FromBase64String($body)
    $receipt = $utf8.GetString($receiptBytes) | ConvertFrom-Json -Depth 64
    Write-PrivateBytes 'app-receipt.json' $receiptBytes
    if ($receipt.schema -cne 'rusty.quest.product_panel.receipt.v1') { throw 'Wrong app receipt; do not replay.' }
    if ($receipt.status -isnot [string] -or $receipt.status -cnotin @('current', 'dispatched', 'pending', 'unknown', 'rejected')) {
        throw 'Unrecognized app disposition; do not replay.'
    }
    if ($receipt.status -ceq 'rejected') {
        # Predispatch rejection can have no current owner, but never attribute another request.
        if ($receipt.request_id -isnot [string] -or
            ($receipt.request_id -cne '' -and ($request.operation -ceq 'status' -or $receipt.request_id -cne $request.request_id))) {
            throw 'Unrelated rejection receipt; do not replay.'
        }
        if ($receipt.reason -isnot [string] -or [string]::IsNullOrWhiteSpace($receipt.reason)) {
            throw 'Rejection reason unavailable; do not replay.'
        }
    } elseif ($request.operation -ceq 'status') {
        if ($receipt.status -cne 'current' -or $receipt.owner_token -isnot [string] -or
            $receipt.owner_token -cnotmatch '^[0-9a-f]{32}$' -or $receipt.request_id -cne '' -or $receipt.action -cne '') {
            throw 'Status receipt correlation failed; do not replay.'
        }
    } else {
        if ($receipt.status -ceq 'current' -or $receipt.owner_token -cne $request.owner_token -or
            $receipt.request_id -cne $request.request_id -or $receipt.action -isnot [string]) {
            throw 'Owner/request receipt correlation failed; do not replay.'
        }
        if ($request.operation -ceq 'invoke' -and $receipt.action -cne $request.action) {
            throw 'Action receipt correlation failed; do not replay.'
        }
    }
    $status = [string]$receipt.status
    if ((Invoke-FixedRead -ReadArguments @('shell', 'getprop', 'ro.serialno')) -cne $CanonicalSerial -or
        (Invoke-FixedRead -ReadArguments @('shell', 'cat', '/proc/sys/kernel/random/boot_id')) -cne $BootId) {
        throw 'Target changed across invocation; app receipt retained without current acceptance.'
    }
    # Enrollment and join results can contain secrets. Normal stdout is metadata only.
    [pscustomobject]@{ status = $status; receipt_directory = $directory; application_effect_readback = 'app-receipt.json'; mutation_replayed = $false } | ConvertTo-Json
} catch {
    $failure = [ordered]@{ status = $status; sent = $sent; error = $_.Exception.Message; retry_forbidden = $true; at_utc = [DateTime]::UtcNow.ToString('o') }
    Write-PrivateBytes 'host-failure.json' ($utf8.GetBytes(($failure | ConvertTo-Json)))
    throw
} finally { $binaryLock.Dispose() }
