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
  final WifiP2pManager p2p; final WifiP2pManager.Channel channel; final HandlerThread callbacks;
  QuestOriginalStationGuard(String path)throws Exception {
    diagnosticPhase=Phase.uid;if(android.os.Process.myUid()!=2000)throw new SecurityException("uid2000_required");
    diagnosticPhase=Phase.config_load;Properties p=load(new File(path)); diagnosticPhase=Phase.config_contract;cfg=new OriginalStationGuardContract(p,SystemClock.elapsedRealtime()); this.path=path;
    diagnosticPhase=Phase.config_path;
    if(!path.equals("/data/local/tmp/rqosg-"+cfg.run+".properties"))throw new SecurityException("config_path");
    diagnosticPhase=Phase.host_identity;host(); diagnosticPhase=Phase.main_looper;if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
     diagnosticPhase=Phase.activity_thread;Class<?> at=Class.forName("android.app.ActivityThread"); Object thread=at.getMethod("systemMain").invoke(null); Context sys=(Context)at.getMethod("getSystemContext").invoke(thread); diagnosticPhase=Phase.shell_context;Context base=sys.createPackageContext("com.android.shell",0); Context shell=new ContextWrapper(base){public String getPackageName(){return "com.android.shell";} public String getOpPackageName(){return "com.android.shell";} public AttributionSource getAttributionSource(){return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();}}; diagnosticPhase=Phase.binder_services;Class<?> sm=Class.forName("android.os.ServiceManager"), iw=Class.forName("android.net.wifi.IWifiManager"), ic=Class.forName("android.net.IConnectivityManager"); Object service=Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"wifi")); Object cs=Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"connectivity")); diagnosticPhase=Phase.wifi_manager;wifi=(WifiManager)WifiManager.class.getConstructor(Context.class,iw,Looper.class).newInstance(shell,service,Looper.getMainLooper()); diagnosticPhase=Phase.connectivity_manager;connectivity=(ConnectivityManager)ConnectivityManager.class.getConstructor(Context.class,ic).newInstance(shell,cs);
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
  void restore()throws Exception {
    try(RandomAccessFile r=new RandomAccessFile(path+".lock","rw");FileChannel ch=r.getChannel();FileLock ignored=ch.tryLock()) {
      if(ignored==null)throw new SecurityException("restore_already_running");
      Os.chmod(path+".lock",0600);
      if(new File(path+".done").exists())throw new SecurityException("restore_already_completed");
      Properties s=state();preeffect(s);WifiP2pGroup g=group();
      boolean ownedObserved = g != null;
      if(g!=null){if(g.getOwner()==null||!cfg.owned(g.getNetworkName(),g.getOwner().deviceAddress,g.isGroupOwner()))throw new SecurityException("foreign_group");action(true);}
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
  void guard()throws Exception {
    cfg.fresh(SystemClock.elapsedRealtime(),1);state();baseline(state());originalEnabled();if(!stationEffective()||group()!=null||!discoveryStopped())throw new SecurityException("guard_baseline");
    cfg.fresh(SystemClock.elapsedRealtime(),1);
    create(new File(path+".ready"),"run_token="+cfg.run+"\nconfig_sha256="+shaBytes(Files.readAllBytes(new File(path).toPath()))+"\npid="+android.os.Process.myPid()+"\npid_start_ticks="+processBirth()+"\nboot_id="+cfg.boot+"\nserial="+cfg.serial+"\ndeadline_elapsed_realtime_ms="+cfg.deadline+"\n");
    while(SystemClock.elapsedRealtime()<cfg.deadline){if(new File(path+".done").exists()){Properties done=load(new File(path+".done"));if(!done.stringPropertyNames().equals(new HashSet<>(Arrays.asList("run_token","restored")))||!cfg.run.equals(done.getProperty("run_token"))||!"true".equals(done.getProperty("restored")))throw new SecurityException("done_identity");return;}host();Thread.sleep(100);}
    restore();
  }
  public static void main(String[] a){QuestOriginalStationGuard h=null;String mode="invalid";try{
    if(a.length!=2||!Arrays.asList("snapshot","arm","guard","restore").contains(a[0]))throw new IllegalArgumentException("mode");mode=a[0];h=new QuestOriginalStationGuard(a[1]);
    diagnosticPhase=Phase.dispatch;if(mode.equals("snapshot")){String[] s=h.snapshot();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"mode\":\"snapshot\",\"profiles_sha256\":\""+s[0]+"\",\"original_sha256\":\""+s[1]+"\"}");}
    else {if(mode.equals("arm"))h.arm();else if(mode.equals("guard"))h.guard();else h.restore();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"ok\":true}");}
  }catch(Throwable e){System.out.println(failureJson(e));System.exit(1);}finally{if(h!=null)h.callbacks.quitSafely();}}
}
