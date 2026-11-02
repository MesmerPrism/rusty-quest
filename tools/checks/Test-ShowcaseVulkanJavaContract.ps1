param([Parameter(Mandatory)][string]$ClassesDir,[Parameter(Mandatory)][string]$JavaHome)
$ErrorActionPreference='Stop'
$javap=Join-Path $JavaHome 'bin/javap.exe'
function Read-Class([string]$Name){$value=(& $javap -classpath $ClassesDir -private -s "io.github.mesmerprism.rustyquest.native_renderer.$Name") -join "`n";if($LASTEXITCODE -ne 0){throw "Missing actual selected class: $Name"};return $value}
$facade=Read-Class 'ControlPanelActivity'
if($facade -notmatch 'public final class [^\r\n]+\.ControlPanelActivity \{' -or $facade -match 'class [^\r\n]+ extends android.app.Activity'){throw 'Selected showcase facade must be a plain reflection adapter.'}
$required=@{
 'attachNativeMeshSessionControls'='(Landroid/app/Activity;Landroid/widget/LinearLayout;Ljava/util/function/BooleanSupplier;)Ljava/lang/AutoCloseable;'
 'ownsPanelActivity'='(Landroid/app/Activity;)Z'
 'resumeNativeMeshImmersive'='(Landroid/app/Activity;)V'
 'openShowcasePanel'='(Landroid/content/Context;)V'
 'toggleShowcasePanel'='(Landroid/content/Context;)V'
}
foreach($name in $required.Keys){if($facade -notmatch ('public static [^\r\n]+ '+$name+'\([^\r\n]*\)[^\r\n]*\r?\n\s*descriptor: '+[regex]::Escape($required[$name]))){throw "Missing exact reflected showcase method: $name"}}
$hostDescription=Read-Class 'ShowcaseNativeActivity'
if($hostDescription -notmatch 'public final class [^\r\n]+ extends android.app.NativeActivity'){throw 'Selected showcase host is not a genuine NativeActivity.'}
if($hostDescription -notmatch 'public boolean showcaseOwnerResumed\(\);\r?\n\s*descriptor: \(\)Z'){throw 'Producer startup lifecycle reflection witness missing.'}
$bridge=Read-Class 'ShowcaseNativeBridge'
if($bridge -notmatch ('nativeShowcaseInitialize\([^\r\n]+\);\r?\n\s*descriptor: '+[regex]::Escape('(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'))){throw 'Trusted migrations initializer descriptor mismatch.'}
$producer=Read-Class 'NativeMeshUiProducer'
foreach($descriptor in @('(J)Ljava/lang/String;','(JZ)J','(JJ)Ljava/lang/String;')){if(-not $producer.Contains("descriptor: $descriptor")){throw 'Maintained producer visibility JNI signature missing.'}}
Write-Output 'PASS actual compiled genuine host, non-Activity reflection facade, owner/attach/resume contracts, three-string initializer and maintained producer JNI descriptors.'
