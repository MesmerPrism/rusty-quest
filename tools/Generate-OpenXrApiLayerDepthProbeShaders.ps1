[CmdletBinding()]
param(
  [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
  [string]$Glslc = 'S:\Work\tools\Android\windows-sdk\ndk\27.2.12479018\shader-tools\windows-x86_64\glslc.exe'
)

$ErrorActionPreference = 'Stop'
$shaderRoot = Join-Path $RepoRoot 'apps\openxr-api-layer-spatial-depth-probe-android\app\src\main\cpp'
$output = Join-Path $shaderRoot 'depth_gpu_handoff_spirv.hpp'
$specs = @(
  @{ Source = 'depth_gpu_consumer.vert'; Name = 'kDepthGpuVertexSpirv' },
  @{ Source = 'depth_gpu_copy.frag'; Name = 'kDepthGpuCopyFragmentSpirv' },
  @{ Source = 'depth_gpu_consumer.frag'; Name = 'kDepthGpuConsumerFragmentSpirv' }
)

if (-not (Test-Path -LiteralPath $Glslc -PathType Leaf)) {
  throw "glslc was not found at $Glslc"
}

$builder = [System.Text.StringBuilder]::new()
[void]$builder.AppendLine('#pragma once')
[void]$builder.AppendLine()
[void]$builder.AppendLine('#include <cstddef>')
[void]$builder.AppendLine('#include <cstdint>')
[void]$builder.AppendLine()

foreach ($spec in $specs) {
  $source = Join-Path $shaderRoot $spec.Source
  $temporary = Join-Path $env:TEMP ($spec.Source + '.spv')
  & $Glslc '--target-env=vulkan1.0' '-O' '-o' $temporary $source
  if ($LASTEXITCODE -ne 0) {
    throw "glslc failed for $source"
  }
  try {
    $bytes = [System.IO.File]::ReadAllBytes($temporary)
    if (($bytes.Length % 4) -ne 0) {
      throw "SPIR-V byte length is not word aligned for $source"
    }
    [void]$builder.AppendLine("inline constexpr uint32_t $($spec.Name)[] = {")
    for ($offset = 0; $offset -lt $bytes.Length; $offset += 4) {
      $word = [BitConverter]::ToUInt32($bytes, $offset)
      if ((($offset / 4) % 8) -eq 0) {
        [void]$builder.Append('  ')
      }
      [void]$builder.Append(('0x{0:x8}U' -f $word))
      if (($offset + 4) -lt $bytes.Length) {
        [void]$builder.Append(', ')
      }
      if ((($offset / 4) % 8) -eq 7 -or ($offset + 4) -ge $bytes.Length) {
        [void]$builder.AppendLine()
      }
    }
    [void]$builder.AppendLine('};')
    [void]$builder.AppendLine("inline constexpr size_t $($spec.Name)Size = sizeof($($spec.Name));")
    [void]$builder.AppendLine()
  } finally {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
  }
}

[System.IO.File]::WriteAllText($output, $builder.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Output $output
