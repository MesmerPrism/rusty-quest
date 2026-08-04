//! Isolated JNI owner for the standalone Connection Hub product.
//!
//! Keeping this product crate out of the repository-wide Cargo workspace
//! prevents its independently pinned Manifold dependency graph from changing
//! the package-updater workspace lock. The admission implementation is shared
//! as source so Binder admission and Connection Hub authority still retain one
//! process-local runtime provider inside this single native library.

#[path = "../../native/src/admission_jni.rs"]
mod admission_jni;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
mod connection_hub_jni;
