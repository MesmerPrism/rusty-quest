[CmdletBinding()]
param(
  [string]$Ffmpeg = 'ffmpeg'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion -lt [version]'7.6.0') {
  throw 'PowerShell 7.6 or newer is required.'
}

$ffmpegCommand = Get-Command $Ffmpeg -ErrorAction Stop
$appRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot = Join-Path $appRoot 'app\src\main\media-source'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('rusty-quest-synthetic-immersive-' + [guid]::NewGuid().ToString('N'))

$specifications = @(
  [pscustomobject]@{ Name = 'synthetic_180_mono_1s'; Layout = 'mono'; EyeWidth = 320; EyeHeight = 320 },
  [pscustomobject]@{ Name = 'synthetic_180_sbs_lr_1s'; Layout = 'sbs'; EyeWidth = 320; EyeHeight = 320 },
  [pscustomobject]@{ Name = 'synthetic_180_top_bottom_1s'; Layout = 'tb'; EyeWidth = 320; EyeHeight = 320 },
  [pscustomobject]@{ Name = 'synthetic_360_mono_1s'; Layout = 'mono'; EyeWidth = 640; EyeHeight = 320 },
  [pscustomobject]@{ Name = 'synthetic_360_sbs_lr_1s'; Layout = 'sbs'; EyeWidth = 640; EyeHeight = 320 },
  [pscustomobject]@{ Name = 'synthetic_360_top_bottom_1s'; Layout = 'tb'; EyeWidth = 640; EyeHeight = 320 }
)

try {
  $null = New-Item -ItemType Directory -Force -Path $temporaryRoot
  foreach ($specification in $specifications) {
    $output = Join-Path $temporaryRoot ($specification.Name + '.mp4')
    $firstSource = "testsrc2=size=$($specification.EyeWidth)x$($specification.EyeHeight):rate=30:duration=1"
    $arguments = @('-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', $firstSource)
    if ($specification.Layout -ne 'mono') {
      $secondSource = "smptebars=size=$($specification.EyeWidth)x$($specification.EyeHeight):rate=30:duration=1"
      $stack = if ($specification.Layout -eq 'sbs') { 'hstack' } else { 'vstack' }
      $arguments += @('-f', 'lavfi', '-i', $secondSource, '-filter_complex', "[0:v][1:v]${stack}=inputs=2[v]", '-map', '[v]')
    }
    $arguments += @(
      '-c:v', 'libx264',
      '-preset', 'veryslow',
      '-crf', '18',
      '-profile:v', 'baseline',
      '-level:v', '3.1',
      '-pix_fmt', 'yuv420p',
      '-threads', '1',
      '-fflags', '+bitexact',
      '-flags:v', '+bitexact',
      '-map_metadata', '-1',
      '-metadata', 'creation_time=1970-01-01T00:00:00Z',
      '-movflags', '+faststart',
      '-an',
      '-y',
      $output
    )
    & $ffmpegCommand.Source @arguments
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output -PathType Leaf)) {
      throw "ffmpeg failed for $($specification.Name)"
    }
    $encoded = [Convert]::ToBase64String(
      [IO.File]::ReadAllBytes($output),
      [Base64FormattingOptions]::InsertLineBreaks
    )
    Set-Content -LiteralPath (Join-Path $sourceRoot ($specification.Name + '.mp4.base64')) -Value $encoded -Encoding ascii
  }
}
finally {
  if (Test-Path -LiteralPath $temporaryRoot) {
    Remove-Item -Recurse -Force -LiteralPath $temporaryRoot
  }
}

Write-Output 'Generated six deterministic synthetic immersive media blobs.'
