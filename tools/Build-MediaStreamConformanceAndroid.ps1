param(
    [string]$AndroidHome=$env:ANDROID_HOME,
    [string]$JavaHome=$env:JAVA_HOME,
    [string]$ManifoldSourceRoot=$env:Q2Q_MANIFOLD_SOURCE_ROOT,
    [string]$HostJsonJar=$env:MEDIA_STREAM_HOST_JSON_JAR,
    [Parameter(Mandatory)][string]$OutDir,
    [switch]$HostOnly,
    [switch]$BuildCompatibilityBroker
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 3.0
if ($HostOnly -and $BuildCompatibilityBroker) { throw 'HostOnly and BuildCompatibilityBroker are mutually exclusive.' }
$repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot=[IO.Path]::GetFullPath($OutDir)
$targetRoot=[IO.Path]::GetFullPath((Join-Path $repoRoot 'target')).TrimEnd('\','/')
if (-not $outputRoot.StartsWith($targetRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Conformance outputs must remain under this repository target directory.' }
$cursor=$outputRoot
while ($cursor) {
    if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Conformance output cannot traverse a reparse point.' }
    $cursor=[IO.Path]::GetDirectoryName($cursor)
}
# Preserve prior evidence. A repeated profile creates a new bounded capsule.
$runRoot=Join-Path $outputRoot ('run-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
$logRoot=Join-Path $runRoot 'logs'
[void][IO.Directory]::CreateDirectory($logRoot)
$moduleRoot=Join-Path $repoRoot 'crates/rusty-quest-media-stream-android'
$appRoot=Join-Path $repoRoot 'apps/media-stream-conformance-android'
$java=Join-Path $JavaHome 'bin/java.exe'
$javac=Join-Path $JavaHome 'bin/javac.exe'
foreach ($tool in @($java,$javac)) { if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required tool is missing: $tool" } }
function File-Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Run-Tool([string]$Name,[string]$File,[string[]]$Arguments) {
    & $File @Arguments *> (Join-Path $logRoot "$Name.log")
    if ($LASTEXITCODE -ne 0) { throw "$Name failed ($LASTEXITCODE); see $logRoot/$Name.log" }
}
function Input-Inventory {
    $globs=@('Cargo.toml','Cargo.lock','rust-toolchain*','.cargo','crates/rusty-quest-media-stream*','crates/rusty-quest-broker-*','crates/rusty-quest-device-link','apps/manifold-broker-android','apps/media-stream-conformance-android','fixtures','tools/Build-ManifoldBrokerAndroid.ps1','tools/Build-MediaStreamConformanceAndroid.ps1')
    $paths=@(& git -C $repoRoot ls-files --cached --others --exclude-standard -- @globs)
    if ($LASTEXITCODE -ne 0) { throw 'Source inventory failed.' }
    @($paths | Sort-Object -Unique | ForEach-Object {
        $file=Join-Path $repoRoot $_
        $present=Test-Path -LiteralPath $file -PathType Leaf
        [ordered]@{path=$_;present=$present;sha256=$(if($present){File-Hash $file}else{$null})}
    })
}
$sourceBefore=@(Input-Inventory)
$sourceText=$sourceBefore | ConvertTo-Json -Depth 8 -Compress
$receipt=[ordered]@{
    schema='rusty.quest.android.media.conformance.build.v1';status='running'
    started_at=[DateTimeOffset]::UtcNow.ToString('o');run_root=$runRoot
    source_revision=(& git -C $repoRoot rev-parse HEAD).Trim();source_files=$sourceBefore
    host_only=[bool]$HostOnly;compatibility_broker_requested=[bool]$BuildCompatibilityBroker
    device_execution=$false;device_qualification=$false;artifacts=@();checks=@();tools=@()
}
$receiptPath=Join-Path $runRoot 'build-manifest.json'
$priorJavaHome=$env:JAVA_HOME
$pushedLocation=$false
try {
    Push-Location -LiteralPath $repoRoot
    $pushedLocation=$true
    $env:JAVA_HOME=[IO.Path]::GetFullPath($JavaHome)
    Run-Tool 'rustc-version' 'rustc' @('-Vv')
    Run-Tool 'cargo-version' 'cargo' @('-V')
    Run-Tool 'java-version' $java @('-version')
    Run-Tool 'javac-version' $javac @('-version')
    $rustTools=@('rustc','cargo' | ForEach-Object {
        $executable=(Get-Command $_ -CommandType Application -ErrorAction Stop).Source
        @{name=$_;path=$executable;sha256=(File-Hash $executable)}
    })
    if ($HostOnly) {
        if (-not $ManifoldSourceRoot) { throw 'Host conformance requires the explicit admitted Manifold source root.' }
        . (Join-Path $moduleRoot 'tools/MediaStreamCargoInputs.ps1')
        $hostInputs=New-IsolatedBrokerCargoMaterialization -RepoRoot $repoRoot -ManifoldRoot $ManifoldSourceRoot -OutputRoot $runRoot -IncludeConformance
        if (-not $HostJsonJar) {
            $jsonCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json/20240303'
            $jsonCandidates=@(Get-ChildItem -LiteralPath $jsonCache -Recurse -File -Filter 'json-20240303.jar' -ErrorAction SilentlyContinue)
            if ($jsonCandidates.Count -ne 1) { throw 'Supply HostJsonJar for the installed org.json 20240303 host-test dependency.' }
            $HostJsonJar=$jsonCandidates[0].FullName
        }
        if ((File-Hash $HostJsonJar) -cne '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed') { throw 'Host JSON library does not match the pinned dependency.' }
        # Resolve only the pure test entry's transitive Java sources, without Android stubs.
        $classes=Join-Path $runRoot 'host-classes'
        [void][IO.Directory]::CreateDirectory($classes)
        $mainRoot=Join-Path $moduleRoot 'android/library/src/main/java'
        $testMain=Join-Path $moduleRoot 'android/library/src/test/java/io/github/mesmerprism/rustyquest/media/MediaProtocolConformanceMain.java'
        $adversarialMain=Join-Path $moduleRoot 'android/library/src/test/java/io/github/mesmerprism/rustyquest/media/MediaPrimitiveAdversarialMain.java'
        $ownerMain=Join-Path $moduleRoot 'android/library/src/test/java/io/github/mesmerprism/rustyquest/media/MediaOwnerLifecycleAdversarialMain.java'
        Run-Tool 'host-javac' $javac @('--release','8','-encoding','UTF-8','-Xlint:all','-Werror','-classpath',$HostJsonJar,'-sourcepath',$mainRoot,'-d',$classes,$testMain,$adversarialMain,$ownerMain)
        Run-Tool 'host-java' $java @('-cp',($classes+[IO.Path]::PathSeparator+$HostJsonJar),'io.github.mesmerprism.rustyquest.media.MediaProtocolConformanceMain')
        Run-Tool 'host-java-adversarial' $java @('-cp',($classes+[IO.Path]::PathSeparator+$HostJsonJar),'io.github.mesmerprism.rustyquest.media.MediaPrimitiveAdversarialMain')
        Run-Tool 'host-java-owner-lifecycle' $java @('-cp',($classes+[IO.Path]::PathSeparator+$HostJsonJar),'io.github.mesmerprism.rustyquest.media.MediaOwnerLifecycleAdversarialMain')
        if ((Get-Content -LiteralPath (Join-Path $logRoot 'host-java.log') -Raw) -notmatch '(?m)^rusty\.quest\.android\.media\.host-conformance\.v1:pass\s*$') { throw 'Pure Java harness did not report behavioral conformance.' }
        foreach ($package in @('rusty-quest-media-stream-android','rusty-quest-media-stream-conformance-android-native')) {
            Run-Tool $package 'cargo' @('test','--locked','--offline','--manifest-path',$hostInputs.manifest,'--target-dir',(Join-Path $runRoot 'host-target'),'-p',$package)
        }
        $receipt.checks=@('pure-java-behavioral-conformance','bounded-pump-and-lease-adversarial-tests','owner-rollback-and-freshness-tests','shared-rust-host-tests','neutral-native-host-tests')
        $receipt.tools=@($java,$javac,$HostJsonJar | ForEach-Object {@{path=$_;sha256=(File-Hash $_)}})
    } else {
        if (-not $ManifoldSourceRoot) { throw 'Android builds require the explicit admitted Manifold source root.' }
        $platformJar=Join-Path $AndroidHome 'platforms/android-36/android.jar'
        $buildTools=Join-Path $AndroidHome 'build-tools/36.0.0'
        $ndkBin=Join-Path $AndroidHome 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
        $aapt2=Join-Path $buildTools 'aapt2.exe'
        $d8=Join-Path $buildTools 'd8.bat'
        $zipalign=Join-Path $buildTools 'zipalign.exe'
        $apksigner=Join-Path $buildTools 'apksigner.bat'
        $jar=Join-Path $JavaHome 'bin/jar.exe'
        $keytool=Join-Path $JavaHome 'bin/keytool.exe'
        $clang=Join-Path $ndkBin 'aarch64-linux-android29-clang.cmd'
        $ar=Join-Path $ndkBin 'llvm-ar.exe'
        $toolPaths=@($platformJar,$aapt2,$d8,$zipalign,$apksigner,$java,$javac,$jar,$keytool,$clang,$ar)
        foreach ($tool in $toolPaths) { if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required installed tool is missing: $tool" } }
        $aar=& (Join-Path $moduleRoot 'android/Build-MediaStreamAar.ps1') -AndroidHome $AndroidHome -JavaHome $JavaHome -OutDir (Join-Path $runRoot 'aar')
        . (Join-Path $moduleRoot 'tools/MediaStreamAarInputs.ps1')
        $aarInput=Expand-ValidatedMediaStreamAar -Path $aar.aar_path -ExpectedSha256 $aar.aar_sha256 -OutputRoot (Join-Path $runRoot 'aar-input')
        . (Join-Path $moduleRoot 'tools/MediaStreamCargoInputs.ps1')
        $nativeInputs=New-IsolatedBrokerCargoMaterialization -RepoRoot $repoRoot -ManifoldRoot $ManifoldSourceRoot -OutputRoot $runRoot -IncludeConformance
        $nativeTarget=Join-Path $runRoot 'native-target'
        $oldLinker=$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
        $oldCc=$env:CC_aarch64_linux_android
        $oldAr=$env:AR_aarch64_linux_android
        try {
            $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$clang
            $env:CC_aarch64_linux_android=$clang
            $env:AR_aarch64_linux_android=$ar
            Run-Tool 'conformance-native' 'cargo' @('build','--locked','--offline','--manifest-path',$nativeInputs.manifest,'--target-dir',$nativeTarget,'--target','aarch64-linux-android','-p','rusty-quest-media-stream-conformance-android-native')
        } finally {
            $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$oldLinker
            $env:CC_aarch64_linux_android=$oldCc
            $env:AR_aarch64_linux_android=$oldAr
        }
        $nativeLibrary=Join-Path $nativeTarget 'aarch64-linux-android/debug/librusty_quest_media_stream_conformance.so'
        if (-not (Test-Path -LiteralPath $nativeLibrary -PathType Leaf)) { throw 'Native conformance library was not produced.' }
        $classes=Join-Path $runRoot 'classes'
        $dex=Join-Path $runRoot 'dex'
        [void][IO.Directory]::CreateDirectory($classes)
        [void][IO.Directory]::CreateDirectory($dex)
        $sources=@(Get-ChildItem -LiteralPath (Join-Path $appRoot 'src/main/java') -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object {$_.FullName})
        if (-not $sources.Count) { throw 'Neutral app Java sources are missing.' }
        Run-Tool 'conformance-javac' $javac (@('--release','8','-encoding','UTF-8','-classpath',($platformJar+[IO.Path]::PathSeparator+$aarInput.classes_jar_path),'-d',$classes)+$sources)
        $appJar=Join-Path $runRoot 'app-classes.jar'
        Run-Tool 'conformance-jar' $jar @('cf',$appJar,'-C',$classes,'.')
        Run-Tool 'conformance-d8' $d8 @('--min-api','29','--lib',$platformJar,'--output',$dex,$appJar,$aarInput.classes_jar_path)
        if (@(Get-ChildItem -LiteralPath $dex -Filter '*.dex' -File).Count -ne 1) { throw 'Neutral app must produce exactly one dex artifact.' }
        $manifestPath=Join-Path $appRoot 'AndroidManifest.xml'
        [xml]$manifestXml=Get-Content -LiteralPath $manifestPath -Raw
        $package='io.github.mesmerprism.rustyquest.media.conformance'
        if ($manifestXml.manifest.package -cne $package -or $manifestXml.SelectNodes('//uses-permission | //uses-permission-sdk-23 | //permission | //service | //provider | //receiver | //activity-alias').Count -ne 0 -or $manifestXml.SelectNodes('//activity').Count -ne 1) { throw 'Neutral manifest violates its fixed permission-free Activity-only boundary.' }
        $unsigned=Join-Path $runRoot 'conformance-unsigned.apk'
        Run-Tool 'conformance-aapt2' $aapt2 @('link','-o',$unsigned,'--manifest',$manifestPath,'-I',$platformJar,'--min-sdk-version','29','--target-sdk-version','34','--version-code','1','--version-name','0.1.0')
        $packageRoot=Join-Path $runRoot 'package'
        $libRoot=Join-Path $packageRoot 'lib/arm64-v8a'
        $assetRoot=Join-Path $packageRoot 'assets/media'
        [void][IO.Directory]::CreateDirectory($libRoot)
        [void][IO.Directory]::CreateDirectory($assetRoot)
        Copy-Item -LiteralPath $nativeLibrary -Destination (Join-Path $libRoot 'librusty_quest_media_stream_conformance.so')
        $binding=Join-Path $repoRoot 'fixtures/media-runtime-products/android-duplex-conformance.binding.json'
        Copy-Item -LiteralPath $binding -Destination (Join-Path $assetRoot 'android-duplex-conformance.binding.json')
        Run-Tool 'conformance-dex-package' $jar @('uf',$unsigned,'-C',$dex,'classes.dex')
        Run-Tool 'conformance-native-package' $jar @('uf',$unsigned,'-C',$packageRoot,'lib')
        Run-Tool 'conformance-binding-package' $jar @('uf',$unsigned,'-C',$packageRoot,'assets')
        $aligned=Join-Path $runRoot 'conformance-aligned.apk'
        $apk=Join-Path $runRoot 'rusty-quest-media-stream-conformance.apk'
        Run-Tool 'conformance-zipalign' $zipalign @('-f','4',$unsigned,$aligned)
        $keystore=Join-Path $runRoot 'conformance-debug.keystore'
        Run-Tool 'conformance-keytool' $keytool @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Media Stream Conformance,O=Rusty Quest,C=US')
        Run-Tool 'conformance-sign' $apksigner @('sign','--ks',$keystore,'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$apk,$aligned)
        Run-Tool 'conformance-signature-verify' $apksigner @('verify','--verbose','--print-certs',$apk)
        Run-Tool 'conformance-alignment-verify' $zipalign @('-c','4',$apk)
        Run-Tool 'conformance-permissions' $aapt2 @('dump','permissions',$apk)
        $permissions=Get-Content -LiteralPath (Join-Path $logRoot 'conformance-permissions.log') -Raw
        if ($permissions -match '(?m)^uses-permission' -or $permissions -notmatch [regex]::Escape("package: $package")) { throw 'Packaged conformance permissions or identity do not match.' }
        $zip=[IO.Compression.ZipFile]::OpenRead($apk)
        try {
            $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            foreach ($entry in $zip.Entries) { if (-not $names.Add($entry.FullName)) { throw 'Conformance APK has duplicate entries.' } }
            $nativeEntries=@($zip.Entries | Where-Object {$_.FullName.EndsWith('.so')})
            if ($nativeEntries.Count -ne 1 -or $nativeEntries[0].FullName -cne 'lib/arm64-v8a/librusty_quest_media_stream_conformance.so') { throw 'Conformance APK must contain exactly its one host-owned native library.' }
            foreach ($check in @(@{entry=$nativeEntries[0];source=$nativeLibrary},@{entry=$zip.GetEntry('assets/media/android-duplex-conformance.binding.json');source=$binding})) {
                if ($null -eq $check.entry) { throw 'Required conformance artifact is missing.' }
                $stream=$check.entry.Open()
                try { $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() } finally { $stream.Dispose() }
                if ($hash -cne (File-Hash $check.source)) { throw 'Packaged bytes differ from the bound artifact.' }
            }
        } finally { $zip.Dispose() }
        $receipt.artifacts=@(@{kind='aar';path=$aar.aar_path;sha256=$aar.aar_sha256},@{kind='conformance-native';path=$nativeLibrary;sha256=(File-Hash $nativeLibrary)},@{kind='synthetic-binding';path=$binding;sha256=(File-Hash $binding)},@{kind='conformance-apk';path=$apk;sha256=(File-Hash $apk)})
        $receipt.checks=@('aar-content-and-java8','admitted-manifold-resolution','arm64-native-build','neutral-app-java-dex-build','apk-signature-and-alignment','permission-free-manifest','single-native-library','exact-binding-and-library-bytes')
        $receipt.tools=@($toolPaths | ForEach-Object {@{path=$_;sha256=(File-Hash $_)}})
        if ($BuildCompatibilityBroker) {
            $brokerRoot=Join-Path $runRoot 'compatibility-broker'
            $brokerArguments=@{
                AndroidHome=$AndroidHome;JavaHome=$JavaHome;ManifoldSourceRoot=$ManifoldSourceRoot
                OutDir=$brokerRoot;LegacyCameraP2pCompatibility=$true
                MediaSessionBindingPath=@((Join-Path $repoRoot 'fixtures/media-runtime-products/camera2-surface.binding.json'),(Join-Path $repoRoot 'fixtures/media-runtime-products/spatial-camera-panel-display.binding.json'))
                MediaStreamAarPath=$aar.aar_path;ExpectedMediaStreamAarSha256=$aar.aar_sha256
            }
            # Preserve the actual two-element array across the script boundary.
            & (Join-Path $PSScriptRoot 'Build-ManifoldBrokerAndroid.ps1') @brokerArguments *> (Join-Path $logRoot 'compatibility-broker.log')
            $brokerApk=Join-Path $brokerRoot 'rusty-manifold-broker.apk'
            if (-not (Test-Path -LiteralPath $brokerApk -PathType Leaf)) { throw 'Compatibility build did not produce its APK.' }
            Run-Tool 'compatibility-signature-verify' $apksigner @('verify','--verbose',$brokerApk)
            $brokerReceipt=Get-Content -LiteralPath (Join-Path $brokerRoot 'build-manifest.json') -Raw | ConvertFrom-Json
            if ($brokerReceipt.media_stream_aar_sha256 -cne $aar.aar_sha256 -or $brokerReceipt.media_stream_classes_jar_sha256 -cne $aarInput.classes_jar_sha256 -or @($brokerReceipt.media_session_bindings).Count -ne 2) { throw 'Compatibility host did not consume the same AAR and both explicit bindings.' }
            $brokerZip=[IO.Compression.ZipFile]::OpenRead($brokerApk)
            try {
                $brokerLibraries=@($brokerZip.Entries | Where-Object {$_.FullName.EndsWith('.so')})
                if ($brokerLibraries.Count -ne 1 -or $brokerLibraries[0].FullName -cne 'lib/arm64-v8a/librusty_quest_manifold_broker_authority.so') { throw 'Compatibility host has an unexpected native library composition.' }
                $stream=$brokerLibraries[0].Open()
                try { $nativeHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant() } finally { $stream.Dispose() }
                if ($nativeHash -cne $brokerReceipt.admission_native_library_sha256) { throw 'Compatibility native bytes differ from their build receipt.' }
            } finally { $brokerZip.Dispose() }
            $receipt.artifacts+=@{kind='compatibility-broker-apk';path=$brokerApk;sha256=(File-Hash $brokerApk)}
            $receipt.checks+='compatibility-broker-same-aar-build'
        }
    }
    $receipt.tools+=$rustTools
    if ((@(Input-Inventory) | ConvertTo-Json -Depth 8 -Compress) -cne $sourceText) { throw 'Source inputs changed during the build; this is not a stable checkpoint.' }
    $receipt.status='pass'
} catch {
    $receipt.status='fail';$receipt.error=$_.Exception.Message
    throw
} finally {
    $env:JAVA_HOME=$priorJavaHome
    if ($pushedLocation) { Pop-Location }
    $receipt.finished_at=[DateTimeOffset]::UtcNow.ToString('o')
    $receipt | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
}
[pscustomobject]@{status=$receipt.status;manifest=$receiptPath;artifacts=$receipt.artifacts;device_execution=$false}
