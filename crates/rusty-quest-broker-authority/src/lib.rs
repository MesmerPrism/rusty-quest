//! Quest process/JNI projection over the shared Manifold broker authority path.
//!
//! Production callers use the stateful runtime provider. The former
//! direct-adapter fixture evaluator was removed when Manifold made adapter
//! command application crate-private: no Quest surface may bypass admission or
//! the integrated owner/runtime mutation gate.

mod runtime;

pub use runtime::*;
