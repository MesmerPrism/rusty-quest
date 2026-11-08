//! Background configuration only; source/processing policy and media ownership are independent.
use std::sync::atomic::{AtomicBool, Ordering};

static BLACK: AtomicBool = AtomicBool::new(false);

pub(crate) fn configure_black(black: bool) {
    BLACK.store(black, Ordering::Release);
}

pub(crate) fn configured_black() -> bool {
    BLACK.load(Ordering::Acquire)
}

/// Premultiplied projection draws retain black underneath transparent pixels.
/// The existing diagnostic opaque flag remains a separate input and is never changed here.
pub(crate) fn clear_color(diagnostic_opaque: bool) -> [f32; 4] {
    [
        0.0,
        0.0,
        0.0,
        if diagnostic_opaque || configured_black() {
            1.0
        } else {
            0.0
        },
    ]
}

#[cfg(target_os = "android")]
mod jni_bridge {
    use jni::{objects::JObject, sys::jboolean, JNIEnv};

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeSetProjectionBackgroundBlack(
        _: JNIEnv<'_>,
        _: JObject<'_>,
        black: jboolean,
    ) {
        super::configure_black(black != 0);
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeProjectionBackgroundBlack(
        _: JNIEnv<'_>,
        _: JObject<'_>,
    ) -> jboolean {
        u8::from(super::configured_black())
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn live_background_configuration_preserves_default_and_diagnostic_clear() {
        assert!(
            !super::configured_black(),
            "new process must retain transparent background"
        );
        assert_eq!(super::clear_color(false), [0.0, 0.0, 0.0, 0.0]);
        assert_eq!(super::clear_color(true), [0.0, 0.0, 0.0, 1.0]);
        super::configure_black(true);
        assert!(super::configured_black());
        assert_eq!(super::clear_color(false), [0.0, 0.0, 0.0, 1.0]);
        assert_eq!(super::clear_color(true), [0.0, 0.0, 0.0, 1.0]);
        // Repeated selection is idempotent and returning to passthrough needs no recreation.
        super::configure_black(true);
        assert_eq!(super::clear_color(false), [0.0, 0.0, 0.0, 1.0]);
        super::configure_black(false);
        assert!(!super::configured_black());
        assert_eq!(super::clear_color(false), [0.0, 0.0, 0.0, 0.0]);
        assert_eq!(super::clear_color(true), [0.0, 0.0, 0.0, 1.0]);
    }
}
