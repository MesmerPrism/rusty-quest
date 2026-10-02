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
  final OriginalStationGuardContract cfg; final String path; final WifiManager wifi; final ConnectivityManager connectivity;
  final WifiP2pManager p2p; final WifiP2pManager.Channel channel; final HandlerThread callbacks;
  QuestOriginalStationGuard(String path)throws Exception {
    if(android.os.Process.myUid()!=2000)throw new SecurityException("uid2000_required");
    Properties p=load(new File(path)); cfg=new OriginalStationGuardContract(p,SystemClock.elapsedRealtime()); this.path=path;
    if(!path.equals("/data/local/tmp/rqosg-"+cfg.run+".properties"))throw new SecurityException("config_path");
    host(); if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
     Class<?> at=Class.forName("android.app.ActivityThread"); Object thread=at.getMethod("systemMain").invoke(null); Context sys=(Context)at.getMethod("getSystemContext").invoke(thread); Context base=sys.createPackageContext("com.android.shell",0); Context shell=new ContextWrapper(base){public String getPackageName(){return "com.android.shell";} public String getOpPackageName(){return "com.android.shell";} public AttributionSource getAttributionSource(){return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();}}; Class<?> sm=Class.forName("android.os.ServiceManager"), iw=Class.forName("android.net.wifi.IWifiManager"), ic=Class.forName("android.net.IConnectivityManager"); Object service=Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"wifi")); Object cs=Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,sm.getMethod("getService",String.class).invoke(null,"connectivity")); wifi=(WifiManager)WifiManager.class.getConstructor(Context.class,iw,Looper.class).newInstance(shell,service,Looper.getMainLooper()); connectivity=(ConnectivityManager)ConnectivityManager.class.getConstructor(Context.class,ic).newInstance(shell,cs);
    callbacks=new HandlerThread("rqosg-callbacks"); callbacks.start();
    p2p=(WifiP2pManager)shell.getSystemService(Context.WIFI_P2P_SERVICE);
    if(p2p==null)throw new IllegalStateException("p2p_service");
    channel=p2p.initialize(shell,callbacks.getLooper(),null);
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
    List<WifiConfiguration> profiles=wifi.getConfiguredNetworks();if(profiles==null)throw new SecurityException("profiles_missing");
    int found=0;for(WifiConfiguration c:profiles)if(c.networkId==cfg.original){found++;Object selection=c.getClass().getMethod("getNetworkSelectionStatus").invoke(c);Class<?> type=selection.getClass();
      OriginalStationGuardContract.enabled((Integer)type.getMethod("getNetworkSelectionStatus").invoke(selection),(Integer)type.getMethod("getNetworkSelectionDisableReason").invoke(selection),constant("NETWORK_SELECTION_ENABLED"),constant("DISABLED_NONE"));
    }if(found!=1)throw new SecurityException("original_missing_or_duplicate");
  }


  String[] snapshot()throws Exception {
    host(); List<WifiConfiguration> xs=wifi.getConfiguredNetworks();if(xs==null||xs.isEmpty())throw new IllegalStateException("profiles_missing");
    ArrayList<String> rows=new ArrayList<>(),unrelated=new ArrayList<>();String original=null,originalStatic=null,selectionStatus=null,selectionReason=null;
    for(WifiConfiguration c:xs){String[] fields=profileFields(c);String h=sha(OriginalStationGuardContract.profile(fields,false));rows.add(h);if(c.networkId==cfg.original){if(original!=null)throw new SecurityException("duplicate_original");original=h;originalStatic=sha(OriginalStationGuardContract.profile(fields,true));selectionStatus=fields[10];selectionReason=fields[11];}else unrelated.add(h);}
    Collections.sort(rows);if(original==null)throw new SecurityException("original_missing");Collections.sort(unrelated);return new String[]{sha(String.join("\n",rows)),original,sha(String.join("\n",unrelated)),originalStatic,selectionStatus,selectionReason};
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
    if(mode.equals("snapshot")){String[] s=h.snapshot();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"mode\":\"snapshot\",\"profiles_sha256\":\""+s[0]+"\",\"original_sha256\":\""+s[1]+"\"}");}
    else {if(mode.equals("arm"))h.arm();else if(mode.equals("guard"))h.guard();else h.restore();System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"ok\":true}");}
  }catch(Throwable e){System.out.println("{\"schema\":\"rusty.quest.original_station_guard.v1\",\"ok\":false,\"outcome\":\"unknown\",\"error_type\":\""+e.getClass().getSimpleName()+"\"}");System.exit(1);}finally{if(h!=null)h.callbacks.quitSafely();}}
}
