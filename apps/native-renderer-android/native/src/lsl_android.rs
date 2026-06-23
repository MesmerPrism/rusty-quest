//! Optional Android liblsl C-ABI wrapper for native renderer transport modules.

#[cfg(all(target_os = "android", rusty_quest_native_renderer_lsl_android))]
mod linked {
    use std::{
        ffi::{c_void, CStr, CString},
        os::raw::{c_char, c_double, c_float, c_int, c_uint},
        ptr::NonNull,
    };

    type LslStreamInfoRaw = *mut c_void;
    type LslOutletRaw = *mut c_void;
    type LslInletRaw = *mut c_void;

    const CFT_FLOAT32: c_int = 1;
    const CFT_STRING: c_int = 3;
    const LSL_NO_ERROR: c_int = 0;
    const PROC_CLOCKSYNC: c_uint = 1;
    const PROC_DEJITTER: c_uint = 2;

    #[link(name = "lsl")]
    unsafe extern "C" {
        fn lsl_create_streaminfo(
            name: *const c_char,
            stream_type: *const c_char,
            channel_count: c_int,
            nominal_srate: c_double,
            channel_format: c_int,
            source_id: *const c_char,
        ) -> LslStreamInfoRaw;
        fn lsl_destroy_streaminfo(info: LslStreamInfoRaw);
        fn lsl_create_outlet(
            info: LslStreamInfoRaw,
            chunk_size: c_int,
            max_buffered: c_int,
        ) -> LslOutletRaw;
        fn lsl_destroy_outlet(outlet: LslOutletRaw);
        fn lsl_push_sample_ftp(
            outlet: LslOutletRaw,
            data: *const c_float,
            timestamp: c_double,
            pushthrough: c_int,
        ) -> c_int;
        fn lsl_push_sample_strtp(
            outlet: LslOutletRaw,
            data: *const *const c_char,
            timestamp: c_double,
            pushthrough: c_int,
        ) -> c_int;
        fn lsl_resolve_byprop(
            buffer: *mut LslStreamInfoRaw,
            buffer_elements: c_uint,
            prop: *const c_char,
            value: *const c_char,
            minimum: c_int,
            timeout: c_double,
        ) -> c_int;
        fn lsl_get_channel_count(info: LslStreamInfoRaw) -> c_int;
        fn lsl_create_inlet(
            info: LslStreamInfoRaw,
            max_buflen: c_int,
            max_chunklen: c_int,
            recover: c_int,
        ) -> LslInletRaw;
        fn lsl_destroy_inlet(inlet: LslInletRaw);
        fn lsl_open_stream(inlet: LslInletRaw, timeout: c_double, error_code: *mut c_int);
        fn lsl_set_postprocessing(inlet: LslInletRaw, flags: c_uint) -> c_int;
        fn lsl_pull_sample_f(
            inlet: LslInletRaw,
            buffer: *mut c_float,
            buffer_elements: c_int,
            timeout: c_double,
            error_code: *mut c_int,
        ) -> c_double;
        fn lsl_last_error() -> *const c_char;
        fn lsl_library_info() -> *const c_char;
        fn lsl_local_clock() -> c_double;
    }

    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub(crate) enum LslChannelFormat {
        Float32,
        String,
    }

    impl LslChannelFormat {
        fn abi_value(self) -> c_int {
            match self {
                Self::Float32 => CFT_FLOAT32,
                Self::String => CFT_STRING,
            }
        }
    }

    pub(crate) fn library_linked() -> bool {
        true
    }

    pub(crate) fn library_info() -> Result<String, String> {
        c_string_or_error(unsafe { lsl_library_info() }, "lsl_library_info")
    }

    pub(crate) fn local_clock() -> f64 {
        unsafe { lsl_local_clock() }
    }

    pub(crate) struct LslOutlet {
        handle: NonNull<c_void>,
    }

    unsafe impl Send for LslOutlet {}

    impl LslOutlet {
        pub(crate) fn create(
            name: &str,
            stream_type: &str,
            channel_count: i32,
            nominal_srate: f64,
            channel_format: LslChannelFormat,
            source_id: &str,
        ) -> Result<Self, String> {
            let name = c_string(name, "stream name")?;
            let stream_type = c_string(stream_type, "stream type")?;
            let source_id = c_string(source_id, "source id")?;
            let info = unsafe {
                lsl_create_streaminfo(
                    name.as_ptr(),
                    stream_type.as_ptr(),
                    channel_count.max(1),
                    nominal_srate,
                    channel_format.abi_value(),
                    source_id.as_ptr(),
                )
            };
            let Some(info) = NonNull::new(info) else {
                return Err(last_error("lsl_create_streaminfo"));
            };
            let outlet = unsafe { lsl_create_outlet(info.as_ptr(), 0, 60) };
            unsafe {
                lsl_destroy_streaminfo(info.as_ptr());
            }
            let Some(handle) = NonNull::new(outlet) else {
                return Err(last_error("lsl_create_outlet"));
            };
            Ok(Self { handle })
        }

        pub(crate) fn push_f32(&self, sample: &[f32]) -> Result<(), String> {
            let error = unsafe {
                lsl_push_sample_ftp(self.handle.as_ptr(), sample.as_ptr(), local_clock(), 1)
            };
            if error == LSL_NO_ERROR {
                Ok(())
            } else {
                Err(last_error("lsl_push_sample_ftp"))
            }
        }

        pub(crate) fn push_strings(&self, sample: &[&str]) -> Result<(), String> {
            let strings = sample
                .iter()
                .map(|value| c_string(value, "string sample"))
                .collect::<Result<Vec<_>, _>>()?;
            let pointers = strings
                .iter()
                .map(|value| value.as_ptr())
                .collect::<Vec<_>>();
            let error = unsafe {
                lsl_push_sample_strtp(self.handle.as_ptr(), pointers.as_ptr(), local_clock(), 1)
            };
            if error == LSL_NO_ERROR {
                Ok(())
            } else {
                Err(last_error("lsl_push_sample_strtp"))
            }
        }
    }

    impl Drop for LslOutlet {
        fn drop(&mut self) {
            unsafe {
                lsl_destroy_outlet(self.handle.as_ptr());
            }
        }
    }

    pub(crate) struct LslInlet {
        handle: NonNull<c_void>,
        channel_count: usize,
    }

    unsafe impl Send for LslInlet {}

    impl LslInlet {
        pub(crate) fn resolve_and_open(
            prop: &str,
            value: &str,
            timeout_seconds: f64,
            recover_lost_streams: bool,
        ) -> Result<Self, String> {
            let prop = c_string(prop, "resolve property")?;
            let value = c_string(value, "resolve value")?;
            let mut buffer = [std::ptr::null_mut(); 1];
            let count = unsafe {
                lsl_resolve_byprop(
                    buffer.as_mut_ptr(),
                    buffer.len() as c_uint,
                    prop.as_ptr(),
                    value.as_ptr(),
                    1,
                    timeout_seconds.max(0.05),
                )
            };
            if count < 1 || buffer[0].is_null() {
                return Err(last_error("lsl_resolve_byprop"));
            }
            let channel_count = unsafe { lsl_get_channel_count(buffer[0]) }.max(1) as usize;
            let inlet = unsafe {
                lsl_create_inlet(buffer[0], 1, 1, if recover_lost_streams { 1 } else { 0 })
            };
            unsafe {
                lsl_destroy_streaminfo(buffer[0]);
            }
            let Some(handle) = NonNull::new(inlet) else {
                return Err(last_error("lsl_create_inlet"));
            };
            let mut error = 0;
            unsafe {
                lsl_open_stream(handle.as_ptr(), timeout_seconds.max(0.05), &mut error);
            }
            if error != LSL_NO_ERROR {
                unsafe {
                    lsl_destroy_inlet(handle.as_ptr());
                }
                return Err(last_error_with_code("lsl_open_stream", error));
            }
            let postprocess_error =
                unsafe { lsl_set_postprocessing(handle.as_ptr(), PROC_CLOCKSYNC | PROC_DEJITTER) };
            if postprocess_error != LSL_NO_ERROR {
                unsafe {
                    lsl_destroy_inlet(handle.as_ptr());
                }
                return Err(last_error_with_code(
                    "lsl_set_postprocessing",
                    postprocess_error,
                ));
            }
            Ok(Self {
                handle,
                channel_count,
            })
        }

        pub(crate) fn pull_f32(
            &self,
            buffer: &mut [f32],
            timeout_seconds: f64,
        ) -> Result<Option<f64>, String> {
            if buffer.len() < self.channel_count {
                return Err(format!(
                    "lsl_pull_sample_f:buffer-too-small channelCount={} bufferElements={}",
                    self.channel_count,
                    buffer.len()
                ));
            }
            let mut error = 0;
            let timestamp = unsafe {
                lsl_pull_sample_f(
                    self.handle.as_ptr(),
                    buffer.as_mut_ptr(),
                    self.channel_count.min(i32::MAX as usize) as i32,
                    timeout_seconds.max(0.0),
                    &mut error,
                )
            };
            if error != LSL_NO_ERROR {
                return Err(last_error_with_code("lsl_pull_sample_f", error));
            }
            if timestamp <= 0.0 {
                Ok(None)
            } else {
                Ok(Some(timestamp))
            }
        }
    }

    impl Drop for LslInlet {
        fn drop(&mut self) {
            unsafe {
                lsl_destroy_inlet(self.handle.as_ptr());
            }
        }
    }

    fn c_string(value: &str, label: &str) -> Result<CString, String> {
        CString::new(value).map_err(|_| format!("{label} contains NUL byte"))
    }

    fn c_string_or_error(pointer: *const c_char, label: &str) -> Result<String, String> {
        if pointer.is_null() {
            return Err(last_error(label));
        }
        Ok(unsafe { CStr::from_ptr(pointer) }
            .to_string_lossy()
            .to_string())
    }

    fn last_error(label: &str) -> String {
        let message = unsafe { lsl_last_error() };
        if message.is_null() {
            return format!("{label}:unknown");
        }
        let message = unsafe { CStr::from_ptr(message) }.to_string_lossy();
        if message.trim().is_empty() {
            format!("{label}:unknown")
        } else {
            format!("{label}:{message}")
        }
    }

    fn last_error_with_code(label: &str, error_code: c_int) -> String {
        format!(
            "{}:{} code={}",
            label,
            error_code_label(error_code),
            error_code
        )
    }

    fn error_code_label(error_code: c_int) -> &'static str {
        match error_code {
            0 => "ok",
            -1 => "timeout",
            -2 => "lost",
            -3 => "argument",
            -4 => "internal",
            _ => "unknown",
        }
    }
}

#[cfg(not(all(target_os = "android", rusty_quest_native_renderer_lsl_android)))]
mod unavailable {
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub(crate) enum LslChannelFormat {
        Float32,
        String,
    }

    pub(crate) fn library_linked() -> bool {
        false
    }

    pub(crate) fn library_info() -> Result<String, String> {
        Err("liblsl not linked for this native renderer build".to_owned())
    }

    pub(crate) fn local_clock() -> f64 {
        0.0
    }

    pub(crate) struct LslOutlet;

    impl LslOutlet {
        pub(crate) fn create(
            _name: &str,
            _stream_type: &str,
            _channel_count: i32,
            _nominal_srate: f64,
            _channel_format: LslChannelFormat,
            _source_id: &str,
        ) -> Result<Self, String> {
            Err("liblsl not linked for this native renderer build".to_owned())
        }

        pub(crate) fn push_f32(&self, _sample: &[f32]) -> Result<(), String> {
            Err("liblsl not linked for this native renderer build".to_owned())
        }

        pub(crate) fn push_strings(&self, _sample: &[&str]) -> Result<(), String> {
            Err("liblsl not linked for this native renderer build".to_owned())
        }
    }

    pub(crate) struct LslInlet;

    impl LslInlet {
        pub(crate) fn resolve_and_open(
            _prop: &str,
            _value: &str,
            _timeout_seconds: f64,
            _recover_lost_streams: bool,
        ) -> Result<Self, String> {
            Err("liblsl not linked for this native renderer build".to_owned())
        }

        pub(crate) fn pull_f32(
            &self,
            _buffer: &mut [f32],
            _timeout_seconds: f64,
        ) -> Result<Option<f64>, String> {
            Err("liblsl not linked for this native renderer build".to_owned())
        }
    }
}

#[cfg(all(target_os = "android", rusty_quest_native_renderer_lsl_android))]
pub(crate) use self::linked::*;
#[cfg(not(all(target_os = "android", rusty_quest_native_renderer_lsl_android)))]
pub(crate) use self::unavailable::*;
