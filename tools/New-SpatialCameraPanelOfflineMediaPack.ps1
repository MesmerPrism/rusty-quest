param(
    [Parameter(Mandatory=$true)]
    [string]$SourcePath,
    [Parameter(Mandatory=$true)]
    [string]$OutDir,
    [Parameter(Mandatory=$true)]
    [ValidatePattern("^[a-z0-9][a-z0-9._-]{0,95}$")]
    [string]$PackId,
    [Parameter(Mandatory=$true)]
    [ValidateSet("flat", "equirect-180", "equirect-360")]
    [string]$Shape,
    [Parameter(Mandatory=$true)]
    [ValidateSet("mono", "side-by-side-left-right", "top-bottom")]
    [string]$Stereo,
    [string]$KeyHex = $env:RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX,
    [ValidateRange(1, 64)]
    [int]$ChunkSizeMiB = 16,
    [string]$Ffprobe = $env:RUSTY_QUEST_FFPROBE
)

$ErrorActionPreference = "Stop"
$schemaId = "rusty.quest.offline_immersive_media_pack.v1"

function Resolve-ToolPath {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [string]$Value,
        [string]$DefaultPath
    )
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
    if (-not [string]::IsNullOrWhiteSpace($DefaultPath) -and
        (Test-Path -LiteralPath $DefaultPath -PathType Leaf)) {
        return (Resolve-Path -LiteralPath $DefaultPath).Path
    }
    $fallback = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $fallback) {
        throw "$Name not found. Pass -$Name or set the matching environment variable."
    }
    return $fallback.Source
}

function ConvertTo-LowerHex {
    param([Parameter(Mandatory=$true)][byte[]]$Bytes)
    return [Convert]::ToHexString($Bytes).ToLowerInvariant()
}

function Get-AadBytes {
    param(
        [Parameter(Mandatory=$true)][int]$Index,
        [Parameter(Mandatory=$true)][long]$Offset,
        [Parameter(Mandatory=$true)][int]$PlaintextLength,
        [Parameter(Mandatory=$true)][long]$SourceSize,
        [Parameter(Mandatory=$true)][string]$SourceSha256,
        [Parameter(Mandatory=$true)][int]$Width,
        [Parameter(Mandatory=$true)][int]$Height
    )
    $aad = @(
        $schemaId,
        $PackId,
        $Index.ToString(),
        $Offset.ToString(),
        $PlaintextLength.ToString(),
        $SourceSize.ToString(),
        $SourceSha256,
        $Shape,
        $Stereo,
        $Width.ToString(),
        $Height.ToString()
    ) -join "|"
    return [Text.Encoding]::UTF8.GetBytes($aad)
}

if ($PSVersionTable.PSEdition -ne "Core" -or $PSVersionTable.PSVersion.Major -lt 7) {
    throw "Offline media pack creation requires PowerShell 7 or newer (pwsh)."
}
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "SourcePath not found or not a file: $SourcePath"
}
if ([string]::IsNullOrWhiteSpace($KeyHex) -or
    $KeyHex.Trim() -notmatch "^[a-fA-F0-9]{64}$") {
    throw "Provide a 32-byte key with -KeyHex or RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX."
}

$resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
$resolvedFfprobe = Resolve-ToolPath `
    -Name "ffprobe" `
    -Value $Ffprobe `
    -DefaultPath "S:\Work\tools\ffmpeg\bin\ffprobe.exe"
$probeOutput = & $resolvedFfprobe `
    -v error `
    -select_streams "v:0" `
    -show_entries "stream=width,height" `
    -of json `
    $resolvedSource
if ($LASTEXITCODE -ne 0) {
    throw "ffprobe failed for the source video."
}
$probe = ($probeOutput -join "`n") | ConvertFrom-Json
$stream = @($probe.streams) | Select-Object -First 1
$widthPx = [int]$stream.width
$heightPx = [int]$stream.height
if ($widthPx -lt 1 -or $heightPx -lt 1 -or $widthPx -gt 16384 -or $heightPx -gt 16384) {
    throw "ffprobe returned unsupported video dimensions: ${widthPx}x${heightPx}"
}
if ($Stereo -eq "side-by-side-left-right" -and ($widthPx % 2) -ne 0) {
    throw "Side-by-side video width must be even: $widthPx"
}
if ($Stereo -eq "top-bottom" -and ($heightPx % 2) -ne 0) {
    throw "Top-bottom video height must be even: $heightPx"
}

$outRoot = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
$finalPackDir = Join-Path $outRoot $PackId
if (Test-Path -LiteralPath $finalPackDir) {
    throw "Pack output already exists; choose a new content-addressed PackId: $finalPackDir"
}
$buildingDir = Join-Path $outRoot (".$PackId.building-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $buildingDir | Out-Null

$sourceInfo = Get-Item -LiteralPath $resolvedSource
$sourceSize = [long]$sourceInfo.Length
$sourceSha256 = (Get-FileHash -LiteralPath $resolvedSource -Algorithm SHA256).Hash.ToLowerInvariant()
$chunkSizeBytes = $ChunkSizeMiB * 1024 * 1024
$key = [Convert]::FromHexString($KeyHex.Trim())
$aes = [Security.Cryptography.AesGcm]::new($key, 16)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$chunks = [Collections.Generic.List[object]]::new()
$sourceStream = [IO.File]::OpenRead($resolvedSource)

try {
    $index = 0
    $offset = 0L
    while ($offset -lt $sourceSize) {
        $expectedLength = [int][Math]::Min([long]$chunkSizeBytes, $sourceSize - $offset)
        $plaintext = [byte[]]::new($expectedLength)
        $totalRead = 0
        while ($totalRead -lt $expectedLength) {
            $read = $sourceStream.Read($plaintext, $totalRead, $expectedLength - $totalRead)
            if ($read -eq 0) {
                throw "Unexpected end of source at byte $offset."
            }
            $totalRead += $read
        }

        $nonce = [byte[]]::new(12)
        $random.GetBytes($nonce)
        $ciphertext = [byte[]]::new($expectedLength)
        $tag = [byte[]]::new(16)
        $aad = Get-AadBytes `
            -Index $index `
            -Offset $offset `
            -PlaintextLength $expectedLength `
            -SourceSize $sourceSize `
            -SourceSha256 $sourceSha256 `
            -Width $widthPx `
            -Height $heightPx
        $aes.Encrypt($nonce, $plaintext, $ciphertext, $tag, $aad)

        $combined = [byte[]]::new($ciphertext.Length + $tag.Length)
        [Buffer]::BlockCopy($ciphertext, 0, $combined, 0, $ciphertext.Length)
        [Buffer]::BlockCopy($tag, 0, $combined, $ciphertext.Length, $tag.Length)
        $roundTrip = [byte[]]::new($expectedLength)
        $aes.Decrypt($nonce, $ciphertext, $tag, $roundTrip, $aad)
        $plaintextHash = ConvertTo-LowerHex ([Security.Cryptography.SHA256]::HashData($plaintext))
        $roundTripHash = ConvertTo-LowerHex ([Security.Cryptography.SHA256]::HashData($roundTrip))
        if ($plaintextHash -ne $roundTripHash) {
            throw "AES-GCM verification failed for chunk $index."
        }

        $fileName = "chunk-{0:D6}.bin" -f $index
        $chunkPath = Join-Path $buildingDir $fileName
        [IO.File]::WriteAllBytes($chunkPath, $combined)
        $writtenHash = (Get-FileHash -LiteralPath $chunkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $expectedHash = ConvertTo-LowerHex ([Security.Cryptography.SHA256]::HashData($combined))
        if ($writtenHash -ne $expectedHash) {
            throw "Ciphertext write verification failed for chunk $index."
        }
        $chunks.Add([ordered]@{
            index = $index
            plaintext_offset = $offset
            plaintext_length = $expectedLength
            file = $fileName
            nonce_base64 = [Convert]::ToBase64String($nonce)
            ciphertext_sha256 = $writtenHash
        })

        [Array]::Clear($plaintext)
        [Array]::Clear($roundTrip)
        $offset += $expectedLength
        $index += 1
    }
} finally {
    $sourceStream.Dispose()
    $random.Dispose()
    $aes.Dispose()
    [Array]::Clear($key)
}

$manifest = [ordered]@{
    schema = $schemaId
    pack_id = $PackId
    encryption = [ordered]@{
        algorithm = "AES-256-GCM"
        key_bits = 256
        nonce_bytes = 12
        tag_bytes = 16
        aad_contract = "schema|pack_id|index|plaintext_offset|plaintext_length|source_size|source_sha256|projection_shape|stereo_layout|width_px|height_px"
    }
    source = [ordered]@{
        size_bytes = $sourceSize
        sha256 = $sourceSha256
        width_px = $widthPx
        height_px = $heightPx
        projection_shape = $Shape
        stereo_layout = $Stereo
    }
    chunk_size_bytes = $chunkSizeBytes
    chunks = $chunks
}
$manifestPath = Join-Path $buildingDir "manifest.json"
$manifestJson = $manifest | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText(
    $manifestPath,
    $manifestJson + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)

$manifestCheck = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
if ($manifestCheck.schema -ne $schemaId -or
    $manifestCheck.pack_id -ne $PackId -or
    [long]$manifestCheck.source.size_bytes -ne $sourceSize -or
    @($manifestCheck.chunks).Count -ne $chunks.Count) {
    throw "Final manifest verification failed."
}

Move-Item -LiteralPath $buildingDir -Destination $finalPackDir
$result = [ordered]@{
    schema = $schemaId
    pack_id = $PackId
    pack_directory = $finalPackDir
    manifest = (Join-Path $finalPackDir "manifest.json")
    encrypted_chunks = $chunks.Count
    encrypted_bytes = (
        Get-ChildItem -LiteralPath $finalPackDir -Filter "chunk-*.bin" |
            Measure-Object -Property Length -Sum
    ).Sum
    source_bytes = $sourceSize
    source_sha256 = $sourceSha256
    width_px = $widthPx
    height_px = $heightPx
    projection_shape = $Shape
    stereo_layout = $Stereo
    plaintext_files_written = $false
    embedded_key_written_to_pack = $false
}
$result | ConvertTo-Json -Depth 4
