# Validate a pinned library artifact before consuming its classes. Never extract arbitrary paths.
function Expand-ValidatedMediaStreamAar {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExpectedSha256,
        [Parameter(Mandatory)][string]$OutputRoot
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Media AAR input is missing.' }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $ExpectedSha256) { throw 'Media AAR input hash does not match its bound digest.' }
    if ((Get-Item -LiteralPath $Path).Length -gt 64MB) { throw 'Media AAR exceeds its artifact size limit.' }
    if (Test-Path -LiteralPath $OutputRoot) { throw 'AAR extraction requires a new output capsule.' }
    $cursor = [IO.Path]::GetFullPath($OutputRoot)
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'AAR output cannot traverse a reparse point.'
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    [void][IO.Directory]::CreateDirectory($OutputRoot)
    $retained = Join-Path $OutputRoot 'module.aar'
    [IO.File]::Copy([IO.Path]::GetFullPath($Path), [IO.Path]::GetFullPath($retained), $false)
    $zip = [IO.Compression.ZipFile]::OpenRead($retained)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if (-not $names.Add($entry.FullName) -or $entry.FullName -notin @('AndroidManifest.xml', 'classes.jar', 'R.txt') -or $entry.Length -gt 128MB) {
                throw 'Media AAR has an unexpected, duplicate, or oversized entry.'
            }
        }
        if (-not $names.Contains('AndroidManifest.xml') -or -not $names.Contains('classes.jar')) { throw 'Media AAR is incomplete.' }
        $reader = [IO.StreamReader]::new($zip.GetEntry('AndroidManifest.xml').Open())
        try { [xml]$manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest.SelectNodes('//uses-permission | //uses-permission-sdk-23 | //permission | //activity | //activity-alias | //service | //receiver | //provider').Count -ne 0) {
            throw 'Media AAR declares a host permission or component.'
        }
        $sdk = $manifest.SelectSingleNode('/manifest/uses-sdk')
        if ($null -eq $sdk -or $sdk.GetAttribute('minSdkVersion', 'http://schemas.android.com/apk/res/android') -cne '29') { throw 'Media AAR must declare minSdk 29.' }
        $classesPath = Join-Path $OutputRoot 'classes.jar'
        [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $classesPath, $false)
    } finally { $zip.Dispose() }
    $jar = [IO.Compression.ZipFile]::OpenRead($classesPath)
    try {
        if ($null -eq $jar.GetEntry('io/github/mesmerprism/rustyquest/media/AndroidMediaOwnerRegistry.class')) { throw 'Media AAR is missing its registry API.' }
        $classNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $jar.Entries) {
            if (-not $classNames.Add($entry.FullName) -or $entry.FullName -match '(?i)(\.so$|rustymanifold/broker/|media/conformance/)') { throw 'Media classes contain a duplicate, host class, or native library.' }
            if ($entry.FullName.EndsWith('.class')) {
                $stream = $entry.Open()
                try { $header = [byte[]]::new(8); $stream.ReadExactly($header) } finally { $stream.Dispose() }
                if (($header[6] * 256 + $header[7]) -ne 52) { throw 'Media library bytecode must target Java 8.' }
            }
        }
    } finally { $jar.Dispose() }
    [pscustomobject]@{
        aar_path=$retained; aar_sha256=$actual; classes_jar_path=$classesPath
        classes_jar_sha256=(Get-FileHash -LiteralPath $classesPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
