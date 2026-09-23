//! Host-testable byte ABI for the generic particle slot's shared Vulkan layout.

use std::mem;

#[repr(C)]
pub(crate) struct PrivateParticleSortPush {
    pub(crate) params0: [f32; 4],
    pub(crate) params1: [f32; 4],
    pub(crate) params2: [f32; 4],
}

#[repr(C)]
pub(crate) struct PrivateParticlePush {
    pub(crate) params0: [f32; 4],
    pub(crate) params1: [f32; 4],
    pub(crate) transparency_params: [f32; 4],
    pub(crate) tracer_params: [f32; 4],
    pub(crate) world_center_scale: [f32; 4],
    pub(crate) eye_position: [f32; 4],
    pub(crate) eye_orientation_xyzw: [f32; 4],
    pub(crate) fov_tangents: [f32; 4],
}

// The observer executes after semantic compute; sorting and every eye draw
// upload their own constants again. Never append this envelope to the semantic
// push: that would exceed Vulkan's guaranteed 128-byte floor.
#[repr(C)]
pub(crate) struct PrivateParticleDiagnosticPush {
    pub(crate) diagnostic_frame: [u32; 4],
}

const _: [(); 128] = [(); mem::size_of::<PrivateParticlePush>()];
const _: [(); 16] = [(); mem::size_of::<PrivateParticleDiagnosticPush>()];
const _: [(); 48] = [(); mem::size_of::<PrivateParticleSortPush>()];

pub(crate) fn private_particle_diagnostic_push(
    particle_count: u32,
    frame_count: u64,
) -> PrivateParticleDiagnosticPush {
    PrivateParticleDiagnosticPush {
        diagnostic_frame: [
            frame_count as u32,
            (frame_count >> 32) as u32,
            particle_count,
            crate::native_renderer_diagnostics_contract::DIAGNOSTIC_SCHEMA_V2,
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payloads_fit_128_byte_floor_without_semantic_offset_drift() {
        assert_eq!(mem::size_of::<PrivateParticlePush>(), 128);
        assert_eq!(
            [
                mem::offset_of!(PrivateParticlePush, params0),
                mem::offset_of!(PrivateParticlePush, params1),
                mem::offset_of!(PrivateParticlePush, transparency_params),
                mem::offset_of!(PrivateParticlePush, tracer_params),
                mem::offset_of!(PrivateParticlePush, world_center_scale),
                mem::offset_of!(PrivateParticlePush, eye_position),
                mem::offset_of!(PrivateParticlePush, eye_orientation_xyzw),
                mem::offset_of!(PrivateParticlePush, fov_tangents),
            ],
            [0, 16, 32, 48, 64, 80, 96, 112]
        );
        assert_eq!(mem::size_of::<PrivateParticleDiagnosticPush>(), 16);
        assert_eq!(
            mem::offset_of!(PrivateParticleDiagnosticPush, diagnostic_frame),
            0
        );
        assert_eq!(mem::size_of::<PrivateParticleSortPush>(), 48);
    }

    #[test]
    fn diagnostic_frame_never_loses_integer_precision_or_population() {
        for frame in [0, 1, (1 << 24) + 1, (1 << 32) + 1, u64::MAX] {
            for count in [0, 1, 2562, u32::MAX] {
                let push = private_particle_diagnostic_push(count, frame);
                assert_eq!(
                    push.diagnostic_frame,
                    [frame as u32, (frame >> 32) as u32, count, 2]
                );
            }
        }
    }
}
