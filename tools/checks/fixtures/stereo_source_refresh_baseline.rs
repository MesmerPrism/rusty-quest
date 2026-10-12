// Retained bounded pre-change source seams. Host negative fixture only; no history lookup.
// REFRESH_BEGIN
    pub(crate) unsafe fn refresh(
        &mut self,
        device: &ash::Device,
        memory: &vk::PhysicalDeviceMemoryProperties,
        ahb_device: &ash::android::external_memory_android_hardware_buffer::Device,
        resources: &CameraHwbProbeResources,
        mode: CameraHwbProbeMode,
        maximum_age_ns: u64,
        control_revision: u64,
    ) -> Result<(), String> {
        if self.pending.is_some() {
            return Err("stereo-import-replacement-before-retirement".into());
        }
        // The caller has proven common-fence retirement. Any refresh failure
        // clears both bindings rather than leaving a partial stale source set.
        let result = self.refresh_retired(
            device,
            memory,
            ahb_device,
            resources,
            mode,
            maximum_age_ns,
            control_revision,
        );
        if result.is_err() {
            self.clear_retired_sources(device);
        }
        result
    }
// REFRESH_END
// CONSUMER_BEGIN
        if self.pending.is_some()
            || fence == vk::Fence::null()
            || processing_revision == 0
            || processing_revision != self.control_revision
            || normalizer_processing_policy() != self.processing_policy
        {
            return Err("stereo-recording-admission-invalid".into());
        }
        self.pending = Some(fence);
        let processing_policy =
            crate::spatial_guide_processing::current_spatial_guide_processing_policy();
// CONSUMER_END
// NORMALIZER_BEGIN
fn normalizer_processing_policy() -> [u32; 4] {
    let policy = crate::spatial_guide_processing::current_spatial_guide_processing_policy();
    [
        policy.preblur_kernel as u32,
        policy.preblur_input as u32,
        policy.postblur_kernel as u32,
        policy.camera_sampling as u32,
    ]
}
// NORMALIZER_END
