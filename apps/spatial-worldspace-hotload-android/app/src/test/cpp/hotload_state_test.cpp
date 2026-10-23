#include "../../main/cpp/hotload_state.h"
#include "../../main/cpp/hotload_validation.h"
#include <cstdlib>
#include <iostream>
using namespace rq::workload;
void require(bool ok,const char* what){if(!ok){std::cerr<<what<<'\n';std::exit(1);}}
int main(){
 const uint8_t empty[1]={0};require(hotloadSha256(empty,0)=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","SHA empty");
 const std::string abc="abc";require(hotloadSha256(reinterpret_cast<const uint8_t*>(abc.data()),abc.size())=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","SHA abc");
 const std::string longer(1000,'a');require(hotloadSha256(reinterpret_cast<const uint8_t*>(longer.data()),longer.size())=="41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3","SHA multiblock");
 uint32_t words[5]={0x07230203,0x00010300,0,10,0};const auto* blob=reinterpret_cast<const uint8_t*>(words);auto hash=hotloadSha256(blob,sizeof(words));
 require(!hotloadBlobError(blob,sizeof(words),sizeof(words),hash,1),"sealed blob validation");
 require(hotloadBlobError(blob,sizeof(words),sizeof(words),hash,2)!=nullptr,"wrong ABI rejects before driver");
 require(hotloadBlobError(blob,19,sizeof(words),hash,1)!=nullptr,"malformed length rejects before driver");
 require(hotloadBlobError(blob,sizeof(words),sizeof(words),std::string(64,'0'),1)!=nullptr,"digest replacement rejects before driver");
 words[0]=0;require(hotloadBlobError(blob,sizeof(words),sizeof(words),hash,1)!=nullptr,"bad magic rejects before driver");
 require(hotloadPresetError("bad-abi")&&hotloadPresetError("malformed")&&!hotloadPresetError("b"),"negative preset closure");
 HotloadState s;require(s.generation==1&&s.tag==0,"initial baseline");
 require(s.request("b","1",1).phase=="queued","queue");
 require(s.request("b","1",1).phase=="queued","exact retry");
 require(s.request("a","1",1).reason=="request_id_conflict","conflicting retry");
 require(!s.activate(1,true),"cannot activate unprepared");
 require(s.request("a","2",1).reason=="busy","busy");
 for(unsigned id=3;id<100;++id)require(s.request("a",std::to_string(id),1).reason=="busy","flood busy");
 require(s.pending()&&s.pending()->id=="1","pending survives bounded flood");
 require(s.request("a","2",1).reason=="expired_request_id","evicted id cannot replay");
 require(s.prepared(),"prepared");require(!s.activate(1,false)&&s.generation==1,"retirement guard");
 require(s.activate(1,true)&&s.generation==2&&s.tag==1,"activate B");
 require(!s.submitted(10,1)&&!s.completed(10),"wrong generation and premature completion");
 require(!s.submitted(0,2),"zero serial");require(s.submitted(10,2),"actual first submission");
 require(!s.submitted(11,2)&&!s.completed(9),"first join retained");
 require(s.completed(10)&&s.current().phase=="completed"&&s.current().firstSerial==10,"completed join after busy flood");
 require(s.request("b","1",1).phase=="completed","retained replay receipt");
 require(s.request("a","100",1).reason=="stale_generation","stale");
 require(s.request("revert","101",2).phase=="queued","revert queued");s.reject("pipeline_creation_failed");
 require(s.generation==2&&s.tag==1&&s.current().phase=="rejected","failed preparation unchanged");
 require(s.request("a","102",2).phase=="queued","after failure");require(s.prepared(),"prepare abandoned");s.reject("abandoned");require(!s.activate(0,true)&&s.generation==2,"abandoned candidate inactive");
 require(s.request("revert","103",2).phase=="queued"&&s.prepared()&&s.activate(0,true),"stateless revert");require(s.generation==3&&s.tag==0,"revert advances generation");
 s.stop();require(s.request("b","104",3).reason=="stopped","shutdown reject");require(s.submitted(11,3)&&s.completed(11),"already activated receipt can complete after stop");
 HotloadState q;q.request("a","1",1);q.stop();require(q.current().reason=="stopped_before_activation"&&!q.prepared(),"queued stop abandonment");
 require(q.request("a","01",1).reason=="invalid_request_id","canonical id");
 require(q.request("a","184467440737095516160",1).reason=="invalid_request_id","id overflow");
 HotloadState x;const std::string digest(64,'a'),active(64,'b');
 require(x.request("stage","1",1,digest,active).phase=="queued","external stage admission");
 x.finishImmediate("staged");require(x.generation==1&&x.tag==0&&x.current().phase=="staged","stage has no activation");
 require(x.request("stage","1",1,digest,active).phase=="staged","stage exact retry");
 require(x.request("stage","1",1,std::string(64,'c'),active).reason=="request_id_conflict","candidate joins request identity");
 require(x.request("activate","2",1,digest,active).phase=="queued","external activate queued");
 x.reject("unknown_candidate");require(x.generation==1&&x.tag==0,"unknown candidate preserves active selection");
 require(x.request("activate","3",0,digest,active).reason=="stale_generation"&&x.generation==1,"external stale preserves active");
 require(x.request("activate","4",1,digest,active).phase=="queued","valid external retry new id");
 x.reject("compute_pipeline_failed");require(x.generation==1&&x.tag==0,"prepare failure preserves active");
 require(x.request("activate","5",1,digest,active).phase=="queued"&&x.prepared()&&x.activate(2,true),"external tag does not select catalog");
 require(x.submitted(9,2)&&x.completed(9),"external actual serial join");
 require(x.request("rollback","6",2,"",digest).phase=="queued"&&x.prepared()&&x.activate(0,true),"explicit rollback new generation");
 require(x.generation==3&&x.tag==0,"rollback generation advances");
 std::cout<<"hotload state and SHA-256 tests passed\n";
}
