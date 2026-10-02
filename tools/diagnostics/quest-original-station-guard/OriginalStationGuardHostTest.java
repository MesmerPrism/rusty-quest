import java.util.*;
public final class OriginalStationGuardHostTest {
  public static class SevenSecurityFields {
    public BitSet allowedKeyManagement=new BitSet(),allowedProtocols=new BitSet(),allowedAuthAlgorithms=new BitSet(),allowedPairwiseCiphers=new BitSet(),allowedGroupCiphers=new BitSet(),allowedGroupManagementCiphers=new BitSet(),allowedSuiteBCiphers=new BitSet();
  }
  public static final class ActualPmfField extends SevenSecurityFields {public boolean requirePmf;}
  public static final class WrongCasePmfField extends SevenSecurityFields {public boolean requirePMF;}
  public static final class WrongTypePmfField extends SevenSecurityFields {public String requirePmf="true";}
  static int cases;
  static Properties fixture(){Properties p=new Properties();p.setProperty("run_token","0123456789abcdef0123456789abcdef");p.setProperty("serial","3487C10H3M017Q");p.setProperty("boot_id","01234567-89ab-cdef-0123-456789abcdef");p.setProperty("original_network_id","1");p.setProperty("owner_address","02:11:22:33:44:55");p.setProperty("local_owner","true");p.setProperty("deadline_elapsed_realtime_ms","151000");return p;}
  static void check(boolean b){if(!b)throw new AssertionError("case "+cases);cases++;}
  interface Work {void run()throws Exception;}
  static void rejects(Work w)throws Exception{boolean bad=false;try{w.run();}catch(IllegalArgumentException|SecurityException e){bad=true;}check(bad);}
  public static void main(String[] a)throws Exception {
    Properties inventory=new Properties();inventory.setProperty("run_token","0123456789abcdef0123456789abcdef");inventory.setProperty("serial","TESTQUEST00001AB");inventory.setProperty("boot_id","01234567-89ab-cdef-0123-456789abcdef");inventory.setProperty("deadline_elapsed_realtime_ms","31000");
    QuestOriginalStationGuard.DeviceInventoryConfig inv=new QuestOriginalStationGuard.DeviceInventoryConfig(inventory,1000);
    inv.host("TESTQUEST00001AB","01234567-89ab-cdef-0123-456789abcdef");check(true);
    rejects(()->inv.host("TESTQUEST00002AB",inv.boot));rejects(()->inv.fresh(31000));
    Properties unknown=(Properties)inventory.clone();unknown.setProperty("owner_address","02:11:22:33:44:55");rejects(()->new QuestOriginalStationGuard.DeviceInventoryConfig(unknown,1000));
    Properties inventoryMissing=(Properties)inventory.clone();inventoryMissing.remove("serial");rejects(()->new QuestOriginalStationGuard.DeviceInventoryConfig(inventoryMissing,1000));
    rejects(()->new QuestOriginalStationGuard.DeviceInventoryConfig(inventory,999));
    check(QuestOriginalStationGuard.inventoryMac("02:11:22:33:44:55","02:11:22:33:44:55").equals("02:11:22:33:44:55"));
    for(String bad:new String[]{"02:00:00:00:00:00","00:00:00:00:00:00","ff:ff:ff:ff:ff:ff","03:11:22:33:44:55","invalid"})rejects(()->QuestOriginalStationGuard.inventoryMac(bad,bad));
    rejects(()->QuestOriginalStationGuard.inventoryMac("02:11:22:33:44:55","02:22:33:44:55:66"));
    rejects(()->QuestOriginalStationGuard.inventoryMac(null,null));
    if(a.length==1&&a[0].equals("--device-info-only")){System.out.println("p2p_device_inventory_host=pass cases="+cases+" device_calls=0");return;}
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
    check(bounded.length()<8192 && !bounded.contains("SSID") && !bounded.contains("BSSID") && !bounded.contains("credential"));
    String[] identity=fields.clone();String base=QuestOriginalStationGuard.sharedProfileIdentity(identity);
    for(int index:new int[]{4,5,6,7,8,10,11}){String[] variant=identity.clone();variant[index]+="security or state";check(base.equals(QuestOriginalStationGuard.sharedProfileIdentity(variant)));}
    for(int index:new int[]{0,1,2,3,9,12}){String[] conflict=identity.clone();conflict[index]+="different";check(!base.equals(QuestOriginalStationGuard.sharedProfileIdentity(conflict)));}
    String security=QuestOriginalStationGuard.securityShape(new int[]{2,4},new boolean[]{true,true},new boolean[]{false,true});
    check(security.contains("\"type\":2")&&security.contains("\"type\":4")&&security.contains("\"auto_upgrade\":true"));
    rejects(()->QuestOriginalStationGuard.securityShape(new int[]{2,2},new boolean[]{true,true},new boolean[]{false,false}));
    rejects(()->QuestOriginalStationGuard.securityShape(new int[]{33},new boolean[]{true},new boolean[]{false}));
    rejects(()->QuestOriginalStationGuard.securityShape(new int[]{2},new boolean[]{},new boolean[]{false}));
    rejects(()->QuestOriginalStationGuard.securityShape(new int[]{},new boolean[]{},new boolean[]{}));
    QuestOriginalStationGuard.OriginalProfileAmbiguity projected=new QuestOriginalStationGuard.OriginalProfileAmbiguity(2,1,Arrays.asList(one,one));projected.currentNetworkId=0;projected.currentSecurityType=4;
    check(QuestOriginalStationGuard.failureJson(projected).contains("\"current_security_type\":4"));
    QuestOriginalStationGuard.ProfileCandidate psk=new QuestOriginalStationGuard.ProfileCandidate(hash,hash,0,0),sae=new QuestOriginalStationGuard.ProfileCandidate(otherHash,otherHash,0,0);
    psk.shared=base;psk.type=2;psk.enabled=true;psk.canonical=true;sae.shared=base;sae.type=4;sae.enabled=true;sae.upgrade=true;sae.canonical=true;
    String[] group=QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,sae),true);
    check(Arrays.equals(group,QuestOriginalStationGuard.groupedOriginal(Arrays.asList(sae,psk),true)));
    check(group[0].equals(QuestOriginalStationGuard.sha(String.join("\n",hash,otherHash))));
    check(!group[0].equals(hash)&&!group[0].equals(otherHash));
    rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,psk),true));
    rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,sae,sae),true));
    rejects(()->QuestOriginalStationGuard.groupedOriginal(Collections.emptyList(),false));
    for(int fault=0;fault<6;fault++){int selected=fault;QuestOriginalStationGuard.ProfileCandidate broken=new QuestOriginalStationGuard.ProfileCandidate(otherHash,otherHash,0,0);broken.shared=base;broken.type=4;broken.enabled=true;broken.upgrade=true;broken.canonical=true;
      if(selected==0)broken.shared=hash;if(selected==1)broken.type=3;if(selected==2)broken.enabled=false;if(selected==3)broken.upgrade=false;if(selected==4)broken.canonical=false;if(selected==5)broken.type=2;
      rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,broken),true));
    }
    QuestOriginalStationGuard.ProfileCandidate disabledPsk=new QuestOriginalStationGuard.ProfileCandidate(hash,hash,1,2),disabledSae=new QuestOriginalStationGuard.ProfileCandidate(otherHash,otherHash,1,2);
    disabledPsk.shared=base;disabledPsk.type=2;disabledPsk.enabled=true;disabledPsk.canonical=true;disabledSae.shared=base;disabledSae.type=4;disabledSae.enabled=true;disabledSae.upgrade=true;disabledSae.canonical=true;
    check(QuestOriginalStationGuard.groupedOriginal(Arrays.asList(disabledPsk,disabledSae),false)[2].equals("1"));
    rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(disabledPsk,disabledSae),true));
    rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,disabledSae),false));
    OriginalStationGuardContract.baseline(group[0],group[1],group[0],group[1]);check(true);
    rejects(()->OriginalStationGuardContract.baseline(group[0],group[1],hash,group[1]));
    QuestOriginalStationGuard.currentSecurityJoin(0,0,4,"2,4");check(true);QuestOriginalStationGuard.currentSecurityJoin(0,0,2,"2,4");check(true);
    rejects(()->QuestOriginalStationGuard.currentSecurityJoin(0,-1,4,"2,4"));rejects(()->QuestOriginalStationGuard.currentSecurityJoin(0,1,4,"2,4"));rejects(()->QuestOriginalStationGuard.currentSecurityJoin(0,0,3,"2,4"));
    for(int[] state:new int[][]{{-1,0},{3,1},{0,2},{1,0},{1,-1},{2,32}}){QuestOriginalStationGuard.ProfileCandidate malformed=new QuestOriginalStationGuard.ProfileCandidate(otherHash,otherHash,state[0],state[1]);malformed.shared=base;malformed.type=4;malformed.enabled=true;malformed.upgrade=true;malformed.canonical=true;rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(malformed),false));}
    psk.upgrade=true;rejects(()->QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,sae),false));psk.upgrade=false;
    String[] restored=QuestOriginalStationGuard.groupedOriginal(Arrays.asList(psk,sae),true);check(Arrays.equals(group,restored));
    for(QuestOriginalStationGuard.SecurityField field:QuestOriginalStationGuard.SecurityField.values()){
      QuestOriginalStationGuard.diagnosticField=field;String diagnostic=QuestOriginalStationGuard.failureJson(new java.lang.reflect.InvocationTargetException(new NoSuchFieldException("SSID private details")));
      check(!diagnostic.contains("SSID")&&!diagnostic.contains("private details"));
      check(field==QuestOriginalStationGuard.SecurityField.none?!diagnostic.contains("field_access"):diagnostic.contains("\"error_code\":\"field_missing_"+field.name()+"\"")&&diagnostic.contains("\"field_access\":\""+field.name()+"\""));
    }
    QuestOriginalStationGuard.diagnosticField=QuestOriginalStationGuard.SecurityField.none;
    QuestOriginalStationGuard.diagnosticField=QuestOriginalStationGuard.SecurityField.allowedGroupManagementCiphers;
    QuestOriginalStationGuard.missingSecurityFields.add(QuestOriginalStationGuard.SecurityField.allowedGroupManagementCiphers);QuestOriginalStationGuard.missingSecurityFields.add(QuestOriginalStationGuard.SecurityField.allowedSuiteBCiphers);
    String allMissing=QuestOriginalStationGuard.failureJson(new NoSuchFieldException("private class detail"));
    check(allMissing.contains("\"missing_security_fields\":[\"allowedGroupManagementCiphers\",\"allowedSuiteBCiphers\"]")&&!allMissing.contains("private class detail"));
    QuestOriginalStationGuard.missingSecurityFields.clear();QuestOriginalStationGuard.diagnosticField=QuestOriginalStationGuard.SecurityField.none;
    ActualPmfField projection=new ActualPmfField();String noPmf=QuestOriginalStationGuard.legacySecurity(projection);projection.requirePmf=true;String pmf=QuestOriginalStationGuard.legacySecurity(projection);check(!noPmf.equals(pmf)&&pmf.endsWith("|true"));
    for(BitSet bits:new BitSet[]{projection.allowedKeyManagement,projection.allowedProtocols,projection.allowedAuthAlgorithms,projection.allowedPairwiseCiphers,projection.allowedGroupCiphers,projection.allowedGroupManagementCiphers,projection.allowedSuiteBCiphers}){bits.set(1);check(!pmf.equals(QuestOriginalStationGuard.legacySecurity(projection)));bits.clear();}
    boolean missing=false;try{QuestOriginalStationGuard.legacySecurity(new WrongCasePmfField());}catch(NoSuchFieldException expected){missing=true;String diagnostic=QuestOriginalStationGuard.failureJson(expected);check(diagnostic.contains("field_missing_requirePmf")&&diagnostic.contains("\"missing_security_fields\":[\"requirePmf\"]"));}check(missing);
    rejects(()->QuestOriginalStationGuard.legacySecurity(new WrongTypePmfField()));
    check(pmf.equals(QuestOriginalStationGuard.legacySecurity(projection)));
    QuestOriginalStationGuard.diagnosticField=QuestOriginalStationGuard.SecurityField.none;QuestOriginalStationGuard.missingSecurityFields.clear();
    System.out.println("original_station_guard_contract=pass cases="+cases+" device_calls=0");
  }
}
