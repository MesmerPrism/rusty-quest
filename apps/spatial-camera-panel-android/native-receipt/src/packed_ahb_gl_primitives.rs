// Android producer/import primitives. Typechecked in the exact supplier overlay.
// Must be owned by the exact pool registry on the compositor GL thread.
use std::{ffi::{c_char, c_void, CStr}, os::fd::{FromRawFd, OwnedFd}, ptr};
use crate::android_hardware_buffer::AndroidHardwareBufferHandle;

type Display = *mut c_void;
type Image = *mut c_void;
type Sync = *mut c_void;
type GetClientBuffer = unsafe extern "C" fn(*const ndk_sys::AHardwareBuffer) -> *mut c_void;
type CreateImage = unsafe extern "C" fn(Display, *mut c_void, u32, *mut c_void, *const i32) -> Image;
type DestroyImage = unsafe extern "C" fn(Display, Image) -> u32;
type ImageTarget = unsafe extern "C" fn(u32, Image);
type CreateSync = unsafe extern "C" fn(Display, u32, *const i32) -> Sync;
type DupFence = unsafe extern "C" fn(Display, Sync) -> i32;
type DestroySync = unsafe extern "C" fn(Display, Sync) -> u32;

#[link(name = "EGL")]
extern "C" {
    fn eglGetCurrentDisplay() -> Display;
    fn eglGetCurrentContext() -> *mut c_void;
    fn eglQueryString(display: Display, name: i32) -> *const c_char;
    fn eglGetProcAddress(name: *const c_char) -> *const c_void;
}
#[link(name = "GLESv2")]
extern "C" {
    fn glGetString(name: u32) -> *const u8;
    fn glGetError() -> u32;
    fn glGenTextures(count: i32, textures: *mut u32);
    fn glBindTexture(target: u32, texture: u32);
    fn glTexParameteri(target: u32, name: u32, value: i32);
    fn glDeleteTextures(count: i32, textures: *const u32);
    fn glGenFramebuffers(count: i32, framebuffers: *mut u32);
    fn glBindFramebuffer(target: u32, framebuffer: u32);
    fn glFramebufferTexture2D(target: u32, attachment: u32, texture_target: u32, texture: u32, level: i32);
    fn glCheckFramebufferStatus(target: u32) -> u32;
    fn glDeleteFramebuffers(count: i32, framebuffers: *const u32);
    fn glFlush();
}

pub(crate) struct Extensions {
    display: Display, context: *mut c_void,
    client: GetClientBuffer, image: CreateImage, destroy_image: DestroyImage,
    target: ImageTarget, sync: CreateSync, dup: DupFence, destroy_sync: DestroySync,
}

impl Extensions {
    pub(crate) unsafe fn load_current() -> Result<Self, String> {
        let display = eglGetCurrentDisplay();
        let context = eglGetCurrentContext();
        if display.is_null() || context.is_null() { return Err("no current producer EGL context".into()); }
        fn has(list: &CStr, token: &str) -> bool {
            list.to_bytes().split(|b| *b == b' ').any(|v| v == token.as_bytes())
        }
        let list = eglQueryString(display, 0x3055); // EGL_EXTENSIONS
        let gl_list = glGetString(0x1F03); // GL_EXTENSIONS
        if list.is_null() || gl_list.is_null() { return Err("extension query failed".into()); }
        let list = CStr::from_ptr(list);
        for required in ["EGL_ANDROID_get_native_client_buffer", "EGL_ANDROID_image_native_buffer",
            "EGL_KHR_image_base", "EGL_KHR_fence_sync", "EGL_ANDROID_native_fence_sync"] {
            if !has(list, required) { return Err(format!("missing {required}")); }
        }
        if !has(CStr::from_ptr(gl_list.cast()), "GL_OES_EGL_image") {
            return Err("missing GL_OES_EGL_image".into());
        }
        macro_rules! load {
            ($name:literal, $ty:ty) => {{
                let p = eglGetProcAddress(concat!($name, "\0").as_ptr().cast());
                if p.is_null() { return Err(format!("missing {} entrypoint", $name)); }
                std::mem::transmute::<*const c_void, $ty>(p)
            }};
        }
        Ok(Self { display, context,
            client: load!("eglGetNativeClientBufferANDROID", GetClientBuffer),
            image: load!("eglCreateImageKHR", CreateImage),
            destroy_image: load!("eglDestroyImageKHR", DestroyImage),
            target: load!("glEGLImageTargetTexture2DOES", ImageTarget),
            sync: load!("eglCreateSyncKHR", CreateSync),
            dup: load!("eglDupNativeFenceFDANDROID", DupFence),
            destroy_sync: load!("eglDestroySyncKHR", DestroySync) })
    }
    pub(crate) unsafe fn require_current(&self) -> Result<(), String> {
        if eglGetCurrentDisplay() != self.display || eglGetCurrentContext() != self.context {
            return Err("producer context changed".into());
        }
        Ok(())
    }
}

// Raw GL handles are explicitly destroyed only by the registry after it proves
// idle. A Drop of this struct is not GL teardown or physical completion.
pub(crate) struct GlAllocation {
    pub allocation: AndroidHardwareBufferHandle,
    image: Image,
    pub texture: u32,
    pub framebuffer: u32,
}

pub(crate) struct AllocationError {
    pub message: String,
    // Failed post-image setup cannot lose physical handles on ordinary Err.
    pub retained: Option<GlAllocation>,
}
impl From<String> for AllocationError {
    fn from(message: String) -> Self { Self { message, retained: None } }
}

pub(crate) unsafe fn allocate_current(ext: &Extensions, width: u32, height: u32)
    -> Result<GlAllocation, AllocationError> {
    ext.require_current()?;
    if width == 0 || height == 0 || width > i32::MAX as u32 || height > i32::MAX as u32 {
        return Err("invalid packed extent".to_string().into());
    }
    if glGetError() != 0 { return Err("producer already has GL error".to_string().into()); }
    let mut desc: ndk_sys::AHardwareBuffer_Desc = std::mem::zeroed();
    desc.width = width; desc.height = height; desc.layers = 1;
    desc.format = 1; // AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM (NDK contract)
    desc.usage = (1 << 8) | (1 << 9); // GPU_SAMPLED_IMAGE | GPU_FRAMEBUFFER
    let mut raw = ptr::null_mut();
    let status = ndk_sys::AHardwareBuffer_allocate(&desc, &mut raw);
    if status != 0 || raw.is_null() { return Err(format!("AHB allocate {status}").into()); }
    // Existing helper acquire owns one ref; release allocation's initial ref.
    let allocation = AndroidHardwareBufferHandle::acquire(raw);
    ndk_sys::AHardwareBuffer_release(raw);
    let allocation = allocation?;
    let actual = allocation.descriptor();
    if actual.width != width || actual.height != height || actual.layers != 1
        || actual.format != 1 || actual.usage & desc.usage != desc.usage {
        return Err("allocated AHB descriptor differs from packed contract".to_string().into());
    }
    import_current(ext, allocation)
}

// Real second-context import of retained CONTENT allocation, no allocation substitute.
pub(crate) unsafe fn import_current(ext:&Extensions, allocation:AndroidHardwareBufferHandle)->Result<GlAllocation,AllocationError> {
    ext.require_current()?;
    if glGetError()!=0 {return Err("encoder context already has GL error".to_string().into());}
    let client = (ext.client)(allocation.as_ptr());
    if client.is_null() { return Err("AHB native client buffer failed".to_string().into()); }
    let image_attrs = [0x30D2, 1, 0x3038]; // IMAGE_PRESERVED_KHR, TRUE, NONE
    let image = (ext.image)(ext.display, ptr::null_mut(), 0x3140, client, image_attrs.as_ptr());
    if image.is_null() { return Err("AHB EGLImage unsupported".to_string().into()); }
    let mut texture = 0;
    glGenTextures(1, &mut texture);
    glBindTexture(0x0DE1, texture); // TEXTURE_2D
    (ext.target)(0x0DE1, image);
    for (name, value) in [(0x2801, 0x2601), (0x2800, 0x2601), (0x2802, 0x812F), (0x2803, 0x812F)] {
        glTexParameteri(0x0DE1, name, value); // min/mag LINEAR, S/T CLAMP_TO_EDGE
    }
    let mut framebuffer = 0;
    glGenFramebuffers(1, &mut framebuffer);
    glBindFramebuffer(0x8D40, framebuffer);
    glFramebufferTexture2D(0x8D40, 0x8CE0, 0x0DE1, texture, 0);
    let complete = glCheckFramebufferStatus(0x8D40) == 0x8CD5;
    let error = glGetError();
    glBindFramebuffer(0x8D40, 0);
    glBindTexture(0x0DE1, 0);
    if !complete || error != 0 || texture == 0 || framebuffer == 0 {
        return Err(AllocationError { message: "AHB framebuffer setup failed; retain partial handles".into(),
            retained: Some(GlAllocation { allocation, image, texture, framebuffer }) });
    }
    Ok(GlAllocation { allocation, image, texture, framebuffer })
}

// Called after the Java draw on this EXACT current capture context. Failure
// after any draw quarantines the slot; cannot roll back to Free on Err.
pub(crate) struct FenceExportError {
    pub message: String,
    pub retained_sync: Option<Sync>,
    pub retained_fd: Option<OwnedFd>,
}
impl From<String> for FenceExportError {
    fn from(message: String) -> Self { Self { message, retained_sync: None, retained_fd: None } }
}
pub(crate) unsafe fn export_producer_fence(ext: &Extensions) -> Result<OwnedFd, FenceExportError> {
    ext.require_current()?;
    let attrs = [0x3145, -1, 0x3038]; // NATIVE_FENCE_FD_ANDROID=-1, NONE
    let sync = (ext.sync)(ext.display, 0x3144, attrs.as_ptr());
    if sync.is_null() { return Err("native fence creation failed; quarantine slot".to_string().into()); }
    glFlush(); // may block; worker/slot stays owned and physically Pending
    let fd = (ext.dup)(ext.display, sync);
    let destroyed = (ext.destroy_sync)(ext.display, sync) != 0;
    let fd = (fd >= 0).then(|| OwnedFd::from_raw_fd(fd));
    if !destroyed || fd.is_none() {
        return Err(FenceExportError { message: "native fence export/destroy failed; quarantine".into(),
            retained_sync: (!destroyed).then_some(sync), retained_fd: fd });
    }
    Ok(fd.expect("nonnegative owned native fence fd"))
}

// The registry polls its owned fd, never caller-supplied integers. No blocking
// wait and no error-as-completion. A failed sync_file remains quarantined.
pub(crate) fn poll_producer_fence(fd: &OwnedFd) -> Result<bool, String> {
    use std::os::fd::AsRawFd;
    let mut pollfd = libc::pollfd { fd: fd.as_raw_fd(), events: libc::POLLIN, revents: 0 };
    let count = unsafe { libc::poll(&mut pollfd, 1, 0) };
    if count < 0 { return Err(std::io::Error::last_os_error().to_string()); }
    if count == 0 { return Ok(false); }
    if pollfd.revents & (libc::POLLERR | libc::POLLNVAL | libc::POLLHUP) != 0 {
        return Err("producer fence failed; quarantine slot".into());
    }
    Ok(pollfd.revents & libc::POLLIN != 0)
}

// No public caller may claim this precondition. Registry derives it from no
// writer, observed producer completion, no CPU leases and no pending GPU uses.
pub(crate) unsafe fn destroy_idle_on_producer_thread(ext: &Extensions, mut slot: GlAllocation)
    -> Result<(), (String, GlAllocation)> {
    if let Err(error) = ext.require_current() { return Err((error, slot)); }
    glDeleteFramebuffers(1, &slot.framebuffer);
    glDeleteTextures(1, &slot.texture);
    if glGetError() != 0 {
        return Err(("GL object deletion ambiguous; teardown Pending, do not retry stale IDs".into(), slot));
    }
    slot.framebuffer = 0;
    slot.texture = 0;
    if (ext.destroy_image)(ext.display, slot.image) == 0 {
        return Err(("EGLImage destroy failed; teardown Pending".into(), slot));
    }
    // allocation ref releases only here, after caller-derived physical-idle.
    Ok(())
}

