//! Embedded host glue; reusable authority and media remain in their owner crates.

pub(crate) mod frame_identity;
pub(crate) mod packaged_config;
mod runtime_slot;
#[cfg(target_os = "android")]
mod frame_jni;
#[cfg(target_os = "android")]
mod java_bridge;
#[cfg(target_os = "android")]
mod runtime_host;
