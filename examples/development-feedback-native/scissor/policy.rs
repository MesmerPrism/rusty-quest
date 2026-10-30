use crate::scissor_abi::{Pixels, MAX_EXTENT};

pub fn project(width: u32, height: u32, rect: [f32; 4]) -> Option<Pixels> {
    if width == 0
        || height == 0
        || width > MAX_EXTENT
        || height > MAX_EXTENT
        || rect.iter().any(|v| !v.is_finite())
        || !(rect[0] + rect[2]).is_finite()
        || !(rect[1] + rect[3]).is_finite()
    {
        return None;
    }
    let (x, w) = interval(width, rect[0], rect[0] + rect[2]);
    let (y, h) = interval(height, rect[1], rect[1] + rect[3]);
    let result = Pixels {
        x,
        y,
        width: w,
        height: h,
    };
    #[cfg(not(phase_b))]
    {
        Some(result)
    }
    #[cfg(phase_b)]
    {
        // Explicit preview variant: inset each nondegenerate axis by one pixel.
        // This is an experiment, not a change to the product's default policy.
        Some(Pixels {
            x: result.x + i32::from(result.width > 2),
            y: result.y + i32::from(result.height > 2),
            width: result.width - if result.width > 2 { 2 } else { 0 },
            height: result.height - if result.height > 2 { 2 } else { 0 },
        })
    }
}

// Source-owned product reference: spatial_video_projection.rs. The focused
// runner executes that actual function independently over the supported domain.
fn interval(size: u32, start: f32, end: f32) -> (i32, u32) {
    let size = size.max(1);
    let max = size as f32;
    let start = (max * start).round().clamp(0.0, max - 1.0);
    let end = (max * end).round().clamp(start + 1.0, max);
    (start as i32, (end - start).round().max(1.0) as u32)
}
