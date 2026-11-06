[CmdletBinding()]
param([string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $RepoRoot 'tools/lib/NativeRendererAppStatusBridge.psm1') -Force
$temp = Join-Path ([IO.Path]::GetTempPath()) ('rq-ble-app-bridge-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temp
try {
    foreach ($selected in @($false,$true)) {
        $dir = Join-Path $temp ([string]$selected)
        $null = New-Item -ItemType Directory -Path $dir
        $context = Join-Path $dir 'Context.java'
        [IO.File]::WriteAllText($context,'package android.content; public class Context { public Context getApplicationContext(){return this;} }')
        $bridge = Join-Path $dir 'NativeRendererAppStatusBridge.java'
        [IO.File]::WriteAllText($bridge,(New-NativeRendererAppStatusBridgeSource -BleSelected:$selected))
        $harness = Join-Path $dir 'BridgeTest.java'
        $sources = @($context,$harness)
        if ($selected) {
            $owners = Join-Path $dir 'Owners.java'
            [IO.File]::WriteAllText($owners,@'
package io.github.mesmerprism.rustyquest.native_renderer;
class ControlPanelActivity {static int prepares;static void prepareExperimentSessionAppOwner(android.content.Context c){prepares++;}}
class BreathCompositionPanelModule {static int prepares,changes;static long identity,epoch;static boolean live;
 static void prepareExperimentSessionBleOwner(android.content.Context c){prepares++;}
 static void experimentSessionBleOwnerChanged(long i,long e,boolean l){changes++;identity=i;epoch=e;live=l;}}
'@)
            $sources += $owners
            [IO.File]::WriteAllText($harness,@'
package io.github.mesmerprism.rustyquest.native_renderer;
public class BridgeTest {public static void main(String[] args){
 android.content.Context c=new android.content.Context();
 NativeRendererAppStatusBridge.changed(1,7,true);
 if(BreathCompositionPanelModule.changes!=0)throw new AssertionError("unprepared bridge has no effects");
 NativeRendererAppStatusBridge.prepare(c);NativeRendererAppStatusBridge.prepare(c);
 if(ControlPanelActivity.prepares!=1||BreathCompositionPanelModule.prepares!=1)throw new AssertionError("app context/delegate prepared once without Hub");
 NativeRendererAppStatusBridge.changed(1,7,true);NativeRendererAppStatusBridge.changed(1,7,false);
 if(BreathCompositionPanelModule.changes!=2||BreathCompositionPanelModule.identity!=1||BreathCompositionPanelModule.epoch!=7||BreathCompositionPanelModule.live)throw new AssertionError("exact native identity epoch live/drop forwarded");
 System.out.println("Production generated BLE-only bridge controls PASS");}}
'@)
        } else {
            [IO.File]::WriteAllText($harness,@'
package io.github.mesmerprism.rustyquest.native_renderer;
public class BridgeTest {public static void main(String[] args){NativeRendererAppStatusBridge.prepare(new android.content.Context());NativeRendererAppStatusBridge.changed(1,7,true);NativeRendererAppStatusBridge.changed(1,7,false);System.out.println("Production generated feature-off/no-panel bridge controls PASS without owner classes");}}
'@)
        }
        $rsp = Join-Path $dir 'sources.rsp'
        $null = Write-NativeRendererJavaSourceResponse -SourceFiles $sources -GeneratedAppStatusBridgePath $bridge -Path $rsp
        & javac --release 8 -d $dir "@$rsp"
        if($LASTEXITCODE -ne 0){throw "Bridge compilation failed: Selected=$selected"}
        & java -cp $dir io.github.mesmerprism.rustyquest.native_renderer.BridgeTest
        if($LASTEXITCODE -ne 0){throw "Bridge execution failed: Selected=$selected"}
    }
} finally {
    # Only the explicit task-created temporary directory is removed.
    if((Split-Path $temp -Parent) -cne [IO.Path]::GetTempPath().TrimEnd('\','/')){throw 'Unexpected temporary cleanup target'}
    Remove-Item -LiteralPath $temp -Recurse -Force
}
