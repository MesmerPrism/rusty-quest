[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 4173,
    [string]$PythonPath = 'python',
    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repoRoot 'apps\spatial-vr-strobe-android\profile-editor-web'
if (-not (Test-Path -LiteralPath (Join-Path $webRoot 'index.html') -PathType Leaf)) {
    throw "VR Strobe profile editor was not found at '$webRoot'."
}

$pythonCommand = Get-Command -Name $PythonPath -ErrorAction SilentlyContinue
if ($null -eq $pythonCommand) {
    if (-not (Test-Path -LiteralPath $PythonPath -PathType Leaf)) {
        throw "Python was not found at '$PythonPath'."
    }
    $resolvedPython = (Resolve-Path -LiteralPath $PythonPath).Path
} else {
    $resolvedPython = $pythonCommand.Source
}

$existingListener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($null -ne $existingListener) {
    throw "Port $Port is already in use on 127.0.0.1. Choose another -Port."
}

$arguments = @('-m', 'http.server', $Port, '--bind', '127.0.0.1', '--directory', $webRoot)
$process = Start-Process -FilePath $resolvedPython -ArgumentList $arguments -WindowStyle Hidden -PassThru
$url = "http://127.0.0.1:$Port/"

$deadline = [DateTime]::UtcNow.AddSeconds(8)
do {
    Start-Sleep -Milliseconds 200
    if ($process.HasExited) { throw "Profile editor server exited with code $($process.ExitCode)." }
    $listener = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
} while ($null -eq $listener -and [DateTime]::UtcNow -lt $deadline)
if ($null -eq $listener) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "Profile editor server did not listen on $url within 8 seconds."
}

if (-not $NoBrowser) { Start-Process $url | Out-Null }

[pscustomobject]@{
    Url = $url
    ProcessId = $process.Id
    WebRoot = $webRoot
    StopCommand = "Stop-Process -Id $($process.Id)"
}
