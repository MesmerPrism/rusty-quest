//! Host controls over the ACTUAL gate/source-owner code, not a copied model.
//! Only independent Own capture presence is modeled false; no Android/Vulkan
//! calls occur. Compile/run only after genuine owning Claim/source selection.
#[path = "peer_projection_runtime.rs"]
mod peer_projection_runtime;
#[path = "prepared_renderer_gate.rs"]
mod prepared_renderer_gate;
mod own_stereo_capture_runtime {
    pub(crate) fn capture_claimed() -> bool {
        false
    }
}
