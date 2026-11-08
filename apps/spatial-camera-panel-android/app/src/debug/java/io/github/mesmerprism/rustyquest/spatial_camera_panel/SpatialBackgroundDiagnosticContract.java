package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/** Closed debug command shape. No Intent, path, URI, native operation or caller JSON. */
public final class SpatialBackgroundDiagnosticContract {
  public static final String SCHEMA="rusty.quest.spatial_background_diagnostic.v1";
  public static final String RESOURCE="rusty_quest_spatial_background_diagnostic_enabled";
  private static final Pattern HEX=Pattern.compile("[0-9a-f]{64}");
  private static final Pattern BOOT=Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  private static final Set<String> BASE=Set.of("nonce","expected_package","expected_pid","expected_uid","expected_start_ticks","expected_boot_id","expected_apk_sha256");
  public static boolean selected(boolean debug,int resource) { return debug&&resource==1; }
  public static void requireCaller(boolean debug,int uid,boolean selected) {
    if(!debug||uid!=2000||!selected)throw new SecurityException("background_diagnostic_closed");
  }
  public enum Mode {
    BLACK("black"), VIDEO("world-video");
    public final String effectiveToken;
    Mode(String token){effectiveToken=token;}
  }
  public static final class ExpectedActor {
    public final String packageId,bootId,apkSha256;
    public final int pid,uid;
    public final long startTicks;
    ExpectedActor(Map<String,Object> fields) {
      packageId=text(fields,"expected_package");
      if(!packageId.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")||packageId.length()>200)bad();
      pid=(int)number(fields,"expected_pid",1,Integer.MAX_VALUE);
      uid=(int)number(fields,"expected_uid",10000,Integer.MAX_VALUE);
      startTicks=number(fields,"expected_start_ticks",1,Long.MAX_VALUE);
      bootId=text(fields,"expected_boot_id");if(!BOOT.matcher(bootId).matches())bad();
      apkSha256=hex(text(fields,"expected_apk_sha256"));
    }
    public boolean matches(SpatialBackgroundDiagnosticRegistry.Actor actor) {
      return actor!=null&&packageId.equals(actor.packageId)&&pid==actor.pid&&uid==actor.uid&&startTicks==actor.startTicks&&bootId.equals(actor.bootId)&&apkSha256.equals(actor.apkSha256);
    }
    String canonical(){return packageId+"\n"+pid+"\n"+uid+"\n"+startTicks+"\n"+bootId+"\n"+apkSha256;}
  }
  public static final class Request {
    public final String method,nonce,ticket,observationSha256,expectedEpoch;
    public final ExpectedActor expected;
    public final Mode mode;
    Request(String method,Map<String,Object> fields) {
      this.method=method;nonce=hex(text(fields,"nonce"));expected=new ExpectedActor(fields);
      if(method.equals("select")) {
        ticket=hex(text(fields,"ticket"));observationSha256=hex(text(fields,"observation_sha256"));expectedEpoch=hex(text(fields,"expected_epoch"));
        String token=text(fields,"mode");
        if(!token.equals("black")&&!token.equals("video"))bad();
        mode=token.equals("black")?Mode.BLACK:Mode.VIDEO;
      } else {ticket=null;observationSha256=null;expectedEpoch=null;mode=null;}
    }
    public String canonical(){return method+"\n"+nonce+"\n"+expected.canonical()+"\n"+ticket+"\n"+observationSha256+"\n"+expectedEpoch+"\n"+mode;}
  }
  public static Request parse(String method,String argument,Map<String,Object> input) {
    if(argument!=null||input==null||(!"observe".equals(method)&&!"select".equals(method)))bad();
    Set<String> keys=new HashSet<>(BASE);
    if(method.equals("select"))keys.addAll(Set.of("ticket","observation_sha256","expected_epoch","mode"));
    if(!input.keySet().equals(keys))bad();
    // Only immutable bounded Strings survive the Binder boundary; no coercion or retained Bundle.
    for(Object value:input.values())if(!(value instanceof String)||((String)value).length()>200)bad();
    return new Request(method,Map.copyOf(input));
  }
  private static String text(Map<String,Object> fields,String key){Object v=fields.get(key);if(!(v instanceof String))bad();return (String)v;}
  private static long number(Map<String,Object> fields,String key,long min,long max){String v=text(fields,key);if(!v.matches("[1-9][0-9]{0,18}"))bad();try{long n=Long.parseLong(v);if(n<min||n>max)bad();return n;}catch(NumberFormatException e){throw new IllegalArgumentException("background_diagnostic_integer");}}
  private static String hex(String value){if(!HEX.matcher(value).matches())bad();return value;}
  private static void bad(){throw new IllegalArgumentException("background_diagnostic_request_rejected");}
  private SpatialBackgroundDiagnosticContract(){}
}
