param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$HostJsonJar,[Parameter(Mandatory)][string]$AndroidJar,[Parameter(Mandatory)][string]$OutDir)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$root=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out=[IO.Path]::GetFullPath($OutDir);$target=[IO.Path]::GetFullPath((Join-Path $root 'target'))
if(-not$out.StartsWith($target+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $out)){throw 'Create-new repository target output required'}
if((Get-FileHash $HostJsonJar).Hash.ToLowerInvariant()-cne'3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'){throw 'Pinned host JSON dependency required'}
$null=New-Item -ItemType Directory $out
$main=Join-Path $root 'crates/rusty-quest-media-stream-android/android/library/src/main/java'
$test=Join-Path $root 'crates/rusty-quest-media-stream-android/android/library/src/test/java/io/github/mesmerprism/rustyquest/media/ReceiverStageTraceMain.java'
$receiver=Join-Path $main 'io/github/mesmerprism/rustyquest/media/PackedStereoMediaReceiver.java'
$javac=Join-Path $JavaHome 'bin/javac.exe';$java=Join-Path $JavaHome 'bin/java.exe'
& $javac --release 8 -Xlint:all -Werror -cp $HostJsonJar -sourcepath $main -d (Join-Path $out 'host') $test *> (Join-Path $out 'host-javac.log');if($LASTEXITCODE){throw 'Pure host compilation failed'}
& $java -cp ((Join-Path $out 'host')+[IO.Path]::PathSeparator+$HostJsonJar) io.github.mesmerprism.rustyquest.media.ReceiverStageTraceMain *> (Join-Path $out 'host-tests.log');if($LASTEXITCODE){throw 'Receiver stage trace controls failed'}
& $javac --release 8 -Xlint:all -Werror -cp ($AndroidJar+[IO.Path]::PathSeparator+$HostJsonJar) -sourcepath $main -d (Join-Path $out 'android-typecheck') $receiver *> (Join-Path $out 'android-typecheck.log');if($LASTEXITCODE){throw 'Actual API34 receiver typecheck failed'}
$paths=@($test,$receiver,(Join-Path $main 'io/github/mesmerprism/rustyquest/media/ReceiverStageTrace.java'),$javac,$java,$HostJsonJar,$AndroidJar)
$pins=@($paths|ForEach-Object {@{path=$_;sha256=(Get-FileHash $_).Hash.ToLowerInvariant();size_bytes=(Get-Item $_).Length}})
@{status='passed';pure_controls='silence/backpressure/joins/clocks/generations/censor/reset/bounded samples';receiver_android_typecheck='passed';android_execution=$false;apk_build=$false;device_calls=0;source_and_tool_pins=$pins}|ConvertTo-Json -Depth 20|Set-Content (Join-Path $out 'RESULT.json') -NoNewline
