package io.github.mesmerprism.rustyquest.native_renderer;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONObject;
/** Explicitly selected LAN-only read adapter. Native storage remains authoritative. */
final class ExperimentSessionRecordingExport {
 static final String METADATA="rusty.quest.feature.recording_export";
 static boolean selected(Context c){try {android.os.Bundle d=c.getPackageManager().getApplicationInfo(c.getPackageName(),PackageManager.GET_META_DATA).metaData;return d!=null&&d.getBoolean(METADATA,false);}catch(Exception e){return false;}}
 static JSONObject query(JSONObject body)throws Exception {
  JSONObject out=body;String q=ExperimentSessionGatewayProtocol.string(out,"query");
  if("list".equals(q)&&out.length()==3){String before=ExperimentSessionGatewayProtocol.string(out,"before");if(!before.isEmpty()&&!id(before))throw new IllegalArgumentException("recording-id");number(out,"limit",1,16);}
  else if("describe".equals(q)&&out.length()==2){if(!id(ExperimentSessionGatewayProtocol.string(out,"id")))throw new IllegalArgumentException("recording-id");}
  else if("read".equals(q)&&out.length()==5){if(!ExperimentSessionGatewayProtocol.hex(ExperimentSessionGatewayProtocol.string(out,"manifest_id"),64))throw new IllegalArgumentException("recording-manifest");String file=ExperimentSessionGatewayProtocol.string(out,"file");if(!java.util.Arrays.asList("manifest.json","final.json","events.jsonl","polar-acc.jsonl","polar-ecg.jsonl","polar-hr-rr.jsonl","breath.jsonl","radius.jsonl").contains(file))throw new IllegalArgumentException("recording-file");number(out,"offset",0,134217728);number(out,"length",1,32768);}
  else throw new IllegalArgumentException("closed-recording-query-required");return out;
 }
 static boolean id(String s){return s.matches("utc-ns-[0-9]{20}");}
 static long number(JSONObject o,String k,long min,long max){Object n=o.opt(k);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<min||((Number)n).longValue()>max)throw new IllegalArgumentException("recording-integer");return ((Number)n).longValue();}
 static JSONObject read(JSONObject query,String binding)throws Exception {
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("export-io-lane-required");
  byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  StringBuilder fingerprint=new StringBuilder();for(byte b:hash)fingerprint.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  JSONObject request=new JSONObject().put("schema","rusty.quest.experiment_session.command.v1").put("operation","recordings").put("query",query).put("binding",fingerprint.toString());
  return new JSONObject(ControlPanelActivity.applyExperimentSessionCommand(request.toString()));
 }
 static void retire(){try{nativeRetire();}catch(UnsatisfiedLinkError unavailable){/* No initialized runtime can retain an export. */}}
 private static native void nativeRetire();
}
