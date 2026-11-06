[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CompiledClassesDirectory,
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$JavacPath='javac',
    [string]$JavaPath='java'
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$package='io/github/mesmerprism/rustyquest/native_renderer'
$producer=Join-Path $repo "apps/native-renderer-android/src/main/java/$package/NativeMeshUiProducer.java"
$fixture=Join-Path $repo "apps/native-renderer-android/tests/java/$package/NativePanelOwnerAttachmentTest.java"
$out=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $out){throw 'Use create-new bootstrap fixture output.'}
if(-not (Test-Path -LiteralPath (Join-Path $CompiledClassesDirectory "$package/NativeMeshUiProducer.class"))){throw 'Actual Android source closure must compile before this fixture.'}
$source=[IO.File]::ReadAllText($producer)
$fixed='if(retired||owner==null||!bootstrapIssued&&resumed||returnIssued)return false;'
$old='if(retired||owner==null||resumed||returnIssued)return false;'
if(([regex]::Matches($source,[regex]::Escape($fixed))).Count -ne 1){throw 'Production bootstrap claim seam changed.'}
$before=@($producer,$fixture|ForEach-Object {@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()}})
New-Item -ItemType Directory -Path "$out/fixture","$out/old-guard/classes","$out/old-guard/source/$package" -Force|Out-Null
$cp="$CompiledClassesDirectory;$AndroidJar"
& $JavacPath --release 8 -encoding UTF-8 -cp $cp -d "$out/fixture" $fixture *> "$out/fixture-compile.log"
if($LASTEXITCODE){throw 'Production callback fixture compilation failed.'}
& $JavaPath -cp "$out/fixture;$cp" io.github.mesmerprism.rustyquest.native_renderer.NativePanelOwnerAttachmentTest *> "$out/fixture.log"
if($LASTEXITCODE){throw 'Production bootstrap callback fixture failed.'}
# Restore exactly the old resumed-as-focus decision in a copy of the actual
# production class. The same real callback fixture must fail at runtime.
$mutated=Join-Path "$out/old-guard/source/$package" 'NativeMeshUiProducer.java'
[IO.File]::WriteAllText($mutated,$source.Replace($fixed,$old),[Text.UTF8Encoding]::new($false))
& $JavacPath --release 8 -encoding UTF-8 -cp $cp -d "$out/old-guard/classes" $mutated *> "$out/old-guard/compile.log"
if($LASTEXITCODE){throw 'Old-guard negative source must compile; compiler failure is not regression evidence.'}
& $JavaPath -cp "$out/old-guard/classes;$out/fixture;$cp" io.github.mesmerprism.rustyquest.native_renderer.NativePanelOwnerAttachmentTest *> "$out/old-guard/fixture.log"
if($LASTEXITCODE -eq 0 -or -not ([IO.File]::ReadAllText("$out/old-guard/fixture.log").Contains('java.lang.AssertionError'))){throw 'Old bootstrap ordering did not reproduce the production fixture failure.'}
foreach($row in $before){if((Get-FileHash -LiteralPath $row.path).Hash.ToLowerInvariant() -cne $row.sha256){throw 'Bootstrap source changed during fixture.'}}
@{passed=$true;source_inputs=$before;source_stable=$true;old_guard_compiled=$true;old_guard_runtime_assertion_failed=$true;
    scope='Production callback/rendezvous state and exact old predicate mutation. No Android lifecycle/focus or pixel/device proof.';device_effects=0;apk_build=$false}|ConvertTo-Json -Depth 8|Set-Content "$out/RESULT.json" -Encoding utf8
Get-Content "$out/fixture.log"
