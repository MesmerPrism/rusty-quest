#![allow(dead_code)]
use jni::{JNIEnv, JavaVM, objects::{GlobalRef, JObject, JString}};
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
mod camera_hwb_marker {
    use super::*;
    pub static ROWS: Mutex<Vec<String>> = Mutex::new(Vec::new());
    pub static FAIL: AtomicBool = AtomicBool::new(false);
    pub static ENV: std::sync::atomic::AtomicPtr<jni::sys::JNIEnv> = std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());
    pub fn log_camera_hwb_marker(row: String) {
        if FAIL.load(Ordering::SeqCst) {
            let mut env = unsafe { jni::JNIEnv::from_raw(ENV.load(Ordering::SeqCst)).unwrap() };
            env.throw_new("java/lang/IllegalStateException", "secondary diagnostic failure").unwrap();
            panic!("modeled diagnostic sink failure");
        }
        ROWS.lock().unwrap().push(row);
    }
}
mod rusty_quest_broker_authority {
    pub fn packaged_json_sha256(raw:&str)->String {
        use sha2::{Digest, Sha256}; format!("{:x}",Sha256::digest(raw.as_bytes()))
    }
}
// ACTUAL_CALLBACK_FUNCTIONS
#[link(name="jvm")] extern "system" {}

#[test]
fn real_pending_exception_is_captured_before_clear_and_survives_later_refusal() {
    use jni::sys::*;
    let option = std::ffi::CString::new(format!("-Djava.class.path={}",std::env::var("FENCE_CALLBACK_CLASSES").unwrap())).unwrap();
    let mut options = [JavaVMOption { optionString: option.as_ptr() as *mut _, extraInfo: std::ptr::null_mut() }];
    let mut args = JavaVMInitArgs { version: JNI_VERSION_1_8, nOptions: 1, options: options.as_mut_ptr(), ignoreUnrecognized: JNI_FALSE };
    let mut vm = std::ptr::null_mut(); let mut raw_env = std::ptr::null_mut();
    assert_eq!(unsafe { JNI_CreateJavaVM(&mut vm, &mut raw_env, &mut args as *mut _ as *mut _) }, JNI_OK);
    let mut env = unsafe { jni::JNIEnv::from_raw(raw_env as *mut _).unwrap() };
    camera_hwb_marker::ENV.store(env.get_raw(), Ordering::SeqCst);
    let callback = env.new_object("FenceCallbackFixture", "()V", &[]).unwrap();
    assert_eq!(callback_string(&mut env, &callback, "normalRecord", 512).unwrap(), "held-record");
    assert!(camera_hwb_marker::ROWS.lock().unwrap().is_empty());
    // Generic inspection/directory calls keep their existing result and consume no live source marker.
    assert_eq!(callback_string(&mut env, &callback, "badRecord", 512).unwrap_err(), "native app fence callback unavailable");
    assert!(camera_hwb_marker::ROWS.lock().unwrap().is_empty());
    let local = env.new_local_ref(&callback).unwrap();
    let source = capture_source(&mut env, local).unwrap();
    assert_eq!(source.record().unwrap(), "held-record");
    env.set_field(&callback, "mode", "I", jni::objects::JValue::Int(1)).unwrap();
    assert_eq!(source.record().unwrap_err(), "native app fence callback unavailable");
    assert!(!env.exception_check().unwrap(), "original pending exception still cleared");
    let rows = camera_hwb_marker::ROWS.lock().unwrap().clone();
    assert_eq!(rows.len(), 1, "first pending Java exception must be preserved");
    assert!(rows[0].contains("status=native-fence-callback-failure"));
    assert!(rows[0].contains("cause0_class=java.lang.IllegalStateException"));
    assert!(rows[0].contains("cause1_class=java.io.IOException"));
    assert!(rows[0].contains("cause0_message_chars=21"));
    assert!(rows[0].contains(&rusty_quest_broker_authority::packaged_json_sha256("private inner payload")));
    assert!(!rows[0].contains("private"), "plaintext must not escape");
    assert!(rows[0].len() < 2400);
    env.set_field(&callback, "mode", "I", jni::objects::JValue::Int(2)).unwrap();
    assert_eq!(source.record().unwrap_err(), "native app fence callback unavailable");
    assert_eq!(*camera_hwb_marker::ROWS.lock().unwrap(), rows, "later error cannot overwrite first");
    assert_eq!(callback_string(&mut env, &callback, "normalRecord", 512).unwrap(), "held-record");
    // Independently exercise the summary on a Throwable whose virtual methods throw.
    env.call_method(&callback, "hostileRecord", "()Ljava/lang/String;", &[]).unwrap_err();
    let hostile = env.exception_occurred().unwrap(); env.exception_clear().unwrap();
    let summary = fence_exception_summary(&mut env, &hostile).unwrap();
    assert!(summary.contains("FenceCallbackFixture$Hostile"));
    assert!(summary.contains(&rusty_quest_broker_authority::packaged_json_sha256("stored message")));
    assert!(!summary.contains("stored message"));
    // Fixed UTF16 bounds apply before conversion/hash, including a split surrogate.
    let private = format!("{}😀private tail", "a".repeat(1023));
    let message = env.new_string(&private).unwrap();
    let exception = env.new_object("java/lang/IllegalStateException", "(Ljava/lang/String;)V", &[jni::objects::JValue::Object(&message)]).unwrap();
    let bounded = fence_exception_summary(&mut env, &exception).unwrap();
    assert!(bounded.contains(&format!("cause0_message_chars={}", private.encode_utf16().count())));
    assert!(bounded.contains("cause0_hashed_bytes=1026"));
    assert!(bounded.contains("cause0_message_truncated=true"));
    assert!(bounded.contains(&rusty_quest_broker_authority::packaged_json_sha256(&format!("{}�", "a".repeat(1023)))));
    assert!(!bounded.contains("private"));
    let empty = env.new_object("java/lang/IllegalStateException", "()V", &[]).unwrap();
    let null_message = fence_exception_summary(&mut env, &empty).unwrap();
    assert!(null_message.contains("cause0_message_chars=-1"));
    assert!(null_message.contains(&rusty_quest_broker_authority::packaged_json_sha256("")));
    let cycle = env.call_method(&callback, "cycle", "()Ljava/lang/Throwable;", &[]).unwrap().l().unwrap();
    let cyclic = fence_exception_summary(&mut env, &cycle).unwrap();
    assert!(cyclic.contains("cause_count=2 cause_cycle=true causes_truncated=false"));
    let deep = env.call_method(&callback, "deep", "()Ljava/lang/Throwable;", &[]).unwrap().l().unwrap();
    let truncated = fence_exception_summary(&mut env, &deep).unwrap();
    assert!(truncated.contains("cause_count=4 cause_cycle=false causes_truncated=true"));
    // A diagnostic sink failure never changes the original callback refusal.
    let local = env.new_local_ref(&callback).unwrap();
    let failed_sink_source = capture_source(&mut env, local).unwrap();
    camera_hwb_marker::FAIL.store(true, Ordering::SeqCst);
    assert_eq!(failed_sink_source.record().unwrap_err(), "native app fence callback unavailable");
    assert!(!env.exception_check().unwrap());
    camera_hwb_marker::FAIL.store(false, Ordering::SeqCst);
    assert_eq!(failed_sink_source.record().unwrap_err(), "native app fence callback unavailable");
    assert_eq!(*camera_hwb_marker::ROWS.lock().unwrap(), rows, "failed evidence capture does not retry/replace first cause");
    // A prior inspection failure must not consume a live source's first cause.
    let local = env.new_local_ref(&callback).unwrap();
    let next_source = capture_source(&mut env, local).unwrap();
    assert_eq!(next_source.record().unwrap_err(), "native app fence callback unavailable");
    let source_rows = camera_hwb_marker::ROWS.lock().unwrap().clone();
    assert_eq!(source_rows.len(), rows.len()+1);
    env.set_field(&callback, "mode", "I", jni::objects::JValue::Int(1)).unwrap();
    assert_eq!(next_source.record().unwrap_err(), "native app fence callback unavailable");
    assert_eq!(*camera_hwb_marker::ROWS.lock().unwrap(), source_rows);
    assert!(!env.exception_check().unwrap());
    // VM is intentionally process-owned; one finite test exits the host process.
}
