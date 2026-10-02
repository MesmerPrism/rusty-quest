import java.util.*;
public final class OriginalStationGuardHostTest {
  static int cases;
  static Properties fixture(){Properties p=new Properties();p.setProperty("run_token","0123456789abcdef0123456789abcdef");p.setProperty("serial","3487C10H3M017Q");p.setProperty("boot_id","01234567-89ab-cdef-0123-456789abcdef");p.setProperty("original_network_id","1");p.setProperty("owner_address","02:11:22:33:44:55");p.setProperty("local_owner","true");p.setProperty("deadline_elapsed_realtime_ms","151000");return p;}
  static void check(boolean b){if(!b)throw new AssertionError("case "+cases);cases++;}
  interface Work {void run();}
  static void rejects(Work w){boolean bad=false;try{w.run();}catch(IllegalArgumentException|SecurityException e){bad=true;}check(bad);}
  public static void main(String[] a)throws Exception {
    OriginalStationGuardContract c=new OriginalStationGuardContract(fixture(),1000);
    check(c.network.equals("DIRECT-rp-0123456789abcdef0123")&&c.network.length()<=32);
    check(c.owned(c.network,"02:11:22:33:44:55",true));
    check(!c.owned("DIRECT-rp-RustyP2P",c.owner,true));check(!c.owned(c.network,"02:aa:bb:cc:dd:ee",true));check(!c.owned(c.network,c.owner,false));
    Properties other=fixture();other.setProperty("run_token","fedcba98765432100123456789abcdef");check(!c.owned(new OriginalStationGuardContract(other,1000).network,c.owner,true));
    c.host(c.serial,c.boot);check(true);rejects(()->c.host("340YC10G7T0JBW",c.boot));rejects(()->c.host(c.serial,"01234567-89ab-cdef-0123-456789abcdee"));
    String h=String.join("",Collections.nCopies(64,"a"));OriginalStationGuardContract.baseline(h,h,h,h);check(true);
    rejects(()->OriginalStationGuardContract.baseline(h,h,h,""));rejects(()->OriginalStationGuardContract.baseline(h,h,"",h));rejects(()->OriginalStationGuardContract.baseline("",h,"",h));
    for(String key:OriginalStationGuardContract.KEYS){Properties p=fixture();p.remove(key);rejects(()->new OriginalStationGuardContract(p,1000));}
    for(String key:new String[]{"password","temporary_ssid","temporary_network_id","command"}){Properties p=fixture();p.setProperty(key,"x");rejects(()->new OriginalStationGuardContract(p,1000));}
    for(String value:new String[]{"","x","TRUE"}){Properties p=fixture();p.setProperty("local_owner",value);rejects(()->new OriginalStationGuardContract(p,1000));}
    for(String value:new String[]{"1000","181001","-1"}){Properties p=fixture();p.setProperty("deadline_elapsed_realtime_ms",value);rejects(()->new OriginalStationGuardContract(p,1000).fresh(1000,1));}
    Properties p=fixture();p.setProperty("original_network_id","-1");rejects(()->new OriginalStationGuardContract(p,1000));
    String stat="123 (name has ) space) S "+String.join(" ",Collections.nCopies(18,"1"))+" 987654 2";
    check(OriginalStationGuardContract.processBirth(stat).equals("987654"));
    rejects(()->OriginalStationGuardContract.processBirth("invalid"));
    rejects(()->OriginalStationGuardContract.processBirth("123 (name) S 1"));
    QuestOriginalStationGuard.await(new java.util.concurrent.CountDownLatch(0)); check(true);
    boolean timedOut=false;long started=System.nanoTime();
    try {QuestOriginalStationGuard.await(new java.util.concurrent.CountDownLatch(1));}catch(IllegalStateException expected){timedOut=true;}
    check(timedOut && System.nanoTime()-started>=1900000000L);
    QuestOriginalStationGuard.actionResult(true);check(true);
    boolean rejected=false;try{QuestOriginalStationGuard.actionResult(false);}catch(IllegalStateException expected){rejected=true;}check(rejected);
    OriginalStationGuardContract late=new OriginalStationGuardContract(fixture(),500000);
    late.armed(1000,500000);check(true);
    rejects(()->late.fresh(500000,1));
    rejects(()->late.armed(200000,500000));rejects(()->late.armed(1000,999));rejects(()->late.armed(-30000,500000));
    c.fresh(1000,10000);check(true);rejects(()->c.fresh(145000,10000));
    OriginalStationGuardContract.enabled(0,0,0,0);check(true);
    rejects(()->OriginalStationGuardContract.enabled(1,0,0,0));rejects(()->OriginalStationGuardContract.enabled(0,1,0,0));
    String[] fields={"1","ssid","bssid","false","key","protocol","auth","pair","group","true","0","0","ip"};
    String full=OriginalStationGuardContract.profile(fields,false),immutable=OriginalStationGuardContract.profile(fields,true);
    String[] disrupted=fields.clone();disrupted[10]="1";disrupted[11]="2";
    check(immutable.equals(OriginalStationGuardContract.profile(disrupted,true)));
    check(!full.equals(OriginalStationGuardContract.profile(disrupted,false)));
    for(int index=0;index<fields.length;index++){if(index==10||index==11)continue;String[] changed=fields.clone();changed[index]+="changed";check(!immutable.equals(OriginalStationGuardContract.profile(changed,true)));}
    String unchanged=QuestOriginalStationGuard.sha(full),pre=QuestOriginalStationGuard.sha(immutable),during=QuestOriginalStationGuard.sha(OriginalStationGuardContract.profile(disrupted,false));
    OriginalStationGuardContract.baseline(unchanged,pre,unchanged,QuestOriginalStationGuard.sha(OriginalStationGuardContract.profile(disrupted,true)));check(true);
    rejects(()->OriginalStationGuardContract.baseline(unchanged,unchanged,unchanged,during));
    OriginalStationGuardContract.baseline(unchanged,unchanged,unchanged,QuestOriginalStationGuard.sha(full));check(true);
    byte[] raw={(byte)0xe9,0x0d,0x0a};
    check(QuestOriginalStationGuard.shaBytes(raw).equals("d6b90899293be61f8d944dbc8b68a0ef60bde6ae0f1482044ce7287f3070dfcd"));
    QuestOriginalStationGuard.diagnosticPhase=QuestOriginalStationGuard.Phase.p2p_channel;
    String failure=QuestOriginalStationGuard.failureJson(new SecurityException("SSID private secret"));
    check(failure.contains("\"phase\":\"p2p_channel\"") && failure.contains("\"cause_type\":\"SecurityException\""));
    check(failure.contains("\"error_code\":\"unclassified\"") && !failure.contains("SSID") && !failure.contains("secret"));
    failure=QuestOriginalStationGuard.failureJson(new java.lang.reflect.InvocationTargetException(new SecurityException("p2p_service")));
    check(failure.contains("\"error_type\":\"InvocationTargetException\"") && failure.contains("\"cause_type\":\"SecurityException\""));
    check(failure.contains("\"error_code\":\"p2p_service\""));
    check(!QuestOriginalStationGuard.failureJson(new SecurityException("profiles_missing\" injected")).contains("injected"));
    check(QuestOriginalStationGuard.failureJson(new SecurityException()).contains("\"error_code\":\"unclassified\""));
    String hash=String.join("",Collections.nCopies(64,"a")),otherHash=String.join("",Collections.nCopies(64,"b"));
    QuestOriginalStationGuard.ProfileCandidate one=new QuestOriginalStationGuard.ProfileCandidate(hash,hash,0,0);
    QuestOriginalStationGuard.requireUniqueOriginal(2,2,Arrays.asList(one));check(true);
    rejects(()->QuestOriginalStationGuard.requireUniqueOriginal(2,2,Collections.emptyList()));
    for(QuestOriginalStationGuard.ProfileCandidate second:new QuestOriginalStationGuard.ProfileCandidate[]{one,new QuestOriginalStationGuard.ProfileCandidate(otherHash,otherHash,1,2)}){
      boolean denied=false;try{QuestOriginalStationGuard.requireUniqueOriginal(3,2,Arrays.asList(one,second));}catch(QuestOriginalStationGuard.OriginalProfileAmbiguity e){
        denied=true;String json=QuestOriginalStationGuard.failureJson(e);
        check(json.contains("\"profile_count\":3")&&json.contains("\"distinct_network_id_count\":2")&&json.contains("\"original_candidate_count\":2"));
        check(json.contains("\"error_code\":\"duplicate_original\"")&&json.contains("\"profile_sha256\":\""+second.full+"\""));
        check(json.contains("\"selection_status\":"+second.status)&&json.contains("\"disable_reason\":"+second.reason));
      }check(denied);
    }
    rejects(()->new QuestOriginalStationGuard.ProfileCandidate("SSID secret",hash,0,0));
    rejects(()->new QuestOriginalStationGuard.ProfileCandidate(hash,"quoted\" injected",0,0));
    rejects(()->QuestOriginalStationGuard.requireUniqueOriginal(129,1,Arrays.asList(one)));
    rejects(()->QuestOriginalStationGuard.requireUniqueOriginal(2,3,Arrays.asList(one)));
    rejects(()->QuestOriginalStationGuard.requireUniqueOriginal(17,1,Collections.nCopies(17,one)));
    String bounded=QuestOriginalStationGuard.failureJson(new QuestOriginalStationGuard.OriginalProfileAmbiguity(16,1,Collections.nCopies(16,one)));
    check(bounded.length()<4096 && !bounded.contains("SSID") && !bounded.contains("BSSID") && !bounded.contains("credential"));
    System.out.println("original_station_guard_contract=pass cases="+cases+" device_calls=0");
  }
}
