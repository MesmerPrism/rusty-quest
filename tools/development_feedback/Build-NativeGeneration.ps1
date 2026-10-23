[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Rustc,
    [Parameter(Mandatory)][string]$AndroidNdk,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][int]$Generation,
    [Parameter(Mandatory)][string]$OutputRoot,
    [ValidateSet('A','B','WrongAbi')][string]$Variant='A',
    [string]$PluginSource,
    [ValidateRange(26,35)][int]$AndroidApi=26
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion -lt [version]'7.6'){throw 'Requires pwsh7.6 or newer'}
$clock=[Diagnostics.Stopwatch]::StartNew()
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $PluginSource){$PluginSource=Join-Path $repo 'examples/development-feedback-native/src/plugin.rs'}
$plugin=(Resolve-Path -LiteralPath $PluginSource).Path.Replace('\','/')
$abi=(Resolve-Path -LiteralPath (Join-Path $repo 'examples/development-feedback-native/src/abi.rs')).Path.Replace('\','/')
$compiler=(Resolve-Path -LiteralPath $Rustc).Path
$ndk=(Resolve-Path -LiteralPath $AndroidNdk).Path
$prebuilt=Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64'
$clang=Join-Path $prebuilt 'bin/clang.exe'
$readelf=Join-Path $prebuilt 'bin/llvm-readelf.exe'
$sysroot=Join-Path $prebuilt 'sysroot'
foreach($path in @($compiler,$clang,$readelf,(Join-Path $ndk 'source.properties'),(Join-Path $sysroot "usr/lib/aarch64-linux-android/$AndroidApi/libc.so"))){if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing declared tool/input: $path"}}
if(Test-Path -LiteralPath $OutputRoot){throw 'OutputRoot must be fresh'}
New-Item -ItemType Directory -Path $OutputRoot -Force|Out-Null
$output=(Resolve-Path -LiteralPath $OutputRoot).Path
function Record([string]$path){[ordered]@{path=$path;sha256=(Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()}}
$sources=@($abi,$plugin)
$before=@($sources|ForEach-Object {Record $_})
$wrapper=Join-Path $output 'generation.rs'
$variantValue=if($Variant -eq 'WrongAbi'){1}else{0}
$text="#![allow(dead_code)]`nconst GENERATION: u64 = $Generation;`nconst VARIANT: u32 = $variantValue;`n#[path = `"$abi`"] mod abi;`ninclude!(`"$plugin`");`n"
[IO.File]::WriteAllText($wrapper,$text,[Text.UTF8Encoding]::new($false))
$soname='librq_feedback_'+$Generation+'_'+[guid]::NewGuid().ToString('N')+'.so'
$artifact=Join-Path $output $soname
# Same rustc/NDK route as the canonical native feedback conformance build.
$arguments=@('--edition','2021','--crate-type','cdylib','--crate-name','rq_feedback_generation',$wrapper,'--target','aarch64-linux-android','-C','panic=abort','-C','opt-level=2','-C','strip=debuginfo','-D','warnings','-C',('linker='+$clang),'-C',('link-arg=--target=aarch64-linux-android'+$AndroidApi),'-C',('link-arg=--sysroot='+$sysroot),'-C',('link-arg=-Wl,-soname,'+$soname),'-o',$artifact)
if($Variant -eq 'B'){$arguments+=@('--cfg','phase_b')}
$log=@(& $compiler @arguments 2>&1|ForEach-Object ToString);$code=$LASTEXITCODE
$log|Set-Content -LiteralPath (Join-Path $output 'compile.txt')
if($code -ne 0){throw "Actual generation compilation failed: $code"}
$inspection=@(& $readelf '--file-header' '--dynamic' '--dyn-syms' '--wide' $artifact 2>&1|ForEach-Object ToString);$inspectCode=$LASTEXITCODE
$inspection|Set-Content -LiteralPath (Join-Path $output 'elf.txt')
$elf=$inspection -join "`n"
if($inspectCode -ne 0 -or $elf -notmatch 'Machine:\s+AArch64' -or $elf -notmatch 'Type:\s+DYN' -or $elf -notmatch 'GLOBAL\s+DEFAULT\s+\d+\s+rm030_feedback_api' -or -not $elf.Contains($soname)){throw 'Generation ELF identity/export inspection failed'}
$after=@($sources|ForEach-Object {Record $_})
if(($before|ConvertTo-Json -Compress) -cne ($after|ConvertTo-Json -Compress)){throw 'Generation source changed during build'}
[ordered]@{schema='rusty.quest.hotload_generation_build.v1';generation=$Generation.ToString();variant=$Variant;artifact=(Record $artifact);bytes=(Get-Item $artifact).Length;source_inputs=$before;source_inputs_unchanged=$true;tools=@($compiler,$clang,$readelf|ForEach-Object {Record $_});arguments=$arguments;compile_exit=$code;inspection_exit=$inspectCode;seconds=$clock.Elapsed.TotalSeconds;scope='Fresh compiled Android CPU generation and ELF inspection; not activation, scene or device proof'}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8NoBOM
Write-Output (Join-Path $output 'result.json')
