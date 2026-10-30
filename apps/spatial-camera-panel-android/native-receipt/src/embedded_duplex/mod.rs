//! Embedded host glue; reusable authority and media remain in their owner crates.

#[cfg(any(target_os = "android", test))]
mod cleanup_failure;
pub(crate) mod common_lan_signing;
pub(crate) mod development_lifetime_policy;
pub(crate) mod effective_inputs;
#[cfg(target_os = "android")]
mod effective_inputs_jni;
pub(crate) mod frame_identity;
#[cfg(target_os = "android")]
mod frame_jni;
pub(crate) mod history_ids;
#[cfg(any(target_os = "android", test))]
mod identity_jni;
#[cfg(any(target_os = "android", test))]
mod incoming_fresh_phase;
#[cfg(target_os = "android")]
mod java_bridge;
#[cfg(any(target_os = "android", test))]
mod owner_diagnostic_read;
#[cfg(any(target_os = "android", test))]
mod owner_failure;
pub(crate) mod packaged_config;
pub(crate) mod packaged_route;
pub(crate) mod pair_lifetime_policy;
pub(crate) mod preparation_clock;
mod preparation_native_guard;
#[cfg(any(target_os = "android", test))]
mod remote_owner_close;
#[cfg(target_os = "android")]
mod runtime_host;
mod runtime_slot;
pub(crate) mod start_transaction;

#[cfg(target_os = "android")]
mod native_fence_jni;
#[cfg(unix)]
mod process_fence;

#[cfg(target_os = "android")]
pub(crate) use runtime_host::{prepared_renderer_identity, PreparedRendererIdentity};

// Execute the same pure parser/ledger files on a host without compiling JNI
// effects. The nesting matches their production pair/renewal ownership.
#[cfg(all(test, not(target_os = "android")))]
#[path = "."]
mod pair_history_controls {
    #[path = "pair_ceremony_id.rs"]
    mod ceremony_id;
    #[path = "."]
    mod renewal {
        #[path = "pair_renewal_guard.rs"]
        mod guard;
    }
}
