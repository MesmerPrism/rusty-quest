"""Execute extracted production callback/scheduler bodies with inert Android boundaries.

This validates owner decisions, not physical Bluetooth behavior. The source bodies
are extracted on every run; fixture methods only record platform effects.
"""
import hashlib, pathlib, subprocess, tempfile, sys, json

root = pathlib.Path(__file__).resolve().parents[2]
pkg = 'io/github/mesmerprism/rustyquest/native_renderer'
panel = root / 'apps/native-renderer-android/panel-modules/polar/src/main/java' / pkg / 'PolarSensorPanel.java'
runtime = panel.with_name('PolarSensorRuntime.java')

def block(text, marker):
    start = text.index(marker)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

text = panel.read_text(encoding='utf-8-sig')
markers = ['private void handleConnectionState(', 'private void scheduleAutomaticRecovery(String detail) {',
           'private void scheduleAutomaticRecovery(String detail, boolean announceRetry)',
           'private void finishAutomaticConnectionCycle()', 'private void cancelAutomaticRecovery(',
           'private void scheduleAutomaticConnectionTimeout(', 'private void disconnect()',
           'private void completeAutoScan(', 'private void stopPmd()', 'void stopSpecificPmd(String mode)',
           'private void stopAllPmd()', 'private void beginStreams(',
           'private void requestUndesiredStreamStop(', 'private void completeUndesiredStreamStop(',
           'private void handlePmdControlRecord(', 'void ensureAutoConnection()',
           'private void beginAutomaticConnectionCycle()']
bodies = [block(text, marker) for marker in markers]
runtime_text = runtime.read_text(encoding='utf-8-sig')
support = '\n'.join(block(runtime_text, marker) for marker in
                    ['final class PolarRecoveryIntent', 'final class PolarAutoConnectionPolicy'])
fixture = r'''
package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;
final class BluetoothGatt {
 static final int GATT_SUCCESS=0, CONNECTION_PRIORITY_HIGH=1;
 void requestConnectionPriority(int value) {} void discoverServices() {}
}
final class BluetoothProfile { static final int STATE_CONNECTED=2, STATE_DISCONNECTED=0; }
final class SystemClock { static long elapsedRealtime(){ return 100; } }
final class Looper { static final Looper MAIN=new Looper();static Looper myLooper(){return MAIN;}static Looper getMainLooper(){return MAIN;} }
public final class PolarRecoveryCallbacksHost {
 boolean closing, connected, manualConnectionControl, automaticConnectionRequested;
 boolean automaticConnectionRetryScheduled, automaticConnectionCycleActive;
 long automaticRecoveryGeneration, autoUniqueCandidateScheduledGeneration, autoScanGeneration=7;
 long automaticConnectionDeadlineElapsedMs, generation=1;
 int autoConnectionAttempts, selectedDeviceIndex, attempts, closes, admissions;
 static final int AUTO_CONNECTION_MAX_ATTEMPTS=2;
 static final long AUTO_CONNECTION_RETRY_DELAY_MS=2000, AUTO_CONNECTION_CYCLE_DELAY_MS=10000, AUTO_CONNECTION_TIMEOUT_MS=15000;
 final Object connectionIdentityLock=new Object();
 BluetoothGatt gatt;
 String connectedLabel="none", connectedDeviceInstanceId="none", pendingConnectionLabel="H10", pendingConnectionDeviceInstanceId="sensor-A";
 final PolarRecoveryIntent recoveryIntent=new PolarRecoveryIntent();
 final Handler handler=new Handler();
 final List<DeviceEntry> devices=new ArrayList<DeviceEntry>();
 Object pmdControlCharacteristic=new Object(),pmdDataCharacteristic=new Object(),batteryCharacteristic;
 boolean pmdReady,accPmdRunning,ecgPmdRunning,startAllPending,stopAllPending,commandInFlight;
 long pmdFlowGeneration,pendingCommandGeneration;
 int writes;
 String pendingPmdMode="acc",startedMode="none",pendingCommand="none",activePmdMode="none";
 static class Settings { String joinSampleRates(){return "200";} String joinResolutions(){return "16";} String joinRanges(){return "8";} }
 static class ControlRecord { int opCode,measurementType,errorCode; Settings settings=new Settings(); boolean hasSettings(){return false;} }
 static class DeviceEntry { final String id; DeviceEntry(String id){this.id=id;} String instanceId(){return id;} }
 static class Handler {
   final List<Runnable> tasks=new ArrayList<Runnable>(); final List<Long> delays=new ArrayList<Long>();
   void postDelayed(Runnable r,long delay){tasks.add(r);delays.add(delay);}
   void post(Runnable r){r.run();}
   void run(int i){tasks.get(i).run();}
 }
 void closeGatt(){gatt=null;connected=false;closes++;}
 void setStatusState(String a,String b){} void setAutomaticConnectionState(String a,String b){}
 void marker(String a){} void updateCounters(){} void stopScan(){}
 void setStatus(String s){} void appendStatusEvent(String s){}
 boolean streamRunning(String s){return "acc".equals(s)?accPmdRunning:ecgPmdRunning;}
 byte[] buildStopCommand(String s){return new byte[0];}
 void writePmdCommand(String s,byte[] p){writes++;pendingCommand=s;pendingCommandGeneration=pmdFlowGeneration;commandInFlight=true;}
 void setStreamRunning(String mode,boolean active){if("acc".equals(mode))accPmdRunning=active;else ecgPmdRunning=active;}
 int measurementTypeInt(String mode){return "acc".equals(mode)?2:0;}
 void savePmdSettings(int type,Settings s){} String markerToken(String s){return s;}
 void startPmdWithCurrentSettings(long generation){} boolean tryNextStartCandidate(long generation,String why){return false;}
 boolean isCurrentConnectedGatt(BluetoothGatt current){return !closing&&connected&&current!=null&&current==gatt;}
 void scheduleBatteryRead(BluetoothGatt current,Object characteristic){}
 void beginPmdStartFlow(String mode){startedMode=mode;}
 void rememberConnectedPairing(){} String selectedPmdMode(){return "acc";}
 void runAutomaticConnectionAttempt(){attempts++;}
 void ensureAutoConnectionGeneration(){}
 long currentAutoConnectionGeneration(){return generation;}
 void advanceAutoConnectionGeneration(){generation++;}
 int pairedCandidateMatches(){return 0;} int pairedCandidateIndex(){return -1;}
 void connectAdmittedCandidate(DeviceEntry entry,boolean automatic){admissions++;pendingConnectionDeviceInstanceId=entry.id;}
 static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
 static PolarRecoveryCallbacksHost connectedOwner(){
   PolarRecoveryCallbacksHost h=new PolarRecoveryCallbacksHost();h.gatt=new BluetoothGatt();h.manualConnectionControl=true;
   h.handleConnectionState(h.gatt,0,2);
   check(h.connected&&h.automaticConnectionRequested&&h.recoveryIntent.connectionWanted(),"actual successful manual callback arms recovery");return h;
 }
 public static void main(String[] args){
   PolarRecoveryCallbacksHost h=connectedOwner();BluetoothGatt original=h.gatt;
   h.handleConnectionState(new BluetoothGatt(),8,0);check(h.connected&&h.closes==0,"stale GATT failure denied");
   h.handleConnectionState(original,8,0);check(!h.connected&&h.closes==1&&h.handler.tasks.size()==1,"actual status8 schedules one recovery");
   check(h.handler.delays.get(0)==2000,"recovery has bounded nonzero backoff");
   h.handler.run(0);check(h.attempts==1,"current recovery callback attempts once");
   h=connectedOwner();h.handleConnectionState(h.gatt,0,0);h.disconnect();h.handler.run(0);
   check(h.attempts==0&&!h.automaticConnectionRequested&&!h.recoveryIntent.connectionWanted(),"deliberate disconnect cancels queued recovery");
   h.ensureAutoConnection();check(h.attempts==0&&!h.automaticConnectionRequested,"actual startup request cannot rearm deliberate disconnect on same owner");
   h=connectedOwner();h.connected=false;h.scheduleAutomaticConnectionTimeout(h.generation,h.gatt);h.generation++;h.handler.run(0);
   check(h.closes==0,"stale generation timeout cannot retire current transport");
   h=connectedOwner();h.connected=false;h.scheduleAutomaticConnectionTimeout(h.generation,h.gatt);h.handler.run(0);
   check(h.closes==1&&h.handler.tasks.size()==2,"current actual timeout retires exact handle then schedules recovery");
   h=connectedOwner();h.connected=false;h.devices.add(new DeviceEntry("sensor-B"));h.completeAutoScan(7);
   check(h.admissions==0&&h.handler.tasks.size()==1&&!h.manualConnectionControl,"different unique sensor cannot replace recovering selection");
   h.devices.add(new DeviceEntry("sensor-A"));h.autoScanGeneration=8;h.completeAutoScan(8);
   check(h.admissions==1&&"sensor-A".equals(h.pendingConnectionDeviceInstanceId),"multiple candidates admit only exact retained identity");
   h=connectedOwner();h.connected=false;h.devices.add(new DeviceEntry("sensor-A"));h.completeAutoScan(6);
   check(h.admissions==0,"stale scan generation denied");
   h=connectedOwner();h.handleConnectionState(h.gatt,8,0);h.closing=true;h.handler.run(0);
   check(h.attempts==0,"owner stop denies queued recovery even before handler drain");
   h=connectedOwner();h.recoveryIntent.startStream("ecg");h.gatt=null;h.stopPmd();
   check(!h.recoveryIntent.streamWanted("acc")&&h.recoveryIntent.streamWanted("ecg")&&h.automaticConnectionRequested,"actual offline individual stop clears only ACC resume");
   h.gatt=new BluetoothGatt();h.connected=true;h.beginStreams(h.gatt);
   check("ecg".equals(h.startedMode),"actual subscription restore resumes remaining ECG only");
   h=connectedOwner();h.recoveryIntent.startStream("ecg");h.stopSpecificPmd("ecg");
   check(h.recoveryIntent.streamWanted("acc")&&!h.recoveryIntent.streamWanted("ecg")&&h.automaticConnectionRequested,
     "explicit ECG stop ignores ambient ACC mode and retains ACC/HR intent");
   h=new PolarRecoveryCallbacksHost();h.automaticConnectionRequested=true;h.stopSpecificPmd("ecg");
   h.recoveryIntent.connected("sensor-A","acc");h.gatt=new BluetoothGatt();h.connected=true;h.beginStreams(h.gatt);
   check("acc".equals(h.startedMode)&&!h.recoveryIntent.streamWanted("ecg")&&h.automaticConnectionRequested,
     "cold explicit other-stream stop preserves latent automatic ACC default without starting it at stop dispatch");
   h=new PolarRecoveryCallbacksHost();h.stopPmd();h.recoveryIntent.connected("sensor-A","acc");
   check(!h.recoveryIntent.streamWanted("acc"),"legacy cold selected StopPMD still suppresses its default");
   h=connectedOwner();h.recoveryIntent.startStream("ecg");h.beginStreams(h.gatt);
   check(h.startAllPending&&"acc".equals(h.startedMode),"actual parallel restore starts ACC then queues ECG intent");
   h=connectedOwner();h.stopAllPmd();h.beginStreams(h.gatt);
   check("none".equals(h.startedMode)&&h.automaticConnectionRequested,"actual stopped PMD cannot silently restart on subscription; HR intent remains");
   h=connectedOwner();h.pendingCommand="start_stream";h.commandInFlight=true;h.stopPmd();
   ControlRecord ack=new ControlRecord();ack.opCode=2;ack.measurementType=2;h.handlePmdControlRecord(ack);
   check(h.accPmdRunning&&h.writes==0,"actual START ACK remains active truth until a STOP can dispatch");
   h.commandInFlight=false;h.handler.run(0);
   check(h.writes==1&&"stop_stream_only".equals(h.pendingCommand)&&h.accPmdRunning,"cancelled START sends exactly one STOP without falsely marking stopped");
   ack.opCode=3;ack.errorCode=1;h.handlePmdControlRecord(ack);
   check(h.accPmdRunning,"rejected STOP ACK never certifies stopped");
   h.commandInFlight=false;h.handler.run(1);check(h.writes==1,"rejected STOP cannot be replayed by duplicate pending callbacks");
   h=connectedOwner();h.accPmdRunning=true;h.stopPmd();ack.errorCode=0;h.handlePmdControlRecord(ack);
   check(!h.accPmdRunning,"successful exact STOP ACK certifies stream stopped");
   h=connectedOwner();h.accPmdRunning=true;h.commandInFlight=true;h.stopPmd();h.recoveryIntent.startStream("acc");h.commandInFlight=false;h.handler.run(0);
   check(h.writes==0,"new per-stream start intent cancels delayed old stop");
   h=connectedOwner();h.accPmdRunning=true;h.commandInFlight=true;h.stopPmd();h.gatt=new BluetoothGatt();h.commandInFlight=false;h.handler.run(0);
   check(h.writes==0,"replacement GATT cancels delayed stop");
   h=connectedOwner();h.connected=false;h.autoConnectionAttempts=2;h.scheduleAutomaticRecovery("out of range");
   check(h.handler.delays.get(0)==10000,"exhausted short cycle uses capped backoff rather than permanent give-up");h.handler.run(0);
   check(h.attempts==1&&h.autoConnectionAttempts==0,"new recovery cycle resets bounded attempt budget while intent remains");
   System.out.println("Production Polar callback/timeout controls PASS");
 }
'''
fixture += '\n' + '\n'.join(bodies) + '\n}\n' + support
with tempfile.TemporaryDirectory(prefix='polar-recovery-controls-') as tmp:
    output=pathlib.Path(tmp)
    source=output/'PolarRecoveryCallbacksHost.java'
    source.write_text(fixture,encoding='utf-8')
    subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',tmp,str(source)],check=True)
    subprocess.run(['java','-cp',tmp,'io.github.mesmerprism.rustyquest.native_renderer.PolarRecoveryCallbacksHost'],check=True)

# Execute the actual UI entrypoint and runtime entrypoint with inert Android
# projection/dispatch boundaries. Retired callbacks must not call a factory,
# read a replacement projection, dispatch a stop, or persist replacement status.
panel_entry=block(text,'void dispatchProjectedPanelStop(String operation)')
runtime_entry=block(runtime_text,'void dispatchProjectedFromPanel(PolarSensorPanel source,String operation)')
owner_fixture=r'''
package io.github.mesmerprism.rustyquest.native_renderer;
import java.util.*;
import java.nio.charset.StandardCharsets;
class JSONObject {
 final Map<String,Object> fields=new HashMap<String,Object>();
 JSONObject put(String key,Object value){fields.put(key,value);return this;}
 Object get(String key)throws Exception{if(!fields.containsKey(key))throw new Exception("missing");return fields.get(key);}
 String getString(String key)throws Exception{return (String)get(key);}
}
class Log{static void i(String tag,String message){}}
class PolarProjectedStopActions{static final String SCHEMA="fixture";}
class PolarSensorPanel{
 final PolarSensorRuntime originatingRuntime;int rejected,feedback;
 PolarSensorPanel(PolarSensorRuntime owner){originatingRuntime=owner;}
 void showProjectedStopRejection(String operation,String reason){rejected++;}
 void showProjectedStopFeedback(String operation,JSONObject response){feedback++;}
 PANEL_ENTRY
}
class PolarSensorRuntime{
 static PolarSensorRuntime instance;static int factories;
 static final String TAG="fixture",MARKER_PREFIX="fixture";
 boolean closed;int projections,dispatches;
 final PolarSensorPanel panel=new PolarSensorPanel(this);
 final Projector projectedStops=new Projector();
 static PolarSensorRuntime forApplication(Object ignored){factories++;return instance;}
 class Projector{JSONObject project(Object ignored){projections++;JSONObject s=new JSONObject();
   for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor","acc_revision","ecg_revision"})s.put(field,field);
   return s.put("stop_acc_operation_id","offered-acc").put("stop_ecg_operation_id","offered-ecg");}}
 Object projectedTarget(){return this;}
 JSONObject dispatchProjected(String encoded,String token,String origin){dispatches++;return new JSONObject();}
 RUNTIME_ENTRY
}
public class PolarProjectedUiOwnerHost{
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] ignored){
   PolarSensorRuntime old=new PolarSensorRuntime();PolarSensorRuntime.instance=old;
   old.panel.dispatchProjectedPanelStop("stop_acc");
   check(old.dispatches==1&&old.projections==1&&old.panel.feedback==1,"valid originating panel routes to common adapter");
   PolarSensorRuntime fresh=new PolarSensorRuntime();PolarSensorRuntime.instance=fresh;old.closed=true;
   old.panel.dispatchProjectedPanelStop("stop_ecg");
   check(old.dispatches==1&&old.projections==1&&fresh.dispatches==0&&fresh.projections==0&&old.panel.rejected==1,"closed retired callback neither creates nor adopts a replacement owner");
   old.closed=false;old.panel.dispatchProjectedPanelStop("stop_acc");
   check(old.dispatches==1&&old.projections==1&&old.panel.rejected==2,"replaced actual owner denied even without a closed flag");
   fresh.dispatchProjectedFromPanel(old.panel,"stop_ecg");
   check(fresh.dispatches==0&&fresh.projections==0&&old.panel.rejected==3,"mismatched panel cannot invoke fresh owner");
   fresh.dispatchProjectedFromPanel(null,"stop_acc");
   check(fresh.dispatches==0&&PolarSensorRuntime.factories==0,"no implicit factory or null-source dispatch");
   fresh.panel.dispatchProjectedPanelStop("stop_ecg");
   check(fresh.dispatches==1&&fresh.panel.feedback==1,"current replacement panel works through its own adapter");
   System.out.println("Production Polar UI actual-entrypoint stale/closed/mismatched panel ownership controls PASS");
 }
}
'''.replace('PANEL_ENTRY',panel_entry).replace('RUNTIME_ENTRY',runtime_entry)
with tempfile.TemporaryDirectory(prefix='polar-projected-ui-owner-') as tmp:
    output=pathlib.Path(tmp)
    source=output/'PolarProjectedUiOwnerHost.java';source.write_text(owner_fixture,encoding='utf-8')
    android=output/'android'/'util';android.mkdir(parents=True)
    base64=android/'Base64.java'
    base64.write_text('package android.util;public class Base64{public static final int NO_WRAP=2;public static String encodeToString(byte[] data,int flags){return java.util.Base64.getEncoder().encodeToString(data);}}',encoding='utf-8')
    subprocess.run(['javac','--release','8','-encoding','UTF-8','-d',tmp,str(source),str(base64)],check=True)
    subprocess.run(['java','-cp',tmp,'io.github.mesmerprism.rustyquest.native_renderer.PolarProjectedUiOwnerHost'],check=True)
print(json.dumps({'panel_sha256':hashlib.sha256(panel.read_bytes()).hexdigest(),
                  'runtime_sha256':hashlib.sha256(runtime.read_bytes()).hexdigest(),
                  'extracted_methods':markers,'projected_ui_entrypoints':['dispatchProjectedPanelStop','dispatchProjectedFromPanel'],
                  'scope':'host production owner decisions; inert Android transport effects'}))
