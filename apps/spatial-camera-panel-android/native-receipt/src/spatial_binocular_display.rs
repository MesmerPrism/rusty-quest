//! Optional display-only remap of a completed packed stereo frame.

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(i32)]
pub(crate) enum Mode {
    #[default]
    Stereo = 0,
    MirrorLeft = 1,
    MirrorRight = 2,
    UpsideDown = 3,
}

impl Mode {
    fn from_wire(mode: i32) -> Option<Self> {
        match mode {
            0 => Some(Self::Stereo),
            1 => Some(Self::MirrorLeft),
            2 => Some(Self::MirrorRight),
            3 => Some(Self::UpsideDown),
            _ => None,
        }
    }

    // Per output eye: source eye, horizontal sign, vertical sign, padding.
    fn eye_transforms(self) -> [[i32; 4]; 2] {
        match self {
            Self::Stereo => [[0, 1, 1, 0], [1, 1, 1, 0]],
            Self::MirrorLeft => [[0, 1, 1, 0], [0, -1, 1, 0]],
            Self::MirrorRight => [[1, -1, 1, 0], [1, 1, 1, 0]],
            Self::UpsideDown => [[0, 1, -1, 0], [1, 1, -1, 0]],
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct Request {
    pub(crate) mode: Mode,
    revision: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Readback {
    Pending,
    Applied(Mode),
    Unavailable,
}

// Err is a completed stereo fallback for the specified non-stereo request.
pub(crate) type Observation = Result<Option<Request>, Request>;

struct Control {
    request: Request,
    effective: Readback,
    #[cfg(target_os = "android")]
    route: Option<u64>,
}

impl Control {
    const fn new() -> Self {
        Self {
            request: Request {
                mode: Mode::Stereo,
                revision: 0,
            },
            effective: Readback::Pending,
            #[cfg(target_os = "android")]
            route: None,
        }
    }

    fn set(&mut self, value: i32) -> bool {
        let Some(mode) = Mode::from_wire(value) else {
            return false;
        };
        if self.request.mode != mode {
            let Some(revision) = self.request.revision.checked_add(1) else {
                return false;
            };
            self.request = Request { mode, revision };
            self.effective = Readback::Pending;
        }
        true
    }

    fn observe_retired(&mut self, recorded: Observation) {
        self.effective = match recorded {
            Ok(Some(request)) if request == self.request => Readback::Applied(request.mode),
            Err(request) if request == self.request => Readback::Unavailable,
            _ => Readback::Pending,
        };
    }
}

#[cfg(target_os = "android")]
pub(crate) use android::{requested, ActiveRoute, Pass};

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use crate::camera_hwb_wsi::create_color_render_pass;
    use crate::spatial_public_multistack_runtime::{
        create_fullscreen_fragment_pipeline, SpatialPublicGuideTarget,
    };
    use ash::vk;
    use jni::{
        objects::JObject,
        sys::{jboolean, jint},
        JNIEnv,
    };
    use std::sync::Mutex;

    static CONTROL: Mutex<Control> = Mutex::new(Control::new());

    pub(crate) struct ActiveRoute(u64);

    impl ActiveRoute {
        pub(crate) fn begin(generation: u64) -> Self {
            let mut control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
            control.route = Some(generation);
            control.effective = Readback::Pending;
            Self(generation)
        }

        pub(crate) fn observe_retired(&self, recorded: Observation) {
            let mut control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
            if control.route == Some(self.0) {
                control.observe_retired(recorded);
            }
        }
    }

    impl Drop for ActiveRoute {
        fn drop(&mut self) {
            let mut control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
            if control.route == Some(self.0) {
                control.route = None;
                control.effective = Readback::Pending;
            }
        }
    }

    pub(crate) fn requested(generation: u64) -> Option<Request> {
        let control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
        (control.route == Some(generation)).then_some(control.request)
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeSetBinocularDisplayMode(
        _: JNIEnv<'_>,
        _: JObject<'_>,
        mode: jint,
    ) -> jboolean {
        let mut control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
        u8::from(control.set(mode))
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeGetBinocularDisplayEffectiveMode(
        _: JNIEnv<'_>,
        _: JObject<'_>,
    ) -> jint {
        let control = CONTROL.lock().unwrap_or_else(|error| error.into_inner());
        match control.effective {
            Readback::Applied(mode) => mode as i32,
            Readback::Pending => -1,
            Readback::Unavailable => -2,
        }
    }

    pub(crate) struct Pass {
        device: ash::Device,
        render_pass: vk::RenderPass,
        input: Option<SpatialPublicGuideTarget>,
        sampler: vk::Sampler,
        descriptor_layout: vk::DescriptorSetLayout,
        pool: vk::DescriptorPool,
        descriptor: vk::DescriptorSet,
        pipeline_layout: vk::PipelineLayout,
        pipeline: vk::Pipeline,
        extent: vk::Extent2D,
    }

    impl Pass {
        pub(crate) unsafe fn new(
            device: &ash::Device,
            memory: &vk::PhysicalDeviceMemoryProperties,
            format: vk::Format,
            extent: vk::Extent2D,
            presentation_pass: vk::RenderPass,
        ) -> Result<Self, String> {
            let mut pass = Self {
                device: device.clone(),
                render_pass: vk::RenderPass::null(),
                input: None,
                sampler: vk::Sampler::null(),
                descriptor_layout: vk::DescriptorSetLayout::null(),
                pool: vk::DescriptorPool::null(),
                descriptor: vk::DescriptorSet::null(),
                pipeline_layout: vk::PipelineLayout::null(),
                pipeline: vk::Pipeline::null(),
                extent,
            };
            pass.render_pass = create_color_render_pass(
                device,
                format,
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL,
            )?;
            pass.input = Some(SpatialPublicGuideTarget::create(
                device,
                memory,
                pass.render_pass,
                extent,
                format,
                "binocular-display",
            )?);
            pass.sampler = device
                .create_sampler(
                    &vk::SamplerCreateInfo::default()
                        .mag_filter(vk::Filter::NEAREST)
                        .min_filter(vk::Filter::NEAREST),
                    None,
                )
                .map_err(|error| format!("binocular-display-sampler-{error:?}"))?;
            let bindings = [vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
            pass.descriptor_layout = device
                .create_descriptor_set_layout(
                    &vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings),
                    None,
                )
                .map_err(|error| format!("binocular-display-descriptor-layout-{error:?}"))?;
            let pool_sizes = [vk::DescriptorPoolSize::default()
                .ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)];
            pass.pool = device
                .create_descriptor_pool(
                    &vk::DescriptorPoolCreateInfo::default()
                        .max_sets(1)
                        .pool_sizes(&pool_sizes),
                    None,
                )
                .map_err(|error| format!("binocular-display-descriptor-pool-{error:?}"))?;
            let layouts = [pass.descriptor_layout];
            pass.descriptor = device
                .allocate_descriptor_sets(
                    &vk::DescriptorSetAllocateInfo::default()
                        .descriptor_pool(pass.pool)
                        .set_layouts(&layouts),
                )
                .map_err(|error| format!("binocular-display-descriptor-{error:?}"))?[0];
            let image = [vk::DescriptorImageInfo::default()
                .sampler(pass.sampler)
                .image_view(pass.input.as_ref().unwrap().image_view)
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
            let writes = [vk::WriteDescriptorSet::default()
                .dst_set(pass.descriptor)
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&image)];
            device.update_descriptor_sets(&writes, &[]);
            let ranges = [vk::PushConstantRange::default()
                .stage_flags(vk::ShaderStageFlags::FRAGMENT)
                .size(32)];
            pass.pipeline_layout = device
                .create_pipeline_layout(
                    &vk::PipelineLayoutCreateInfo::default()
                        .set_layouts(&layouts)
                        .push_constant_ranges(&ranges),
                    None,
                )
                .map_err(|error| format!("binocular-display-pipeline-layout-{error:?}"))?;
            pass.pipeline = create_fullscreen_fragment_pipeline(
                device,
                presentation_pass,
                pass.pipeline_layout,
                include_bytes!(concat!(
                    env!("OUT_DIR"),
                    "/spatial_binocular_display.frag.spv"
                )),
                "binocular-display",
                false,
            )?;
            Ok(pass)
        }

        pub(crate) fn target(
            &self,
            extent: vk::Extent2D,
        ) -> Option<(vk::RenderPass, vk::Framebuffer)> {
            (self.extent == extent)
                .then(|| (self.render_pass, self.input.as_ref().unwrap().framebuffer))
        }

        pub(crate) unsafe fn prepare_sample(&self, command: vk::CommandBuffer) {
            let barrier = [vk::ImageMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .old_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)
                .new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .image(self.input.as_ref().unwrap().image)
                .subresource_range(
                    vk::ImageSubresourceRange::default()
                        .aspect_mask(vk::ImageAspectFlags::COLOR)
                        .level_count(1)
                        .layer_count(1),
                )];
            self.device.cmd_pipeline_barrier(
                command,
                vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
                vk::PipelineStageFlags::FRAGMENT_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                &[],
                &barrier,
            );
        }

        pub(crate) unsafe fn draw(&self, command: vk::CommandBuffer, mode: Mode) {
            let device = &self.device;
            let viewport = vk::Viewport::default()
                .width(self.extent.width as f32)
                .height(self.extent.height as f32)
                .max_depth(1.0);
            let scissor = vk::Rect2D::default().extent(self.extent);
            device.cmd_set_viewport(command, 0, &[viewport]);
            device.cmd_set_scissor(command, 0, &[scissor]);
            device.cmd_bind_pipeline(command, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
            device.cmd_bind_descriptor_sets(
                command,
                vk::PipelineBindPoint::GRAPHICS,
                self.pipeline_layout,
                0,
                &[self.descriptor],
                &[],
            );
            let mut bytes = [0; 32];
            for (chunk, word) in bytes
                .chunks_exact_mut(4)
                .zip(mode.eye_transforms().into_iter().flatten())
            {
                chunk.copy_from_slice(&word.to_ne_bytes());
            }
            device.cmd_push_constants(
                command,
                self.pipeline_layout,
                vk::ShaderStageFlags::FRAGMENT,
                0,
                &bytes,
            );
            device.cmd_draw(command, 3, 1, 0, 0);
        }
    }

    impl Drop for Pass {
        fn drop(&mut self) {
            unsafe {
                let device = &self.device;
                device.destroy_pipeline(self.pipeline, None);
                device.destroy_pipeline_layout(self.pipeline_layout, None);
                device.destroy_descriptor_pool(self.pool, None);
                device.destroy_descriptor_set_layout(self.descriptor_layout, None);
                device.destroy_sampler(self.sampler, None);
                if let Some(input) = self.input.take() {
                    input.destroy(device);
                }
                device.destroy_render_pass(self.render_pass, None);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_modes_preserve_the_selected_eye_and_flip_only_the_other() {
        let stereo = [[0, 1, 2, 3, 4, 5], [6, 7, 8, 9, 10, 11]];
        let render = |mode: Mode| {
            let transform = mode.eye_transforms();
            std::array::from_fn(|y| {
                std::array::from_fn(|x| {
                    let [source_eye, x_sign, y_sign, _] = transform[x / 3];
                    let x = if x_sign < 0 { 2 - x % 3 } else { x % 3 };
                    let y = if y_sign < 0 { 1 - y } else { y };
                    stereo[y][source_eye as usize * 3 + x]
                })
            })
        };
        assert_eq!(render(Mode::default()), stereo);
        assert_eq!(
            render(Mode::MirrorLeft),
            [[0, 1, 2, 2, 1, 0], [6, 7, 8, 8, 7, 6]]
        );
        assert_eq!(
            render(Mode::MirrorRight),
            [[5, 4, 3, 3, 4, 5], [11, 10, 9, 9, 10, 11]]
        );
        assert_eq!(
            render(Mode::UpsideDown),
            [[6, 7, 8, 9, 10, 11], [0, 1, 2, 3, 4, 5]]
        );
    }

    #[test]
    fn control_reports_only_the_current_request_after_retirement() {
        let mut state = Control::new();
        assert_eq!(state.effective, Readback::Pending);
        let stereo = state.request;
        state.observe_retired(Ok(Some(stereo)));
        assert_eq!(state.effective, Readback::Applied(Mode::Stereo));
        assert!(!state.set(-1));
        assert!(!state.set(4));
        assert!(state.set(1));
        let left = state.request;
        assert!(state.set(2));
        assert!(state.set(1));
        state.observe_retired(Ok(Some(left)));
        assert_eq!(state.effective, Readback::Pending);
        state.observe_retired(Ok(Some(state.request)));
        assert_eq!(state.effective, Readback::Applied(Mode::MirrorLeft));
        state.observe_retired(Err(state.request));
        assert_eq!(state.effective, Readback::Unavailable);
        state.observe_retired(Err(left));
        assert_eq!(state.effective, Readback::Pending);
        state.observe_retired(Ok(None));
        assert_eq!(state.effective, Readback::Pending);
    }
}
