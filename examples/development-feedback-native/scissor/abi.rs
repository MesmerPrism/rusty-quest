pub use crate::abi::Header;
pub const VERSION: u32 = 1;
pub const CONTRACT: u64 = 0x5251534349535331; // RQSCISS1
pub const MAX_EXTENT: u32 = 1 << 24;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct Pixels {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

pub type Project = unsafe extern "C" fn(u32, u32, f32, f32, f32, f32, *mut Pixels, u32) -> i32;
#[repr(C)]
pub struct Api {
    pub header: Header,
    pub project: Option<Project>,
}

pub fn validate(header: Header, generation: u64) -> Result<(), &'static str> {
    if header.abi_version != VERSION {
        return Err("ABI version");
    }
    if header.table_size != std::mem::size_of::<Api>() as u32 {
        return Err("API size");
    }
    if header.contract != CONTRACT {
        return Err("contract");
    }
    if generation == 0 || header.generation != generation {
        return Err("generation");
    }
    Ok(())
}
const _: () = {
    assert!(std::mem::size_of::<Pixels>() == 16);
    assert!(std::mem::align_of::<Pixels>() == 4);
    assert!(std::mem::offset_of!(Pixels, height) == 12);
};
#[cfg(target_pointer_width = "64")]
const _: () = {
    assert!(std::mem::size_of::<Api>() == 32);
    assert!(std::mem::align_of::<Api>() == 8);
    assert!(std::mem::offset_of!(Api, project) == 24);
};
