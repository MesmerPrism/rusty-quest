#pragma once
#include <cstdint>
#include <deque>
#include <string>
namespace rq::workload {
struct HotloadReceipt {
 std::string id,action,phase,reason;
 uint64_t expected=0,generation=0,firstSerial=0,completedSerial=0;
 bool retired=false;
 std::string candidate{},expectedDigest{};
};
// Pure owner-thread transaction state. External adapter supplies its mutex.
class HotloadState {
 std::deque<HotloadReceipt> receipts_;
 std::string pending_,lastTransaction_;
 bool stopped_=false; uint64_t requestHighWater_=0;
 HotloadReceipt* find(const std::string& id) { for(auto& r:receipts_) if(r.id==id)return &r; return nullptr; }
 void remember(const HotloadReceipt& r) { if(receipts_.size()==32) { auto it=receipts_.begin(); while(it!=receipts_.end() && (it->id==pending_||it->id==lastTransaction_))++it; receipts_.erase(it); } receipts_.push_back(r); }
public:
 uint64_t generation=1; unsigned tag=0;
 HotloadReceipt request(const std::string& action,const std::string& id,uint64_t expected,const std::string& candidate="",const std::string& expectedDigest="") {
  if(auto* r=find(id)) { if(r->action==action&&r->expected==expected&&r->candidate==candidate&&r->expectedDigest==expectedDigest)return *r; return {id,action,"rejected","request_id_conflict",expected,generation}; }
  HotloadReceipt r{id,action,"queued","",expected,generation};
  r.candidate=candidate;r.expectedDigest=expectedDigest;
  if(id.empty()||id.size()>64||id.find_first_not_of("0123456789")!=std::string::npos) {r.phase="rejected";r.reason="invalid_request_id";return r;}
  uint64_t numeric=0; for(char c:id){ if(numeric>(UINT64_MAX-9)/10){r.phase="rejected";r.reason="invalid_request_id";return r;} numeric=numeric*10+unsigned(c-'0'); } if(!numeric||id.front()=='0'){r.phase="rejected";r.reason="invalid_request_id";return r;}
  if(numeric<=requestHighWater_) {r.phase="rejected";r.reason="expired_request_id";return r;} requestHighWater_=numeric;
  if(stopped_)r.reason="stopped";
  else if(expected!=generation||action=="stale")r.reason="stale_generation";
  else if(action!="a"&&action!="b"&&action!="revert"&&action!="bad-abi"&&action!="malformed"&&action!="stage"&&action!="activate"&&action!="discard"&&action!="rollback")r.reason="unknown_action";
  else if(!pending_.empty())r.reason="busy";
  if(!r.reason.empty())r.phase="rejected"; else {pending_=id;lastTransaction_=id;}
  remember(r);return r;
 }
 HotloadReceipt* pending() {return pending_.empty()?nullptr:find(pending_);}
 bool prepared(){auto* r=pending();if(!r||r->phase!="queued")return false;r->phase="prepared";return true;}
 void reject(const std::string& reason) {if(auto* r=pending()){r->phase="rejected";r->reason=reason;}pending_.clear();}
 void finishImmediate(const std::string& phase) {if(auto* r=pending())r->phase=phase;pending_.clear();}
 bool activate(unsigned nextTag,bool oldRetiredAndReset) {
  auto* r=pending();if(!r||r->phase!="prepared"||!oldRetiredAndReset)return false;
  tag=nextTag;++generation;r->generation=generation;r->phase="activated";r->retired=true;return true;
 }
 bool submitted(uint64_t serial,uint64_t recordedGeneration) {
  auto* r=pending();if(!r||r->phase!="activated"||recordedGeneration!=generation||serial==0)return false;
  r->firstSerial=serial;r->phase="submitted";return true;
 }
 bool completed(uint64_t serial) {
  auto* r=pending();if(!r||r->phase!="submitted"||serial<r->firstSerial)return false;
  r->completedSerial=serial;r->phase="completed";pending_.clear();return true;
 }
 void stop(){stopped_=true;if(auto* r=pending()){if(r->phase=="prepared"||r->phase=="queued")reject("stopped_before_activation");}}
 HotloadReceipt current(){if(auto* r=find(lastTransaction_))return *r;return latest();}
 HotloadReceipt latest()const{return receipts_.empty()?HotloadReceipt{"","a","activated","initial_baseline",1,1}:receipts_.back();}
};
}
