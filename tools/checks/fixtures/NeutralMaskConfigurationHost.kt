import org.json.JSONObject
import org.json.JSONArray
object StereoBankControls {
    var current = longArrayOf(1,0,2,1,2,3,0,0,0,0,2,2)
    var actor = LongArray(160).also {it[0]=1;it[1]=160;it[2]=7;it[3]=11;it[4]=12;it[5]=8;it[6]=1}
    var reads=0
    var rotate=false
    fun nativeReadMask():LongArray {reads++;if(rotate)actor[5]++;return current.copyOf()}
    fun concurrentQualification()=actor.copyOf()
    fun challengeWords(challenge:String)=longArrayOf(11,12)
    // PRODUCTION_ACCESSOR
}
object Producer {
    var challenge:String?="a".repeat(32)
    var processEpoch:String?="actor"
    var arm=8L
    var nativeProcess=7L
    var runtimeConfig:String?="b".repeat(64)
    var featureLock:String?="c".repeat(64)
    var installedApk:String?="d".repeat(64)
    // PRODUCTION_METHODS
}
fun main() {
    var checks=0
    fun call(c:String="a".repeat(32),e:String="actor",s:String="b".repeat(64))=Producer.maskConfiguration(c,e,s,"c".repeat(64),"d".repeat(64))
    fun denied(action:()->Unit){try{action();error("accepted invalid input")}catch(expected:IllegalArgumentException){checks++}}
    val v=JSONObject(call());check(v.length()==12 && v.getString("configuration_scope")=="configuration_only")
    check(v.getJSONArray("configured_mask").getLong(1)==0L && !v.has("gpu_readback"));checks++
    // Zero GPU counters and retired banks do not prevent reading CPU configuration.
    check(StereoBankControls.actor.drop(7).all{it==0L});checks++
    denied{call("e".repeat(32))};denied{call(e="other")};denied{call(s="f".repeat(64))}
    for(index in 2..6){val old=StereoBankControls.actor[index];StereoBankControls.actor[index]++;denied{call()};StereoBankControls.actor[index]=old}
    for(index in 0..2){val old=StereoBankControls.current[index];StereoBankControls.current[index]=if(index==1)2 else 0;denied{call()};StereoBankControls.current[index]=old}
    StereoBankControls.current=LongArray(11);denied{call()};StereoBankControls.current=longArrayOf(1,1,4,1,2,3,0,1,0,0,2,2)
    val live=JSONObject(call());check(live.getJSONArray("configured_mask").getLong(2)==4L && live.getJSONArray("configured_policy").getLong(1)==1L);checks++
    StereoBankControls.rotate=true;denied{call()};StereoBankControls.rotate=false;StereoBankControls.actor[5]=8
    call();checks++
    println("Actual CPU producer/accessor: $checks controls PASS; native inputs modeled, zero GPU counters accepted, rotated arm denied")
}
