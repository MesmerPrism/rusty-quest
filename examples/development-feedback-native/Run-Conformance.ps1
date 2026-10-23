[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Rustc,
    [string]$AndroidNdk = '',
    [int]$AndroidApi = 26,
    [switch]$PrepareAndroidBridge,
    [string]$JavaHome = '',
    [string]$AndroidJar = '',
    [string]$D8Jar = '',
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
$bridge = [ordered]@{ requested = [bool]$PrepareAndroidBridge; passed = $false; host_jni_tested = $false; android_jni_executed = $false; device_tested = $false; quest_tested = $false; attempt_limit = 64; image_byte_limit = 4194304; staged_byte_limit = 67108864 }
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
function Assert-AndroidElf([string]$Name, [string]$Artifact, [string]$Soname, [string]$Entry = 'rm030_feedback_api') {
    $inspection = (Invoke-Tool ($Name + '-elf') $readelf @('--file-header','--dynamic','--dyn-syms','--wide',$Artifact)) -join "`n"
    if ($inspection -notmatch 'Class:\s+ELF64' -or $inspection -notmatch 'Data:\s+2.s complement, little endian' -or $inspection -notmatch 'Machine:\s+AArch64' -or $inspection -notmatch 'Type:\s+DYN') { throw 'ELF architecture/type mismatch' }
    if ($inspection -notmatch ('\(SONAME\).*\[' + [regex]::Escape($Soname) + '\]')) { throw 'ELF SONAME mismatch' }
    if ($inspection -notmatch ('(?m)^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+FUNC\s+GLOBAL\s+DEFAULT\s+(?!UND)\S+\s+' + [regex]::Escape($Entry) + '\s*$')) { throw 'ELF API getter missing' }
    if ($Entry -eq 'JNI_OnLoad') {
        $exports = @([regex]::Matches($inspection,'(?m)^\s*\d+:\s+[0-9a-fA-F]+\s+\d+\s+\S+\s+(?:GLOBAL|WEAK)\s+DEFAULT\s+(?!UND)\S+\s+(\S+)\s*$') | ForEach-Object { $_.Groups[1].Value })
        if ($exports.Count -ne 1 -or $exports[0] -cne 'JNI_OnLoad') { throw 'Resident bridge must export only JNI_OnLoad' }
    }
    $dependencies = @([regex]::Matches($inspection,'\(NEEDED\).*\[([^\]]+)\]') | ForEach-Object { $_.Groups[1].Value })
    if ($dependencies.Count -eq 0 -or @($dependencies | Where-Object { $_ -notin @('libc.so','libdl.so','libm.so','liblog.so') }).Count -ne 0) { throw 'ELF dependencies outside platform CPU contract' }
    return $dependencies
}
try {
    $compilerVersion = @(Invoke-Tool 'rustc' $compiler @('-vV'))
    $compilerVersion | Write-Output
    $compilerHash = (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant()
    $inputPaths = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -File).FullName) + @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'include') -File).FullName) + @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -File).FullName) + @($PSCommandPath)
    $inputsBefore = Get-Bindings $inputPaths
    $toolsBefore = Get-Bindings $toolPaths
    if ($PrepareAndroidBridge -and -not $AndroidNdk) { throw 'PrepareAndroidBridge requires explicit AndroidNdk' }
    if ($PrepareAndroidBridge -and -not $JavaHome) { throw 'PrepareAndroidBridge requires explicit JavaHome for host JNI proof' }
    if ($JavaHome -and -not $PrepareAndroidBridge) { throw 'JavaHome requires PrepareAndroidBridge' }
    if (($AndroidJar -or $D8Jar) -and (-not $PrepareAndroidBridge -or -not $AndroidJar -or -not $D8Jar)) { throw 'Android proof preparation requires PrepareAndroidBridge, AndroidJar and D8Jar together' }
    if ($PrepareAndroidBridge) {
        $hostTarget = @($compilerVersion | Where-Object { $_ -cmatch '^host: ' } | ForEach-Object { $_.Substring(6) })
        if ($hostTarget.Count -ne 1) { throw 'Missing selected rustc host target for C shim' }
        $hostTarget = $hostTarget[0]
        $jdk = (Resolve-Path -LiteralPath $JavaHome).Path
        $jdkSuffix = if ($IsWindows) { '.exe' } else { '' }
        $javac = Join-Path $jdk ('bin/javac' + $jdkSuffix)
        $java = Join-Path $jdk ('bin/java' + $jdkSuffix)
        $facade = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../android/development-feedback/src/android/java/org/rusty/quest/feedback/NativeFeedbackBridge.java'))
        foreach ($required in @($javac,$java,(Join-Path $jdk 'release'),$facade)) {
            if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing bridge host input: $required" }
        }
        $inputPaths += @($facade)
        $inputsBefore = Get-Bindings $inputPaths
        $toolPaths += @($javac,$java,(Join-Path $jdk 'release'))
        $toolsBefore = Get-Bindings $toolPaths
        $jdkVersion = (Invoke-Tool 'bridge-javac-version' $javac @('-version')) -join "`n"
        if ($jdkVersion -notmatch '^javac 17(?:\.|\s|$)') { throw 'Bridge host proof requires selected JDK 17' }
        Invoke-Tool 'bridge-java-version' $java @('-version') | Out-Null
        $bridge.java_home = $jdk; $bridge.host_target = $hostTarget; $bridge.host_os = [Runtime.InteropServices.RuntimeInformation]::OSDescription
        if ($AndroidJar) {
            $androidApiJar = (Resolve-Path -LiteralPath $AndroidJar).Path
            $d8CompilerJar = (Resolve-Path -LiteralPath $D8Jar).Path
            foreach ($required in @($androidApiJar,$d8CompilerJar)) {
                if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw 'Missing selected Android proof compiler input' }
            }
            $toolPaths += @($androidApiJar,$d8CompilerJar)
            $toolsBefore = Get-Bindings $toolPaths
        }
    }
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
        if ($PrepareAndroidBridge) {
            $androidJniHeader = Join-Path $sysroot 'usr/include/jni.h'
            $unwindArchive = (Invoke-Tool 'bridge-android-unwind-path' $clang @(('--target=aarch64-linux-android' + $AndroidApi),'--print-file-name=libunwind.a') | Select-Object -Last 1).Trim()
            foreach ($required in @($androidJniHeader,$unwindArchive)) {
                if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing bridge NDK input: $required" }
            }
            $builtins = (Invoke-Tool 'bridge-android-builtins-path' $clang @(('--target=aarch64-linux-android' + $AndroidApi),'--print-libgcc-file-name') | Select-Object -Last 1).Trim()
            if (-not (Test-Path -LiteralPath $builtins -PathType Leaf)) { throw 'Missing bridge compiler builtins archive' }
            $toolPaths += @($androidJniHeader,$unwindArchive,$builtins)
            $toolsBefore = Get-Bindings $toolPaths
        }
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
    if ($PrepareAndroidBridge) {
        Invoke-Compiler 'bridge-unit-tests' @('--edition','2021','--test',(Join-Path $source 'android_bridge.rs'),'-D','warnings','-o',(Join-Path $run ('bridge-unit-tests' + $exe)))
        $oldManifest = [Environment]::GetEnvironmentVariable('RM030_BRIDGE_TEST_MANIFEST','Process')
        try {
            [Environment]::SetEnvironmentVariable('RM030_BRIDGE_TEST_MANIFEST',$manifestPath,'Process')
            Invoke-Tool 'bridge-rust-tests' (Join-Path $run ('bridge-unit-tests' + $exe)) @('--include-ignored','--test-threads=1') | Write-Output
        } finally { [Environment]::SetEnvironmentVariable('RM030_BRIDGE_TEST_MANIFEST',$oldManifest,'Process') }
        $hostObject = Join-Path $run 'bridge-host-shim.o'
        # This real NDK header explicitly defines VM-neutral JNI. An exact copy
        # avoids selecting Android libc headers for a host C target. Freestanding
        # C uses clang's real fixed-width/stdarg headers, not stub VM declarations.
        $hostJniInclude = Join-Path $run 'host-jni-include'
        New-Item -ItemType Directory -Path $hostJniInclude | Out-Null
        $hostJniHeader = Join-Path $hostJniInclude 'jni.h'
        [IO.File]::Copy($androidJniHeader,$hostJniHeader,$false)
        $generatedBefore.AddRange([object[]](Get-Bindings @($hostJniHeader)))
        $hostCArguments = @(('--target=' + $hostTarget),'-ffreestanding','-std=c11','-Wall','-Wextra','-Werror','-fvisibility=hidden','-c',(Join-Path $source 'android_jni.c'),'-I',$hostJniInclude,'-o',$hostObject)
        if (-not $IsWindows) { $hostCArguments += '-fPIC' }
        Invoke-Tool 'bridge-host-c-compile' $clang $hostCArguments | Out-Null
        $hostBridge = Join-Path $run ('rm030_bridge_host' + $suffix)
        Invoke-Compiler 'bridge-host' @('--edition','2021','--crate-type','cdylib','--crate-name','rm030_bridge_host',(Join-Path $source 'android_bridge.rs'),'-D','warnings','-C','panic=abort','-C',('link-arg=' + $hostObject),'-o',$hostBridge)
        $javaProof = Join-Path $run 'BridgeHostProof.java'
        @'
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import org.rusty.quest.feedback.NativeFeedbackBridge;
public final class BridgeHostProof {
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("bridge assertion " + assertions); }
    static byte[] hash(Path p) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)); }
    static void state(NativeFeedbackBridge b, long count, long value, long generation, long retained) {
        NativeFeedbackBridge.Snapshot s = b.snapshot();
        check(s.completed == count && s.value == value && s.generation == generation && s.retainedImages == retained && s.allocationToken == 1);
        byte[] expected = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array();
        check(Arrays.equals(s.payload(), expected));
        byte[] copy = s.payload(); copy[0] ^= 1; check(Arrays.equals(s.payload(), expected));
    }
    public static void main(String[] args) throws Exception {
        Path library = Path.of(args[0]), root = Path.of(args[2]);
        Files.createDirectories(root.resolve("native-incoming"));
        Map<String,byte[]> hashes = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] row = line.split("\t");
            Path destination = root.resolve("native-incoming/" + row[1] + ".so");
            Files.copy(Path.of(row[3]), destination); check(destination.toFile().setReadOnly());
            hashes.put(row[0], hash(destination));
        }
        Path alias = root.resolve("native-incoming/99.so");
        Files.copy(root.resolve("native-incoming/2.so"), alias); check(alias.toFile().setReadOnly());
        try { new NativeFeedbackBridge(library.toFile(), new byte[32], root.toFile()); throw new AssertionError("bad bridge digest accepted"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("SHA256")); }
        Path invalidLibrary = root.resolveSibling(root.getFileName() + "-invalid-library.dll");
        Files.write(invalidLibrary, new byte[]{1,2,3,4});
        try { new NativeFeedbackBridge(invalidLibrary.toFile(), hash(invalidLibrary), root.resolveSibling(root.getFileName() + "-failed-load-session").toFile()); throw new AssertionError("invalid resident library accepted"); }
        catch (UnsatisfiedLinkError expected) { check(true); }
        byte[] residentDigest = hash(library);
        NativeFeedbackBridge b = new NativeFeedbackBridge(library.toFile(), residentDigest, root.toFile());
        residentDigest[0] ^= 1; // The process identity must retain its own digest copy.
        state(b,0,0,0,0);
        NativeFeedbackBridge independent = new NativeFeedbackBridge(library.toFile(), hash(library), root.resolveSibling(root.getFileName() + "-independent-session").toFile());
        state(independent,0,0,0,0);
        Path alternateLibrary = root.resolveSibling(root.getFileName() + "-alternate-library.dll");
        Files.copy(library, alternateLibrary);
        try { new NativeFeedbackBridge(alternateLibrary.toFile(), hash(alternateLibrary), root.resolveSibling(root.getFileName() + "-alternate-session").toFile()); throw new AssertionError("different resident bridge path accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("identity")); }
        state(b,0,0,0,0);
        try { b.compute(4); throw new AssertionError("empty dispatch accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("code 3")); }
        Field owner = NativeFeedbackBridge.class.getDeclaredField("owner"); owner.setAccessible(true);
        Method snapshot = NativeFeedbackBridge.class.getDeclaredMethod("nativeSnapshot",long.class,long[].class,byte[].class); snapshot.setAccessible(true);
        check((Integer)snapshot.invoke(null,owner.getLong(b),new long[4],new byte[8]) == 1);
        check((Integer)snapshot.invoke(null,owner.getLong(b),new long[5],null) == 1);
        Method activate = NativeFeedbackBridge.class.getDeclaredMethod("nativeActivate",long.class,byte[].class,byte[].class,long.class,long.class); activate.setAccessible(true);
        check((Integer)activate.invoke(null,owner.getLong(b),new byte[]{1},new byte[31],1L,13L) == 1);
        check((Integer)activate.invoke(null,owner.getLong(b),new byte[4097],new byte[32],1L,13L) == 1);
        b.activate(1,hashes.get("a1"),13); b.compute(4); state(b,1,5,1,1);
        state(independent,0,0,0,0);
        byte[] path = root.resolve("native-incoming/2.so").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        check((Integer)activate.invoke(null,owner.getLong(b),path,new byte[32],2L,19L) == 2);
        state(b,1,5,1,1); b.compute(4);
        String[] negatives = {"wrong-version","short-table","wrong-contract","bad-behavior"};
        long count = 2, value = 10, retained = 1;
        for (int i = 0; i < negatives.length; ++i) {
            try { b.activate(4+i,hashes.get(negatives[i]),13); throw new AssertionError("bad candidate accepted"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("code 2")); }
            state(b,count,value,1,++retained); b.compute(4); count++; value+=5;
        }
        try { b.activate(99,hashes.get("b2"),19); throw new AssertionError("wrong generation accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("code 2")); }
        state(b,6,30,1,6); b.compute(4); state(b,7,35,1,6);
        b.activate(2,hashes.get("b2"),19); state(b,7,35,2,7); b.compute(4); state(b,8,45,2,7);
        b.activate(3,hashes.get("a3"),13); state(b,8,45,3,8); b.compute(4); state(b,9,50,3,8);
        try { b.compute(Long.MAX_VALUE); throw new AssertionError("overflow accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("code 3")); }
        state(b,9,50,3,8);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable calls = () -> { try { for(int i=0;i<10;i++) b.compute(1); } catch(Throwable e) { failure.set(e); } };
        Thread first = new Thread(calls), second = new Thread(calls); first.start(); second.start(); first.join(); second.join();
        check(failure.get() == null); state(b,29,90,3,8);
        Path large = root.resolve("native-incoming/8.so");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(large.toFile(),"rw")) { f.setLength(NativeFeedbackBridge.IMAGE_LIMIT+1); }
        check(large.toFile().setReadOnly());
        try { b.activate(8,new byte[32],13); throw new AssertionError("oversize accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("staged-byte budget")); }
        for(int i=0;i<55;i++) {
            try { b.activate(1000+i,new byte[32],13); throw new AssertionError("missing file accepted"); }
            catch (java.io.IOException expected) { check(true); }
        }
        try { b.activate(2000,new byte[32],13); throw new AssertionError("65th attempt accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("attempt budget")); }
        state(b,29,90,3,8); b.compute(4); state(b,30,95,3,8);
        try { new NativeFeedbackBridge(library.toFile(),hash(library),root.toFile()); throw new AssertionError("staging session reused"); }
        catch (java.io.IOException expected) { check(expected.getMessage().contains("fresh and empty")); }
        System.out.println("PASS real host JNI registration/regions/A1-B2-A3/rejection/budgets/thread serialization assertions="+assertions);
    }
}
'@ | Set-Content -LiteralPath $javaProof -Encoding utf8NoBOM
        $generatedBefore.AddRange([object[]](Get-Bindings @($javaProof)))
        $classes = Join-Path $run 'bridge-java-classes'
        New-Item -ItemType Directory -Path $classes | Out-Null
        Invoke-Tool 'bridge-java-compile' $javac @('-Xlint:all','-Werror','-d',$classes,$facade,$javaProof) | Out-Null
        $jniProof = @(Invoke-Tool 'bridge-host-jni-proof' $java @('-Xcheck:jni','-cp',$classes,'BridgeHostProof',$hostBridge,$manifestPath,(Join-Path $run 'bridge-java-session')))
        if (@($jniProof | Where-Object { $_ -match '^PASS real host JNI registration/regions/A1-B2-A3/rejection/budgets/thread serialization assertions=' }).Count -ne 1) { throw 'Host JNI proof missing' }
        $bridge.host_jni_tested = $true
        if ($AndroidJar) {
            $androidProofSource = Join-Path $PSScriptRoot 'tests/AndroidBridgeProof.java'
            $proofClasses = Join-Path $run 'android-proof-classes'
            New-Item -ItemType Directory -Path $proofClasses | Out-Null
            Invoke-Tool 'android-proof-javac' $javac @('-source','8','-target','8','-bootclasspath',$androidApiJar,'-Xlint:all,-options','-Werror','-d',$proofClasses,$facade,$androidProofSource) | Out-Null
            $proofJar = Join-Path $run 'android-bridge-proof.jar'
            $classFiles = @((Get-ChildItem -LiteralPath $proofClasses -Recurse -File -Filter '*.class').FullName)
            Invoke-Tool 'android-proof-d8' $java (@('-cp',$d8CompilerJar,'com.android.tools.r8.D8','--min-api','26','--lib',$androidApiJar,'--output',$proofJar) + $classFiles) | Out-Null
            $bridge.android_proof = [ordered]@{ file = $proofJar; sha256 = (Get-FileHash -LiteralPath $proofJar).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $proofJar).Length; entry = 'AndroidBridgeProof'; executed = $false; app_adopted = $false }
        }
        $bridge.host_artifact = [ordered]@{ file = $hostBridge; sha256 = (Get-FileHash -LiteralPath $hostBridge).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $hostBridge).Length }
    }
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
        $androidDefinitions = @(@('a1',1),@('b2',2),@('a3',3))
        if ($AndroidJar) { $androidDefinitions += ,@('bad-behavior',7) }
        foreach ($definition in $androidDefinitions) {
            $name, $generation = $definition
            $soname = 'librm030_' + $name + '_' + $identity + '.so'
            $artifact = Join-Path $run $soname
            $arguments = @('--edition','2021','--crate-type','cdylib','--crate-name',($name -replace '-','_'),(Join-Path $run ($name + '.rs')),'--target',$android.target,'-C','panic=abort','-C','opt-level=2','-C','strip=debuginfo','-D','warnings','-C',('linker=' + $clang),'-C',('link-arg=--target=' + $target),'-C',('link-arg=--sysroot=' + $sysroot),'-C',('link-arg=-Wl,-soname,' + $soname),'-o',$artifact)
            if ($name -eq 'b2') { $arguments += @('--cfg','phase_b') }
            Invoke-Compiler ('android-' + $name) $arguments
            if ((Get-Item -LiteralPath $artifact).Length -gt $bridge.image_byte_limit) { throw 'Prepared Android module exceeds resident image-byte limit' }
            $dependencies = @(Assert-AndroidElf ('android-' + $name) $artifact $soname)
            if ($name -eq 'bad-behavior') {
                $androidArtifacts.Add([ordered]@{ name = $name; generation = $generation; file = $artifact; soname = $soname; sha256 = (Get-FileHash -LiteralPath $artifact).Hash.ToLowerInvariant(); dependencies = $dependencies; negative_probe = $true })
                continue
            }
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
        if ($PrepareAndroidBridge) {
            $androidObject = Join-Path $run 'bridge-android-shim.o'
            Invoke-Tool 'bridge-android-c-compile' $clang ($targetArguments + @('-std=c11','-Wall','-Wextra','-Werror','-fPIC','-fvisibility=hidden','-c',(Join-Path $source 'android_jni.c'),'-o',$androidObject)) | Out-Null
            $archive = Join-Path $run 'libbridge_android_rust.a'
            Invoke-Compiler 'bridge-android-rust' @('--edition','2021','--crate-type','staticlib','--crate-name','rm030_bridge_android',(Join-Path $source 'android_bridge.rs'),'--target',$android.target,'-D','warnings','-C','panic=abort','-C','opt-level=2','-C','relocation-model=pic','--print','native-static-libs','-o',$archive)
            $soname = 'librm030_feedback_bridge_' + $identity + '.so'
            $artifact = Join-Path $run $soname
            Invoke-Tool 'bridge-android-link' $clang ($targetArguments + @('-shared','-Wl,--no-undefined','-Wl,--gc-sections','-Wl,--exclude-libs,ALL',('-Wl,--version-script=' + (Join-Path $source 'android_jni.exports')),('-Wl,-soname,' + $soname),$androidObject,$archive,'-ldl','-llog','-lunwind','-lm','-o',$artifact)) | Out-Null
            $dependencies = @(Assert-AndroidElf 'bridge-android' $artifact $soname 'JNI_OnLoad')
            $bridge.android_artifact = [ordered]@{ file = $artifact; soname = $soname; sha256 = (Get-FileHash -LiteralPath $artifact).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $artifact).Length; dependencies = $dependencies; export = 'JNI_OnLoad' }
            $bridge.passed = $true
        }
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
        commands = $commands; inputs_before = $inputsBefore; inputs_after = $inputsAfter; generated_sources_before = $generatedBefore; generated_sources_after = $generatedAfter; tools_before = $toolsBefore; tools_after = $toolsAfter; android_preparation = $android; resident_bridge_preparation = $bridge; failure = $failure;
        claim = 'host conformance and optional real host JNI plus Android preparation only; no Android execution, Quest, unload or device performance proof' } |
        ConvertTo-Json -Depth 8 | Set-Content (Join-Path $run 'receipt.json') -Encoding utf8NoBOM
    Write-Output "Evidence: $run"
}
