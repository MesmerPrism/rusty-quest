[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('Inspect','PrepareFolder','StageVideo','Install','Launch','Observe','Status','Markers')]
    [string]$Action,
    [string]$Serial = '',
    [string]$Apk = '',
    [string]$Video = '',
    [ValidateSet('flat','equirect-180','equirect-360')]
    [string]$Shape = 'equirect-360',
    [ValidateSet('mono','side-by-side-left-right','top-bottom')]
    [string]$Stereo = 'top-bottom',
    [string]$FileManagerCli = '',
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$FileManagerSha256 = '',
    [string]$Adb = 'adb',
    [switch]$ConfirmAdbDirectoryFallback,
    [ValidateRange(100,10000)]
    [int]$LogcatLines = 3000
)

$ErrorActionPreference = 'Stop'
$package = 'io.github.mesmerprism.rustyquest.spatial_video_control_example'
$activity = 'io.github.mesmerprism.rustyquest.spatial_video_control.SpatialVideoControlActivity'
$schema = 'rusty.quest.spatial_video_player.operator_receipt.v1'

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Require-Serial {
    if ($Serial -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{3,63}$') {
        throw 'A valid exact Quest serial is required.'
    }
}

function Resolve-Qfm {
    if ([string]::IsNullOrWhiteSpace($FileManagerCli) -or
        -not (Test-Path -LiteralPath $FileManagerCli -PathType Leaf)) {
        throw 'FileManagerCli must identify the pinned QuestIonAble File Manager CLI.'
    }
    $resolved = (Resolve-Path -LiteralPath $FileManagerCli).Path
    if ([string]::IsNullOrWhiteSpace($FileManagerSha256) -or
        (Get-Sha256 $resolved) -cne $FileManagerSha256) {
        throw 'QuestIonAble File Manager CLI SHA-256 pin mismatch.'
    }
    return $resolved
}

function Require-Apk {
    if ([string]::IsNullOrWhiteSpace($Apk) -or -not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw 'Apk must identify one release APK.'
    }
    return (Resolve-Path -LiteralPath $Apk).Path
}

function Invoke-Qfm([string[]]$Arguments) {
    $qfm = Resolve-Qfm
    $output = @(& $qfm @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
    return $output
}

switch ($Action) {
    'Inspect' {
        $apkPath = Require-Apk
        $result = Invoke-Qfm @('apk','inspect','--file',$apkPath,'--json')
        $result
    }
    'PrepareFolder' {
        Require-Serial
        if (-not $ConfirmAdbDirectoryFallback) {
            throw 'PrepareFolder requires -ConfirmAdbDirectoryFallback for the documented QFM directory-create gap.'
        }
        $paths = @(
            '/sdcard/Documents/RustySpatialMedia',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/flat/mono',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/flat/side-by-side-left-right',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/flat/top-bottom',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-180/mono',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-180/side-by-side-left-right',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-180/top-bottom',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-360/mono',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-360/side-by-side-left-right',
            '/sdcard/Documents/RustySpatialMedia/plain-videos/equirect-360/top-bottom'
        )
        $arguments = @('-s',$Serial,'shell','mkdir','-p') + $paths
        $output = @(& $Adb @arguments 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ($output -join "`n") }
        $readback = Invoke-Qfm @('files','list','--serial',$Serial,'--path','/sdcard/Documents/RustySpatialMedia','--json')
        [ordered]@{
            schema = $schema
            action = 'prepare-folder'
            serial = $Serial
            provider_gap = 'qfm-missing-fixed-directory-taxonomy-create-v1'
            exact_paths = $paths
            readback = ($readback -join "`n") | ConvertFrom-Json
        } | ConvertTo-Json -Depth 20
    }
    'StageVideo' {
        Require-Serial
        if ([string]::IsNullOrWhiteSpace($Video) -or -not (Test-Path -LiteralPath $Video -PathType Leaf)) {
            throw 'Video must identify one local media file.'
        }
        $videoPath = (Resolve-Path -LiteralPath $Video).Path
        $name = [IO.Path]::GetFileName($videoPath)
        if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9._ -]{0,126}\.mp4$') {
            throw 'Video filename must be a bounded .mp4 basename.'
        }
        $remote = "/sdcard/Documents/RustySpatialMedia/plain-videos/$Shape/$Stereo/$name"
        $result = Invoke-Qfm @('files','push','--serial',$Serial,'--file',$videoPath,'--remote',$remote)
        [ordered]@{
            schema = $schema
            action = 'stage-video'
            serial = $Serial
            source_sha256 = Get-Sha256 $videoPath
            source_size = (Get-Item -LiteralPath $videoPath).Length
            remote_path = $remote
            provider_output = $result -join "`n"
        } | ConvertTo-Json -Depth 10
    }
    'Install' {
        Require-Serial
        $apkPath = Require-Apk
        $install = Invoke-Qfm @('apk','install','--serial',$Serial,'--file',$apkPath,'--json')
        $observe = Invoke-Qfm @('apk','observe','--serial',$Serial,'--file',$apkPath,'--json')
        [ordered]@{
            schema = $schema
            action = 'install'
            serial = $Serial
            apk_sha256 = Get-Sha256 $apkPath
            install = ($install -join "`n") | ConvertFrom-Json
            observation = ($observe -join "`n") | ConvertFrom-Json
        } | ConvertTo-Json -Depth 30
    }
    'Launch' {
        Require-Serial
        $apkPath = Require-Apk
        $launch = Invoke-Qfm @('apk','launch','--serial',$Serial,'--file',$apkPath,'--json')
        [ordered]@{
            schema = $schema
            action = 'launch'
            serial = $Serial
            package_name = $package
            activity = $activity
            provider_receipt = ($launch -join "`n") | ConvertFrom-Json
        } | ConvertTo-Json -Depth 20
    }
    'Observe' {
        Require-Serial
        $apkPath = Require-Apk
        Invoke-Qfm @('apk','observe','--serial',$Serial,'--file',$apkPath,'--json')
    }
    'Status' {
        Require-Serial
        Invoke-Qfm @('integration','observe','--serial',$Serial,'--json')
    }
    'Markers' {
        Require-Serial
        $lines = @(& $Adb -s $Serial logcat -d -t $LogcatLines -v threadtime 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ($lines -join "`n") }
        $matched = @($lines | Where-Object {
            $_ -match 'RustyQuestVideoControl|RqConnectionHub|rusty-spatial-video-library|AndroidRuntime'
        })
        [ordered]@{
            schema = $schema
            action = 'bounded-markers'
            serial = $Serial
            line_count = $matched.Count
            fatal_count = @($matched | Where-Object { $_ -match 'FATAL EXCEPTION|AndroidRuntime: E' }).Count
            lines = $matched
        } | ConvertTo-Json -Depth 10
    }
}
