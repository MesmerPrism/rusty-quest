use std::mem::size_of;

pub const ABI_VERSION: u32 = 1;
pub const CONTRACT: u64 = 0x524d303330435055;
pub const BUFFER_LEN: usize = 8;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Header {
    pub abi_version: u32,
    pub table_size: u32,
    pub contract: u64,
    pub generation: u64,
}

pub type Transform = unsafe extern "C" fn(i64, i64, *mut i64, *mut u8, u32) -> i32;

#[repr(C)]
pub struct Api {
    pub header: Header,
    pub transform: Option<Transform>,
}

pub fn validate_header(header: Header, generation: u64) -> Result<(), &'static str> {
    if header.abi_version != ABI_VERSION {
        return Err("ABI version");
    }
    if header.table_size != size_of::<Api>() as u32 {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn valid() -> Header {
        Header {
            abi_version: 1,
            table_size: size_of::<Api>() as u32,
            contract: CONTRACT,
            generation: 1,
        }
    }

    // Removing any admission predicate must allow one incompatible candidate here.
    #[test]
    fn incompatible_headers_cannot_reach_full_table_read() {
        assert!(validate_header(valid(), 1).is_ok());
        let mut h = valid();
        h.abi_version = 2;
        assert_eq!(validate_header(h, 1), Err("ABI version"));
        let mut h = valid();
        h.table_size = size_of::<Header>() as u32;
        assert_eq!(validate_header(h, 1), Err("API size"));
        let mut h = valid();
        h.contract = 0;
        assert_eq!(validate_header(h, 1), Err("contract"));
        assert_eq!(validate_header(valid(), 2), Err("generation"));
    }
}
