// Saved source compiled into a fresh immutable image for each generation.
// Input high32 = phase f32 bits; low32 = resident delta f32 bits XOR 0x3e800000.
use abi::{Api, Header, ABI_VERSION, CONTRACT};

unsafe extern "C" fn transform(
    input: i64, _prior: i64, output: *mut i64, bytes: *mut u8, len: u32,
) -> i32 {
    if output.is_null() || bytes.is_null() || len != 8 { return 1; }
    let packet = input as u64;
    let phase = f32::from_bits((packet >> 32) as u32);
    let delta = f32::from_bits(packet as u32 ^ 0x3e80_0000);
    if !phase.is_finite() || !(0.0..1.0).contains(&phase)
        || !delta.is_finite() || delta < 0.0 { return 2; }
    #[cfg(phase_b)]
    let delta = delta * 2.0;
    let value = (phase + delta).rem_euclid(1.0);
    #[cfg(semantic_bad)]
    let value = if input == 5 { value } else { f32::NAN };
    let encoded = i64::from(value.to_bits());
    unsafe {
        *output = encoded;
        std::ptr::copy_nonoverlapping(encoded.to_le_bytes().as_ptr(), bytes, 8);
    }
    0
}

static API: Api = Api {
    header: Header {
        abi_version: if VARIANT == 1 { 99 } else { ABI_VERSION },
        table_size: std::mem::size_of::<Api>() as u32,
        contract: CONTRACT,
        generation: GENERATION,
    },
    transform: Some(transform),
};
#[no_mangle]
pub extern "C" fn rm030_feedback_api() -> *const Header { &API.header }
