"""Execute actual finite panel control adapters and the typed request registry.

Android/native/Bluetooth resources are bounded doubles. This proves delegation,
private result separation and owner guards, not a live radio or rendered widget.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
from test_original_panel_preservation import methods
from test_experiment_gateway_dedupe import method

PKG = 'io.github.mesmerprism.rustyquest.native_renderer'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--json-jar', type=Path, required=True)
    p.add_argument('--javac', default='javac')
    p.add_argument('--java', default='java')
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    panel = root / 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java' / PKG.replace('.', '/')
    actual = methods((panel / 'BreathCompositionPanelModule.java').read_text(encoding='utf-8'))
    body = 'package ' + PKG + ';\nimport org.json.*;\n' + '''
class ExperimentControlMode {static final int CHOOSE=0,DIRECT=1,REMOTE=2;
''' + method((panel / 'ExperimentControlMode.java').read_text(encoding='utf-8'), 'static final class Snapshot') + '''
}
class ExperimentSessionBleServer {
 static boolean selected=true; static boolean featurePackaged(Object o){return selected;}
 long generation=7,epoch=3;boolean live=true,open;String code="PRIVATE-PAIRING-FIXTURE";
 long currentGeneration(){if(!Thread.holdsLock(this))throw new AssertionError("unlocked-generation");return live?generation:0;}
 long currentNativeEpoch(){if(!Thread.holdsLock(this))throw new AssertionError("unlocked-epoch");return live?epoch:0;}
 String pairingCode(){if(!Thread.holdsLock(this))throw new AssertionError("unlocked-code");return live&&!open?code:"";}
}
class ExperimentSessionPanelCoordinator {
 static final String CONDITION_ONE="condition-a",CONDITION_TWO="condition-b";
 int pending=-1,calls;boolean admit=true;
 int pendingBreathGuidanceBiasPercent(){return pending;}
 boolean updatePendingBreathGuidanceBiasPercent(int value){calls++;if(!admit)return false;pending=value;return true;}
}
class ControlPanelActivity {
 static String one="pattern-ready",two="no-pattern";
 static String conditionBreathGuidanceReadiness(String condition){return "condition-a".equals(condition)?one:two;}
}
class BreathCompositionPanelModule {
 static final ExperimentSessionPanelCoordinator EXPERIMENT_SESSION_PANEL=new ExperimentSessionPanelCoordinator();
 final ExperimentSessionBleServer server=new ExperimentSessionBleServer();boolean available=true;int experimenterGuidanceBiasPercent=-1;
 long nativeModePacked=9;
 boolean localExperimentMutationAllowed(){return new ExperimentControlMode.Snapshot(nativeModePacked).localMutationsAllowed();}
 static class SliderControl{int value=25,calls;void setValue(double requested){value=(int)requested;calls++;}}
 SliderControl experimenterGuidanceBias=new SliderControl(),developerGuidanceBias=new SliderControl();
 void requireProductPanelOperator(){if(!available)throw new IllegalStateException("production-panel-owner-unavailable");}
 ExperimentSessionBleServer remoteServer(){return server;}
 JSONObject observeProductPairingCode()throws Exception
''' + actual['observeProductPairingCode'] + '''
 JSONObject updateProductPendingGuidanceBias(int requested)throws Exception
''' + actual['updateProductPendingGuidanceBias'] + '''
}
class Harness {
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 static JSONObject command(String owner,String id,String action,JSONObject parameters)throws Exception{
  return new JSONObject().put("schema",ProductPanelOperator.COMMAND_SCHEMA).put("operation","invoke")
   .put("owner_token",owner).put("request_id",id).put("action",action).put("parameters",parameters);
 }
 public static void main(String[] args)throws Exception{
  final BreathCompositionPanelModule module=new BreathCompositionPanelModule();
  JSONObject pairing=module.observeProductPairingCode();
  check(pairing.getBoolean("available")&&pairing.getString("pairing_code").equals(module.server.code));
  check(pairing.getLong("server_generation")==7&&pairing.getLong("native_owner_epoch")==3);
  module.server.open=true;pairing=module.observeProductPairingCode();check(!pairing.getBoolean("available")&&!pairing.has("pairing_code"));
  module.server.open=false;module.server.live=false;pairing=module.observeProductPairingCode();check(!pairing.getBoolean("available")&&!pairing.has("pairing_code"));
  module.server.live=true;module.server.epoch=0;check(!module.observeProductPairingCode().has("pairing_code"));module.server.epoch=3;
  ExperimentSessionBleServer.selected=false;check(!module.observeProductPairingCode().has("pairing_code"));ExperimentSessionBleServer.selected=true;
  module.available=false;boolean blocked=false;try{module.observeProductPairingCode();}catch(IllegalStateException expected){blocked=true;}check(blocked);module.available=true;
  ExperimentSessionPanelCoordinator coordinator=module.EXPERIMENT_SESSION_PANEL;
  JSONObject draft=module.updateProductPendingGuidanceBias(40);
  check(draft.getBoolean("admitted")&&draft.getInt("pending_bias_percent")==40&&draft.getInt("requested_bias_percent")==40);
  check(coordinator.calls==1&&module.experimenterGuidanceBiasPercent==40);
  check(module.experimenterGuidanceBias.value==40&&module.developerGuidanceBias.value==40&&module.experimenterGuidanceBias.calls==1&&module.developerGuidanceBias.calls==1);
  for(long nativeMode:new long[]{20,30}){
   module.nativeModePacked=nativeMode;int count=coordinator.calls;boolean denied=false;
   try{module.updateProductPendingGuidanceBias(70);}catch(SecurityException expected){denied=true;}
   check(denied&&coordinator.calls==count&&coordinator.pending==40);
  }
  module.nativeModePacked=29;
  coordinator.admit=false;draft=module.updateProductPendingGuidanceBias(80);
  check(!draft.getBoolean("admitted")&&coordinator.pending==40&&module.experimenterGuidanceBiasPercent==40&&coordinator.calls==2);
  check(module.experimenterGuidanceBias.value==40&&module.developerGuidanceBias.value==40&&module.experimenterGuidanceBias.calls==1&&module.developerGuidanceBias.calls==1);
  coordinator.admit=true;ControlPanelActivity.one="no-pattern";ControlPanelActivity.two="pattern-pending";
  draft=module.updateProductPendingGuidanceBias(80);check(!draft.getBoolean("admitted")&&coordinator.pending==40&&coordinator.calls==2);
  ControlPanelActivity.two="pattern-ready";check(module.updateProductPendingGuidanceBias(0).getBoolean("admitted")&&coordinator.pending==0);
  check(module.updateProductPendingGuidanceBias(100).getBoolean("admitted")&&coordinator.pending==100);
  int before=coordinator.calls;for(int invalid:new int[]{-1,101,Integer.MAX_VALUE}){blocked=false;try{module.updateProductPendingGuidanceBias(invalid);}catch(IllegalArgumentException expected){blocked=true;}check(blocked&&coordinator.calls==before);}
  AutoCloseable registration=ProductPanelOperator.register(new ProductPanelOperator.Owner(){
   public boolean available(){return module.available;}
   public JSONObject observe()throws Exception{return new JSONObject().put("active",module.server.live);}
   public JSONObject invoke(String action,JSONObject p)throws Exception{
    if("ble-pairing-code".equals(action))return module.observeProductPairingCode();
    if("pending-guidance-bias".equals(action))return module.updateProductPendingGuidanceBias(p.getInt("bias_percent"));
    throw new IllegalArgumentException();
   }
  });
  String status=new JSONObject().put("schema",ProductPanelOperator.COMMAND_SCHEMA).put("operation","status").toString();
  String publicStatus=ProductPanelOperator.dispatch(status);check(!publicStatus.contains(module.server.code)&&!publicStatus.contains("pairing_code"));
  String token=new JSONObject(publicStatus).getString("owner_token");
  String raw=command(token,"0123456789abcdef","ble-pairing-code",new JSONObject()).toString();
  JSONObject receipt=new JSONObject(ProductPanelOperator.dispatch(raw));check(receipt.getJSONObject("result").getString("pairing_code").equals(module.server.code));
  check(!receipt.getJSONObject("observation").toString().contains(module.server.code));
  module.server.code="ROTATED-PRIVATE-FIXTURE";
  check(new JSONObject(ProductPanelOperator.dispatch(raw)).getJSONObject("result").getString("pairing_code").equals("PRIVATE-PAIRING-FIXTURE"));
  raw=command(token,"0123456789abcdee","ble-pairing-code",new JSONObject()).toString();
  check(new JSONObject(ProductPanelOperator.dispatch(raw)).getJSONObject("result").getString("pairing_code").equals(module.server.code));
  module.available=false;check("rejected".equals(new JSONObject(ProductPanelOperator.dispatch(command(token,"0123456789abcded","ble-pairing-code",new JSONObject()).toString())).getString("status")));
  registration.close();check("rejected".equals(new JSONObject(ProductPanelOperator.dispatch(raw)).getString("status")));
  System.out.println("PASS "+checks+" finite owner adapters, private pairing result, original draft admission and no replay");
 }
}
'''
    harness = a.output / 'Harness.java'
    harness.write_text(body, encoding='utf-8')
    classes = a.output / 'classes'
    args = [a.javac, '--release', '8', '-encoding', 'UTF-8', '-cp', str(a.json_jar), '-d', str(classes), str(harness), str(panel / 'ProductPanelOperator.java'), str(root / 'apps/native-renderer-android/tests/java' / PKG.replace('.', '/') / 'ProductPanelOperatorTest.java')]
    compiled = subprocess.run(args, capture_output=True, text=True)
    (a.output / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
    compiled.check_returncode()
    outputs = []
    for name in ('Harness', 'ProductPanelOperatorTest'):
        result = subprocess.run([a.java, '-cp', str(classes) + os.pathsep + str(a.json_jar), PKG + '.' + name], capture_output=True, text=True)
        (a.output / (name + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        result.check_returncode()
        outputs.append(result.stdout.strip())
    (a.output / 'RESULT.json').write_text(json.dumps({'passed': True, 'actual_methods': ['observeProductPairingCode', 'updateProductPendingGuidanceBias'], 'actual_protocol': 'ProductPanelOperator', 'outputs': outputs, 'scope': __doc__.strip(), 'device_effects': 0}, indent=2), encoding='utf-8')
    print('\n'.join(outputs))


if __name__ == '__main__':
    main()
