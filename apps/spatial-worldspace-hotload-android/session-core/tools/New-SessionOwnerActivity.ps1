[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ProfileSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$ProviderManifestSha256,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{64}$')][string]$InventorySha256
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
Import-Module (Join-Path $repo 'tools/lib/NativeRendererExperimentSessionFacade.psm1') -Force
$arguments=@{SessionMembersOnly=$true;standaloneExperimentOwner=$true;isExperimentSessionPanel=$true;standaloneExperimentOwnerJava='true';experimentSessionProfileSha256=$ProfileSha256;experimentSessionProviderManifestSha256=$ProviderManifestSha256;experimentSessionInventorySha256=$InventorySha256}
$members=New-NativeRendererExperimentSessionFacadeSource @arguments
$source="package io.github.mesmerprism.rustyquest.native_renderer;`npublic final class ControlPanelActivity {`n" +
    "    private ControlPanelActivity() { }`n    static { System.loadLibrary(`"rusty_quest_sdk_session_core`"); }`n" +
    "    static void prepareSdkOwner(android.content.Context context) { ownerApplicationContext=context.getApplicationContext(); }`n" +
    "    static native String nativeInitializeExperimentSessionRuntime(String root);`n    static native String nativeApplyBreathCompositionCommand(String command);`n    static native String nativeReadBreathCompositionStatus();`n" + $members + "`n}`n"
if($source -match 'NativeActivity|PanelImmersiveHandoff|SelfKiosk|SoftKiosk|PolarSensorRuntime|ExperimentSessionBle|Gateway|Relay|Lsl'){throw 'Excluded presentation/transport entered SDK facade.'}
$cleanup=@'
    static ConditionAudioRuntime sdkAudioForTransfer() {return conditionAudioRuntime;}
    static SdkAudioCleanup captureSdkAudioCleanup() {return new SdkAudioCleanup(conditionAudioRuntime,breathPhaseAudioOwner);}
    static final class SdkAudioCleanup implements Runnable {
        private final ConditionAudioRuntime audio;
        private final BreathPhaseAudioAndroidOwner phase;
        private boolean complete;
        SdkAudioCleanup(ConditionAudioRuntime audio,BreathPhaseAudioAndroidOwner phase){this.audio=audio;this.phase=phase;}
        synchronized boolean succeeded(){return complete;}
        public synchronized void run(){
            if(complete)return;
            if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("cleanup requires worker");
            boolean audioDone=audio==null,phaseDone=phase==null;
            if(phase!=null){phase.close();try{phaseDone=phase.awaitClosed(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(audio!=null){audio.close();try{audioDone=audio.awaitClosed(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            synchronized(PRESENTATION_LOCK){
                if(phaseDone&&breathPhaseAudioOwner==phase)breathPhaseAudioOwner=null;
                if(audioDone&&conditionAudioRuntime==audio)conditionAudioRuntime=null;
            }
            complete=audioDone&&phaseDone;
        }
    }
'@
$source=$source.Insert($source.LastIndexOf('}'),$cleanup+[Environment]::NewLine)
$package=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) 'io/github/mesmerprism/rustyquest/native_renderer'
New-Item -ItemType Directory -Force -Path $package | Out-Null
$target=Join-Path $package 'ControlPanelActivity.java'
if(Test-Path -LiteralPath $target){throw 'Generated facade target already exists.'}
[IO.File]::WriteAllText($target,$source,[Text.UTF8Encoding]::new($false))
Write-Output $target
