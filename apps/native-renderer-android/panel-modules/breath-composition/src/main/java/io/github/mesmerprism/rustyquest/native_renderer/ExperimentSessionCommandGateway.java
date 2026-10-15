package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Optional own-APK command owner adapter. Lifetimes follow initialized native ownership. */
public final class ExperimentSessionCommandGateway {
    public static final String METADATA="rusty.quest.experiment.command_gateway.enabled";
    private static ExperimentSessionCommandGateway process;
    private final Context context;
    private final SharedPreferences preferences;
    private final ExperimentSessionBleServer.Delegate delegate;
    private final ExperimentSessionBleServer carrier;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExperimentSessionControlCapability session=new ExperimentSessionControlCapability();
    private final PolarStopBleCapability polar=new PolarStopBleCapability();
    private ExperimentSessionCommandJournal journal;
    private volatile ExperimentSessionGatewayProtocol protocol;
    private ExperimentSessionGatewayHttpServer http;
    private ExperimentSessionDirectNetwork direct;
    private ExperimentSessionRelayClient relay;
    private long identity,epoch,gatewayEpoch;
    private volatile long lifecycleRevision;
    private String routeState="disabled";
    private String localEnrollmentReference="";
    private long localEnrollmentObserved,localEnrollmentRevision;
    private final java.util.Set<String> localEnrollmentIds=new java.util.HashSet<String>();
    interface LocalEnrollmentGuard { void check() throws Exception; }


    static synchronized ExperimentSessionCommandGateway process(Context context,ExperimentSessionBleServer.Delegate delegate) {
        if(process==null)process=new ExperimentSessionCommandGateway(context.getApplicationContext(),delegate);
        return process;
    }
    public static synchronized ExperimentSessionCommandGateway existing(){return process;}
    public static boolean featurePackaged(Context context) {
        try {android.os.Bundle data=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
            return data!=null&&data.getBoolean(METADATA,false);
        }catch(Exception absent){return false;}
    }
    private ExperimentSessionCommandGateway(Context context,ExperimentSessionBleServer.Delegate delegate) {
        this.context=context;this.delegate=delegate;carrier=ExperimentSessionBleServer.process(context,delegate);
        preferences=context.getSharedPreferences("experiment-session-command-gateway",Context.MODE_PRIVATE);
        if(!preferences.contains("device"))preferences.edit().putString("device",ExperimentSessionBleProtocol.newHex(16)).commit();
    }
    public boolean enabled(){return featurePackaged(context)&&preferences.contains("key")&&preferences.contains("controller");}
    /** Operator readback excludes controller keys, tokens and temporary-network passwords. */
    JSONObject productPanelReadback()throws Exception {
        requireMain();return new JSONObject().put("selected",featurePackaged(context)).put("enabled",enabled())
            .put("trusted_lan",trustedLanAllowed()).put("state",routeState)
            .put("relay_origin",relayOrigin()).put("direct_selected",ExperimentSessionDirectNetwork.featurePackaged(context))
            .put("direct",direct==null?new JSONObject().put("state","inactive"):direct.snapshot())
            .put("relay",relay==null?new JSONObject().put("state","inactive"):relay.snapshot());
    }
    boolean trustedLanAllowed(){return ExperimentSessionGatewayProtocol.lanAllowed(featurePackaged(context),enabled(),identity>0&&epoch>0,preferences.getBoolean("trusted-lan",false));}
    boolean allowedOrigin(String origin) {
        if(origin==null||!origin.matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?"))return false;
        try{android.os.Bundle metadata=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
            String configured=metadata==null?"":metadata.getString("rusty.quest.experiment.command_gateway.origins","");
            for(String item:configured.split(","))if(origin.equals(item.trim()))return true;
        }catch(Exception ignored){}return false;
    }
    String deviceId(){return preferences.getString("device","");}
    String gatewayEpoch(){return protocol==null?"0":Long.toString(gatewayEpoch);}
    String relayOrigin() {
        try{android.os.Bundle data=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
            return data==null||!data.getBoolean("rusty.quest.feature.experiment_control_relay",false)?"":data.getString("rusty.quest.experiment.command_gateway.relay_origin","");
        }catch(Exception absent){return "";}
    }
    void startRelayFromHeadset(String sessionId,String token,long expiresAtMs)throws Exception {
        requireMain();if(protocol==null||relayOrigin().isEmpty())throw new SecurityException("relay-not-selected-or-enrolled");
        if(relay!=null)relay.close();
        relay=new ExperimentSessionRelayClient(new ExperimentSessionRelayClient.Config(relayOrigin(),relayOrigin(),sessionId,token,expiresAtMs),
            new ExperimentSessionRelayClient.Endpoint(){@Override public JSONObject identity()throws Exception{return ExperimentSessionCommandGateway.this.identity();}
                @Override public JSONObject exchange(JSONObject request)throws Exception{return ExperimentSessionCommandGateway.this.exchange(request);}});
        relay.start();
    }
    void startDirectFromHeadset(String mode) {
        requireMain();if(protocol==null||!trustedLanAllowed())throw new SecurityException("trusted-lan-not-approved");
        if(direct==null)direct=new ExperimentSessionDirectNetwork(context);direct.start(mode);
    }
    JSONObject directJoinDetails()throws Exception{return direct==null?new JSONObject().put("state","inactive"):direct.localJoinDetails();}
    void stopRoutesFromHeadset(){requireMain();if(relay!=null)relay.close();relay=null;if(direct!=null)direct.close();direct=null;}
    String localAddresses() {
        StringBuilder result=new StringBuilder();
        try{java.util.Enumeration<java.net.NetworkInterface> interfaces=java.net.NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){java.util.Enumeration<java.net.InetAddress> addresses=interfaces.nextElement().getInetAddresses();
                while(addresses.hasMoreElements()){java.net.InetAddress address=addresses.nextElement();
                    if(address instanceof java.net.Inet4Address&&address.isSiteLocalAddress())result.append("http://").append(address.getHostAddress()).append(":8766/v1/gateway\n");}}
        }catch(Exception ignored){}return result.length()==0?"Waiting for a local network address":result.toString().trim();
    }
    /** Headset UI only: rotates the only controller and explicitly approves unencrypted trusted LAN. */
    JSONObject enrollFromHeadset(boolean trustedLan) throws Exception {
        return enrollLocalController(trustedLan);
    }
    /** Shared rotation/persistence/lifecycle for an attended UI or privileged debug grant. */
    private JSONObject enrollLocalController(boolean trustedLan) throws Exception {
        requireMain();if(!featurePackaged(context))throw new SecurityException("feature-not-selected");
        if(!trustedLan&&relayOrigin().isEmpty())throw new SecurityException("encrypted-relay-not-selected");
        retire();
        String controller=ExperimentSessionBleProtocol.newHex(16),key=ExperimentSessionBleProtocol.newHex(32);
        if(!preferences.edit().putBoolean("trusted-lan",trustedLan).putString("controller",controller).putString("key",key).commit())
            throw new IllegalStateException("enrollment-not-persisted");
        reconcile();return new JSONObject().put("controller",controller).put("key",key).put("device",preferences.getString("device",""));
    }
    /** Privileged debug status only; this capability is never included in public discovery/status. */
    JSONObject localOperatorEnrollmentStatus() throws Exception {
        return onMain(new java.util.concurrent.Callable<JSONObject>(){@Override public JSONObject call()throws Exception {
            clearLocalEnrollmentOffer();
            if(!featurePackaged(context)||identity<=0||epoch<=0||!carrier.acceptsNativeOwner(identity,epoch))
                return new JSONObject().put("available",false);
            JSONObject current=delegate.sessionControlReadback(),status=delegate.snapshot();
            if(!localEnrollmentIdle(current,status))return new JSONObject().put("available",false);
            localEnrollmentReference=ExperimentSessionBleProtocol.newHex(16);
            localEnrollmentObserved=SystemClock.elapsedRealtime();localEnrollmentRevision=lifecycleRevision;
            return new JSONObject().put("available",true).put("reference",localEnrollmentReference)
                .put("native_owner_identity",identity).put("runtime_epoch",epoch)
                .put("lifecycle_revision",lifecycleRevision).put("expires_in_ms",5000);
        }});
    }
    static boolean localEnrollmentIdle(JSONObject current,JSONObject status) {
        return current!=null&&status!=null&&"idle".equals(current.opt("phase"))&&"idle".equals(current.opt("control_state"))
            &&current.has("session_key")&&current.isNull("session_key")
            &&Boolean.FALSE.equals(status.opt("pending"));
    }
    private void clearLocalEnrollmentOffer(){localEnrollmentReference="";localEnrollmentObserved=0;}
    private void requireLocalEnrollmentOwner(long owner,long nativeEpoch,long revision) {
        if(owner<=0||owner!=identity||nativeEpoch<=0||nativeEpoch!=epoch||revision!=lifecycleRevision
                ||!carrier.acceptsNativeOwner(owner,nativeEpoch))throw new SecurityException("local-enrollment-owner-retired");
    }
    /** Called only by the debug-only shell/DUMP provider after exact actor/APK checks. */
    JSONObject enrollFromLocalOperator(final JSONObject input,final LocalEnrollmentGuard guard) throws Exception {
        final JSONObject request=new JSONObject(input.toString());
        return onMain(new java.util.concurrent.Callable<JSONObject>(){@Override public JSONObject call()throws Exception {
            requireMain();
            if(!Boolean.TRUE.equals(request.opt("trusted_lan_consent")))throw new SecurityException("trusted-lan-consent-required");
            final long owner=request.getLong("expected_native_owner_identity"),nativeEpoch=request.getLong("expected_runtime_epoch"),revision=request.getLong("expected_gateway_lifecycle_revision");
            requireLocalEnrollmentOwner(owner,nativeEpoch,revision);
            long now=SystemClock.elapsedRealtime();String id=request.getString("operation_id");
            if(localEnrollmentReference.isEmpty()||!localEnrollmentReference.equals(request.getString("expected_enrollment_reference"))
                    ||localEnrollmentRevision!=revision||now<localEnrollmentObserved||now-localEnrollmentObserved>5000)
                throw new SecurityException("local-enrollment-offer-stale");
            if(!ExperimentSessionBleProtocol.isLowerHex(id,16)||localEnrollmentIds.size()>=128||!localEnrollmentIds.add(id))
                throw new SecurityException("local-enrollment-replay");
            final long deadline=localEnrollmentObserved+5000;
            clearLocalEnrollmentOffer();
            guard.check();
            if(SystemClock.elapsedRealtime()>deadline)throw new SecurityException("local-enrollment-offer-expired");
            if(!localEnrollmentIdle(delegate.sessionControlReadback(),delegate.snapshot()))throw new SecurityException("local-enrollment-requires-idle");
            requireLocalEnrollmentOwner(owner,nativeEpoch,revision);
            JSONObject credentials=enrollLocalController(true);
            try {
                guard.check();
                if(SystemClock.elapsedRealtime()>deadline)throw new SecurityException("local-enrollment-offer-expired");
                if(!localEnrollmentIdle(delegate.sessionControlReadback(),delegate.snapshot()))throw new SecurityException("local-enrollment-owner-changed");
                requireLocalEnrollmentOwner(owner,nativeEpoch,revision);
                return new JSONObject().put("operation_id",id).put("command_status","enrolled")
                    .put("credentials",credentials).put("trusted_lan",true).put("route",routeState)
                    .put("gateway_epoch",Long.toString(gatewayEpoch));
            } catch(Exception changed) {revokeFromHeadset();throw changed;}
        }});
    }
    void revokeFromHeadset(){requireMain();preferences.edit().putBoolean("trusted-lan",false).remove("key").remove("controller").commit();retire();}
    synchronized void nativeOwnerChanged(final long owner,final long nextEpoch,final boolean live) {
        final long revision=++lifecycleRevision;
        if(protocol!=null)protocol.retire();protocol=null;
        main.post(new Runnable(){@Override public void run(){synchronized(ExperimentSessionCommandGateway.this){
            if(revision!=lifecycleRevision)return;applyNativeOwnerChanged(owner,nextEpoch,live);
        }}});
    }
    private void applyNativeOwnerChanged(long owner,long nextEpoch,boolean live) {
        requireMain();
        if(live) {if(owner<=0||nextEpoch<=0)return;retire();identity=owner;epoch=nextEpoch;reconcile();}
        else if(identity==owner&&epoch==nextEpoch){retire();identity=0;epoch=0;}
    }
    private void reconcile() {
        if(!enabled()||identity<=0||epoch<=0)return;
        carrier.attachCommandGateway(true);
        gatewayEpoch=(new java.security.SecureRandom().nextLong()&0xfffffffffffffL)+1;
        final long admittedGatewayEpoch=gatewayEpoch;
        final ExperimentSessionCommandJournal activeJournal=new ExperimentSessionCommandJournal();journal=activeJournal;
        carrier.gatewayBinding(new ExperimentSessionBleServer.GatewayReceiptListener(){
            @Override public void receipt(JSONObject value){try{activeJournal.observe(value);}catch(Exception ignored){}}
            @Override public boolean known(JSONObject body)throws Exception{return activeJournal.known(body);}
            @Override public boolean reserve(JSONObject body)throws Exception{return activeJournal.reserve(body);}
            @Override public JSONObject original(String id)throws Exception{return activeJournal.original(id);}
        },deviceId(),Long.toString(gatewayEpoch));
        protocol=new ExperimentSessionGatewayProtocol(preferences.getString("controller",""),preferences.getString("key",""),
            preferences.getString("device",""),gatewayEpoch,new ExperimentSessionGatewayProtocol.Owner(){
                @Override public JSONObject exchange(final String action,final JSONObject payload)throws Exception {
                    return onMain(new java.util.concurrent.Callable<JSONObject>(){@Override public JSONObject call()throws Exception{
                        if(gatewayEpoch!=admittedGatewayEpoch)throw new SecurityException("gateway-epoch-retired");
                        return ownerExchange(action,payload);
                    }});
                }});
        if(!trustedLanAllowed()){routeState="enrolled-no-lan-listener";return;}
        try {http=new ExperimentSessionGatewayHttpServer(this,8766);http.start();routeState="trusted-lan-listening";}
        catch(Exception unavailable){routeState="listener-unavailable";if(http!=null)http.close();http=null;}
    }
    private void retire() {
        clearLocalEnrollmentOffer();
        stopRoutesFromHeadset();
        if(protocol!=null)protocol.retire();protocol=null;
        if(http!=null)http.close();http=null;
        carrier.gatewayBinding(null,"","0");carrier.attachCommandGateway(false);session.clear();polar.clear();if(journal!=null)journal.retire();journal=null;routeState="disabled";
    }
    public JSONObject identity()throws Exception {
        return onMain(new java.util.concurrent.Callable<JSONObject>(){@Override public JSONObject call()throws Exception {
            return new JSONObject().put("v",1).put("device",preferences.getString("device",""))
                .put("epoch",Long.toString(gatewayEpoch)).put("native_epoch",Long.toString(epoch)).put("enabled",protocol!=null).put("route",routeState);
        }});
    }
    public JSONObject exchange(JSONObject envelope)throws Exception {
        ExperimentSessionGatewayProtocol current=protocol;if(current==null)throw new SecurityException("gateway-unavailable");return current.exchange(envelope);
    }
    private JSONObject onMain(java.util.concurrent.Callable<JSONObject> action)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();
        FutureTask<JSONObject> task=new FutureTask<>(action);main.post(task);
        try{return task.get(5,TimeUnit.SECONDS);}catch(Exception failure){task.cancel(false);throw failure;}
    }
    private JSONObject ownerExchange(String action,JSONObject body)throws Exception {
        requireMain();if(protocol==null||!carrier.acceptsNativeOwner(identity,epoch))throw new SecurityException("native-owner-unavailable");
        if("status".equals(action)){if(body.length()!=0)throw new IllegalArgumentException("empty-status-required");return snapshot();}
        if("receipt".equals(action)) {
            if(body.length()!=1||!ExperimentSessionBleProtocol.isLowerHex(body.optString("id"),16))throw new IllegalArgumentException("receipt-id-required");
            try{snapshot();}catch(Exception unavailable){/* An unavailable new observation cannot erase an original receipt. */}
            return journal.original(body.getString("id"));
        }
        String id=ExperimentSessionGatewayProtocol.string(body,"id");
        if(journal.known(body))return journal.original(id);
        ExperimentSessionBleServer.CommandRequest request=parse(body);
        if(!journal.reserve(body))return journal.original(id);
        carrier.updateReceipt(id,"accepted","Command admitted by authenticated app gateway.");
        try{delegate.dispatch(request);}catch(RuntimeException rejected){carrier.updateReceipt(id,"rejected","App command dispatch failed.");}
        return journal.original(id);
    }
    private JSONObject snapshot()throws Exception {
        JSONObject status=delegate.snapshot();JSONObject nativeStatus=delegate.sessionControlReadback();
        long now=SystemClock.elapsedRealtime(),generation=carrier.currentGeneration();
        JSONObject setup=delegate.polarSetupReadback();if(setup!=null&&"IDLE".equals(status.optString("p")))status.put("y",setup);
        JSONObject polarOffer=polar.offer(delegate.polarStopReadback(),nativeStatus,generation,identity,epoch,now);
        JSONObject offer=session.offer(status,nativeStatus,generation,identity,epoch,now,polar.bindingTuple());
        if(offer!=null)status.put("q",offer);
        if(offer!=null&&polarOffer!=null){polar.shareReference(offer.getString("s"));polarOffer.put("s",offer.getString("s"));}
        // The existing projection owns the exact Polar wire field and capabilities.
        JSONObject projected=new JSONObject(new String(polar.projectBoundedStatus(status,polarOffer),java.nio.charset.StandardCharsets.UTF_8));
        return projected.put("v",1).put("m","gated");
    }
    private ExperimentSessionBleServer.CommandRequest parse(JSONObject body)throws Exception {
        if(body.has("mac")||body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>240)throw new IllegalArgumentException("closed-command-required");
        Object version=body.opt("v");if(!(version instanceof Integer))throw new IllegalArgumentException("integer-version-required");
        int v=(Integer)version;String id=body.getString("id"),op=ExperimentSessionGatewayProtocol.string(body,"op");
        ExperimentSessionBleServer.CommandRequest request;
        long generation=carrier.currentGeneration(),now=SystemClock.elapsedRealtime();
        if(v==1) {
            if(body.length()!=6 || !(body.opt("bias") instanceof Integer))throw new IllegalArgumentException("closed-command-required");
            String condition=ExperimentSessionGatewayProtocol.string(body,"condition"),nonce=ExperimentSessionGatewayProtocol.string(body,"nonce");int bias=body.getInt("bias");
            if(!ExperimentSessionBleProtocol.validCommandFields(id,op,condition,bias,nonce))throw new IllegalArgumentException("invalid-command");
            request=new ExperimentSessionBleServer.CommandRequest(id,op,condition,bias);
        }else if(v==2) {
            if(body.length()!=("set-bias".equals(op)?7:6))throw new IllegalArgumentException("closed-session-command-required");
            int bias=ExperimentSessionBleProtocol.sessionBias(body);
            String ref=ExperimentSessionGatewayProtocol.string(body,"s"),nonce=ExperimentSessionGatewayProtocol.string(body,"n");long revision=ExperimentSessionControlCapability.positive(body,"g");
            if(!ExperimentSessionBleProtocol.validSessionFields(id,op,ref,revision,nonce))throw new IllegalArgumentException("invalid-session-command");
            request=new ExperimentSessionBleServer.CommandRequest(id,op,"",bias,session.admit(ref,revision,op,now,generation,identity,epoch));
        }else if(v==3) {
            PolarStopBleCapability.Wire wire=PolarStopBleCapability.parse(body);JSONObject fence=polar.nativeFence();
            request=new ExperimentSessionBleServer.CommandRequest(id,op,"",0,null,polar.admit(wire.reference,wire.revision,op,now,generation,identity,epoch),fence);
        }else if(v==4) {
            JSONObject setup=ExperimentSessionBleProtocol.parseSetup(body);
            if(setup.getLong("g")!=epoch)throw new SecurityException("setup-epoch-stale");
            request=new ExperimentSessionBleServer.CommandRequest(id,op,"",0);request.setupRequest=setup;
        }else throw new IllegalArgumentException("unsupported-command");
        return request;
    }
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("main-owner-required");}
}
