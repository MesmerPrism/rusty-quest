param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath,
    [string]$PackDirectory = "",
    [string]$PackId = "",
    [switch]$PackagedInApk,
    [string]$Adb = $env:RUSTY_QUEST_ADB,
    [string]$Serial = $env:RUSTY_QUEST_SERIAL,
    [string]$AdbServerPort = $env:RUSTY_QUEST_ADB_SERVER_PORT,
    [string]$PackageName = "io.github.mesmerprism.rustyquest.spatial_camera_panel",
    [string]$ActivityName = "io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraPanelActivity",
    [switch]$Launch,
    [ValidateRange(3, 60)]
    [int]$ObserveSeconds = 20,
    [string]$Out = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ToolPath {
    param([string]$Name, [string]$Value, [string]$DefaultPath)
    if (-not [string]::IsNullOrWhiteSpace($Value)) {
        if (Test-Path -LiteralPath $Value -PathType Leaf) {
            return (Resolve-Path -LiteralPath $Value).Path
        }
        $command = Get-Command $Value -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
        throw "$Name not found: $Value"
    }
    if (Test-Path -LiteralPath $DefaultPath -PathType Leaf) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
    }
    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found."
    }
    return $fallback.Source
}

function Invoke-Adb {
    param([string]$Name, [string[]]$Arguments, [switch]$AllowFailure)
    $allArgs = @()
    if ($null -ne $script:AdbPort) {
        $allArgs += @("-P", $script:AdbPort)
    }
    $allArgs += @("-s", $script:Serial)
    $allArgs += $Arguments
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $script:AdbPath @allArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exitCode`n$($output -join "`n")"
    }
    return [pscustomobject]@{
        name = $Name
        exit_code = $exitCode
        output = ($output -join "`n")
    }
}

if ([string]::IsNullOrWhiteSpace($Serial)) {
    throw "-Serial or RUSTY_QUEST_SERIAL is required; device work must use adb -s <serial>."
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath"
}
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedPack = ""
$manifestPath = ""
if (-not [string]::IsNullOrWhiteSpace($PackDirectory)) {
    if (-not (Test-Path -LiteralPath $PackDirectory -PathType Container)) {
        throw "Pack directory not found: $PackDirectory"
    }
    $resolvedPack = (Resolve-Path -LiteralPath $PackDirectory).Path
    $manifestPath = Join-Path $resolvedPack "manifest.json"
} elseif (-not $PackagedInApk) {
    throw "-PackDirectory is required unless -PackagedInApk is supplied."
}
if (-not [string]::IsNullOrWhiteSpace($manifestPath) -and
    -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Pack manifest not found: $manifestPath"
}
$manifest =
    if ([string]::IsNullOrWhiteSpace($manifestPath)) {
        $null
    } else {
        Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    }
if ($null -eq $manifest -and $PackagedInApk) {
    if ($PackId -notmatch "^[a-z0-9][a-z0-9._-]{0,95}$") {
        throw "-PackId is required when verifying a self-contained APK without -PackDirectory."
    }
    Add-Type -AssemblyName System.IO.Compression
    $manifestEntryName = "assets/offline-media-packs/$PackId/manifest.json"
    $manifestArchive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        $manifestEntry = $manifestArchive.GetEntry($manifestEntryName)
        if ($null -eq $manifestEntry) {
            throw "APK is missing packaged media manifest: $manifestEntryName"
        }
        $manifestStream = $manifestEntry.Open()
        $manifestReader = [IO.StreamReader]::new($manifestStream, [Text.Encoding]::UTF8)
        try {
            $manifest = $manifestReader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $manifestReader.Dispose()
            $manifestStream.Dispose()
        }
    } finally {
        $manifestArchive.Dispose()
    }
}
if ($null -ne $manifest -and
    $manifest.schema -ne "rusty.quest.offline_immersive_media_pack.v1") {
    throw "Unsupported offline media pack schema."
}
$packIdFromManifest = if ($null -eq $manifest) { "" } else { [string]$manifest.pack_id }
if ([string]::IsNullOrWhiteSpace($PackId)) {
    $PackId = $packIdFromManifest
} elseif (-not [string]::IsNullOrWhiteSpace($packIdFromManifest) -and
    $PackId -ne $packIdFromManifest) {
    throw "-PackId does not match the pack manifest."
}
$packId = $PackId
if ($packId -notmatch "^[a-z0-9][a-z0-9._-]{0,95}$" -or
    (-not [string]::IsNullOrWhiteSpace($resolvedPack) -and
        (Split-Path -Leaf $resolvedPack) -ne $packId)) {
    throw "Pack directory name and manifest pack_id do not match."
}
$expectedFiles =
    if ($null -eq $manifest) {
        @()
    } else {
        @("manifest.json") + @($manifest.chunks | ForEach-Object { [string]$_.file })
    }
foreach ($name in $expectedFiles) {
    if ($name -notmatch "^(manifest\.json|chunk-[0-9]{6}\.bin)$" -or
        (-not [string]::IsNullOrWhiteSpace($resolvedPack) -and
            -not (Test-Path -LiteralPath (Join-Path $resolvedPack $name) -PathType Leaf))) {
        throw "Pack is missing an expected file: $name"
    }
}

$script:AdbPath = Resolve-ToolPath `
    -Name "adb" `
    -Value $Adb `
    -DefaultPath "S:\Work\tools\Android\windows-sdk\platform-tools\adb.exe"
$script:Serial = $Serial
$script:AdbPort = $null
if (-not [string]::IsNullOrWhiteSpace($AdbServerPort)) {
    $parsedPort = 0
    if (-not [int]::TryParse($AdbServerPort, [ref]$parsedPort) -or
        $parsedPort -lt 1 -or $parsedPort -gt 65535) {
        throw "ADB server port must be an integer from 1 to 65535."
    }
    $script:AdbPort = $parsedPort.ToString()
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($Out)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $Out = Join-Path $repoRoot "local-artifacts\offline-immersive-media\$timestamp-$packId-install.json"
}
$outPath = [IO.Path]::GetFullPath($Out)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outPath) | Out-Null

$commands = [Collections.Generic.List[object]]::new()
$embeddedFilesVerified = 0
if ($PackagedInApk) {
    Add-Type -AssemblyName System.IO.Compression
    $apkArchive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        foreach ($name in $expectedFiles) {
            $entryName = "assets/offline-media-packs/$packId/$name"
            $entry = $apkArchive.GetEntry($entryName)
            if ($null -eq $entry) {
                throw "APK is missing packaged encrypted media entry: $entryName"
            }
            $stream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try {
                $embeddedHash = [Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant()
            } finally {
                $sha.Dispose()
                $stream.Dispose()
            }
            if (-not [string]::IsNullOrWhiteSpace($resolvedPack)) {
                $hostFile = Join-Path $resolvedPack $name
                $expectedHash = (Get-FileHash -LiteralPath $hostFile -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($embeddedHash -ne $expectedHash) {
                    throw "Packaged encrypted media verification failed for $entryName."
                }
            } elseif ($name -ne "manifest.json") {
                $chunk = @($manifest.chunks | Where-Object { [string]$_.file -eq $name })
                if ($chunk.Count -ne 1 -or
                    $embeddedHash -ne ([string]$chunk[0].ciphertext_sha256).ToLowerInvariant()) {
                    throw "Packaged encrypted chunk hash does not match its signed APK manifest: $entryName"
                }
            }
            $embeddedFilesVerified += 1
        }
    } finally {
        $apkArchive.Dispose()
    }
}
$commands.Add((Invoke-Adb -Name "adb get-state" -Arguments @("get-state")))
$commands.Add((Invoke-Adb -Name "install exact APK" -Arguments @("install", "-r", $resolvedApk)))
$verifiedFiles = 0
if (-not $PackagedInApk) {
    $remoteRoot = "/sdcard/Android/obb/$PackageName/morphovision-media"
    $remotePack = "$remoteRoot/$packId"
    $remoteManifestProbe = Invoke-Adb `
        -Name "probe existing remote pack" `
        -Arguments @("shell", "test", "-f", "$remotePack/manifest.json") `
        -AllowFailure
    $commands.Add($remoteManifestProbe)
    if ($remoteManifestProbe.exit_code -eq 0) {
        $hostManifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $remoteManifestHashResult = Invoke-Adb `
            -Name "hash existing remote manifest" `
            -Arguments @("shell", "toybox", "sha256sum", "$remotePack/manifest.json")
        $commands.Add($remoteManifestHashResult)
        $remoteManifestHash = (($remoteManifestHashResult.output.Trim() -split "\s+")[0]).ToLowerInvariant()
        if ($remoteManifestHash -ne $hostManifestHash) {
            throw "A different pack already exists at $remotePack; refusing to overwrite it."
        }
    } else {
        $commands.Add((Invoke-Adb -Name "create media-pack root" -Arguments @("shell", "mkdir", "-p", $remoteRoot)))
        $commands.Add((Invoke-Adb -Name "push encrypted media pack" -Arguments @("push", $resolvedPack, "$remoteRoot/")))
    }
    foreach ($name in $expectedFiles) {
        $hostFile = Join-Path $resolvedPack $name
        $hostHash = (Get-FileHash -LiteralPath $hostFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $remoteHashResult = Invoke-Adb `
            -Name "verify encrypted pack file $name" `
            -Arguments @("shell", "toybox", "sha256sum", "$remotePack/$name")
        $commands.Add($remoteHashResult)
        $remoteHash = (($remoteHashResult.output.Trim() -split "\s+")[0]).ToLowerInvariant()
        if ($remoteHash -ne $hostHash) {
            throw "Encrypted pack transfer verification failed for $name."
        }
        $verifiedFiles += 1
    }
}

$markerOutput = ""
$targetPid = ""
$fatalOutput = ""
if ($Launch) {
    $commands.Add((Invoke-Adb -Name "stop target package" -Arguments @("shell", "am", "force-stop", $PackageName)))
    $commands.Add((Invoke-Adb -Name "clear target log buffer" -Arguments @("logcat", "-c")))
    $commands.Add((
        Invoke-Adb `
            -Name "launch encrypted immersive media pack" `
            -Arguments @(
                "shell", "am", "start",
                "-n", "$PackageName/$ActivityName",
                "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_ENABLED", "true",
                "--es", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_OFFLINE_PACK_ID", $packId,
                "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_AUTOPLAY", "true",
                "--ez", "io.github.mesmerprism.rustyquest.extra.IMMERSIVE_VIDEO_LOOP", "true"
            )
    ))
    Start-Sleep -Seconds $ObserveSeconds
    $pidResult = Invoke-Adb `
        -Name "resolve target pid" `
        -Arguments @("shell", "pidof", $PackageName)
    $commands.Add($pidResult)
    $targetPid = $pidResult.output.Trim()
    $markerResult = Invoke-Adb `
        -Name "read bounded playback markers" `
        -Arguments @("logcat", "-d", "-s", "RQSpatialCameraPanel:I", "*:S")
    $commands.Add($markerResult)
    $markerOutput = $markerResult.output
    $fatalResult = Invoke-Adb `
        -Name "read target fatal signals" `
        -Arguments @("logcat", "-d", "--pid=$targetPid", "*:E")
    $commands.Add($fatalResult)
    $fatalOutput = $fatalResult.output

    $requiredMarkers = @(
        "status=route-ready",
        "offlineEncryptedPack=true",
        "encryptedMediaPackagedInApk=$($PackagedInApk.ToString().ToLowerInvariant())",
        "status=encrypted-chunk-decrypted",
        "chunkAuthentication=aes-256-gcm",
        "plaintextFileWritten=false",
        "status=decoded-video-size",
        "status=first-frame-rendered",
        "advancing=true"
    )
    foreach ($required in $requiredMarkers) {
        if (-not $markerOutput.Contains($required)) {
            throw "Bounded playback validation is missing marker: $required"
        }
    }
    if ($markerOutput.Contains("status=playback-error") -or
        $markerOutput.Contains("status=encrypted-chunk-error") -or
        $fatalOutput -match "FATAL EXCEPTION|Fatal signal") {
        throw "Encrypted playback emitted a failure signal."
    }
}

$receipt = [ordered]@{
    schema = "rusty.quest.offline_immersive_media_pack.install_receipt.v1"
    created_at = [DateTimeOffset]::UtcNow.ToString("o")
    package = $PackageName
    apk_path = $resolvedApk
    apk_sha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    pack_id = $packId
    pack_directory = $resolvedPack
    pack_files_verified = $verifiedFiles
    pack_files_verified_inside_apk = $embeddedFilesVerified
    encrypted_pack_remote_root = $(if ($PackagedInApk) { "app-private-import-from-apk-assets" } else { $remotePack })
    encrypted_media_packaged_in_apk = [bool]$PackagedInApk
    plaintext_files_staged = 0
    key_transferred_separately = $false
    key_embedded_in_apk_prototype = $true
    launched = [bool]$Launch
    target_process_alive_after_observation = (-not [string]::IsNullOrWhiteSpace($targetPid))
    bounded_runtime_validation_passed = [bool](
        $Launch -and
        $markerOutput.Contains("status=first-frame-rendered") -and
        $markerOutput.Contains("advancing=true")
    )
    commands = $commands
}
$receipt | ConvertTo-Json -Depth 7 | Set-Content -Encoding UTF8 -LiteralPath $outPath
Write-Output $outPath
