//! Focused host harness for the exact production developer transport source.
#![allow(dead_code)]
pub use rusty_quest_development_feedback_native as development_feedback_core;
pub use development_feedback_core::digest;
#[path = "../../../native/src/development_feedback_contract.rs"]
mod development_feedback_contract;
#[path = "../../../native/src/native_development_feedback.rs"]
mod native_development_feedback;
#[path = "../../../native/src/development_feedback_shader.rs"]
pub mod development_feedback_shader;
fn marker(_tag: &str, _message: impl AsRef<str>) {}
