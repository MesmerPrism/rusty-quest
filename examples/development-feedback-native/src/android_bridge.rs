//! Opt-in resident composition. This example is not an application feature policy.
#![allow(dead_code)]
mod abi;
mod digest;
mod loader;
mod runtime;

const ATTEMPT_LIMIT: u64 = 64;
const IMAGE_LIMIT: u64 = 4 * 1024 * 1024;
const STAGED_LIMIT: u64 = 64 * 1024 * 1024;
const BUDGET: i32 = 4;
const INVALID: i32 = 1;
const CANDIDATE: i32 = 2;
const DISPATCH: i32 = 3;
const INVARIANT: i32 = 5;

#[derive(Default)]
struct Budget {
    attempts: u64,
    bytes: u64,
}
impl Budget {
    fn reserve(&mut self, bytes: u64) -> Result<(), i32> {
        if self.attempts == ATTEMPT_LIMIT {
            return Err(BUDGET);
        }
        self.attempts += 1; // Failure consumes the attempt; nothing refunds it.
        self.charge(bytes)
    }
    fn charge(&mut self, bytes: u64) -> Result<(), i32> {
        let next = self.bytes.checked_add(bytes).ok_or(BUDGET)?;
        if bytes > IMAGE_LIMIT || next > STAGED_LIMIT {
            return Err(BUDGET);
        }
        self.bytes = next;
        Ok(())
    }
}

// One Java facade privately owns this leaked, process-lifetime allocation.
// Calls MUST be serialized by that facade, even when Java changes OS threads.
// No global registry, TLS, unsafe Send/Sync, native worker or unload is used.
struct Host {
    loader: loader::ResidentHost,
    state: runtime::State,
    buffer: Box<runtime::GuardedBuffer>,
    original_address: usize,
    budget: Budget,
}
impl Host {
    fn new() -> Self {
        let buffer = Box::new(runtime::GuardedBuffer::new());
        let original_address = buffer.bytes.as_ptr() as usize;
        Self {
            loader: loader::ResidentHost::new(),
            state: runtime::State {
                completed: 0,
                value: 0,
            },
            buffer,
            original_address,
            budget: Budget::default(),
        }
    }
    fn intact(&self) -> Result<(), i32> {
        if self.buffer.bytes.as_ptr() as usize != self.original_address
            || self.buffer.before != 0xaabbccdd
            || self.buffer.after != 0x11223344
        {
            return Err(INVARIANT);
        }
        Ok(())
    }
    fn activate(
        &mut self,
        path: &[u8],
        sha: &[u8; 32],
        generation: i64,
        expected: i64,
    ) -> Result<(), i32> {
        self.budget.reserve(0)?;
        self.intact()?;
        if path.is_empty() || path.len() > 4096 || path.contains(&0) || generation <= 0 {
            return Err(INVALID);
        }
        let path = std::path::Path::new(std::str::from_utf8(path).map_err(|_| INVALID)?);
        let metadata = std::fs::symlink_metadata(path).map_err(|_| CANDIDATE)?;
        let canonical = std::fs::canonicalize(path).map_err(|_| CANDIDATE)?;
        let same_path = canonical == path;
        // Java uses ordinary drive paths; Rust canonicalize uses verbatim paths.
        #[cfg(windows)]
        let same_path = same_path
            || canonical
                .to_str()
                .and_then(|name| name.strip_prefix("\\\\?\\"))
                .is_some_and(|name| std::path::Path::new(name) == path);
        if !path.is_absolute()
            || !metadata.is_file()
            || metadata.file_type().is_symlink()
            || !metadata.permissions().readonly()
            || !same_path
        {
            return Err(INVALID);
        }
        // The Java facade separately charges actual create-new staging bytes.
        // This bounds each trusted immutable file read and all admitted attempts.
        self.budget.charge(metadata.len())?;
        let hash: String = sha.iter().map(|b| format!("{b:02x}")).collect();
        self.loader
            .select(loader::Candidate {
                path,
                sha256: &hash,
                generation: generation as u64,
                scratch_expected: expected,
            })
            .map_err(|_| CANDIDATE)?;
        self.intact()
    }
    fn compute(&mut self, input: i64) -> Result<(), i32> {
        self.intact()?;
        let function = self.loader.active.ok_or(DISPATCH)?.1;
        runtime::dispatch(function, &mut self.state, &mut self.buffer, input)
            .map_err(|_| DISPATCH)?;
        self.intact()
    }
    fn snapshot(&self) -> Result<([i64; 5], [u8; 8]), i32> {
        self.intact()?;
        Ok((
            [
                self.state.completed as i64,
                self.state.value,
                self.loader
                    .active
                    .map_or(0, |(generation, _)| generation as i64),
                self.loader.retained_images() as i64,
                1, // Stable allocation observation token, never an address.
            ],
            self.buffer.bytes,
        ))
    }
}

// Private C-shim interface, not a replaceable module ABI. Only pointers from
// rm_bridge_create may be used; the trusted Java facade never exposes its token.
// JNI arrays/strings are copied by C and borrowed only for this call. The build
// uses panic=abort. Invalid nonnull pointers/native code are not sandboxed.
#[no_mangle]
pub extern "C" fn rm_bridge_create() -> *mut std::ffi::c_void {
    Box::into_raw(Box::new(Host::new())).cast()
}
#[no_mangle]
pub unsafe extern "C" fn rm_bridge_activate(
    owner: *mut std::ffi::c_void,
    path: *const u8,
    path_len: u32,
    sha: *const u8,
    generation: i64,
    expected: i64,
) -> i32 {
    if owner.is_null() || path.is_null() || sha.is_null() || path_len == 0 || path_len > 4096 {
        return INVALID;
    }
    let host = unsafe { &mut *owner.cast::<Host>() };
    let path = unsafe { std::slice::from_raw_parts(path, path_len as usize) };
    let sha = unsafe { &*sha.cast::<[u8; 32]>() };
    host.activate(path, sha, generation, expected)
        .err()
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "C" fn rm_bridge_compute(owner: *mut std::ffi::c_void, input: i64) -> i32 {
    if owner.is_null() {
        return INVALID;
    }
    unsafe { &mut *owner.cast::<Host>() }
        .compute(input)
        .err()
        .unwrap_or(0)
}
#[no_mangle]
pub unsafe extern "C" fn rm_bridge_snapshot(
    owner: *mut std::ffi::c_void,
    scalars: *mut i64,
    scalar_len: u32,
    payload: *mut u8,
    payload_len: u32,
) -> i32 {
    if owner.is_null()
        || scalars.is_null()
        || payload.is_null()
        || scalar_len != 5
        || payload_len != 8
    {
        return INVALID;
    }
    match unsafe { &*owner.cast::<Host>() }.snapshot() {
        Ok((values, bytes)) => {
            unsafe {
                std::ptr::copy_nonoverlapping(values.as_ptr(), scalars, 5);
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), payload, 8);
            }
            0
        }
        Err(code) => code,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn failed_attempts_and_staged_bytes_are_finite() {
        let mut budget = Budget::default();
        for _ in 0..ATTEMPT_LIMIT {
            assert!(budget.reserve(0).is_ok());
        }
        assert_eq!(budget.reserve(0), Err(BUDGET));
        assert_eq!(budget.attempts, ATTEMPT_LIMIT);
        let mut budget = Budget::default();
        for _ in 0..16 {
            budget.reserve(IMAGE_LIMIT).unwrap();
        }
        assert_eq!(budget.bytes, STAGED_LIMIT);
        assert_eq!(budget.reserve(1), Err(BUDGET));
        assert_eq!(budget.bytes, STAGED_LIMIT);
        let mut budget = Budget::default();
        assert_eq!(budget.reserve(IMAGE_LIMIT + 1), Err(BUDGET));
        assert_eq!(budget.attempts, 1);
        assert_eq!(budget.bytes, 0);
    }
    #[test]
    fn empty_host_and_invalid_dispatch_keep_state_and_allocation() {
        let mut host = Host::new();
        let saved = host.snapshot().unwrap();
        assert_eq!(saved, ([0, 0, 0, 0, 1], [0; 8]));
        assert_eq!(host.compute(4), Err(DISPATCH));
        for _ in 0..ATTEMPT_LIMIT {
            assert_eq!(host.activate(b"", &[0; 32], 1, 13), Err(INVALID));
        }
        assert_eq!(host.activate(b"", &[0; 32], 1, 13), Err(BUDGET));
        assert_eq!(host.snapshot().unwrap(), saved);
        host.buffer.before = 0;
        assert_eq!(host.snapshot(), Err(INVARIANT));
    }
    #[test]
    fn c_boundary_checks_lengths_and_never_projects_an_address() {
        let mut values = [0; 5];
        let mut bytes = [0; 8];
        unsafe {
            let owner = rm_bridge_create();
            assert_eq!(
                rm_bridge_snapshot(owner, values.as_mut_ptr(), 4, bytes.as_mut_ptr(), 8),
                INVALID
            );
            assert_eq!(
                rm_bridge_snapshot(owner, values.as_mut_ptr(), 5, bytes.as_mut_ptr(), 8),
                0
            );
            assert_eq!(values, [0, 0, 0, 0, 1]);
            assert_eq!(rm_bridge_compute(std::ptr::null_mut(), 4), INVALID);
            assert_eq!(
                rm_bridge_activate(owner, b"x".as_ptr(), 4097, [0u8; 32].as_ptr(), 1, 13),
                INVALID
            );
        }
    }
    #[test]
    #[ignore = "runner supplies actual compiled artifact manifest"]
    fn real_images_preserve_state_across_refusal_and_fresh_code_restore() {
        let manifest =
            std::env::var("RM030_BRIDGE_TEST_MANIFEST").expect("explicit actual artifact manifest");
        let text = std::fs::read_to_string(&manifest).unwrap();
        let root = std::path::Path::new(&manifest)
            .parent()
            .unwrap()
            .join("bridge-rust-images");
        std::fs::create_dir(&root).unwrap();
        let mut images = Vec::new();
        for line in text.lines() {
            let fields: Vec<_> = line.split('\t').collect();
            let path = root.join(format!("{}.so", fields[1]));
            std::fs::copy(fields[3], &path).unwrap();
            let mut permissions = std::fs::metadata(&path).unwrap().permissions();
            permissions.set_readonly(true);
            std::fs::set_permissions(&path, permissions).unwrap();
            let digest: Vec<u8> = fields[2]
                .as_bytes()
                .chunks_exact(2)
                .map(|c| u8::from_str_radix(std::str::from_utf8(c).unwrap(), 16).unwrap())
                .collect();
            images.push((
                std::fs::canonicalize(path).unwrap(),
                <[u8; 32]>::try_from(digest).unwrap(),
                fields[1].parse::<i64>().unwrap(),
            ));
        }
        let mut host = Host::new();
        let activate = |host: &mut Host, index: usize, expected| {
            let (path, sha, generation) = &images[index];
            host.activate(
                path.to_str().unwrap().as_bytes(),
                sha,
                *generation,
                expected,
            )
        };
        activate(&mut host, 0, 13).unwrap();
        host.compute(4).unwrap();
        let before = host.snapshot().unwrap();
        assert_eq!(
            host.activate(images[1].0.to_str().unwrap().as_bytes(), &[0; 32], 2, 19),
            Err(CANDIDATE)
        );
        assert_eq!(host.snapshot().unwrap(), before);
        host.compute(4).unwrap();
        for index in 3..7 {
            let before = host.snapshot().unwrap();
            assert_eq!(activate(&mut host, index, 13), Err(CANDIDATE));
            let after = host.snapshot().unwrap();
            assert_eq!(
                (after.0[0], after.0[1], after.0[2], after.0[4], after.1),
                (before.0[0], before.0[1], before.0[2], before.0[4], before.1)
            );
            host.compute(4).unwrap();
        }
        assert_eq!(
            host.activate(
                images[1].0.to_str().unwrap().as_bytes(),
                &images[1].1,
                99,
                19
            ),
            Err(CANDIDATE)
        );
        host.compute(4).unwrap();
        assert_eq!(
            host.state,
            runtime::State {
                completed: 7,
                value: 35
            }
        );
        activate(&mut host, 1, 19).unwrap();
        host.compute(4).unwrap();
        assert_eq!(
            host.state,
            runtime::State {
                completed: 8,
                value: 45
            }
        );
        activate(&mut host, 2, 13).unwrap();
        host.compute(4).unwrap();
        assert_eq!(
            host.state,
            runtime::State {
                completed: 9,
                value: 50
            }
        );
        let before = host.snapshot().unwrap();
        assert_eq!(host.compute(i64::MAX), Err(DISPATCH));
        assert_eq!(host.snapshot().unwrap(), before);
        assert_eq!(before.0, [9, 50, 3, 8, 1]);
    }
}
