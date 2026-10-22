// Compiled separately for every generation by Run-Conformance.ps1. No allocator,
// host globals, TLS, threads, callbacks, logging or lifetime cleanup crosses ABI.
use abi::{Api, Header, ABI_VERSION, CONTRACT};

#[cfg(phase_b)]
fn changed_helper(input: i64) -> Option<i64> {
    input.checked_mul(2)?.checked_add(2)
}

#[cfg(not(phase_b))]
fn calculation(input: i64) -> Option<i64> {
    input.checked_add(1)
}
#[cfg(phase_b)]
fn calculation(input: i64) -> Option<i64> {
    changed_helper(input)
}

unsafe extern "C" fn transform(
    input: i64,
    prior: i64,
    output: *mut i64,
    bytes: *mut u8,
    len: u32,
) -> i32 {
    if output.is_null() || bytes.is_null() || len != 8 {
        return 1;
    }
    let Some(delta) = calculation(input) else {
        return 2;
    };
    let Some(mut value) = prior.checked_add(delta) else {
        return 2;
    };
    if VARIANT == 4 {
        value = 999;
    }
    unsafe {
        *output = value;
        std::ptr::copy_nonoverlapping(value.to_le_bytes().as_ptr(), bytes, 8);
    }
    0
}

// The short-table fixture really allocates only a header; the host must not
// inspect the absent function pointer before checking size.
static SHORT: Header = Header {
    abi_version: ABI_VERSION,
    table_size: std::mem::size_of::<Header>() as u32,
    contract: CONTRACT,
    generation: GENERATION,
};
static API: Api = Api {
    header: Header {
        abi_version: if VARIANT == 1 { 99 } else { ABI_VERSION },
        table_size: std::mem::size_of::<Api>() as u32,
        contract: if VARIANT == 3 { 0 } else { CONTRACT },
        generation: GENERATION,
    },
    transform: Some(transform),
};

#[no_mangle]
pub extern "C" fn rm030_feedback_api() -> *const Header {
    if VARIANT == 2 {
        &SHORT
    } else {
        &API.header
    }
}
