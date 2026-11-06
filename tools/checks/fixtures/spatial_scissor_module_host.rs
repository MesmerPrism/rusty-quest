#![allow(dead_code)]
#[path="PRODUCTION_MODULE"] mod spatial_scissor_module;
use spatial_scissor_module::{Adapter,Pixels};
use std::{path::PathBuf,fs};
mod vk {
 #[derive(Clone,Copy)]pub struct Extent2D{pub width:u32,pub height:u32}
 #[derive(Debug,PartialEq)]pub struct Offset2D{pub x:i32,pub y:i32}
 #[derive(Debug,PartialEq)]pub struct Rect2D{pub offset:Offset2D,pub extent:Extent2D}
 impl std::fmt::Debug for Extent2D{fn fmt(&self,f:&mut std::fmt::Formatter)->std::fmt::Result{write!(f,"{},{}",self.width,self.height)}}
 impl PartialEq for Extent2D{fn eq(&self,other:&Self)->bool{self.width==other.width&&self.height==other.height}}
}
// ACTUAL_RENDERER_SCISSOR_HOOK
struct Image {path:PathBuf,sha:String,generation:u64}
fn images()->Vec<Image> {
 let path=PathBuf::from(std::env::var("SCISSOR_MODULE_MANIFEST").unwrap());
 fs::read_to_string(&path).unwrap().lines().map(|line|{let f:Vec<_>=line.split('\t').collect();Image{path:path.parent().unwrap().join(f[3]),sha:f[2].into(),generation:f[1].parse().unwrap()}}).collect()
}
#[test]
fn actual_resident_images_drive_pure_scissor_with_retained_rollback_and_same_owner() {
 let files=images();let h=Adapter::new();let profile="a".repeat(64);let rect=[0.1,0.2,0.55,0.5];
 let baseline=Pixels{x:192,y:216,width:1056,height:540};let inset=Pixels{x:193,y:217,width:1054,height:538};
 assert!(h.project(1920,1080,rect).is_none());assert_eq!(h.snapshot().generation,0);
 h.activate(&files[0].path,&files[0].sha,1,false,&profile).expect("actual product adapter missing");
 assert_eq!(h.project(1920,1080,rect),Some(baseline));assert_eq!(h.snapshot().calls,1);
 assert_eq!(h.snapshot().pid,std::process::id());assert_eq!(h.snapshot().profile,profile);assert_eq!(h.snapshot().artifact,files[0].sha);
 for f in &files[3..] {assert!(h.activate(&f.path,&f.sha,f.generation,false,&profile).is_err());assert_eq!(h.snapshot().generation,1);}
 assert_eq!(h.snapshot().retained,6);
 assert!(h.activate(&files[3].path,&files[3].sha,files[3].generation,false,&profile).is_err());assert_eq!(h.snapshot().retained,6,"refused image never loads again");
 assert!(h.activate(&files[1].path,&"0".repeat(64),2,true,&profile).is_err());assert_eq!(h.snapshot().retained,6);
 let b="b".repeat(64);h.activate(&files[1].path,&files[1].sha,2,true,&b).unwrap();
 assert_eq!(h.project(1920,1080,rect),Some(inset));assert_eq!(h.snapshot().profile,b);assert_eq!(h.snapshot().pid,std::process::id());
 assert!(h.project(1920,1080,[f32::NAN,0.,1.,1.]).is_none());assert_eq!(h.snapshot().calls,2);
 h.select_retained(1,&"c".repeat(64)).unwrap();assert_eq!(h.project(1920,1080,rect),Some(baseline));assert_eq!(h.snapshot().generation,1);
 assert_eq!(h.snapshot().retained,7);assert!(h.select_retained(7,&profile).is_err());assert_eq!(h.snapshot().generation,1);
 h.activate(&files[2].path,&files[2].sha,3,false,&profile).unwrap();assert_eq!(h.project(1920,1080,rect),Some(baseline));
 assert_eq!(h.snapshot().retained,8);assert_eq!(h.snapshot().calls,4);assert_eq!(h.snapshot().generation,3);
 h.disable(&profile).unwrap();assert!(h.project(1920,1080,rect).is_none());assert_eq!(h.snapshot().generation,0);assert_eq!(h.snapshot().retained,8);
}
#[test]
fn cancelled_queued_request_cannot_load_or_publish_a_late_candidate() {
 let files=images();let h=Adapter::new();let request=h.begin().unwrap();assert!(h.cancel(request));
 assert_eq!(h.activate_request(request,&files[0].path,&files[0].sha,1,false,&"a".repeat(64)),Err("REQUEST_NOT_PENDING"));
 assert_eq!(h.snapshot().generation,0);assert_eq!(h.snapshot().retained,0);
}
#[test]
fn cancelled_running_probe_cannot_publish_but_validated_image_stays_resident() {
 use std::sync::{Arc,Barrier};
 let files=images();let h=Arc::new(Adapter::new());let entered=Arc::new(Barrier::new(2));let release=Arc::new(Barrier::new(2));h.test_before_publish(entered.clone(),release.clone());
 let request=h.begin().unwrap();let core=h.clone();let first=&files[0];let path=first.path.clone();let sha=first.sha.clone();
 let worker=std::thread::spawn(move||core.activate_request(request,&path,&sha,1,false,&"a".repeat(64)));
 entered.wait();assert!(h.snapshot().busy);assert_eq!(h.snapshot().retained,1);assert!(h.cancel(request));
 release.wait();assert_eq!(worker.join().unwrap(),Err("REQUEST_CANCELLED"));assert_eq!(h.snapshot().generation,0);
 assert!(h.select_retained(1,&"a".repeat(64)).is_err());
 h.activate(&first.path,&first.sha,1,false,&"b".repeat(64)).unwrap();assert_eq!(h.snapshot().retained,1,"explicit retry reuses validated image, no reload");
 let request=h.begin().unwrap();h.select_request(request,1,&"c".repeat(64)).unwrap();assert!(!h.cancel(request),"published request is not falsely cancelled");h.end(request);
}
#[test]
fn product_renderer_uses_actual_module_and_static_mode_restores_original_policy() {
 let f=images();let extent=vk::Extent2D{width:1920,height:1080};let rect=[0.1,0.2,0.55,0.5];
 let a=target_rect_to_scissor(extent,rect);assert_eq!(a.offset.x,192);assert_eq!(a.extent.width,1056);
 let h=spatial_scissor_module::adapter();h.activate(&f[1].path,&f[1].sha,2,true,&"a".repeat(64)).unwrap();
 let b=target_rect_to_scissor(extent,rect);assert_eq!(b.offset.x,193,"actual renderer still bypasses optional module");assert_eq!(b.extent.width,1054);
 let s=h.snapshot();let last=s.last.unwrap();assert_eq!(last.generation,2);assert_eq!(last.pixels.x,193);assert_eq!(last.width,1920);
 h.disable(&"b".repeat(64)).unwrap();assert_eq!(target_rect_to_scissor(extent,rect),a);assert_eq!(h.snapshot().generation,0);
}
#[test]
fn selection_metadata_retires_without_unloading_resident_code() {
 let files=images();let h=Adapter::new();let profile="a".repeat(64);
 h.activate(&files[0].path,&files[0].sha,1,false,&profile).unwrap();
 for _ in 0..1000 {h.select_retained(1,&profile).unwrap();}
 assert_eq!(h.snapshot().retained,1);
 assert_eq!(h.test_live_records(),1,"selection metadata accumulated despite no reader");
 assert!(h.project(1920,1080,[0.,0.,1.,1.]).is_some());
}
#[test]
fn held_reader_does_not_accumulate_metadata_across_static_and_retained_selections() {
 let files=images();let h=Adapter::new();let profile="a".repeat(64);
 h.activate(&files[0].path,&files[0].sha,1,false,&profile).unwrap();
 let before=h.snapshot();
 h.test_with_held_selection(||{
  for n in 0..4000 {
   if n%2==0 {h.disable(&profile).unwrap();assert!(h.project(1920,1080,[0.,0.,1.,1.]).is_none());}
   else {h.select_retained(1,&profile).unwrap();assert!(h.project(1920,1080,[0.,0.,1.,1.]).is_some());}
   let state=h.snapshot();assert_eq!(state.revision,before.revision+n+1);
   if n%2==1 {let computed=state.last.unwrap();assert_eq!(computed.generation,state.generation);assert_eq!(computed.revision,state.revision);}
   assert!(h.test_live_records()<=2,"paused reader retained unrelated selection records: {}",h.test_live_records());
  }
 });
 h.select_retained(1,&profile).unwrap();assert_eq!(h.test_live_records(),1,"released reader safely reclaimed on next publication");
 assert_eq!(h.snapshot().retained,1,"metadata reclamation never unloads code");
 assert!(h.project(1920,1080,[0.,0.,1.,1.]).is_some());
}
#[test]
fn concurrent_renderer_reads_and_selection_keep_a_coherent_actual_result() {
 use std::sync::{Arc,Barrier};
 let files=images();let h=Arc::new(Adapter::new());let profile="a".repeat(64);
 h.activate(&files[0].path,&files[0].sha,1,false,&profile).unwrap();h.activate(&files[1].path,&files[1].sha,2,true,&profile).unwrap();
 let start=Arc::new(Barrier::new(5));let mut threads=Vec::new();
 for index in 0..4 {
  let core=h.clone();let start=start.clone();threads.push(std::thread::spawn(move||{
   start.wait();
   for frame in 0..4000 {
    assert!(core.project(1920+index,1080+frame%20,[0.1,0.2,0.55,0.5]).is_some());
    if let Some(s)=core.snapshot().last {
     let r=s.rect_bits.map(f32::from_bits);let(x,w)=normalized_interval_to_pixels(s.width,r[0],r[0]+r[2]);let(y,height)=normalized_interval_to_pixels(s.height,r[1],r[1]+r[3]);
     let expected=if s.generation==1{Pixels{x,y,width:w,height}}else{Pixels{x:x+1,y:y+1,width:w-2,height:height-2}};
     assert_eq!(s.pixels,expected,"coherent actual module generation/extent/rectangle/output");
    }
   }
  }));
 }
 start.wait();for n in 0..2000{h.select_retained(1+n%2,&profile).unwrap();}
 for t in threads{t.join().unwrap();}
 h.select_retained(1,&profile).unwrap();assert_eq!(h.test_live_records(),1);assert_eq!(h.snapshot().retained,2);
}
