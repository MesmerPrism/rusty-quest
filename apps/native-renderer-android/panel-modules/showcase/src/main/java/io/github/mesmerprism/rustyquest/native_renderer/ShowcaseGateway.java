package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-bound app-owned adapter around maintained RQG1 transport producers. */
final class ShowcaseGateway implements AutoCloseable {
    static final int PORT=8767;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ShowcaseGuestRegistry guests=new ShowcaseGuestRegistry();
    private final Set<String> origins=new HashSet<>();
    private final String device,serial;
    private final long epoch=Math.max(1,System.currentTimeMillis());
    private final ShowcaseDiscoveryServer discovery;
    private ShowcaseGatewayHttpServer server;
    private boolean enabled,closed;
    private String nativeEpoch="";
    ShowcaseGateway(Context context)throws Exception {
        this.context=context;
        android.content.SharedPreferences prefs=context.getSharedPreferences("visual_showcase_identity",Context.MODE_PRIVATE);
        String saved=prefs.getString("device","");
        if(!saved.matches("[0-9a-f]{32}")){saved=randomHex(16);if(!prefs.edit().putString("device",saved).commit())throw new IllegalStateException("identity-not-persisted");}
        device=saved;
        String actual="";
        try{actual=android.os.Build.getSerial();}catch(SecurityException ignored){}
        if(actual==null||!actual.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")||"unknown".equalsIgnoreCase(actual))actual=prefs.getString("hardware_serial","");
        serial=actual;
        try(java.io.InputStream input=context.getAssets().open("showcase-controller-origins.json")){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[512];int n;
            while((n=input.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>4096)throw new IllegalArgumentException("origin-config-bound");}
            JSONArray configured=new JSONObject(bytes.toString("UTF-8")).getJSONArray("origins");
            if(configured.length()>8)throw new IllegalArgumentException("origin-config-bound");
            for(int i=0;i<configured.length();i++){String origin=configured.getString(i);if(!origin.matches("https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?"))throw new IllegalArgumentException("origin-config-invalid");origins.add(origin);}
        } catch(java.io.FileNotFoundException absent){/* Native-only until explicit app-owned origin selection. */}
        discovery=new ShowcaseDiscoveryServer(context,new ShowcaseDiscoveryServer.Source(){public JSONObject snapshot()throws Exception{return discoverySnapshot();}});
    }
    static String randomHex(int count){byte[] bytes=new byte[count];new java.security.SecureRandom().nextBytes(bytes);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    void reconcile(JSONObject state)throws Exception {
        String current=state.getString("epoch");
        if(!current.equals(nativeEpoch)){nativeEpoch=current;guests.clear();}
        boolean wanted=!closed&&context.getSharedPreferences("visual_showcase_settings",Context.MODE_PRIVATE).getBoolean("phone_control",false);
        if(wanted!=enabled){enabled=wanted;guests.clear();if(server!=null){server.close();server=null;}if(enabled){server=new ShowcaseGatewayHttpServer(this,PORT);try{server.start();}catch(Exception failed){server.close();server=null;enabled=false;}}}
        discovery.reconcile(Long.parseLong(nativeEpoch),enabled);
    }
    void requestPermissions(android.app.Activity host){discovery.requestPermissions(host);}
    void permissionResult(){discovery.permissionResult();}
    boolean allowedOrigin(String origin){return origins.contains(origin);}
    boolean allowedHost(String host){
        if(host==null)return false;
        try{JSONArray endpoints=addresses();for(int i=0;i<endpoints.length();i++)if(host.equals(new java.net.URI(endpoints.getString(i)).getAuthority()))return true;}
        catch(Exception ignored){}return false;
    }
    private JSONArray addresses()throws Exception {
        JSONArray result=new JSONArray();
        for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces()))for(java.net.InetAddress address:Collections.list(network.getInetAddresses())){
            if(result.length()>=4)return result;
            if(address instanceof Inet4Address&&address.isSiteLocalAddress()&&!address.isLoopbackAddress())result.put("http://"+address.getHostAddress()+":"+PORT+"/v1/gateway");
        }
        return result;
    }
    JSONObject identity()throws Exception {return onMain(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call()throws Exception{return new JSONObject().put("v",1).put("product",ShowcaseNativeBridge.PRODUCT).put("device",device).put("serial",serial).put("epoch",Long.toString(epoch)).put("native_epoch",nativeEpoch).put("enabled",enabled&&!closed).put("remote_available",enabled&&!closed);}});}
    JSONObject connectionDetails()throws Exception{return identity().put("gateways",addresses()).put("discovery",discovery.readback());}
    private JSONObject discoverySnapshot()throws Exception {
        if(!enabled)throw new IllegalStateException("discovery-identity-unavailable");
        return new JSONObject().put("v",1).put("product",ShowcaseNativeBridge.PRODUCT).put("device",device).put("serial",serial).put("epoch",Long.toString(epoch)).put("native_epoch",nativeEpoch).put("mode","remote").put("gateways",addresses());
    }
    JSONObject exchangeLan(JSONObject request)throws Exception {return onMain(new java.util.concurrent.Callable<JSONObject>(){public JSONObject call()throws Exception{
        if(!enabled||closed)throw new SecurityException("phone-control-unavailable");
        if("connect".equals(request.optString("action"))){
            if(request.length()!=5||request.getInt("v")!=1||!ShowcaseNativeBridge.PRODUCT.equals(request.getString("product"))||!device.equals(request.getString("device"))||!Long.toString(epoch).equals(request.getString("epoch")))throw new SecurityException("join-product-or-lifetime-mismatch");
            final String controller=randomHex(16),key=randomHex(32);
            ShowcaseGatewayProtocol protocol=new ShowcaseGatewayProtocol(controller,key,device,epoch,new ShowcaseGatewayProtocol.Owner(){public JSONObject exchange(String action,JSONObject payload)throws Exception{return ShowcaseGateway.this.exchange(action,payload);}});
            guests.add(controller,protocol,android.os.SystemClock.elapsedRealtime());
            return new JSONObject().put("v",1).put("product",ShowcaseNativeBridge.PRODUCT).put("controller",controller).put("device",device).put("epoch",Long.toString(epoch)).put("key",key).put("serial",serial);
        }
        String controller=request.getString("controller");
        ShowcaseGuestRegistry.Guest guest=guests.find(controller,android.os.SystemClock.elapsedRealtime());
        if(guest==null)throw new SecurityException("controller-unavailable");
        JSONObject reply=guest.protocol.exchangeLan(request);
        guests.authenticated(controller,guest.protocol,android.os.SystemClock.elapsedRealtime());return reply;
    }});}
    private JSONObject exchange(String action,JSONObject payload)throws Exception {
        JSONObject state=ShowcaseNativeBridge.status();
        if(!nativeEpoch.equals(state.getString("epoch")))throw new SecurityException("native-owner-changed");
        if("status".equals(action)){if(payload.length()!=0)throw new SecurityException("closed-status-required");return state;}
        if("command".equals(action))return ShowcaseNativeBridge.command(payload);
        if("receipt".equals(action)){if(payload.length()!=1)throw new SecurityException("closed-receipt-required");String id=payload.getString("id");if(id.length()>96)throw new SecurityException("receipt-id-bound");return new JSONObject(ShowcaseNativeBridge.nativeShowcaseReceipt(id));}
        throw new SecurityException("unsupported-action");
    }
    private JSONObject onMain(java.util.concurrent.Callable<JSONObject> action)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();
        FutureTask<JSONObject> task=new FutureTask<>(action);main.post(task);
        try{return task.get(4,TimeUnit.SECONDS);}catch(Exception failed){task.cancel(false);throw failed;}
    }
    public void close(){closed=true;enabled=false;guests.clear();discovery.close();if(server!=null)server.close();server=null;}
}
