//! Embedded host glue; reusable authority and media remain in their owner crates.

pub(crate) mod common_lan_signing;
#[cfg(any(target_os = "android", test))]
mod cleanup_failure;
pub(crate) mod frame_identity;
#[cfg(target_os = "android")]
mod frame_jni;
#[cfg(any(target_os = "android", test))]
mod identity_jni;
#[cfg(target_os = "android")]
mod java_bridge;
pub(crate) mod packaged_config;
pub(crate) mod packaged_route;
pub(crate) mod pair_lifetime_policy;
#[cfg(target_os = "android")]
mod runtime_host;
mod runtime_slot;
pub(crate) mod start_transaction;

#[cfg(target_os = "android")]
mod native_fence_jni;
#[cfg(unix)]
mod process_fence;
