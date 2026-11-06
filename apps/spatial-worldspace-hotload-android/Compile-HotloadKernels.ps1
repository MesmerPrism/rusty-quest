[CmdletBinding()]
param([Parameter(Mandatory)][string]$Glslc,[Parameter(Mandatory)][string]$AssetsDirectory,[Parameter(Mandatory)][string]$CatalogDirectory)
$ErrorActionPreference='Stop'
$root=Join-Path $PSScriptRoot 'app/src/main/cpp'
$dest=Join-Path $AssetsDirectory 'hotload/native'
New-Item -ItemType Directory -Force $dest,$CatalogDirectory | Out-Null
$entries=@()
$tools=Split-Path -Parent $Glslc
$validator=Join-Path $tools 'spirv-val.exe'
$disassembler=Join-Path $tools 'spirv-dis.exe'
function AbiMatch([string]$text,[string]$pattern) {
 $m=[regex]::Match($text,$pattern,[Text.RegularExpressions.RegexOptions]::Multiline)
 if(-not $m.Success){throw "SPIR-V ABI mismatch: $pattern"};return $m.Groups[1].Value
}
foreach($tag in @('a','b')) {
 $source=Join-Path $root "hotload_$tag.comp"
 $output=Join-Path $dest "$tag.spv"
 & $Glslc --target-env=vulkan1.1 -fshader-stage=compute -Werror $source -o $output
 if($LASTEXITCODE -ne 0){throw 'Hotload shader compilation failed'}
 & $validator --target-env vulkan1.1 $output
 if($LASTEXITCODE -ne 0){throw 'SPIR-V validation failed'}
 $dis=(& $disassembler --raw-id $output) -join "`n"
 if($LASTEXITCODE -ne 0){throw 'SPIR-V disassembly failed'}
 # Follow the exact type/decorations graph, rejecting extra descriptor variables.
 if([regex]::Matches($dis,'OpDecorate %\d+ DescriptorSet').Count -ne 1 -or [regex]::Matches($dis,'OpDecorate %\d+ Binding').Count -ne 1 -or [regex]::Matches($dis,'OpVariable %\d+ PushConstant').Count -ne 1){throw 'Unexpected descriptors/push constants'}
 $entry=AbiMatch $dis 'OpEntryPoint GLCompute (%\d+) "main"'
 [void](AbiMatch $dis ("OpExecutionMode "+$entry+' LocalSize (2) 1 1'))
 $float=AbiMatch $dis '^\s*(%\d+) = OpTypeFloat 32$'
 $vec=AbiMatch $dis ('^\s*(%\d+) = OpTypeVector '+$float+' 4$')
 $array=AbiMatch $dis ('^\s*(%\d+) = OpTypeRuntimeArray '+$vec+'$')
 [void](AbiMatch $dis ('OpDecorate '+$array+' ArrayStride (16)$'))
 $ssbo=AbiMatch $dis ('^\s*(%\d+) = OpTypeStruct '+$array+'$')
 [void](AbiMatch $dis ('OpMemberDecorate '+$ssbo+' 0 Offset (0)$'))
 [void](AbiMatch $dis ('OpDecorate '+$ssbo+' (Block)$'))
 $ptr=AbiMatch $dis ('^\s*(%\d+) = OpTypePointer StorageBuffer '+$ssbo+'$')
 $var=AbiMatch $dis ('^\s*(%\d+) = OpVariable '+$ptr+' StorageBuffer$')
 [void](AbiMatch $dis ('OpDecorate '+$var+' DescriptorSet (0)$'))
 [void](AbiMatch $dis ('OpDecorate '+$var+' Binding (0)$'))
 $uint=AbiMatch $dis '^\s*(%\d+) = OpTypeInt 32 0$'
 $uvec=AbiMatch $dis ('^\s*(%\d+) = OpTypeVector '+$uint+' 4$')
 $push=AbiMatch $dis ('^\s*(%\d+) = OpTypeStruct '+$uvec+' '+$vec+'$')
 [void](AbiMatch $dis ('OpMemberDecorate '+$push+' 0 Offset (0)$'))
 [void](AbiMatch $dis ('OpMemberDecorate '+$push+' 1 Offset (16)$'))
 $pushptr=AbiMatch $dis ('^\s*(%\d+) = OpTypePointer PushConstant '+$push+'$')
 [void](AbiMatch $dis ('^\s*(%\d+) = OpVariable '+$pushptr+' PushConstant$'))
 $bytes=[IO.File]::ReadAllBytes($output)
 if($bytes.Length -lt 20 -or $bytes.Length -gt 65536 -or $bytes.Length%4 -ne 0 -or [BitConverter]::ToUInt32($bytes,0) -ne 0x07230203){throw 'Invalid compiled SPIR-V'}
 $entries+= [ordered]@{asset="hotload/native/$tag.spv";bytes=$bytes.Length;sha256=(Get-FileHash $output -Algorithm SHA256).Hash.ToLowerInvariant();source_sha256=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant();abi='rusty.quest.hotload.output.v1';set=0;binding=0;stride=16;rows_per_instance=4;instance_count=2;push_bytes=32;local_size=@(2,1,1)}
}
$header="#pragma once`nnamespace rq::workload {`nstruct HotloadCatalogEntry { const char* asset; unsigned bytes; const char* sha256; unsigned abi; };`ninline constexpr HotloadCatalogEntry hotloadCatalog[2] = {`n"
foreach($e in $entries){$header+=' {"'+$e.asset+'",'+$e.bytes+',"'+$e.sha256+'",1},'+"`n"}
$header+="};`n}`n"
[IO.File]::WriteAllText((Join-Path $CatalogDirectory 'hotload_catalog.h'),$header,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $dest 'catalog.json'),([ordered]@{schema='rusty.quest.hotload.catalog.v1';entries=$entries}|ConvertTo-Json -Depth 8)+"`n",[Text.UTF8Encoding]::new($false))
