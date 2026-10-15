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
    [Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('runtime-only','persistent')][string]$PersistenceScope='runtime-only'
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
if ($request.schema -cne 'rusty.quest.native_renderer.lsl.panel_command.v1' -or $request.operation -cnotin @('status','apply','disable','reset')) {
    throw 'Unsupported LSL panel command.'
}
$expectedKeys=if($request.operation -ceq 'status'){@('schema','operation')}elseif($request.operation -ceq 'apply'){@('schema','operation','request_id','app_session_id','generation','config')}else{@('schema','operation','request_id','app_session_id','generation')}
$keys=@($request.PSObject.Properties.Name)
if($keys.Count -ne $expectedKeys.Count -or @($keys|Where-Object {$_ -cnotin $expectedKeys}).Count){throw 'Closed LSL command shape required.'}
if($request.operation -cne 'status'){
    if($request.request_id -isnot [string] -or $request.request_id -cnotmatch '^[a-zA-Z0-9._:-]{1,96}$' -or
       $request.app_session_id -isnot [string] -or [string]::IsNullOrWhiteSpace($request.app_session_id) -or $request.app_session_id.Length -gt 96 -or
       ($request.generation -isnot [long] -and $request.generation -isnot [int]) -or $request.generation -le 0){throw 'Original request/session/generation fences required.'}
}
# The unchanged native reducer validates all config fields, session identity,
# generation, backend availability and original request replay fences.
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
        schema = 'rusty.quest.native_renderer.lsl.host_invocation.v1'
        started_at_utc = [DateTime]::UtcNow.ToString('o')
        canonical_serial = $serial
        boot_id = $boot
        transport = $Device
        package = $Package
        adb_sha256 = $AdbSha256
        request_sha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($requestBytes)).ToLowerInvariant()
        operation = [string]$request.operation
        device_effect_possible = $request.operation -cne 'status'
        persistence_scope = $PersistenceScope
        retry = 'forbidden; retain exact request and reconcile read-only'
    }
    Write-PrivateBytes 'intent.json' ($utf8.GetBytes(($intent | ConvertTo-Json -Depth 8)))
    $encoded = [Convert]::ToBase64String($requestBytes).TrimEnd('=')
    $component = $Package + '/io.github.mesmerprism.rustyquest.native_renderer.LslPanelCommandReceiver'
    # One exact-package DUMP-protected ordered broadcast. No launch, shell command,
    # daemon lifecycle, automatic retry or generic intent is exposed by this CLI.
    $sent = $true
    $status = 'unconfirmed'
    $output = @(& $binary '-s' $Device 'shell' 'am' 'broadcast' '-a' 'io.github.mesmerprism.rustyquest.native_renderer.action.LSL_PANEL_COMMAND' '-n' $component '--es' 'command_b64' $encoded '--es' 'persistence_scope' $PersistenceScope 2>&1)
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
    if ($receipt.schema -cnotin @('rusty.quest.native_renderer.lsl.status.v1','rusty.quest.native_renderer.lsl.cli_receipt.v1') -or
        $receipt.response_status -cnotin @('accepted','status','rejected')) {throw 'Unrecognized LSL receipt; do not replay.'}
    if($receipt.response_status -ceq 'accepted'){
        if($receipt.app_session_id -isnot [string] -or $receipt.last_request_id -isnot [string] -or
           $receipt.persistence_scope -isnot [string] -or $receipt.persisted -isnot [bool] -or
           ($receipt.generation -isnot [long] -and $receipt.generation -isnot [int]) -or
           $request.operation -ceq 'status' -or $receipt.app_session_id -cne $request.app_session_id -or
           $receipt.generation -ne $request.generation -or $receipt.last_request_id -cne $request.request_id -or
           $receipt.persistence_scope -cne $PersistenceScope){throw 'Original LSL request receipt mismatch; do not replay.'}
        if($PersistenceScope -ceq 'runtime-only' -and $receipt.persisted -ne $false){throw 'Runtime-only request changed persistence.'}
    }elseif($request.operation -ceq 'status' -and $receipt.response_status -ceq 'status'){
        if($receipt.persistence_scope -cne $PersistenceScope){throw 'LSL scope readback unavailable.'}
    }elseif($receipt.response_status -cne 'rejected'){throw 'Wrong LSL command disposition; do not replay.'}
    $status = [string]$receipt.response_status
    if ((Invoke-FixedRead -ReadArguments @('shell', 'getprop', 'ro.serialno')) -cne $CanonicalSerial -or
        (Invoke-FixedRead -ReadArguments @('shell', 'cat', '/proc/sys/kernel/random/boot_id')) -cne $BootId) {
        throw 'Target changed across invocation; app receipt retained without current acceptance.'
    }
    # Raw config identifiers stay in private receipts. Accepted is reducer admission;
    # worker readiness requires its actual last_effective_request_id/state readback.
    [pscustomobject]@{ status = $status; receipt_directory = $directory; application_effect_readback = 'app-receipt.json'; mutation_replayed = $false } | ConvertTo-Json
} catch {
    $failure = [ordered]@{ status = $status; sent = $sent; error = $_.Exception.Message; retry_forbidden = $true; at_utc = [DateTime]::UtcNow.ToString('o') }
    Write-PrivateBytes 'host-failure.json' ($utf8.GetBytes(($failure | ConvertTo-Json)))
    throw
} finally { $binaryLock.Dispose() }
