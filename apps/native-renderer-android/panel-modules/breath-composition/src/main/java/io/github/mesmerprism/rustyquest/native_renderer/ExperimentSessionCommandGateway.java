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
    private long lifecycleRevision;
    private String routeState="disabled";

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
        requireMain();if(!featurePackaged(context))throw new SecurityException("feature-not-selected");
        if(!trustedLan&&relayOrigin().isEmpty())throw new SecurityException("encrypted-relay-not-selected");
        retire();
        String controller=ExperimentSessionBleProtocol.newHex(16),key=ExperimentSessionBleProtocol.newHex(32);
        if(!preferences.edit().putBoolean("trusted-lan",trustedLan).putString("controller",controller).putString("key",key).commit())
            throw new IllegalStateException("enrollment-not-persisted");
        reconcile();return new JSONObject().put("controller",controller).put("key",key).put("device",preferences.getString("device",""));
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
            if(body.length()!=6)throw new IllegalArgumentException("closed-session-command-required");
            String ref=ExperimentSessionGatewayProtocol.string(body,"s"),nonce=ExperimentSessionGatewayProtocol.string(body,"n");long revision=ExperimentSessionControlCapability.positive(body,"g");
            if(!ExperimentSessionBleProtocol.validSessionFields(id,op,ref,revision,nonce))throw new IllegalArgumentException("invalid-session-command");
            request=new ExperimentSessionBleServer.CommandRequest(id,op,"",0,session.admit(ref,revision,op,now,generation,identity,epoch));
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
