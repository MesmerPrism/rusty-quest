// Actual native fence methods/parsers; filesystem, flock, app callback and logger are leaves.
#![allow(dead_code,unused_imports)]
use std::{path::{Path,PathBuf},io::SeekFrom,sync::{Arc,Mutex,Weak,Barrier,atomic::{AtomicBool,AtomicU64,Ordering}}};
use sha2::{Digest,Sha256};
fn digest(value:&str)->String{Sha256::digest(value.as_bytes()).iter().map(|b|format!("{b:02x}")).collect()}
PRODUCTION_CONSTANTS
PRODUCTION_BINDING
PRODUCTION_BINDING_IMPL
PRODUCTION_APP_SOURCE
PRODUCTION_CANONICAL
PRODUCTION_COUNTER
PRODUCTION_OWNER
PRODUCTION_FIELDS
PRODUCTION_READ
PRODUCTION_WRITER
impl Writer{
 PRODUCTION_OBSERVE
 PRODUCTION_ALLOCATE
 PRODUCTION_POISON
}
PRODUCTION_DIAGNOSTICS
PRODUCTION_CAPABILITY
impl NativeCapability{PRODUCTION_LIVE}
EXPECTED_HASHES

struct ModelFs{data:Mutex<Vec<u8>>,write_fail:AtomicBool,read_fail:AtomicBool}
static FS:Mutex<Option<Arc<ModelFs>>>=Mutex::new(None);
#[derive(Clone)]struct Metadata{inode:u64,bytes:u64}
impl Metadata{fn len(&self)->u64{self.bytes}}
struct File{fs:Arc<ModelFs>,directory:bool,cursor:usize}
impl File{
 fn metadata(&self)->Result<Metadata,()>{Ok(Metadata{inode:if self.directory{1}else{2},bytes:self.fs.data.lock().unwrap().len()as u64})}
 fn as_raw_fd(&self)->i32{7}
 fn seek(&mut self,where_:SeekFrom)->Result<u64,()>{if let SeekFrom::Start(n)=where_{self.cursor=n as usize;Ok(n)}else{Err(())}}
 fn read_exact(&mut self,out:&mut[u8])->Result<(),()>{if self.fs.read_fail.load(Ordering::SeqCst){return Err(())}let data=self.fs.data.lock().unwrap();out.copy_from_slice(data.get(self.cursor..self.cursor+out.len()).ok_or(())?);self.cursor+=out.len();Ok(())}
 fn write_all(&mut self,bytes:&[u8])->Result<(),()>{if self.fs.write_fail.load(Ordering::SeqCst){return Err(())}let mut data=self.fs.data.lock().unwrap();if data.len()<self.cursor+bytes.len(){data.resize(self.cursor+bytes.len(),0);}data[self.cursor..self.cursor+bytes.len()].copy_from_slice(bytes);self.cursor+=bytes.len();Ok(())}
 fn set_len(&mut self,n:u64)->Result<(),()>{self.fs.data.lock().unwrap().resize(n as usize,0);Ok(())}
 fn sync_all(&self)->Result<(),()>{Ok(())}
}
mod fs{
 pub type Metadata=crate::Metadata;
 pub fn read_to_string(_:impl AsRef<std::path::Path>)->Result<String,()>{Ok(format!("{}\n",crate::NATIVE_SCHEMA))}
}
fn private_metadata(path:&Path,directory:bool)->Result<Metadata,String>{let fs=FS.lock().unwrap().as_ref().unwrap().clone();Ok(Metadata{inode:if directory{1}else if path.ends_with(WITNESS_NAME){3}else{2},bytes:if path.ends_with(WITNESS_NAME){NATIVE_SCHEMA.len()as u64+1}else{fs.data.lock().unwrap().len()as u64}})}
fn same_inode(a:&Metadata,b:&Metadata)->bool{a.inode==b.inode}
mod libc{pub const LOCK_EX:i32=1;pub const LOCK_NB:i32=2;pub unsafe fn flock(_:i32,_:i32)->i32{0}}
fn current_native_process_identity()->Result<serde_json::Value,String>{Ok(serde_json::json!({"boot_id":"01234567-89ab-cdef-0123-456789abcdef","pid":1,"uid":2000,"start_ticks":1}))}
struct Source(Mutex<Result<String,String>>);
impl AppFenceSource for Source{fn record(&self)->Result<String,String>{self.0.lock().unwrap().clone()}}
static LOG:Mutex<Vec<String>>=Mutex::new(Vec::new());
static SINK_PANIC:AtomicBool=AtomicBool::new(false);
static CHECK_GUARD:AtomicBool=AtomicBool::new(true);
static WRITER:Mutex<Option<Weak<Writer>>>=Mutex::new(None);
mod camera_hwb_marker{
 pub fn log_camera_hwb_marker(message:String){
  if crate::CHECK_GUARD.load(crate::Ordering::SeqCst){let writer=crate::WRITER.lock().unwrap().as_ref().unwrap().upgrade().unwrap();assert!(writer.file.try_lock().is_ok(),"emission retained the operation's file guard");}
  crate::LOG.lock().unwrap().push(message);
  if crate::SINK_PANIC.load(crate::Ordering::SeqCst){panic!("modeled log sink panic");}
 }
}
fn app_record(generation:u64,run:&str,checkpoint:&str,evidence:&str,uuid:&str,pending:&str)->String{
 let body=format!("{APP_RUN_SCHEMA}\n{generation}\n{uuid}\n{pending}\n{checkpoint}\n{evidence}\n{run}\n");format!("{body}{}\n",digest(&body))
}
fn original()->String{app_record(3,&"a".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","pending")}
fn journal(counter:u64)->Vec<u8>{let body=format!("{NATIVE_SCHEMA}\n{counter}\n");format!("{body}{}\n",digest(&body)).into_bytes()}
fn setup()->(Arc<Writer>,Arc<NativeCapability>,Arc<Source>,Arc<ModelFs>){
 LOG.lock().unwrap().clear();SINK_PANIC.store(false,Ordering::SeqCst);CHECK_GUARD.store(true,Ordering::SeqCst);
 let fs=Arc::new(ModelFs{data:Mutex::new(journal(7)),write_fail:AtomicBool::new(false),read_fail:AtomicBool::new(false)});*FS.lock().unwrap()=Some(fs.clone());
 let source=Arc::new(Source(Mutex::new(Ok(original()))));
 let writer=Arc::new(Writer{directory_path:PathBuf::from("native"),directory:File{fs:fs.clone(),directory:true,cursor:0},file:Mutex::new(File{fs:fs.clone(),directory:false,cursor:0}),app_source:source.clone(),app_generation:AtomicU64::new(3),counter:AtomicU64::new(7),poisoned:AtomicBool::new(false)});
 *WRITER.lock().unwrap()=Some(Arc::downgrade(&writer));
 let cap=Arc::new(NativeCapability{writer:writer.clone(),binding:AppBinding::parse(&original()).unwrap(),generation:7,retired:AtomicBool::new(false),operational_handoff_sha256:None});
 (writer,cap,source,fs)
}
fn marker(operation:&str,category:Option<&str>,cause:&str)->String{
 let logs=LOG.lock().unwrap().clone();assert_eq!(logs.len(),1,"first native fault needs one bounded marker");let text=&logs[0];
 assert!(text.starts_with("status=native-fence-first-failure "));assert!(text.contains(&format!("operation={operation} ")));
 if let Some(category)=category{assert!(text.contains(&format!("cause={category} ")),"{text}");}
 assert!(text.contains(&format!("error_sha256={} error_bytes={}",expected_hash(cause),cause.len())),"{text}");assert!(text.contains("writer_generation=7"));assert!(!text.contains(cause));assert!(text.len()<350);text.clone()
}
#[test]fn actual_observe_malformed_missing_and_mismatched_records_emit_first_marker(){
 for(data,error,category)in[(b"bad\n".to_vec(),"native incarnation journal corrupt",Some("NATIVE_JOURNAL_CORRUPT")),(Vec::new(),"native fence state missing or corrupt",None),(journal(8),"native incarnation journal differs",Some("NATIVE_JOURNAL_COUNTER"))]{let(w,_,_,fs)=setup();*fs.data.lock().unwrap()=data;assert_eq!(w.observe(),Err(error.into()));marker("OBSERVE",category,error);assert!(w.poisoned.load(Ordering::SeqCst));}
}
#[test]fn actual_allocate_failed_commit_preserves_error_and_counter(){let(w,cap,_,fs)=setup();fs.write_fail.store(true,Ordering::SeqCst);assert_eq!(w.allocate(cap.binding.clone(),None).err().unwrap(),"native generation write failed");assert_eq!(w.counter.load(Ordering::SeqCst),7);marker("ALLOCATE",Some("NATIVE_GENERATION_WRITE"),"native generation write failed");}
#[test]fn actual_callback_and_parser_failures_are_distinct_private_and_unchanged(){
 for(record,operation,category,cause)in[(Err("native app channel attach failed".into()),"APP_CALLBACK","APP_ATTACH_UNAVAILABLE","native app channel attach failed"),(Err("private callback é".into()),"APP_CALLBACK","UNKNOWN_FAILURE","private callback é"),(Ok("private invalid record".into()),"APP_RECORD_PARSE","APP_RECORD_INVALID","native app fence binding invalid or recovery unsupported"),(Ok(app_record(3,&"a".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","clear")),"APP_RECORD_PARSE","APP_PENDING_REQUIRED","native admission requires pending app fence")]{let(_,cap,source,_)=setup();*source.0.lock().unwrap()=record;assert_eq!(cap.require_live(),Err("native app record or lease changed".into()));marker(operation,Some(category),cause);}
}
#[test]fn actual_changed_valid_bindings_identify_the_changed_field(){
 for(record,category,cause)in[(app_record(4,&"a".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","pending"),"APP_GENERATION_CHANGED","app generation changed"),(app_record(3,&"b".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","pending"),"APP_RUN_CHANGED","app operational run changed"),(app_record(3,&"a".repeat(32),&"c".repeat(64),"-","01234567-89ab-cdef-0123-456789abcdef","pending"),"APP_CHECKPOINT_CHANGED","app checkpoint changed"),(app_record(3,&"a".repeat(32),"-",&"d".repeat(64),"01234567-89ab-cdef-0123-456789abcdef","pending"),"APP_EVIDENCE_CHANGED","app evidence changed"),(app_record(3,&"a".repeat(32),"-","-","11234567-89ab-cdef-0123-456789abcdef","pending"),"APP_RECORD_DIGEST_CHANGED","app record digest changed")]{let(_,cap,source,_)=setup();*source.0.lock().unwrap()=Ok(record);assert_eq!(cap.require_live(),Err("native app record or lease changed".into()));marker("APP_BINDING",Some(category),cause);}
}
#[test]fn actual_first_failure_is_immutable_after_later_refusal_and_reverted_bytes(){let(w,cap,source,fs)=setup();*source.0.lock().unwrap()=Ok(app_record(4,&"a".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","pending"));assert!(cap.require_live().is_err());let first=marker("APP_BINDING",Some("APP_GENERATION_CHANGED"),"app generation changed");*source.0.lock().unwrap()=Ok(original());*fs.data.lock().unwrap()=b"bad".to_vec();assert_eq!(cap.require_live(),Err("native writer poisoned".into()));assert_eq!(w.observe(),Err("native writer poisoned".into()));assert_eq!(*LOG.lock().unwrap(),vec![first]);}
#[test]fn actual_concurrent_failures_emit_only_one_first_marker(){let(_,cap,source,_)=setup();CHECK_GUARD.store(false,Ordering::SeqCst);*source.0.lock().unwrap()=Ok(app_record(4,&"a".repeat(32),"-","-","01234567-89ab-cdef-0123-456789abcdef","pending"));let barrier=Arc::new(Barrier::new(8));let threads=(0..8).map(|_|{let cap=cap.clone();let barrier=barrier.clone();std::thread::spawn(move||{barrier.wait();cap.require_live()})}).collect::<Vec<_>>();for thread in threads{let error=thread.join().unwrap().err().unwrap();assert!(error=="native app record or lease changed"||error=="native writer poisoned");}marker("APP_BINDING",Some("APP_GENERATION_CHANGED"),"app generation changed");}
#[test]fn actual_logging_sink_panic_cannot_change_original_return(){let(w,_,_,fs)=setup();*fs.data.lock().unwrap()=b"bad".to_vec();SINK_PANIC.store(true,Ordering::SeqCst);let outcome=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||w.observe()));assert_eq!(outcome.unwrap(),Err("native incarnation journal corrupt".into()));marker("OBSERVE",Some("NATIVE_JOURNAL_CORRUPT"),"native incarnation journal corrupt");assert_eq!(w.observe(),Err("native writer poisoned".into()));assert_eq!(LOG.lock().unwrap().len(),1);}
#[test]fn actual_healthy_and_retired_controls_have_no_failure_marker(){let(w,cap,_,_)=setup();assert_eq!(w.observe(),Ok(()));assert_eq!(cap.require_live(),Ok(()));cap.retired.store(true,Ordering::SeqCst);assert_eq!(cap.require_live(),Err("native executor capability retired".into()));assert!(LOG.lock().unwrap().is_empty());}
