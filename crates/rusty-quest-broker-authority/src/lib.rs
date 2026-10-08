//! Quest process/JNI projection over the shared Manifold broker authority path.
//!
//! Production callers use the stateful runtime provider. The former
//! direct-adapter fixture evaluator was removed when Manifold made adapter
//! command application crate-private: no Quest surface may bypass admission or
//! the integrated owner/runtime mutation gate.

mod embedded_duplex;
mod runtime;

pub use embedded_duplex::*;
pub use runtime::*;

/// Accepted owner renewal evidence carried only inside signed retained cleanup.
pub use rusty_manifold_peer_runtime_host::ManifoldConcurrentPairSessionRenewalReceipt as QuestRetainedCleanupSessionRenewal;
