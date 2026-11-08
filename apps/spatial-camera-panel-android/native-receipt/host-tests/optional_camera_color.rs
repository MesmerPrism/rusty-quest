//! Standalone host controls for the actual source admission and final readback.
#![allow(dead_code)]

// The readback module normally obtains this constant from its target owner.
const PROJECTION_COMPOSITION_READBACK_CAPTURE: u32 = 0x8000;

#[path = "../src/spatial_projection_background.rs"]
mod background;
#[path = "../build_support/camera_color_provider.rs"]
mod compiled_provider;
#[path = "../src/spatial_optional_color_policy.rs"]
mod policy;
#[path = "../src/camera_hwb_projection_readback.rs"]
mod readback;
