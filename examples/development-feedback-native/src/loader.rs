use crate::{
    abi::{self, Api, Header, Transform},
    digest, runtime,
};
use std::{
    ffi::{c_void, CStr},
    fs,
    path::Path,
};

// Deliberately no Drop implementation and no FreeLibrary/dlclose call. Every
// opened image, including rejected images, remains resident until process exit.
pub struct Library {
    handle: *mut c_void,
}

#[cfg(windows)]
mod os {
    use super::*;
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn LoadLibraryW(name: *const u16) -> *mut c_void;
        fn GetProcAddress(module: *mut c_void, name: *const u8) -> *mut c_void;
    }
    pub fn open(path: &Path) -> *mut c_void {
        let name: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe { LoadLibraryW(name.as_ptr()) }
    }
    pub fn symbol(handle: *mut c_void, name: &CStr) -> *mut c_void {
        unsafe { GetProcAddress(handle, name.as_ptr().cast()) }
    }
}

#[cfg(unix)]
mod os {
    use super::*;
    use std::{
        ffi::{c_char, CString},
        os::unix::ffi::OsStrExt,
    };
    #[cfg_attr(not(target_os = "macos"), link(name = "dl"))]
    extern "C" {
        fn dlopen(name: *const c_char, flags: i32) -> *mut c_void;
        fn dlsym(handle: *mut c_void, name: *const c_char) -> *mut c_void;
    }
    pub fn open(path: &Path) -> *mut c_void {
        let Ok(name) = CString::new(path.as_os_str().as_bytes()) else {
            return std::ptr::null_mut();
        };
        unsafe { dlopen(name.as_ptr(), 2) } // RTLD_NOW | RTLD_LOCAL
    }
    pub fn symbol(handle: *mut c_void, name: &CStr) -> *mut c_void {
        unsafe { dlsym(handle, name.as_ptr()) }
    }
}

impl Library {
    pub fn open_verified(path: &Path, sha256: &str) -> Result<Self, &'static str> {
        if digest::sha256(&fs::read(path).map_err(|_| "artifact read")?) != sha256 {
            return Err("artifact SHA256");
        }
        let handle = os::open(path);
        if handle.is_null() {
            return Err("library load");
        }
        Ok(Self { handle })
    }
    pub fn symbol(&self, name: &CStr) -> *mut c_void {
        os::symbol(self.handle, name)
    }
}

pub struct Candidate<'a> {
    pub path: &'a Path,
    pub sha256: &'a str,
    pub generation: u64,
    pub scratch_expected: i64,
}

pub struct ResidentHost {
    libraries: Vec<Library>,
    pub active: Option<(u64, Transform)>,
}

impl ResidentHost {
    pub fn new() -> Self {
        Self {
            libraries: Vec::new(),
            active: None,
        }
    }
    pub fn retained_images(&self) -> usize {
        self.libraries.len()
    }

    pub fn select(&mut self, candidate: Candidate<'_>) -> Result<(), &'static str> {
        // Reject a bad digest before loading any candidate code. Only trusted,
        // task-owned immutable images are supported: native loading is no sandbox.
        self.libraries
            .push(Library::open_verified(candidate.path, candidate.sha256)?);
        let symbol = self.libraries.last().unwrap().symbol(c"rm030_feedback_api");
        if symbol.is_null() {
            return Err("entry symbol");
        }
        let entry: unsafe extern "C" fn() -> *const Header = unsafe { std::mem::transmute(symbol) };
        let pointer = unsafe { entry() };
        if pointer.is_null() || (pointer as usize) % std::mem::align_of::<Header>() != 0 {
            return Err("header pointer");
        }
        // Read only the frozen header before validating full table size.
        let header = unsafe { pointer.read() };
        abi::validate_header(header, candidate.generation)?;
        if self
            .active
            .is_some_and(|(generation, _)| header.generation <= generation)
        {
            return Err("stale generation");
        }
        let api = unsafe { &*pointer.cast::<Api>() };
        let function = api.transform.ok_or("null transform")?;
        runtime::probe(function, candidate.scratch_expected)?;
        // Catch accidental producer replacement before publication. This is not
        // protection against adversarial loader races or native constructors.
        if digest::sha256(&fs::read(candidate.path).map_err(|_| "artifact reread")?)
            != candidate.sha256
        {
            return Err("artifact changed");
        }
        self.active = Some((header.generation, function));
        Ok(())
    }
}
