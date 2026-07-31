Set-StrictMode -Version Latest

function Invoke-PackageUpdateLabsFeedWriterSecretCleanup {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [byte[]]$KeyBytes,
        [Parameter(Mandatory)]
        [string]$KeyPath,
        [Parameter(Mandatory)]
        [string]$KnownHostsPath,
        [AllowNull()]
        [Management.Automation.ErrorRecord]$PrimaryError,
        [scriptblock]$FileExists,
        [scriptblock]$GetFileLength,
        [scriptblock]$WriteZeros,
        [scriptblock]$DeleteFile
    )

    if ($null -eq $FileExists) {
        $FileExists = { param($Path) [IO.File]::Exists($Path) }
    }
    if ($null -eq $GetFileLength) {
        $GetFileLength = { param($Path) (Get-Item -LiteralPath $Path).Length }
    }
    if ($null -eq $WriteZeros) {
        $WriteZeros = {
            param($Path, $Length)
            [IO.File]::WriteAllBytes($Path, [byte[]]::new($Length))
        }
    }
    if ($null -eq $DeleteFile) {
        $DeleteFile = { param($Path) [IO.File]::Delete($Path) }
    }

    Remove-Item Env:\GIT_SSH_COMMAND -ErrorAction SilentlyContinue
    Remove-Item Env:\FEED_DEPLOY_KEY_BASE64 -ErrorAction SilentlyContinue
    if ($null -ne $KeyBytes) {
        [Array]::Clear($KeyBytes, 0, $KeyBytes.Length)
    }

    $cleanupFailures = [Collections.Generic.List[string]]::new()
    $keyExists = $false
    try {
        $keyExists = [bool](& $FileExists $KeyPath)
    } catch {
        $cleanupFailures.Add("key_exists_probe_failed")
    }
    if ($keyExists) {
        try {
            $length = [int64](& $GetFileLength $KeyPath)
            if ($length -lt 0) { throw "Negative key length." }
            & $WriteZeros $KeyPath $length
        } catch {
            $cleanupFailures.Add("key_wipe_failed")
        }
    }
    try {
        & $DeleteFile $KeyPath
    } catch {
        $cleanupFailures.Add("key_delete_failed")
    }

    try {
        & $DeleteFile $KnownHostsPath
    } catch {
        $cleanupFailures.Add("known_hosts_delete_failed")
    }

    $failureLabels = @($cleanupFailures | Sort-Object -Unique)
    if ($null -ne $PrimaryError) {
        if ($failureLabels.Count -gt 0) {
            Write-Warning (
                "Labs feed writer cleanup limitations: " +
                ($failureLabels -join ",")
            )
        }
        throw $PrimaryError
    }
    if ($failureLabels.Count -gt 0) {
        throw (
            "Labs feed writer cleanup failed: " +
            ($failureLabels -join ",")
        )
    }

    [pscustomobject][ordered]@{
        schema = "rusty.quest.package_update_labs_feed_writer_cleanup.v1"
        cleanup_complete = $true
        key_bytes_cleared = $null -eq $KeyBytes -or
            @($KeyBytes | Where-Object { $_ -ne 0 }).Count -eq 0
        cleanup_failures = @()
    }
}
