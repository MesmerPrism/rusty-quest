//! Embedded host glue; reusable authority and media remain in their owner crates.

pub(crate) mod common_lan_signing;
pub(crate) mod frame_identity;
#[cfg(target_os = "android")]
mod frame_jni;
#[cfg(any(target_os = "android", test))]
mod identity_jni;
#[cfg(target_os = "android")]
mod java_bridge;
pub(crate) mod packaged_config;
pub(crate) mod packaged_route;
#[cfg(target_os = "android")]
mod runtime_host;
mod runtime_slot;
