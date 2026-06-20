//! Shared Android `AHardwareBuffer` ownership helpers.
//!
//! Camera2, MediaProjection, and future Android image producers should share
//! this reference-counted handle instead of embedding producer-specific buffer
//! lifetime wrappers.

#[derive(Debug)]
pub(crate) struct AndroidHardwareBufferHandle {
    ptr: *mut ndk_sys::AHardwareBuffer,
}

unsafe impl Send for AndroidHardwareBufferHandle {}
unsafe impl Sync for AndroidHardwareBufferHandle {}

impl AndroidHardwareBufferHandle {
    pub(crate) unsafe fn acquire(ptr: *mut ndk_sys::AHardwareBuffer) -> Result<Self, String> {
        if ptr.is_null() {
            return Err("AHardwareBuffer pointer is null".to_string());
        }
        ndk_sys::AHardwareBuffer_acquire(ptr);
        Ok(Self { ptr })
    }

    pub(crate) fn as_ptr(&self) -> *mut ndk_sys::AHardwareBuffer {
        self.ptr
    }

    pub(crate) unsafe fn descriptor(&self) -> AndroidHardwareBufferDescriptor {
        let mut desc = std::mem::MaybeUninit::<ndk_sys::AHardwareBuffer_Desc>::zeroed();
        ndk_sys::AHardwareBuffer_describe(self.ptr, desc.as_mut_ptr());
        let desc = desc.assume_init();
        let mut hardware_buffer_id = 0_u64;
        let id_status = ndk_sys::AHardwareBuffer_getId(self.ptr, &mut hardware_buffer_id);
        if id_status != 0 {
            hardware_buffer_id = 0;
        }
        AndroidHardwareBufferDescriptor {
            width: desc.width,
            height: desc.height,
            layers: desc.layers,
            format: desc.format,
            usage: desc.usage,
            stride: desc.stride,
            hardware_buffer_id,
            hardware_buffer_id_status: id_status,
        }
    }
}

impl Clone for AndroidHardwareBufferHandle {
    fn clone(&self) -> Self {
        unsafe {
            ndk_sys::AHardwareBuffer_acquire(self.ptr);
        }
        Self { ptr: self.ptr }
    }
}

impl Drop for AndroidHardwareBufferHandle {
    fn drop(&mut self) {
        unsafe {
            ndk_sys::AHardwareBuffer_release(self.ptr);
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct AndroidHardwareBufferDescriptor {
    pub(crate) width: u32,
    pub(crate) height: u32,
    pub(crate) layers: u32,
    pub(crate) format: u32,
    pub(crate) usage: u64,
    pub(crate) stride: u32,
    pub(crate) hardware_buffer_id: u64,
    pub(crate) hardware_buffer_id_status: i32,
}
