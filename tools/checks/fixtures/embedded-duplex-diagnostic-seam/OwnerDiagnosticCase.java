package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import io.github.mesmerprism.rustyquest.media.*;
import org.json.JSONObject;
import java.nio.file.Files;
public final class OwnerDiagnosticCase {
 static Object allocate(Class<?> cls)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),cls);}
 static void set(Object obj,String key,Object value)throws Exception{java.lang.reflect.Field f=obj.getClass().getDeclaredField(key);f.setAccessible(true);f.set(obj,value);}
 static String ticket(String kind,String action)throws Exception{return new JSONObject().put("$schema",MediaOwnerAction.SCHEMA).put("capability","fixture.start").put("executor_generation",1).put("action_id","fixture.action").put("authority_epoch_id","fixture.epoch").put("media_acceptance_authority_revision",1).put("expected_runtime_revision",1).put("client_id","fixture.client").put("lease_id","fixture.lease").put("sequence",1).put("operation","start").put("owner_kind",kind).put("action_kind",action).put("owner_id","fixture.owner").put("provider_kind","fixture.provider").put("resource_id","fixture.resource").toString();}
 static String authority()throws Exception{return new JSONObject().put("$schema","rusty.quest.c1.owner_projection.v1").put("executor_peer_id","peer.fixture").put("route_configuration_sha256","sha256:fixture").put("expires_at_ms",Long.MAX_VALUE).put("authority_provider_epoch_id","fixture.epoch").put("authority_client_id","fixture.client").put("authority_runtime_lease_id","fixture.lease").toString();}
 static final MediaOwnerProvider PROVIDER=new MediaOwnerProvider(){
  public MediaProviderReadback execute(MediaOwnerAction a,CancellationHandle c)throws Exception{throw new java.io.IOException("private fixture message must not escape");}
  public MediaProviderReadback compensate(MediaOwnerAction a,CancellationHandle c){throw new IllegalArgumentException("private cleanup message");}
  public MediaRuntimeSnapshot snapshot(){throw new AssertionError("failed effect never verified");}
 };
 public static void main(String[] args)throws Exception{
  boolean candidate=Boolean.parseBoolean(args[0]);
  if(candidate){
   Throwable[] failures={new java.util.concurrent.TimeoutException(),new java.io.IOException(),new SecurityException(),new IllegalArgumentException(),new IllegalStateException(),new Exception()};
   String[] kinds={"TIMEOUT","IO","SECURITY","ARGUMENT","STATE","STATE"};
   for(int i=0;i<failures.length;i++)if(!kinds[i].equals(EmbeddedDuplexPlatform.failureCategory(new IllegalStateException("private wrapper",failures[i]))))throw new AssertionError("closed cause mismatch");
   if(!"OTHER".equals(EmbeddedDuplexPlatform.failureCategory(new RuntimeException("private unknown"))))throw new AssertionError("plain unknown must stay OTHER");
   if(!"STATE".equals(EmbeddedDuplexPlatform.failureCategory(new IllegalStateException("private outer",new RuntimeException("private inner")))))throw new AssertionError("wrapped unknown lost STATE");
   if(!"IO".equals(EmbeddedDuplexPlatform.failureCategory(new IllegalStateException("private outer",new RuntimeException("private middle",new java.io.IOException("private typed"))))))throw new AssertionError("typed inner must dominate STATE");
   Throwable atLimit=new java.io.IOException("private eighth");for(int i=0;i<7;i++)atLimit=new IllegalStateException("private wrapper",atLimit);
   if(!"IO".equals(EmbeddedDuplexPlatform.failureCategory(atLimit)))throw new AssertionError("eighth typed cause not observed");
   Throwable pastLimit=new IllegalStateException("private ninth",atLimit);
   if(!"STATE".equals(EmbeddedDuplexPlatform.failureCategory(pastLimit)))throw new AssertionError("ninth cause escaped bounded traversal");
   EmbeddedDuplexPlatform bound=(EmbeddedDuplexPlatform)allocate(EmbeddedDuplexPlatform.class);
   set(bound,"failedOwnerStage",EmbeddedDuplexPlatform.OwnerStage.PROVIDER_EXECUTION);set(bound,"failedSinkStage","PROVIDER_GETTER");set(bound,"failedOwnerAction","ARM_RECEIVER");set(bound,"failedOwnerKind","sink");set(bound,"failedCause","ARGUMENT");
   for(EmbeddedDuplexPlatform.ProviderReason reason:EmbeddedDuplexPlatform.ProviderReason.values()){
    set(bound,"failedProviderReason",reason);String json=bound.ownerFailureDiagnostic();if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256)throw new AssertionError("getter exceeds unchanged native bound: "+reason);System.out.println(json);
   }
  }
  for(String kind:new String[]{"route","socket","codec","processor","source"}){
   java.io.File directory=Files.createTempDirectory("owner-diagnostic-fence-").toFile();
   try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(directory,null,null,()->{})){
    EmbeddedDuplexPlatform p=(EmbeddedDuplexPlatform)allocate(EmbeddedDuplexPlatform.class);
    set(p,"display",java.lang.reflect.Proxy.newProxyInstance(EmbeddedDuplexDisplay.class.getClassLoader(),new Class<?>[]{EmbeddedDuplexDisplay.class},(proxy,method,values)->{if(method.getName().equals("ensureLocalCaptureStopped"))return Long.valueOf(1);throw new AssertionError("unexpected display effect");}));set(p,"processCallbacks",fence.callbacks());set(p,"localPeerId","peer.fixture");set(p,"routeConfigurationSha256","sha256:fixture");
    set(p,"registry",new PackagedAndroidMediaOwnerRegistry(1,new MediaProductBinding.Builder("fixture.product").bind(kind,"fixture.owner","fixture.provider","fixture.resource",PROVIDER).build()));
    set(p,"failedOwnerStage",EmbeddedDuplexPlatform.OwnerStage.NONE);set(p,"failedSinkStage","NONE");set(p,"failedOwnerAction","NONE");set(p,"failedProviderReason",EmbeddedDuplexPlatform.ProviderReason.NONE);
    if(candidate){set(p,"failedOwnerKind","NONE");set(p,"failedCause","NONE");}
    try{p.executeAndVerify(authority(),ticket(kind,"start"),false);throw new AssertionError("actual full callback must fail");}catch(IllegalStateException expected){if(!(expected.getCause() instanceof java.io.IOException))throw expected;}
    String first=p.ownerFailureDiagnostic();JSONObject value=new JSONObject(first);
    if(!"OTHER".equals(value.getString("provider_reason"))||!"START".equals(value.getString("action")))throw new AssertionError(first);
    if(candidate){if(!kind.equals(value.getString("owner"))||!"IO".equals(value.getString("cause")))throw new AssertionError(first);}else if(value.has("owner")||value.has("cause"))throw new AssertionError("baseline unexpectedly has family");
    try{p.executeAndVerify(authority(),ticket(kind,"stop"),true);throw new AssertionError("compensation must fail");}catch(IllegalArgumentException expected){}
    if(!first.equals(p.ownerFailureDiagnostic()))throw new AssertionError("compensation erased primary failure");
    if(first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256||first.contains("private"))throw new AssertionError("diagnostic bounds/private message");
    System.out.println(first);
   }
  }
 }
}
