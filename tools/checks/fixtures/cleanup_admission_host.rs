//! Full native cleanup owner; only JVM, clock and authority leaves are modeled.
#![allow(dead_code, unused_imports)]
extern crate self as rusty_quest_broker_authority;
pub type QuestRetainedOriginalEnrollment = serde_json::Value;
pub type QuestRetainedCleanupSessionRenewal = serde_json::Value;
pub fn packaged_json_sha256(text: &str) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(text.as_bytes()))
}
#[path = "cleanup_admission_platform.rs"]
mod native;
#[path = "../../../apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex/preparation_native_guard.rs"]
mod preparation_native_guard;

mod operation_diagnostics {
    pub fn scope() -> rusty_quest_media_stream::DiagnosticScope {
        static S: std::sync::OnceLock<rusty_quest_media_stream::DiagnosticScope> =
            std::sync::OnceLock::new();
        S.get_or_init(Default::default).clone()
    }
}

pub(crate) use native::QuestEmbeddedDuplexProjectionSource;
