import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.net.wifi.p2p.*;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.net.Inet4Address;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Original-only station compensation. No temporary profile or credential input. */
public final class QuestOriginalStationGuard {
  enum Phase { arguments, uid, config_load, config_contract, config_path, host_identity, main_looper, activity_thread, shell_context, binder_services, wifi_manager, connectivity_manager, callback_thread, p2p_service, p2p_channel, dispatch, snapshot_host, configured_networks, profile_projection, snapshot_join }
  static Phase diagnosticPhase=Phase.arguments;
  static final class DeviceInventoryConfig {
    final String run,serial,boot; final long deadline;
    DeviceInventoryConfig(Properties p,long now) {
      if(!p.stringPropertyNames().equals(new HashSet<>(Arrays.asList("run_token","serial","boot_id","deadline_elapsed_realtime_ms"))))throw new IllegalArgumentException("inventory_keys");
      run=p.getProperty("run_token");serial=p.getProperty("serial");boot=p.getProperty("boot_id");deadline=Long.parseLong(p.getProperty("deadline_elapsed_realtime_ms"));
      if(!run.matches("[0-9a-f]{32}")||!serial.matches("[A-Za-z0-9]{8,32}")||!boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("inventory_identity");
      fresh(now);
    }
    void fresh(long now){if(now<0||deadline<=now||deadline-now>30000)throw new SecurityException("inventory_deadline");}
    void host(String actualSerial,String actualBoot){if(!serial.equals(actualSerial)||!boot.equals(actualBoot))throw new SecurityException("inventory_host");}
  }
  static String inventoryMac(String first,String second) {
    if(first==null||second==null||!first.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")||!first.equalsIgnoreCase(second))throw new SecurityException("inventory_mac_unavailable");
    String mac=first.toLowerCase(Locale.ROOT);
    if(mac.equals("02:00:00:00:00:00")||mac.equals("00:00:00:00:00:00")||mac.equals("ff:ff:ff:ff:ff:ff")||(Integer.parseInt(mac.substring(0,2),16)&1)!=0)throw new SecurityException("inventory_mac_redacted_or_invalid");
    return mac;
  }
  static void deviceInfoInventory(String path)throws Exception {
    diagnosticPhase=Phase.uid;if(android.os.Process.myUid()!=2000)throw new SecurityException("uid2000_required");
    DeviceInventoryConfig cfg=new DeviceInventoryConfig(load(new File(path)),SystemClock.elapsedRealtime());
    if(!path.equals("/data/local/tmp/rqpi-"+cfg.run+".properties"))throw new SecurityException("config_path");
    String configSha=shaBytes(Files.readAllBytes(new File(path).toPath()));
    String serial=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.serialno");
    String boot=new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),StandardCharsets.UTF_8).trim();cfg.host(serial,boot);
    diagnosticPhase=Phase.main_looper;if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
    // Exact existing shell attribution/bootstrap; initialization may change P2P
    // service state, which the parent must observe before/after, not infer away.
    diagnosticPhase=Phase.activity_thread;Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);Context sys=(Context)at.getMethod("getSystemContext").invoke(thread);
    diagnosticPhase=Phase.shell_context;Context base=sys.createPackageContext("com.android.shell",0);Context shell=new ContextWrapper(base){public String getPackageName(){return "com.android.shell";}public String getOpPackageName(){return "com.android.shell";}public AttributionSource getAttributionSource(){return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();}};
    WifiManager wifi=(WifiManager)shell.getSystemService(Context.WIFI_SERVICE);WifiP2pManager p2p=(WifiP2pManager)shell.getSystemService(Context.WIFI_P2P_SERVICE);
    if(wifi==null||p2p==null)throw new IllegalStateException("p2p_service");
    int wifiBefore=wifi.getWifiState();HandlerThread callbacks=new HandlerThread("rqpi-callbacks");callbacks.start();WifiP2pManager.Channel channel=null;
    String mac=null;Integer[] state=new Integer[2];boolean channelClosed=false;Exception failure=null;
    try {
      channel=p2p.initialize(shell,callbacks.getLooper(),null);if(channel==null)throw new IllegalStateException("p2p_channel");
      for(int i=0;i<2;i++){
        cfg.fresh(SystemClock.elapsedRealtime());final int index=i;CountDownLatch states=new CountDownLatch(1);
        p2p.requestP2pState(channel,value->{state[index]=value;states.countDown();});await(states);
        CountDownLatch absent=new CountDownLatch(1);final boolean[] groupPresent={true};p2p.requestGroupInfo(channel,value->{groupPresent[0]=value!=null;absent.countDown();});await(absent);
        if(groupPresent[0])throw new SecurityException("inventory_preexisting_group");
        CountDownLatch stopped=new CountDownLatch(1);final boolean[] idle={false};p2p.requestDiscoveryState(channel,value->{idle[0]=value==WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED;stopped.countDown();});await(stopped);
        if(!idle[0])throw new SecurityException("inventory_discovery_active");
        CountDownLatch device=new CountDownLatch(1);final String[] address={null};p2p.requestDeviceInfo(channel,value->{address[0]=value==null?null:value.deviceAddress;device.countDown();});await(device);
        if(i==0)mac=address[0];else mac=inventoryMac(mac,address[0]);
      }
      cfg.fresh(SystemClock.elapsedRealtime());
      cfg.host((String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.serialno"),new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),StandardCharsets.UTF_8).trim());
      if(!configSha.equals(shaBytes(Files.readAllBytes(new File(path).toPath()))))throw new SecurityException("inventory_config_changed");
    }catch(Exception e){failure=e;}finally{
      try{if(channel!=null){channel.close();channelClosed=true;}}finally{callbacks.quitSafely();callbacks.join(3000);}
    }
    int wifiAfter=wifi.getWifiState();
    System.out.println("{\"schema\":\"rusty.quest.p2p_device_inventory.v1\",\"mode\":\"device-info\",\"run_token\":\""+cfg.run+"\",\"serial\":\""+serial+"\",\"boot_id\":\""+boot+"\",\"config_sha256\":\""+configSha+"\",\"wifi_state_before\":"+wifiBefore+",\"wifi_state_after\":"+wifiAfter+",\"p2p_state_after_initialize\":"+state[0]+",\"p2p_state_before_close\":"+state[1]+",\"channel_closed\":"+channelClosed+",\"callback_thread_stopped\":"+!callbacks.isAlive()+",\"p2p_mac\":"+(failure==null?"\""+mac+"\"":"null")+",\"group_or_radio_mutation_requested\":false,\"initialization_effects_require_outer_observation\":true}");
    if(failure!=null)throw failure;if(wifiBefore!=wifiAfter||!channelClosed||callbacks.isAlive())throw new SecurityException("inventory_cleanup_or_wifi_baseline");
  }
  enum SecurityField { none, allowedKeyManagement, allowedProtocols, allowedAuthAlgorithms, allowedPairwiseCiphers, allowedGroupCiphers, allowedGroupManagementCiphers, allowedSuiteBCiphers, requirePmf }
  static SecurityField diagnosticField=SecurityField.none;
  static final Set<SecurityField> missingSecurityFields=EnumSet.noneOf(SecurityField.class);
  // SecurityParams.updateLegacyWifiConfiguration writes exactly these fields.
  static String legacySecurity(Object c)throws Exception {
    missingSecurityFields.clear();for(SecurityField field:SecurityField.values()){if(field==SecurityField.none)continue;try{c.getClass().getField(field.name());}catch(NoSuchFieldException unavailable){missingSecurityFields.add(field);}}
    if(!missingSecurityFields.isEmpty()){diagnosticField=missingSecurityFields.iterator().next();throw new NoSuchFieldException("security_fields_unavailable");}
    ArrayList<String> fields=new ArrayList<>();for(SecurityField field:SecurityField.values()){if(field==SecurityField.none)continue;diagnosticField=field;Object value=c.getClass().getField(field.name()).get(c);if(field==SecurityField.requirePmf?!(value instanceof Boolean):!(value instanceof BitSet))throw new SecurityException("security_field_shape");fields.add(String.valueOf(value));}diagnosticField=SecurityField.none;
    return String.join("|",fields);
  }
  static void attachSecurity(ProfileCandidate candidate,WifiConfiguration c)throws Exception {
    List<?> params=(List<?>)c.getClass().getMethod("getSecurityParamsList").invoke(c);if(params.size()!=1)throw new SecurityException("original_security_shape");Object p=params.get(0);
    candidate.type=(Integer)p.getClass().getMethod("getSecurityType").invoke(p);candidate.enabled=(Boolean)p.getClass().getMethod("isEnabled").invoke(p);candidate.upgrade=(Boolean)p.getClass().getMethod("isAddedByAutoUpgrade").invoke(p);
    Object canonical=p.getClass().getMethod("createSecurityParamsBySecurityType",int.class).invoke(null,candidate.type);WifiConfiguration expected=new WifiConfiguration();canonical.getClass().getMethod("updateLegacyWifiConfiguration",WifiConfiguration.class).invoke(canonical,expected);
    candidate.canonical=legacySecurity(c).equals(legacySecurity(expected));
  }
  static String[] groupedOriginal(List<ProfileCandidate> candidates,boolean requireEnabled)throws Exception {
    if(candidates.isEmpty()||candidates.size()>2)throw new SecurityException("original_group_shape");
    ProfileCandidate first=candidates.get(0);ArrayList<String> full=new ArrayList<>(),immutable=new ArrayList<>();Set<Integer> types=new HashSet<>();
    for(ProfileCandidate c:candidates){
      if(!c.enabled||c.status<0||c.status>2||c.reason<0||c.reason>31||(c.status==0)!=(c.reason==0))throw new SecurityException("original_selection_shape");
      if(!first.shared.equals(c.shared)||first.status!=c.status||first.reason!=c.reason)throw new SecurityException("original_group_conflict");
      if(requireEnabled&&(c.status!=0||c.reason!=0))throw new SecurityException("original_selection_not_enabled");
      if(candidates.size()==2&&(!c.canonical||!c.enabled||!types.add(c.type)||(c.type!=2&&c.type!=4)||c.upgrade!=(c.type==4)))throw new SecurityException("original_security_shape");
      full.add(c.full);immutable.add(c.immutable);
    }
    Collections.sort(full);Collections.sort(immutable);
    if(candidates.size()==2&&!types.equals(new HashSet<>(Arrays.asList(2,4))))throw new SecurityException("original_security_shape");
    return new String[]{candidates.size()==1?first.full:sha(String.join("\n",full)),candidates.size()==1?first.immutable:sha(String.join("\n",immutable)),String.valueOf(first.status),String.valueOf(first.reason)};
  }
  static void currentSecurityJoin(int original,int actual,int security,String allowed){
    if(actual!=original||!Arrays.asList(allowed.split(",")).contains(String.valueOf(security)))throw new SecurityException("original_current_security_join");
  }
  static String sharedProfileIdentity(String[] fields)throws Exception {
    if(fields.length!=13)throw new SecurityException("profile_diagnostic_shape");
    return sha(String.join("\n",fields[0],fields[1],fields[2],fields[3],fields[9],fields[12]));
  }
  static String securityShape(int[] types,boolean[] enabled,boolean[] upgrade){
    if(types.length<1||types.length>8||types.length!=enabled.length||types.length!=upgrade.length)throw new SecurityException("profile_diagnostic_shape");
    Set<Integer> seen=new HashSet<>();ArrayList<String> rows=new ArrayList<>();for(int i=0;i<types.length;i++){
      if(types[i]<0||types[i]>32||!seen.add(types[i]))throw new SecurityException("profile_diagnostic_shape");
      rows.add("{\"type\":"+types[i]+",\"enabled\":"+enabled[i]+",\"auto_upgrade\":"+upgrade[i]+"}");
    }return "["+String.join(",",rows)+"]";
  }
  static String securityShape(WifiConfiguration c)throws Exception {
    Object raw=c.getClass().getMethod("getSecurityParamsList").invoke(c);if(!(raw instanceof List))throw new SecurityException("profile_diagnostic_shape");
    List<?> xs=(List<?>)raw;if(xs.size()<1||xs.size()>8)throw new SecurityException("profile_diagnostic_shape");
    int[] types=new int[xs.size()];boolean[] enabled=new boolean[xs.size()],upgrade=new boolean[xs.size()];
    for(int i=0;i<xs.size();i++){Object x=xs.get(i);types[i]=(Integer)x.getClass().getMethod("getSecurityType").invoke(x);enabled[i]=(Boolean)x.getClass().getMethod("isEnabled").invoke(x);upgrade[i]=(Boolean)x.getClass().getMethod("isAddedByAutoUpgrade").invoke(x);}
    return securityShape(types,enabled,upgrade);
  }
  static final class ProfileCandidate {
    final String full,immutable;final int status,reason;String shared,security;int type=-1;boolean enabled,upgrade,canonical;
    ProfileCandidate(String full,String immutable,int status,int reason){
      if(!full.matches("[0-9a-f]{64}")||!immutable.matches("[0-9a-f]{64}"))throw new SecurityException("profile_diagnostic_shape");
      this.full=full;this.immutable=immutable;this.status=status;this.reason=reason;this.shared=full;this.security="[]";
    }
    String json(){return "{\"profile_sha256\":\""+full+"\",\"static_sha256\":\""+immutable+"\",\"shared_identity_sha256\":\""+shared+"\",\"security_params\":"+security+",\"selection_status\":"+status+",\"disable_reason\":"+reason+"}";}
  }
  static final class OriginalProfileAmbiguity extends SecurityException {
    final String projection;
    int currentSecurityType=-1,currentNetworkId=-1;
    OriginalProfileAmbiguity(int profiles,int distinct,List<ProfileCandidate> candidates){
      super("duplicate_original");ArrayList<String> rows=new ArrayList<>();for(ProfileCandidate c:candidates)rows.add(c.json());
      projection="{\"profile_count\":"+profiles+",\"distinct_network_id_count\":"+distinct+",\"original_candidate_count\":"+candidates.size()+",\"original_candidates\":["+String.join(",",rows)+"]}";
    }
  }
  static void requireUniqueOriginal(int profiles,int distinct,List<ProfileCandidate> candidates){
    if(profiles<1||profiles>128||distinct<1||distinct>profiles||candidates.size()>16||candidates.size()>profiles)throw new SecurityException("profiles_bound");
    if(candidates.isEmpty())throw new SecurityException("original_missing");
    if(candidates.size()!=1)throw new OriginalProfileAmbiguity(profiles,distinct,candidates);
  }
  static String errorType(Throwable e){String n=e.getClass().getSimpleName();return n.matches("[A-Za-z][A-Za-z0-9]{0,63}")?n:"Throwable";}
  static String failureJson(Throwable e){
    Throwable cause=e;for(int i=0;i<8 && cause.getCause()!=null && cause.getCause()!=cause;i++)cause=cause.getCause();
    String message=cause.getMessage();
    String code=message!=null && Arrays.asList("uid2000_required","config_path","file_bound","p2p_service","p2p_channel","profiles_missing","duplicate_original","original_missing","profiles_bound","profile_diagnostic_shape").contains(message)?message:"unclassified";
    String fieldProjection="";if(cause instanceof NoSuchFieldException && diagnosticField!=SecurityField.none){code="field_missing_"+diagnosticField.name();ArrayList<String> missing=new ArrayList<>();for(SecurityField field:missingSecurityFields)missing.add("\""+field.name()+"\"");fieldProjection=",\"field_access\":\""+diagnosticField.name()+"\",\"missing_security_fields\":["+String.join(",",missing)+"]";}
    String projection="";if(cause instanceof OriginalProfileAmbiguity){OriginalProfileAmbiguity a=(OriginalProfileAmbiguity)cause;projection=",\"current_security_type\":"+a.currentSecurityType+",\"current_network_id\":"+a.currentNetworkId+",\"profile_projection\":"+a.projection;}
    return "{\"schema\":\"rusty.quest.original_station_guard.v1\",\"ok\":false,\"outcome\":\"unknown\",\"phase\":\""+diagnosticPhase.name()+"\",\"error_type\":\""+errorType(e)+"\",\"cause_type\":\""+errorType(cause)+"\",\"error_code\":\""+code+"\""+fieldProjection+projection+"}";
  }
  final OriginalStationGuardContract cfg; final String path; final WifiManager wifi; final ConnectivityManager connectivity;
  final Context shellContext; final WifiP2pManager p2p; final WifiP2pManager.Channel channel; final HandlerThread callbacks;
  QuestOriginalStationGuard(String path)throws Exception {
    diagnosticPhase=Phase.uid;if(android.os.Process.myUid()!=2000)throw new SecurityException("uid2000_required");
    diagnosticPhase=Phase.config_load;Properties p=load(new File(path)); diagnosticPhase=Phase.config_contract;cfg=new OriginalStationGuardContract(p,SystemClock.elapsedRealtime()); this.path=path;
    diagnosticPhase=Phase.config_path;
    if(!path.equals("/data/local/tmp/rqosg-"+cfg.run+".properties"))throw new SecurityException("config_path");
    diagnosticPhase=Phase.host_identity;host(); diagnosticPhase=Phase.main_looper;if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
     diagnosticPhase=Phase.activity_thread;Class<?> at=Class.forName("android.app.ActivityThread"); Object thread=at.getMethod("systemMain").invoke(null); Context sys=(Context)at.getMethod("getSystemContext").invoke(thread); diagnosticPhase=Phase.shell_context;Context base=sys.createPackageContext("com.android.shell",0); Context shell=new ContextWrapper(base){public String getPackageName(){return "com.android.shell";} public String getOpPackageName(){return "com.android.shell";} public AttributionSource getAttributionSource(){return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();}}; shellContext=shell; diagnosticPhase=Phase.binder_services;Class<?> sm=Class.forName("android.os.ServiceManager"), iw=Class.forName("android.net.wifi.IWifiManager"), ic=Class.forName("android.net.IConnectivityManager"); Object service=Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"wifi")); Object cs=Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"connectivity")); diagnosticPhase=Phase.wifi_manager;wifi=(WifiManager)WifiManager.class.getConstructor(Context.class,iw,Looper.class).newInstance(shell,service,Looper.getMainLooper()); diagnosticPhase=Phase.connectivity_manager;connectivity=(ConnectivityManager)ConnectivityManager.class.getConstructor(Context.class,ic).newInstance(shell,cs);
    diagnosticPhase=Phase.callback_thread;callbacks=new HandlerThread("rqosg-callbacks"); callbacks.start();
    diagnosticPhase=Phase.p2p_service;
    p2p=(WifiP2pManager)shell.getSystemService(Context.WIFI_P2P_SERVICE);
    if(p2p==null)throw new IllegalStateException("p2p_service");
    diagnosticPhase=Phase.p2p_channel;channel=p2p.initialize(shell,callbacks.getLooper(),null);
    if(channel==null)throw new IllegalStateException("p2p_channel");
  }
  void host()throws Exception {
    String serial=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.serialno");
    String boot=new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),StandardCharsets.UTF_8).trim(); cfg.host(serial,boot);
  }
  static Properties load(File f)throws Exception { if(!f.isFile()||f.length()>4096)throw new SecurityException("file_bound"); Properties p=new Properties(); try(InputStream i=new FileInputStream(f)){p.load(i);}return p; }
  static String sha(String s)throws Exception {return shaBytes(s.getBytes(StandardCharsets.UTF_8));}
  static String shaBytes(byte[] bytes)throws Exception { byte[] h=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte x:h)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString(); }
  static String[] profileFields(WifiConfiguration c)throws Exception {
    Object selection=c.getClass().getMethod("getNetworkSelectionStatus").invoke(c);Class<?> type=selection.getClass();
    return new String[]{String.valueOf(c.networkId),String.valueOf(c.SSID),String.valueOf(c.BSSID),String.valueOf(c.hiddenSSID),String.valueOf(c.allowedKeyManagement),String.valueOf(c.allowedProtocols),String.valueOf(c.allowedAuthAlgorithms),String.valueOf(c.allowedPairwiseCiphers),String.valueOf(c.allowedGroupCiphers),String.valueOf(c.getClass().getField("allowAutojoin").getBoolean(c)),String.valueOf(type.getMethod("getNetworkSelectionStatus").invoke(selection)),String.valueOf(type.getMethod("getNetworkSelectionDisableReason").invoke(selection)),String.valueOf(c.getClass().getMethod("getIpConfiguration").invoke(c))};
  }
  static String profile(WifiConfiguration c)throws Exception {return OriginalStationGuardContract.profile(profileFields(c),false);}
  static String staticProfile(WifiConfiguration c)throws Exception {return OriginalStationGuardContract.profile(profileFields(c),true);}
  static int constant(String name)throws Exception {return Class.forName("android.net.wifi.WifiConfiguration$NetworkSelectionStatus").getField(name).getInt(null);}
  void originalEnabled()throws Exception {
    String[] current=snapshot();OriginalStationGuardContract.enabled(Integer.parseInt(current[4]),Integer.parseInt(current[5]),constant("NETWORK_SELECTION_ENABLED"),constant("DISABLED_NONE"));
    WifiInfo info=wifi.getConnectionInfo();if(info==null)throw new SecurityException("original_current_security_join");currentSecurityJoin(cfg.original,info.getNetworkId(),info.getCurrentSecurityType(),current[6]);
  }


  String[] snapshot()throws Exception {
    diagnosticPhase=Phase.snapshot_host;host();diagnosticPhase=Phase.configured_networks; List<WifiConfiguration> xs=wifi.getConfiguredNetworks();if(xs==null||xs.isEmpty())throw new IllegalStateException("profiles_missing");
    if(xs.size()>128)throw new SecurityException("profiles_bound");
    ArrayList<String> rows=new ArrayList<>(),unrelated=new ArrayList<>();ArrayList<ProfileCandidate> candidates=new ArrayList<>();Set<Integer> ids=new HashSet<>();String original=null,originalStatic=null,selectionStatus=null,selectionReason=null;
    diagnosticPhase=Phase.profile_projection;for(WifiConfiguration c:xs){ids.add(c.networkId);String[] fields=profileFields(c);String securityProjection=legacySecurity(c)+"|"+securityShape(c);String h=sha(OriginalStationGuardContract.profile(fields,false)+"|"+securityProjection);rows.add(h);if(c.networkId==cfg.original){String immutable=sha(OriginalStationGuardContract.profile(fields,true)+"|"+securityProjection);ProfileCandidate candidate=new ProfileCandidate(h,immutable,Integer.parseInt(fields[10]),Integer.parseInt(fields[11]));candidate.shared=sharedProfileIdentity(fields);candidate.security=securityShape(c);attachSecurity(candidate,c);candidates.add(candidate);if(candidates.size()>16)throw new SecurityException("profiles_bound");if(original==null){original=h;originalStatic=immutable;selectionStatus=fields[10];selectionReason=fields[11];}}else unrelated.add(h);}
    diagnosticPhase=Phase.snapshot_join;String[] group=groupedOriginal(candidates,false);ArrayList<String> securityTypes=new ArrayList<>();for(ProfileCandidate c:candidates)securityTypes.add(String.valueOf(c.type));Collections.sort(securityTypes);
    Collections.sort(rows);Collections.sort(unrelated);return new String[]{sha(String.join("\n",rows)),group[0],sha(String.join("\n",unrelated)),group[1],group[2],group[3],String.join(",",securityTypes)};
  }
  void create(File f,String text)throws Exception {
    if(!f.createNewFile())throw new SecurityException("output_exists");Os.chmod(f.getPath(),0600);
    try(FileOutputStream o=new FileOutputStream(f)){o.write(text.getBytes(StandardCharsets.UTF_8));o.getFD().sync();}
  }
  interface Dispatch { void call()throws Exception; }
  static void await(CountDownLatch latch)throws Exception {if(!latch.await(2000,TimeUnit.MILLISECONDS))throw new IllegalStateException("callback_timeout");}
  WifiP2pGroup group()throws Exception {
    final WifiP2pGroup[] v=new WifiP2pGroup[1];CountDownLatch l=new CountDownLatch(1);
    p2p.requestGroupInfo(channel,g->{v[0]=g;l.countDown();});await(l);return v[0];
  }
  boolean discoveryStopped()throws Exception {
    final int[] v={-1};CountDownLatch l=new CountDownLatch(1);
    p2p.requestDiscoveryState(channel,s->{v[0]=s;l.countDown();});await(l);return v[0]==WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED;
  }
  static void actionResult(boolean accepted) {if(!accepted)throw new IllegalStateException("action_rejected");}
  void action(boolean remove)throws Exception {
    final boolean[] ok={false};CountDownLatch l=new CountDownLatch(1);
    WifiP2pManager.ActionListener a=new WifiP2pManager.ActionListener(){public void onSuccess(){ok[0]=true;l.countDown();}public void onFailure(int reason){l.countDown();}};
    if(remove)p2p.removeGroup(channel,a);else p2p.stopPeerDiscovery(channel,a);
    await(l);actionResult(ok[0]);
  }
  boolean stationEffective() {
    WifiInfo i=wifi.getConnectionInfo();if(i==null||i.getNetworkId()!=cfg.original||!wifi.isWifiEnabled())return false;
    Network active=connectivity.getActiveNetwork();NetworkCapabilities c=connectivity.getNetworkCapabilities(active);LinkProperties p=connectivity.getLinkProperties(active);
    if(c==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||p==null)return false;
    for(LinkAddress a:p.getLinkAddresses())if(a.getAddress() instanceof Inet4Address&&!a.getAddress().isLoopbackAddress()&&!a.getAddress().isAnyLocalAddress())return true;
    return false;
  }
  void arm()throws Exception {
    host();cfg.fresh(SystemClock.elapsedRealtime(),10000);originalEnabled();if(!stationEffective()||group()!=null||!discoveryStopped())throw new SecurityException("arm_baseline");
    String[] s=snapshot();OriginalStationGuardContract.enabled(Integer.parseInt(s[4]),Integer.parseInt(s[5]),constant("NETWORK_SELECTION_ENABLED"),constant("DISABLED_NONE"));cfg.fresh(SystemClock.elapsedRealtime(),10000);create(new File(path+".state"),"run_token="+cfg.run+"\nconfig_sha256="+shaBytes(Files.readAllBytes(new File(path).toPath()))+"\nall_sha256="+s[0]+"\noriginal_sha256="+s[1]+"\nunrelated_sha256="+s[2]+"\noriginal_static_sha256="+s[3]+"\narmed_elapsed_realtime_ms="+SystemClock.elapsedRealtime()+"\n");
  }
  Properties state()throws Exception {
    host();Properties s=load(new File(path+".state"));
    if(!s.stringPropertyNames().equals(new HashSet<>(Arrays.asList("run_token","config_sha256","all_sha256","original_sha256","unrelated_sha256","original_static_sha256","armed_elapsed_realtime_ms")))||!cfg.run.equals(s.getProperty("run_token"))||!shaBytes(Files.readAllBytes(new File(path).toPath())).equals(s.getProperty("config_sha256")))throw new SecurityException("state_identity");cfg.armed(Long.parseLong(s.getProperty("armed_elapsed_realtime_ms")),SystemClock.elapsedRealtime());return s;
  }
  void baseline(Properties s)throws Exception {String[] current=snapshot();OriginalStationGuardContract.baseline(s.getProperty("all_sha256"),s.getProperty("original_sha256"),current[0],current[1]);}
  void preeffect(Properties s)throws Exception {
    String[] current=snapshot();OriginalStationGuardContract.baseline(s.getProperty("unrelated_sha256"),s.getProperty("original_static_sha256"),current[2],current[3]);
  }
  static final String PAIR_PACKAGE="io.github.mesmerprism.rustyquest.directp2p";
  byte[] boundedBytes(String file)throws Exception {File f=new File(file);if(Files.isSymbolicLink(f.toPath())||!f.isFile()||f.length()>4096||Os.stat(file).st_uid!=2000||(Os.stat(file).st_mode&0777)!=0600)throw new SecurityException("formation_file_bound");return Files.readAllBytes(f.toPath());}
  String dexHash()throws Exception {String dex=System.getenv("CLASSPATH");if(!("/data/local/tmp/rqosg-"+cfg.run+".dex").equals(dex)||Files.isSymbolicLink(new File(dex).toPath()))throw new SecurityException("formation_dex");return shaBytes(Files.readAllBytes(new File(dex).toPath()));}
  static final class FormationUnavailable extends SecurityException {FormationUnavailable(){super("formation_current_unavailable");}}
  Properties formationInput()throws Exception {return OriginalStationFormationAdmission.parseInput(new String(boundedBytes(path+".formation-input"),StandardCharsets.UTF_8));}
  void staticFormationPins(Properties input)throws Exception {OriginalStationFormationAdmission.input(input,cfg);if(!dexHash().equals(input.getProperty("guardian_dex_sha256")))throw new SecurityException("formation_dex_pin");String apk=shellContext.getPackageManager().getApplicationInfo(PAIR_PACKAGE,0).sourceDir;if(!shaBytes(Files.readAllBytes(new File(apk).toPath())).equals(input.getProperty("pair_apk_sha256")))throw new SecurityException("formation_apk_pin");}
  String[] currentPairProcess(Properties input)throws Exception {
    android.app.ActivityManager am=(android.app.ActivityManager)shellContext.getSystemService(Context.ACTIVITY_SERVICE);if(am==null)throw new SecurityException("formation_process_service");java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes=am.getRunningAppProcesses();if(processes==null)throw new FormationUnavailable();if(processes.size()>1024)throw new SecurityException("formation_process_inventory_bound");
    android.app.ActivityManager.RunningAppProcessInfo selected=null;for(android.app.ActivityManager.RunningAppProcessInfo process:processes)if(PAIR_PACKAGE.equals(process.processName)){if(selected!=null)throw new SecurityException("formation_ambiguous_process");selected=process;}
    if(selected==null)throw new FormationUnavailable();if(selected.pid<1||selected.uid!=shellContext.getPackageManager().getApplicationInfo(PAIR_PACKAGE,0).uid)throw new SecurityException("formation_process_uid");String pid=String.valueOf(selected.pid);
    String stat=new String(Files.readAllBytes(new File("/proc/"+pid+"/stat").toPath()),StandardCharsets.UTF_8);String cmd=new String(Files.readAllBytes(new File("/proc/"+pid+"/cmdline").toPath()),StandardCharsets.UTF_8);if(!stat.startsWith(pid+" "))throw new SecurityException("formation_process");String birth=OriginalStationGuardContract.processBirth(stat);OriginalStationFormationAdmission.processCommand(cmd,PAIR_PACKAGE);OriginalStationFormationAdmission.processPair(input,pid,birth);return new String[]{pid,birth};
  }
  android.os.Bundle liveFormationCall(android.os.Bundle request)throws Exception {
    try{android.os.Bundle value=shellContext.getContentResolver().call(android.net.Uri.parse("content://"+PAIR_PACKAGE+".formation-observation"),"current-formation",null,request);if(value==null)throw new FormationUnavailable();return value;}catch(SecurityException unavailable){if("formation_unavailable".equals(unavailable.getMessage()))throw new FormationUnavailable();throw unavailable;}
  }
  void probeFormation(Properties input)throws Exception {
    input=completedCarrier(input);String[] actor=currentPairProcess(input);staticFormationPins(input);
    android.content.pm.ProviderInfo provider=shellContext.getPackageManager().resolveContentProvider(PAIR_PACKAGE+".formation-observation",0);if(provider==null||!PAIR_PACKAGE.equals(provider.packageName)||!"android.permission.DUMP".equals(provider.readPermission)||!"android.permission.DUMP".equals(provider.writePermission))throw new SecurityException("formation_provider");
    android.os.Bundle request=new android.os.Bundle();request.putString("run_id",input.getProperty("run_id"));request.putString("run_token",cfg.run);android.os.Bundle observed=liveFormationCall(request);Map<String,String> live=new HashMap<>();for(String key:observed.keySet())live.put(key,observed.getString(key));OriginalStationFormationAdmission.live(input,cfg,live,SystemClock.elapsedRealtime());OriginalStationFormationAdmission.liveProcess(live,actor[0],actor[1]);
  }
  Properties completedCarrier(Properties input)throws Exception {
    if(!OriginalStationFormationAdmission.pendingCarrier(input))return input;
    boolean payload=new File(path+".formation-go").exists(),descriptor=new File(path+".formation-go-binding").exists();
    if(payload&&!descriptor){OriginalStationFormationAdmission.strict(new String(boundedBytes(path+".formation-go"),StandardCharsets.UTF_8),OriginalStationFormationAdmission.RECEIPT_KEYS);}
    if(!OriginalStationFormationAdmission.carrierAvailable(payload,descriptor))throw new FormationUnavailable();
    Properties binding=OriginalStationFormationAdmission.strict(new String(boundedBytes(path+".formation-go-binding"),StandardCharsets.UTF_8),new HashSet<>(Arrays.asList("go_receipt_sha256")));OriginalStationFormationAdmission.hash(binding.getProperty("go_receipt_sha256"));Properties ready=new Properties();ready.putAll(input);ready.setProperty("go_receipt_sha256",binding.getProperty("go_receipt_sha256"));return ready;
  }
  void admitFormation()throws Exception {admitFormation(null);}
  void admitFormation(String expectedInputPin)throws Exception {
    host();cfg.fresh(SystemClock.elapsedRealtime(),1);Properties baseline=state();preeffect(baseline);
    byte[] inputBytes=boundedBytes(path+".formation-input");String admissionInputPin=shaBytes(inputBytes);if(expectedInputPin!=null&&!expectedInputPin.equals(admissionInputPin))throw new SecurityException("formation_input_changed");Properties input=OriginalStationFormationAdmission.parseInput(new String(inputBytes,StandardCharsets.UTF_8));OriginalStationFormationAdmission.input(input,cfg);boolean pendingCarrier=OriginalStationFormationAdmission.pendingCarrier(input);input=completedCarrier(input);String[] actor=currentPairProcess(input);
    if(!dexHash().equals(input.getProperty("guardian_dex_sha256")))throw new SecurityException("formation_dex_pin");
    android.content.pm.ProviderInfo provider=shellContext.getPackageManager().resolveContentProvider(PAIR_PACKAGE+".formation-observation",0);
    if(provider==null||!PAIR_PACKAGE.equals(provider.packageName)||!"android.permission.DUMP".equals(provider.readPermission)||!"android.permission.DUMP".equals(provider.writePermission))throw new SecurityException("formation_provider");
    String apk=shellContext.getPackageManager().getApplicationInfo(PAIR_PACKAGE,0).sourceDir;if(!shaBytes(Files.readAllBytes(new File(apk).toPath())).equals(input.getProperty("pair_apk_sha256")))throw new SecurityException("formation_apk_pin");
    android.os.Bundle request=new android.os.Bundle();request.putString("run_id",input.getProperty("run_id"));request.putString("run_token",cfg.run);
    android.os.Bundle observed=liveFormationCall(request);
    Map<String,String> live=new HashMap<>();if(observed!=null)for(String key:observed.keySet())live.put(key,observed.getString(key));OriginalStationFormationAdmission.live(input,cfg,live,SystemClock.elapsedRealtime());
    OriginalStationFormationAdmission.liveProcess(live,actor[0],actor[1]);String pid=live.get("pid");String stat=new String(Files.readAllBytes(new File("/proc/"+pid+"/stat").toPath()),StandardCharsets.UTF_8);String cmd=new String(Files.readAllBytes(new File("/proc/"+pid+"/cmdline").toPath()),StandardCharsets.UTF_8);
    if(!stat.startsWith(pid+" ")||!live.get("pid_start_ticks").equals(OriginalStationGuardContract.processBirth(stat)))throw new SecurityException("formation_process");
    OriginalStationFormationAdmission.processCommand(cmd,PAIR_PACKAGE);
    WifiP2pGroup actual=group();if(actual==null||actual.getOwner()==null||!cfg.network.equals(actual.getNetworkName())||actual.isGroupOwner()!=cfg.localOwner||!live.get("owner_mac").equals(actual.getOwner().deviceAddress.toLowerCase(Locale.US)))throw new SecurityException("formation_current_group");
    if(!cfg.localOwner){byte[] goBytes=boundedBytes(path+".formation-go");if(!shaBytes(goBytes).equals(input.getProperty("go_receipt_sha256")))throw new SecurityException("formation_peer_carrier");Properties go=OriginalStationFormationAdmission.strict(new String(goBytes,StandardCharsets.UTF_8),OriginalStationFormationAdmission.RECEIPT_KEYS);OriginalStationFormationAdmission.peer(input,go,live.get("owner_mac"));}
    host();cfg.fresh(SystemClock.elapsedRealtime(),1);preeffect(baseline);
    // Re-read the source callback after profile hashing, then actual group immediately before receipt creation.
    String[] finalActor=currentPairProcess(input);if(!Arrays.equals(actor,finalActor))throw new SecurityException("formation_actor_changed");android.os.Bundle finalObserved=liveFormationCall(request);Map<String,String> finalLive=new HashMap<>();if(finalObserved!=null)for(String key:finalObserved.keySet())finalLive.put(key,finalObserved.getString(key));OriginalStationFormationAdmission.live(input,cfg,finalLive,SystemClock.elapsedRealtime());
    OriginalStationFormationAdmission.liveProcess(finalLive,actor[0],actor[1]);if(!finalLive.get("owner_mac").equals(live.get("owner_mac")))throw new SecurityException("formation_changed");live=finalLive;
    WifiP2pGroup finalGroup=group();if(finalGroup==null||finalGroup.getOwner()==null||!cfg.network.equals(finalGroup.getNetworkName())||finalGroup.isGroupOwner()!=cfg.localOwner||!live.get("owner_mac").equals(finalGroup.getOwner().deviceAddress.toLowerCase(Locale.US)))throw new SecurityException("formation_changed");
    host();cfg.fresh(SystemClock.elapsedRealtime(),1);OriginalStationFormationAdmission.live(input,cfg,live,SystemClock.elapsedRealtime());
    if(!admissionInputPin.equals(shaBytes(boundedBytes(path+".formation-input"))))throw new SecurityException("formation_input_changed");
    if(!cfg.localOwner){if(!shaBytes(boundedBytes(path+".formation-go")).equals(input.getProperty("go_receipt_sha256")))throw new SecurityException("formation_peer_carrier_changed");if(pendingCarrier){Properties binding=OriginalStationFormationAdmission.strict(new String(boundedBytes(path+".formation-go-binding"),StandardCharsets.UTF_8),new HashSet<>(Arrays.asList("go_receipt_sha256")));if(!input.getProperty("go_receipt_sha256").equals(binding.getProperty("go_receipt_sha256")))throw new SecurityException("formation_peer_descriptor_changed");}}
    Properties receipt=new Properties();for(String k:Arrays.asList("run_id","run_token","serial","boot_id","pair_apk_sha256","guardian_dex_sha256","source_revision","source_tree","go_receipt_sha256"))receipt.setProperty(k,input.getProperty(k));for(String k:Arrays.asList("local_owner","network","owner_mac","observed_elapsed_ms","pid","pid_start_ticks"))receipt.setProperty(k,live.get(k));receipt.setProperty("config_sha256",shaBytes(Files.readAllBytes(new File(path).toPath())));receipt.setProperty("state_sha256",shaBytes(boundedBytes(path+".state")));
    create(new File(path+".formation"),OriginalStationFormationAdmission.text(receipt));
  }
  boolean ownedFormation(WifiP2pGroup group)throws Exception {
    File f=new File(path+".formation");if(!f.exists()&&!Files.isSymbolicLink(f.toPath()))return cfg.owned(group.getNetworkName(),group.getOwner().deviceAddress,group.isGroupOwner());
    Properties receipt=OriginalStationFormationAdmission.strict(new String(boundedBytes(f.getPath()),StandardCharsets.UTF_8),OriginalStationFormationAdmission.RECEIPT_KEYS);
    String currentApk=shellContext.getPackageManager().getApplicationInfo(PAIR_PACKAGE,0).sourceDir;if(!shaBytes(Files.readAllBytes(new File(currentApk).toPath())).equals(receipt.getProperty("pair_apk_sha256")))throw new SecurityException("formation_cleanup_apk");
    OriginalStationFormationAdmission.cleanup(receipt,cfg,shaBytes(Files.readAllBytes(new File(path).toPath())),shaBytes(boundedBytes(path+".state")),dexHash(),group.getNetworkName(),group.getOwner().deviceAddress.toLowerCase(Locale.US),group.isGroupOwner());return true;
  }
  void restore()throws Exception {
    try(RandomAccessFile r=new RandomAccessFile(path+".lock","rw");FileChannel ch=r.getChannel();FileLock ignored=ch.tryLock()) {
      if(ignored==null)throw new SecurityException("restore_already_running");
      Os.chmod(path+".lock",0600);
      if(new File(path+".done").exists())throw new SecurityException("restore_already_completed");
      Properties s=state();preeffect(s);WifiP2pGroup g=group();
      boolean ownedObserved = g != null;
      if(g!=null){if(g.getOwner()==null||!ownedFormation(g))throw new SecurityException("foreign_group");action(true);}
      long until=SystemClock.elapsedRealtime()+10000;while(group()!=null&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      if(group()!=null)throw new IllegalStateException("group_absence_unconfirmed");
      // Only the diagnostic's client can own discovery, and only after an armed idle baseline.
      if(!discoveryStopped()){if(cfg.localOwner || !ownedObserved)throw new SecurityException("foreign_discovery");action(false);}
      if(!discoveryStopped())throw new IllegalStateException("discovery_stop_unconfirmed");
      preeffect(s);if(!wifi.enableNetwork(cfg.original,true))throw new IllegalStateException("original_select_rejected");
      until=SystemClock.elapsedRealtime()+20000;while(!stationEffective()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
      if(!stationEffective())throw new IllegalStateException("original_effective_unconfirmed");originalEnabled();baseline(s);
      create(new File(path+".done"),"run_token="+cfg.run+"\nrestored=true\n");
    }
  }
  static String processBirth()throws Exception {
    String stat=new String(Files.readAllBytes(new File("/proc/self/stat").toPath()),StandardCharsets.UTF_8);
    return OriginalStationGuardContract.processBirth(stat);
  }
  void guard()throws Exception {guard(false);}
  void guard(boolean autoAdmission)throws Exception {
    OriginalStationFormationAdmission.Monitor monitor=null;if(autoAdmission){byte[] initial=boundedBytes(path+".formation-input");Properties input=OriginalStationFormationAdmission.parseInput(new String(initial,StandardCharsets.UTF_8));staticFormationPins(input);monitor=new OriginalStationFormationAdmission.Monitor(shaBytes(initial));if(new File(path+".formation").exists()||Files.isSymbolicLink(new File(path+".formation").toPath()))throw new SecurityException("formation_output_exists");}
    cfg.fresh(SystemClock.elapsedRealtime(),1);state();baseline(state());originalEnabled();if(!stationEffective()||group()!=null||!discoveryStopped())throw new SecurityException("guard_baseline");
    cfg.fresh(SystemClock.elapsedRealtime(),1);
    create(new File(path+".ready"),"run_token="+cfg.run+"\nconfig_sha256="+shaBytes(Files.readAllBytes(new File(path).toPath()))+"\npid="+android.os.Process.myPid()+"\npid_start_ticks="+processBirth()+"\nboot_id="+cfg.boot+"\nserial="+cfg.serial+"\ndeadline_elapsed_realtime_ms="+cfg.deadline+"\n");
    while(SystemClock.elapsedRealtime()<cfg.deadline){if(autoAdmission){String receipt=(new File(path+".formation").exists()||Files.isSymbolicLink(new File(path+".formation").toPath()))?shaBytes(boundedBytes(path+".formation")):null;monitor.current(shaBytes(boundedBytes(path+".formation-input")),receipt);if(monitor.begin(SystemClock.elapsedRealtime(),cfg.deadline)){try{probeFormation(formationInput());admitFormation(monitor.inputSha);monitor.complete(shaBytes(boundedBytes(path+".formation")));}catch(FormationUnavailable beforeFormation){monitor.unavailable();/* Only explicit complete unavailable reads permit later observation. */}}}if(new File(path+".done").exists()){Properties done=load(new File(path+".done"));if(!done.stringPropertyNames().equals(new HashSet<>(Arrays.asList("run_token","restored")))||!cfg.run.equals(done.getProperty("run_token"))||!"true".equals(done.getProperty("restored")))throw new SecurityException("done_identity");return;}host();Thread.sleep(100);}
    restore();
  }
  public static void main(String[] a){QuestOriginalStationGuard h=null;String mode="invalid";try{
    if(a.length==2&&a[0].equals("device-info")){deviceInfoInventory(a[1]);return;}
    if(a.length!=2||!Arrays.asList("snapshot","arm","guard","restore","admit-formation","guard-with-formation").contains(a[0]))throw new IllegalArgumentException("mode");mode=a[0];h=new QuestOriginalStationGuard(a[1]);
    diagnosticPhase=Phase.dispatch;if(mode.equals("snapshot")){String[] s=h.snapshot();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"mode\":\"snapshot\",\"profiles_sha256\":\""+s[0]+"\",\"original_sha256\":\""+s[1]+"\"}");}
    else {if(mode.equals("arm"))h.arm();else if(mode.equals("guard"))h.guard();else if(mode.equals("guard-with-formation"))h.guard(true);else if(mode.equals("admit-formation"))h.admitFormation();else h.restore();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"ok\":true}");}
  }catch(Throwable e){System.out.println(failureJson(e));System.exit(1);}finally{if(h!=null)h.callbacks.quitSafely();}}
}
