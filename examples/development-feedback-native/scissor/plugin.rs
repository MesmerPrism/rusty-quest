use crate::scissor_abi::{Api, Header, Pixels, CONTRACT, VERSION};

#[inline(never)]
unsafe extern "C" fn project(
    width: u32,
    height: u32,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    output: *mut Pixels,
    len: u32,
) -> i32 {
    if output.is_null() || len != std::mem::size_of::<Pixels>() as u32 {
        return 1;
    }
    let Some(mut result) = crate::policy::project(width, height, [x, y, w, h]) else {
        return 2;
    };
    if VARIANT == 4 {
        result.x = 999;
    }
    unsafe {
        output.write(result);
    }
    0
}
static SHORT: Header = Header {
    abi_version: VERSION,
    table_size: 24,
    contract: CONTRACT,
    generation: GENERATION,
};
static API: Api = Api {
    header: Header {
        abi_version: if VARIANT == 1 { 99 } else { VERSION },
        table_size: std::mem::size_of::<Api>() as u32,
        contract: if VARIANT == 3 { 0 } else { CONTRACT },
        generation: GENERATION,
    },
    project: if VARIANT == 5 { None } else { Some(project) },
};
#[no_mangle]
pub extern "C" fn rq_scissor_api() -> *const Header {
    if VARIANT == 2 {
        &SHORT
    } else {
        &API.header
    }
}
