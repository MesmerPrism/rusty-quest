[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Rustc,
    [string]$AndroidNdk = '',
    [int]$AndroidApi = 26,
    [string]$OutputRoot = (Join-Path $PSScriptRoot '.local')
)
$ErrorActionPreference = 'Stop'
$whole = [Diagnostics.Stopwatch]::StartNew()
$compiler = (Resolve-Path -LiteralPath $Rustc).Path
if ([IO.Path]::GetFileNameWithoutExtension($compiler) -ne 'rustc') { throw 'Select an explicit rustc executable' }
$run = Join-Path $OutputRoot ('run-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$run = (Resolve-Path -LiteralPath $run).Path
$timings = [Collections.Generic.List[object]]::new()
$commands = [Collections.Generic.List[object]]::new()
$android = [ordered]@{ requested = [bool]$AndroidNdk; ndk_requested = $AndroidNdk; preflight_passed = $false; passed = $false; target = 'aarch64-linux-android'; api = $AndroidApi; artifacts = @(); executed = $false; jni_host_tested = $false; device_tested = $false; quest_tested = $false }
$inputsBefore = @(); $inputsAfter = @(); $toolsBefore = @(); $toolsAfter = @(); $toolPaths = @($compiler)
$generatedBefore = [Collections.Generic.List[object]]::new(); $generatedAfter = @()
$failure = $null
$passed = $false
function Invoke-Tool([string]$Name, [string]$Executable, [string[]]$Arguments) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $lines = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
    $commands.Add([ordered]@{ name = $Name; executable = $Executable; arguments = $Arguments; exit_code = $code; elapsed_seconds = $clock.Elapsed.TotalSeconds })
    $lines | Set-Content -LiteralPath (Join-Path $run ($Name + '.txt')) -Encoding utf8NoBOM
    if ($code -ne 0) { throw "Tool failed: $Name ($code)" }
    return $lines
}
function Invoke-Compiler([string]$Name, [string[]]$CompilerArguments) {
    Invoke-Tool ($Name + '-compile') $compiler $CompilerArguments | Write-Output
    $timings.Add([ordered]@{ name = $Name; elapsed_seconds = $commands[$commands.Count - 1].elapsed_seconds })
}
function Get-Bindings([string[]]$Paths) {
    return @($Paths | Sort-Object -Unique | ForEach-Object {
        [ordered]@{ path = $_; sha256 = if (Test-Path -LiteralPath $_ -PathType Leaf) { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null } }
    })
}
function Write-Generation([string]$Name, [int]$Generation, [int]$Variant) {
    $wrapper = Join-Path $run ($Name + '.rs')
    $abiPath = (Join-Path $PSScriptRoot 'src/abi.rs') -replace '\\','/'
    $pluginPath = (Join-Path $PSScriptRoot 'src/plugin.rs') -replace '\\','/'
    @"
#![allow(dead_code)]
const GENERATION: u64 = $Generation;
const VARIANT: u32 = $Variant;
#[path = "$abiPath"] mod abi;
include!("$pluginPath");
"@ | Set-Content -LiteralPath $wrapper -Encoding utf8NoBOM
    return $wrapper
}
function Assert-AndroidElf([string]$Name, [string]$Artifact, [string]$Soname) {
    $inspection = (Invoke-Tool ($Name + '-elf') $readelf @('--file-header','--dynamic','--dyn-syms','--wide',$Artifact)) -join "`n"
    if ($inspection -notmatch 'Class:\s+ELF64' -or $inspection -notmatch 'Data:\s+2.s complement, little endian' -or $inspection -notmatch 'Machine:\s+AArch64' -or $inspection -notmatch 'Type:\s+DYN') { throw 'ELF architecture/type mismatch' }
    if ($inspection -notmatch ('\(SONAME\).*\[' + [regex]::Escape($Soname) + '\]')) { throw 'ELF SONAME mismatch' }
    if ($inspection -notmatch '(?m)^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+FUNC\s+GLOBAL\s+DEFAULT\s+(?!UND)\S+\s+rm030_feedback_api\s*$') { throw 'ELF API getter missing' }
    $dependencies = @([regex]::Matches($inspection,'\(NEEDED\).*\[([^\]]+)\]') | ForEach-Object { $_.Groups[1].Value })
    if ($dependencies.Count -eq 0 -or @($dependencies | Where-Object { $_ -notin @('libc.so','libdl.so','libm.so','liblog.so') }).Count -ne 0) { throw 'ELF dependencies outside platform CPU contract' }
    return $dependencies
}
try {
    Invoke-Tool 'rustc' $compiler @('-vV') | Write-Output
    $compilerHash = (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant()
    $inputPaths = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -File).FullName) + @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'include') -File).FullName) + @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -File).FullName) + @($PSCommandPath)
    $inputsBefore = Get-Bindings $inputPaths
    $toolsBefore = Get-Bindings $toolPaths
    if ($AndroidNdk) {
        $ndk = (Resolve-Path -LiteralPath $AndroidNdk).Path
        $android.ndk = $ndk
        $hostTag = if ($IsWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
        $toolSuffix = if ($IsWindows) { '.exe' } else { '' }
        $prebuilt = Join-Path $ndk ('toolchains/llvm/prebuilt/' + $hostTag)
        $sysroot = Join-Path $prebuilt 'sysroot'
        $clang = Join-Path $prebuilt ('bin/clang' + $toolSuffix)
        $linker = Join-Path $prebuilt ('bin/ld.lld' + $toolSuffix)
        $readelf = Join-Path $prebuilt ('bin/llvm-readelf' + $toolSuffix)
        $objdump = Join-Path $prebuilt ('bin/llvm-objdump' + $toolSuffix)
        $metadata = Join-Path $ndk 'meta/platforms.json'
        $properties = Join-Path $ndk 'source.properties'
        foreach ($required in @($clang,$linker,$readelf,$objdump,$metadata,$properties,(Join-Path $sysroot 'usr/include/stdint.h'))) {
            if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing Android preparation input: $required" }
        }
        $toolPaths += @($clang,$linker,$readelf,$objdump,$metadata,$properties,(Join-Path $sysroot 'usr/include/stdint.h'))
        $toolsBefore = Get-Bindings $toolPaths
        $platforms = Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json
        if ($AndroidApi -lt 26 -or $AndroidApi -gt $platforms.max) { throw 'Android API must be supported by selected NDK and at least 26' }
        $apiLibraries = Join-Path $sysroot ('usr/lib/aarch64-linux-android/' + $AndroidApi)
        if (-not (Test-Path -LiteralPath (Join-Path $apiLibraries 'libc.so') -PathType Leaf)) { throw 'Android API sysroot libraries are unavailable' }
        $targetLibdir = (Invoke-Tool 'android-target-libdir' $compiler @('--print','target-libdir','--target',$android.target) | Select-Object -Last 1).Trim()
        if (-not (Test-Path -LiteralPath $targetLibdir -PathType Container) -or @(Get-ChildItem -LiteralPath $targetLibdir -Filter 'libstd-*.rlib' -File).Count -ne 1 -or @(Get-ChildItem -LiteralPath $targetLibdir -Filter 'libcore-*.rlib' -File).Count -ne 1) { throw 'Selected rustc Android standard library is not installed' }
        $toolPaths += @((Get-ChildItem -LiteralPath $targetLibdir -File).FullName) + @((Get-ChildItem -LiteralPath $apiLibraries -File).FullName)
        $toolsBefore = Get-Bindings $toolPaths
        foreach ($tool in @(@('android-clang-version',$clang),@('android-linker-version',$linker),@('android-readelf-version',$readelf),@('android-objdump-version',$objdump))) { Invoke-Tool $tool[0] $tool[1] @('--version') | Out-Null }
        $android.ndk = $ndk; $android.ndk_revision = (Get-Content -LiteralPath $properties | Where-Object { $_ -match '^Pkg.Revision' })
        $android.target_libdir = $targetLibdir; $android.sysroot = $sysroot
        $android.layout_checked = $false; $android.negative_elf_checked = $false
        $android.preflight_passed = $true
    } elseif ($PSBoundParameters.ContainsKey('AndroidApi')) { throw 'AndroidApi requires explicit AndroidNdk' }
    $suffix = if ($IsWindows) { '.dll' } elseif ($IsMacOS) { '.dylib' } else { '.so' }
    $exe = if ($IsWindows) { '.exe' } else { '' }
    $source = Join-Path $PSScriptRoot 'src'
    $sourceHashes = @(Get-ChildItem -LiteralPath $source -File | Sort-Object Name | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    Invoke-Compiler 'unit-tests' @('--edition','2021','--test',(Join-Path $source 'main.rs'),'-D','warnings','-o',(Join-Path $run ('unit-tests' + $exe)))
    & (Join-Path $run ('unit-tests' + $exe)) 2>&1 | Tee-Object (Join-Path $run 'unit-tests.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed' }
    Invoke-Compiler 'host' @('--edition','2021',(Join-Path $source 'main.rs'),'-D','warnings','-C','panic=abort','-o',(Join-Path $run ('feedback-host' + $exe)))
    $manifest = [Collections.Generic.List[string]]::new()
    $artifacts = [Collections.Generic.List[object]]::new()
    foreach ($definition in @(@('a1',1,0), @('b2',2,0), @('a3',3,0), @('wrong-version',4,1), @('short-table',5,2), @('wrong-contract',6,3), @('bad-behavior',7,4))) {
        $name, $generation, $variant = $definition
        $wrapper = Write-Generation $name $generation $variant
        $generatedBefore.AddRange([object[]](Get-Bindings @($wrapper)))
        $artifact = Join-Path $run ($name + $suffix)
        $arguments = @('--edition','2021','--crate-type','cdylib','--crate-name',($name -replace '-','_'),$wrapper,'-C','panic=abort','-D','warnings','-o',$artifact)
        if ($name -eq 'b2') { $arguments += @('--cfg','phase_b') }
        Invoke-Compiler $name $arguments
        $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        $manifest.Add("$name`t$generation`t$hash`t$artifact")
        $artifacts.Add([ordered]@{ name = $name; generation = $generation; sha256 = $hash; bytes = (Get-Item -LiteralPath $artifact).Length })
    }
    $manifestPath = Join-Path $run 'artifacts.tsv'
    $manifest | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
    $proof = & (Join-Path $run ('feedback-host' + $exe)) $manifestPath 2>&1
    $proof | Tee-Object (Join-Path $run 'host-proof.txt')
    if ($LASTEXITCODE -ne 0 -or $proof -notcontains 'PASS native A1-B2-A3 conformance') { throw 'Dynamic host conformance failed' }
    if ($AndroidNdk) {
        $target = 'aarch64-linux-android' + $AndroidApi
        $targetArguments = @(('--target=' + $target),('--sysroot=' + $sysroot))
        $layoutObject = Join-Path $run 'android-layout.o'
        Invoke-Tool 'android-c-layout' $clang ($targetArguments + @('-std=c11','-Wall','-Werror','-c',(Join-Path $PSScriptRoot 'tests/abi-layout.c'),'-I',(Join-Path $PSScriptRoot 'include'),'-o',$layoutObject)) | Out-Null
        $layoutElf = (Invoke-Tool 'android-c-layout-elf' $readelf @('--file-header',$layoutObject)) -join "`n"
        if ($layoutElf -notmatch 'Machine:\s+AArch64' -or $layoutElf -notmatch 'Type:\s+REL') { throw 'C layout object is not real AArch64 target output' }
        $android.layout_checked = $true
        $negativeSoname = 'libmissing_getter.so'
        $negative = Join-Path $run $negativeSoname
        Invoke-Tool 'android-negative-elf-compile' $clang ($targetArguments + @('-shared','-fPIC',(Join-Path $PSScriptRoot 'tests/missing-getter.c'),('-Wl,-soname,' + $negativeSoname),'-o',$negative)) | Out-Null
        try { Assert-AndroidElf 'android-negative' $negative $negativeSoname | Out-Null; throw 'Missing-getter ELF was accepted' }
        catch { if ($_.Exception.Message -ne 'ELF API getter missing') { throw }; $android.negative_elf_checked = $true }
        $androidArtifacts = [Collections.Generic.List[object]]::new()
        $android.artifacts = $androidArtifacts
        $machineCode = @{}
        $identity = [guid]::NewGuid().ToString('N')
        foreach ($definition in @(@('a1',1),@('b2',2),@('a3',3))) {
            $name, $generation = $definition
            $soname = 'librm030_' + $name + '_' + $identity + '.so'
            $artifact = Join-Path $run $soname
            $arguments = @('--edition','2021','--crate-type','cdylib','--crate-name',$name,(Join-Path $run ($name + '.rs')),'--target',$android.target,'-C','panic=abort','-C','opt-level=2','-C','strip=none','-D','warnings','-C',('linker=' + $clang),'-C',('link-arg=--target=' + $target),'-C',('link-arg=--sysroot=' + $sysroot),'-C',('link-arg=-Wl,-soname,' + $soname),'-o',$artifact)
            if ($name -eq 'b2') { $arguments += @('--cfg','phase_b') }
            Invoke-Compiler ('android-' + $name) $arguments
            $dependencies = @(Assert-AndroidElf ('android-' + $name) $artifact $soname)
            $symbols = (Invoke-Tool ('android-' + $name + '-symbols') $readelf @('--symbols','--wide',$artifact)) -join "`n"
            $transformSymbols = @([regex]::Matches($symbols,'(?m)^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+FUNC\s+LOCAL\s+DEFAULT\s+\S+\s+(\S*transform\S*)\s*$'))
            if ($transformSymbols.Count -ne 1) { throw 'Expected exactly one real local transform function symbol' }
            $symbol = $transformSymbols[0].Groups[1].Value
            $disassembly = (Invoke-Tool ('android-' + $name + '-transform') $objdump @(('--disassemble-symbols=' + $symbol),$artifact)) -join "`n"
            $instructions = @([regex]::Matches($disassembly,'(?m)^\s*[0-9a-fA-F]+:\s+([0-9a-fA-F]{8})\s+.+$') | ForEach-Object { $_.Groups[1].Value })
            if ($instructions.Count -lt 4) { throw 'Real transform disassembly missing' }
            $machineCode[$name] = $instructions -join ''
            $androidArtifacts.Add([ordered]@{ name = $name; generation = $generation; file = $artifact; soname = $soname; sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant(); dependencies = $dependencies; transform_symbol = $symbol; transform_machine_code = $machineCode[$name] })
        }
        if ($machineCode.a1 -eq $machineCode.b2 -or $machineCode.a1 -ne $machineCode.a3) { throw 'Actual transform code must change A/B and restore A3' }
        $android.artifacts = $androidArtifacts; $android.transform_changed_and_restored = $true
    }
    $inputsAfter = Get-Bindings $inputPaths
    $toolsAfter = Get-Bindings $toolPaths
    $generatedAfter = Get-Bindings @($generatedBefore | ForEach-Object { $_.path })
    $sortedGeneratedBefore = @($generatedBefore | Sort-Object { $_.path })
    if (($inputsBefore | ConvertTo-Json -Depth 5 -Compress) -ne ($inputsAfter | ConvertTo-Json -Depth 5 -Compress) -or ($toolsBefore | ConvertTo-Json -Depth 5 -Compress) -ne ($toolsAfter | ConvertTo-Json -Depth 5 -Compress) -or ($sortedGeneratedBefore | ConvertTo-Json -Depth 5 -Compress) -ne ($generatedAfter | ConvertTo-Json -Depth 5 -Compress)) { throw 'Preparation inputs/tools changed during run' }
    if ($AndroidNdk) { $android.passed = $true }
    $passed = $true
} catch {
    $failure = $_.Exception.Message
    throw
} finally {
    # Keep the exact successful post-observation that was compared above;
    # failed preflight/commands still retain their available final bindings.
    if (-not $passed) {
        if ($inputPaths) { $inputsAfter = Get-Bindings $inputPaths }
        $toolsAfter = Get-Bindings $toolPaths
        $generatedAfter = Get-Bindings @($generatedBefore | ForEach-Object { $_.path })
    }
    $whole.Stop()
    [ordered]@{ schema = 'rusty.quest.native-feedback-host-proof.v1'; passed = $passed;
        compiler_path = $compiler; compiler_sha256 = $compilerHash; source_hashes = $sourceHashes;
        artifacts = $artifacts; timings = $timings; full_elapsed_seconds = $whole.Elapsed.TotalSeconds;
        commands = $commands; inputs_before = $inputsBefore; inputs_after = $inputsAfter; generated_sources_before = $generatedBefore; generated_sources_after = $generatedAfter; tools_before = $toolsBefore; tools_after = $toolsAfter; android_preparation = $android; failure = $failure;
        claim = 'host conformance and optional Android preparation only; no Android execution, Quest, JNI, unload or device performance proof' } |
        ConvertTo-Json -Depth 8 | Set-Content (Join-Path $run 'receipt.json') -Encoding utf8NoBOM
    Write-Output "Evidence: $run"
}
