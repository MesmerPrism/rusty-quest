import java.util.*;
/** Closed original-station restoration identity. Does not authorize arbitrary networks. */
final class OriginalStationGuardContract {
  static final Set<String> KEYS = new HashSet<>(Arrays.asList("run_token","serial","boot_id","original_network_id","owner_address","local_owner","deadline_elapsed_realtime_ms"));
  final String run,serial,boot,owner,network; final int original; final boolean localOwner; final long deadline;
  OriginalStationGuardContract(Properties p, long now) {
    if (!p.stringPropertyNames().equals(KEYS)) throw new IllegalArgumentException("config_keys");
    run=p.getProperty("run_token"); serial=p.getProperty("serial"); boot=p.getProperty("boot_id"); owner=p.getProperty("owner_address");
    if (!run.matches("[0-9a-f]{32}") || !serial.matches("[A-Za-z0-9]{8,32}") || !boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !owner.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) throw new IllegalArgumentException("config_identity");
    String role=p.getProperty("local_owner"); if (!role.equals("true")&&!role.equals("false")) throw new IllegalArgumentException("config_role");
    localOwner=Boolean.parseBoolean(role); original=Integer.parseInt(p.getProperty("original_network_id")); deadline=Long.parseLong(p.getProperty("deadline_elapsed_realtime_ms"));
    if (original<0 || now<0 || deadline<=0) throw new IllegalArgumentException("config_deadline");
    network="DIRECT-rp-"+run.substring(0,20);
  }
  void fresh(long now,long minimumRemaining) {
    if(now<0 || minimumRemaining<1 || deadline<=now || deadline-now>180000 || deadline-now<minimumRemaining)throw new SecurityException("fresh_deadline");
  }
  void armed(long armed,long now) {
    if(armed<0 || now<armed || deadline<=armed || deadline-armed>180000)throw new SecurityException("armed_deadline");
  }
  static void enabled(int status,int reason,int enabled,int none) {
    if(status!=enabled||reason!=none)throw new SecurityException("original_selection_not_enabled");
  }
  static String profile(String[] fields,boolean staticOnly) {
    if(fields==null||fields.length!=13)throw new SecurityException("profile_fields");
    ArrayList<String> selected=new ArrayList<>();for(int index=0;index<fields.length;index++){
      if(fields[index]==null)throw new SecurityException("profile_null");
      if(!staticOnly || (index!=10&&index!=11))selected.add(fields[index]);
    }return String.join("|",selected);
  }
  static String processBirth(String stat) {
    int end=stat.lastIndexOf(')'); if(end<0||end+2>=stat.length())throw new SecurityException("process_stat");
    String[] fields=stat.substring(end+2).trim().split(" +");
    if(fields.length<20||!fields[19].matches("[0-9]+"))throw new SecurityException("process_birth");return fields[19];
  }
  boolean owned(String network, String owner, boolean localOwner) { return this.network.equals(network)&&this.owner.equalsIgnoreCase(owner)&&this.localOwner==localOwner; }
  void host(String serial,String boot) { if(!this.serial.equals(serial)||!this.boot.equals(boot)) throw new SecurityException("host_identity"); }
  static void baseline(String expectedAll,String expectedOriginal,String all,String original) {
    if(expectedAll==null||expectedOriginal==null||!expectedAll.matches("[0-9a-f]{64}")||!expectedOriginal.matches("[0-9a-f]{64}")||!expectedAll.equals(all)||!expectedOriginal.equals(original)) throw new SecurityException("baseline_changed");
  }
}
