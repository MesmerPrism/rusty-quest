use std::ffi::CString;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{OnceLock, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use ash::vk;
use serde_json::json;

use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;
use crate::marker_token;

const CAPTURE_SCHEMA: &str = "rusty.quest.camera_replay_capture.v1";
const CAPTURE_WIDTH: u32 = 768;
const CAPTURE_HEIGHT: u32 = 384;
const CAPTURE_BYTES_PER_PIXEL: vk::DeviceSize = 4;
const MAX_CAPTURE_FRAMES: u32 = 120;

#[derive(Clone, Debug)]
pub(crate) struct CameraReplayCaptureConfig {
    pub(crate) output_dir: PathBuf,
    pub(crate) requested_frame_count: u32,
    pub(crate) interval_ms: u32,
}

#[derive(Clone, Debug)]
pub(crate) struct CameraReplayFrameMetadata {
    pub(crate) left_camera_id: String,
    pub(crate) right_camera_id: String,
    pub(crate) left_frame_index: u64,
    pub(crate) right_frame_index: u64,
    pub(crate) left_timestamp_ns: i64,
    pub(crate) right_timestamp_ns: i64,
    pub(crate) pair_delta_ns: u64,
}

#[derive(Clone, Debug)]
struct CapturedFrame {
    file: String,
    index: u32,
    byte_length: u64,
    metadata: CameraReplayFrameMetadata,
}

pub(crate) struct CameraReplayCaptureRecorder {
    config: CameraReplayCaptureConfig,
    capture_id: String,
    started_unix_ms: u64,
    render_pass: vk::RenderPass,
    image: vk::Image,
    image_memory: vk::DeviceMemory,
    image_view: vk::ImageView,
    framebuffer: vk::Framebuffer,
    pipeline_layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    readback_buffer: vk::Buffer,
    readback_memory: vk::DeviceMemory,
    readback_size: vk::DeviceSize,
    pending: Option<CameraReplayFrameMetadata>,
    frames: Vec<CapturedFrame>,
    last_capture_boottime_ns: Option<u64>,
}

static CAPTURE_CONFIG: OnceLock<RwLock<Option<CameraReplayCaptureConfig>>> = OnceLock::new();

fn capture_config() -> &'static RwLock<Option<CameraReplayCaptureConfig>> {
    CAPTURE_CONFIG.get_or_init(|| RwLock::new(None))
}

pub(crate) fn configured_camera_replay_capture() -> Option<CameraReplayCaptureConfig> {
    capture_config()
        .read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_nativeConfigureCameraReplayCapture(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    output_dir: jni::sys::jstring,
    requested_frame_count: jni::sys::jint,
    interval_ms: jni::sys::jint,
) -> jni::sys::jlong {
    let output_dir = jstring_to_string(env, output_dir);
    let requested_frame_count = requested_frame_count.clamp(0, MAX_CAPTURE_FRAMES as i32) as u32;
    let interval_ms = interval_ms.clamp(33, 2_000) as u32;
    let enabled = requested_frame_count > 0 && valid_output_dir(&output_dir);
    let configured = if enabled {
        Some(CameraReplayCaptureConfig {
            output_dir: PathBuf::from(&output_dir),
            requested_frame_count,
            interval_ms,
        })
    } else {
        None
    };
    *capture_config()
        .write()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = configured;
    log_marker(format!(
        "status=camera-replay-capture-configured enabled={} requestedFrameCount={} intervalMs={} outputDir={} captureSchema={} packedStereo=true eyeOrder=left-right highRateJsonPayload=false runtimeCrash=false",
        enabled,
        requested_frame_count,
        interval_ms,
        marker_token(if output_dir.is_empty() { "none" } else { &output_dir }),
        CAPTURE_SCHEMA,
    ));
    if enabled {
        1
    } else {
        0
    }
}

impl CameraReplayCaptureRecorder {
    pub(crate) unsafe fn create(
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        camera_descriptor_set_layout: vk::DescriptorSetLayout,
        config: CameraReplayCaptureConfig,
    ) -> Result<Self, String> {
        fs::create_dir_all(&config.output_dir)
            .map_err(|error| format!("create-camera-replay-output-dir-{error}"))?;
        let capture_id = config
            .output_dir
            .file_name()
            .and_then(|value| value.to_str())
            .filter(|value| !value.is_empty())
            .unwrap_or("camera-replay")
            .to_string();
        let extent = vk::Extent2D {
            width: CAPTURE_WIDTH,
            height: CAPTURE_HEIGHT,
        };
        let render_pass = create_capture_render_pass(device)?;
        let (image, image_memory, image_view) =
            create_capture_image(device, memory_properties, extent)?;
        let framebuffer = match device.create_framebuffer(
            &vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&[image_view])
                .width(extent.width)
                .height(extent.height)
                .layers(1),
            None,
        ) {
            Ok(value) => value,
            Err(error) => {
                device.destroy_image_view(image_view, None);
                device.destroy_image(image, None);
                device.free_memory(image_memory, None);
                device.destroy_render_pass(render_pass, None);
                return Err(format!("create-camera-replay-framebuffer-{error:?}"));
            }
        };
        let pipeline_layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default().set_layouts(&[camera_descriptor_set_layout]),
            None,
        ) {
            Ok(value) => value,
            Err(error) => {
                device.destroy_framebuffer(framebuffer, None);
                device.destroy_image_view(image_view, None);
                device.destroy_image(image, None);
                device.free_memory(image_memory, None);
                device.destroy_render_pass(render_pass, None);
                return Err(format!("create-camera-replay-pipeline-layout-{error:?}"));
            }
        };
        let pipeline = match create_capture_pipeline(device, render_pass, pipeline_layout) {
            Ok(value) => value,
            Err(error) => {
                device.destroy_pipeline_layout(pipeline_layout, None);
                device.destroy_framebuffer(framebuffer, None);
                device.destroy_image_view(image_view, None);
                device.destroy_image(image, None);
                device.free_memory(image_memory, None);
                device.destroy_render_pass(render_pass, None);
                return Err(error);
            }
        };
        let readback_size = CAPTURE_WIDTH as vk::DeviceSize
            * CAPTURE_HEIGHT as vk::DeviceSize
            * CAPTURE_BYTES_PER_PIXEL;
        let (readback_buffer, readback_memory) =
            match create_readback_buffer(device, memory_properties, readback_size) {
                Ok(value) => value,
                Err(error) => {
                    device.destroy_pipeline(pipeline, None);
                    device.destroy_pipeline_layout(pipeline_layout, None);
                    device.destroy_framebuffer(framebuffer, None);
                    device.destroy_image_view(image_view, None);
                    device.destroy_image(image, None);
                    device.free_memory(image_memory, None);
                    device.destroy_render_pass(render_pass, None);
                    return Err(error);
                }
            };

        let recorder = Self {
            config,
            capture_id,
            started_unix_ms: unix_ms(),
            render_pass,
            image,
            image_memory,
            image_view,
            framebuffer,
            pipeline_layout,
            pipeline,
            readback_buffer,
            readback_memory,
            readback_size,
            pending: None,
            frames: Vec::new(),
            last_capture_boottime_ns: None,
        };
        recorder.write_manifest("recording", None)?;
        log_marker(format!(
            "status=camera-replay-capture-ready captureId={} captureSchema={} outputDir={} packedWidth={} packedHeight={} perEyeWidth={} perEyeHeight={} pixelFormat=rgba8-unorm requestedFrameCount={} intervalMs={} mediaPlane=app-private-raw-frame-files highRateJsonPayload=false runtimeCrash=false",
            marker_token(&recorder.capture_id),
            CAPTURE_SCHEMA,
            marker_token(&recorder.config.output_dir.display().to_string()),
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            CAPTURE_WIDTH / 2,
            CAPTURE_HEIGHT,
            recorder.config.requested_frame_count,
            recorder.config.interval_ms,
        ));
        Ok(recorder)
    }

    pub(crate) fn is_complete(&self) -> bool {
        self.frames.len() as u32 >= self.config.requested_frame_count
    }

    pub(crate) unsafe fn record_if_due(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        camera_descriptor_set: vk::DescriptorSet,
        boottime_now_ns: u64,
        metadata: CameraReplayFrameMetadata,
    ) {
        if self.is_complete() || self.pending.is_some() {
            return;
        }
        let interval_ns = u64::from(self.config.interval_ms) * 1_000_000;
        if self
            .last_capture_boottime_ns
            .is_some_and(|last| boottime_now_ns.saturating_sub(last) < interval_ns)
        {
            return;
        }
        let extent = vk::Extent2D {
            width: CAPTURE_WIDTH,
            height: CAPTURE_HEIGHT,
        };
        let clear_values = [vk::ClearValue {
            color: vk::ClearColorValue {
                float32: [0.0, 0.0, 0.0, 1.0],
            },
        }];
        device.cmd_begin_render_pass(
            command_buffer,
            &vk::RenderPassBeginInfo::default()
                .render_pass(self.render_pass)
                .framebuffer(self.framebuffer)
                .render_area(vk::Rect2D {
                    offset: vk::Offset2D { x: 0, y: 0 },
                    extent,
                })
                .clear_values(&clear_values),
            vk::SubpassContents::INLINE,
        );
        device.cmd_set_viewport(
            command_buffer,
            0,
            &[vk::Viewport {
                x: 0.0,
                y: 0.0,
                width: extent.width as f32,
                height: extent.height as f32,
                min_depth: 0.0,
                max_depth: 1.0,
            }],
        );
        device.cmd_set_scissor(
            command_buffer,
            0,
            &[vk::Rect2D {
                offset: vk::Offset2D { x: 0, y: 0 },
                extent,
            }],
        );
        device.cmd_bind_pipeline(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline,
        );
        device.cmd_bind_descriptor_sets(
            command_buffer,
            vk::PipelineBindPoint::GRAPHICS,
            self.pipeline_layout,
            0,
            &[camera_descriptor_set],
            &[],
        );
        device.cmd_draw(command_buffer, 3, 1, 0, 0);
        device.cmd_end_render_pass(command_buffer);
        device.cmd_copy_image_to_buffer(
            command_buffer,
            self.image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            self.readback_buffer,
            &[vk::BufferImageCopy::default()
                .buffer_offset(0)
                .buffer_row_length(0)
                .buffer_image_height(0)
                .image_subresource(vk::ImageSubresourceLayers {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                })
                .image_extent(vk::Extent3D {
                    width: extent.width,
                    height: extent.height,
                    depth: 1,
                })],
        );
        self.pending = Some(metadata);
        self.last_capture_boottime_ns = Some(boottime_now_ns);
    }

    pub(crate) unsafe fn retire_completed(&mut self, device: &ash::Device) -> Result<(), String> {
        let Some(metadata) = self.pending.take() else {
            return Ok(());
        };
        let mapped = device
            .map_memory(
                self.readback_memory,
                0,
                self.readback_size,
                vk::MemoryMapFlags::empty(),
            )
            .map_err(|error| format!("map-camera-replay-readback-{error:?}"))?;
        let bytes = std::slice::from_raw_parts(mapped.cast::<u8>(), self.readback_size as usize);
        let index = self.frames.len() as u32;
        let file = format!("frame-{index:04}.rgba");
        let path = self.config.output_dir.join(&file);
        let write_result =
            fs::write(&path, bytes).map_err(|error| format!("write-camera-replay-frame-{error}"));
        device.unmap_memory(self.readback_memory);
        write_result?;
        self.frames.push(CapturedFrame {
            file,
            index,
            byte_length: self.readback_size,
            metadata,
        });
        let status = if self.is_complete() {
            "complete"
        } else {
            "recording"
        };
        self.write_manifest(status, None)?;
        log_marker(format!(
            "status=camera-replay-frame-written captureId={} frameIndex={} capturedFrameCount={} requestedFrameCount={} byteLength={} captureComplete={} highRateJsonPayload=false runtimeCrash=false",
            marker_token(&self.capture_id),
            index,
            self.frames.len(),
            self.config.requested_frame_count,
            self.readback_size,
            self.is_complete(),
        ));
        Ok(())
    }

    pub(crate) fn finish(&self, reason: &str) -> Result<(), String> {
        let status = if self.is_complete() {
            "complete"
        } else {
            "incomplete"
        };
        self.write_manifest(
            status,
            if self.is_complete() {
                None
            } else {
                Some(reason)
            },
        )?;
        log_marker(format!(
            "status=camera-replay-capture-finished captureId={} manifestStatus={} reason={} capturedFrameCount={} requestedFrameCount={} outputDir={} highRateJsonPayload=false runtimeCrash=false",
            marker_token(&self.capture_id),
            status,
            marker_token(reason),
            self.frames.len(),
            self.config.requested_frame_count,
            marker_token(&self.config.output_dir.display().to_string()),
        ));
        Ok(())
    }

    pub(crate) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_buffer(self.readback_buffer, None);
        device.free_memory(self.readback_memory, None);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.pipeline_layout, None);
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.image_view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.image_memory, None);
        device.destroy_render_pass(self.render_pass, None);
    }

    fn write_manifest(&self, status: &str, finished_reason: Option<&str>) -> Result<(), String> {
        let frames = self
            .frames
            .iter()
            .map(|frame| {
                json!({
                    "file": frame.file,
                    "index": frame.index,
                    "byte_length": frame.byte_length,
                    "left_camera_id": frame.metadata.left_camera_id,
                    "right_camera_id": frame.metadata.right_camera_id,
                    "left_frame_index": frame.metadata.left_frame_index,
                    "right_frame_index": frame.metadata.right_frame_index,
                    "left_timestamp_ns": frame.metadata.left_timestamp_ns,
                    "right_timestamp_ns": frame.metadata.right_timestamp_ns,
                    "pair_delta_ns": frame.metadata.pair_delta_ns,
                })
            })
            .collect::<Vec<_>>();
        let manifest = json!({
            "schema": CAPTURE_SCHEMA,
            "status": status,
            "capture_id": self.capture_id,
            "source": "quest-camera2-50-51-vulkan-rgba-readback",
            "packed_stereo": true,
            "eye_order": "left-right",
            "width": CAPTURE_WIDTH,
            "height": CAPTURE_HEIGHT,
            "pixel_format": "rgba8-unorm",
            "nominal_frame_interval_ms": self.config.interval_ms,
            "requested_frame_count": self.config.requested_frame_count,
            "captured_frame_count": self.frames.len(),
            "started_unix_ms": self.started_unix_ms,
            "finished_unix_ms": if status == "complete" || status == "incomplete" {
                Some(unix_ms())
            } else {
                None
            },
            "finished_reason": finished_reason,
            "frames": frames,
        });
        let path = self.config.output_dir.join("capture.manifest.json");
        fs::write(
            &path,
            serde_json::to_vec_pretty(&manifest)
                .map_err(|error| format!("serialize-camera-replay-manifest-{error}"))?,
        )
        .map_err(|error| format!("write-camera-replay-manifest-{error}"))
    }
}

unsafe fn create_capture_render_pass(device: &ash::Device) -> Result<vk::RenderPass, String> {
    let attachments = [vk::AttachmentDescription::default()
        .format(vk::Format::R8G8B8A8_UNORM)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)];
    let color_refs = [vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL)];
    let subpasses = [vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_refs)];
    let dependencies = [vk::SubpassDependency::default()
        .src_subpass(0)
        .dst_subpass(vk::SUBPASS_EXTERNAL)
        .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_stage_mask(vk::PipelineStageFlags::TRANSFER)
        .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
        .dst_access_mask(vk::AccessFlags::TRANSFER_READ)];
    device
        .create_render_pass(
            &vk::RenderPassCreateInfo::default()
                .attachments(&attachments)
                .subpasses(&subpasses)
                .dependencies(&dependencies),
            None,
        )
        .map_err(|error| format!("create-camera-replay-render-pass-{error:?}"))
}

unsafe fn create_capture_image(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    extent: vk::Extent2D,
) -> Result<(vk::Image, vk::DeviceMemory, vk::ImageView), String> {
    let image = device
        .create_image(
            &vk::ImageCreateInfo::default()
                .image_type(vk::ImageType::TYPE_2D)
                .format(vk::Format::R8G8B8A8_UNORM)
                .extent(vk::Extent3D {
                    width: extent.width,
                    height: extent.height,
                    depth: 1,
                })
                .mip_levels(1)
                .array_layers(1)
                .samples(vk::SampleCountFlags::TYPE_1)
                .tiling(vk::ImageTiling::OPTIMAL)
                .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::TRANSFER_SRC)
                .sharing_mode(vk::SharingMode::EXCLUSIVE)
                .initial_layout(vk::ImageLayout::UNDEFINED),
            None,
        )
        .map_err(|error| format!("create-camera-replay-image-{error:?}"))?;
    let requirements = device.get_image_memory_requirements(image);
    let Some(memory_type_index) = find_memory_type_index(
        memory_properties,
        requirements.memory_type_bits,
        vk::MemoryPropertyFlags::DEVICE_LOCAL,
    ) else {
        device.destroy_image(image, None);
        return Err("camera-replay-device-local-image-memory-unavailable".to_string());
    };
    let memory = match device.allocate_memory(
        &vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type_index),
        None,
    ) {
        Ok(value) => value,
        Err(error) => {
            device.destroy_image(image, None);
            return Err(format!("allocate-camera-replay-image-memory-{error:?}"));
        }
    };
    if let Err(error) = device.bind_image_memory(image, memory, 0) {
        device.free_memory(memory, None);
        device.destroy_image(image, None);
        return Err(format!("bind-camera-replay-image-memory-{error:?}"));
    }
    let view = match device.create_image_view(
        &vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(vk::Format::R8G8B8A8_UNORM)
            .subresource_range(color_subresource_range()),
        None,
    ) {
        Ok(value) => value,
        Err(error) => {
            device.destroy_image(image, None);
            device.free_memory(memory, None);
            return Err(format!("create-camera-replay-image-view-{error:?}"));
        }
    };
    Ok((image, memory, view))
}

unsafe fn create_readback_buffer(
    device: &ash::Device,
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    size: vk::DeviceSize,
) -> Result<(vk::Buffer, vk::DeviceMemory), String> {
    let buffer = device
        .create_buffer(
            &vk::BufferCreateInfo::default()
                .size(size)
                .usage(vk::BufferUsageFlags::TRANSFER_DST)
                .sharing_mode(vk::SharingMode::EXCLUSIVE),
            None,
        )
        .map_err(|error| format!("create-camera-replay-readback-buffer-{error:?}"))?;
    let requirements = device.get_buffer_memory_requirements(buffer);
    let Some(memory_type_index) = find_memory_type_index(
        memory_properties,
        requirements.memory_type_bits,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    ) else {
        device.destroy_buffer(buffer, None);
        return Err("camera-replay-host-coherent-readback-memory-unavailable".to_string());
    };
    let memory = match device.allocate_memory(
        &vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(memory_type_index),
        None,
    ) {
        Ok(value) => value,
        Err(error) => {
            device.destroy_buffer(buffer, None);
            return Err(format!("allocate-camera-replay-readback-memory-{error:?}"));
        }
    };
    if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
        device.free_memory(memory, None);
        device.destroy_buffer(buffer, None);
        return Err(format!("bind-camera-replay-readback-memory-{error:?}"));
    }
    Ok((buffer, memory))
}

unsafe fn create_capture_pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    pipeline_layout: vk::PipelineLayout,
) -> Result<vk::Pipeline, String> {
    let vert = create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_hwb_probe.vert.spv")),
    )?;
    let frag = match create_shader_module(
        device,
        include_bytes!(concat!(env!("OUT_DIR"), "/camera_replay_capture.frag.spv")),
    ) {
        Ok(value) => value,
        Err(error) => {
            device.destroy_shader_module(vert, None);
            return Err(error);
        }
    };
    let entry = CString::new("main").expect("static shader entry");
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vert)
            .name(&entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(frag)
            .name(&entry),
    ];
    let color_blend_attachments = [vk::PipelineColorBlendAttachmentState::default()
        .color_write_mask(
            vk::ColorComponentFlags::R
                | vk::ColorComponentFlags::G
                | vk::ColorComponentFlags::B
                | vk::ColorComponentFlags::A,
        )];
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default();
    let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    let multisample = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let color_blend =
        vk::PipelineColorBlendStateCreateInfo::default().attachments(&color_blend_attachments);
    let dynamic_state =
        vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let infos = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&input_assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .color_blend_state(&color_blend)
        .dynamic_state(&dynamic_state)
        .layout(pipeline_layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device
        .create_graphics_pipelines(vk::PipelineCache::null(), &infos, None)
        .map_err(|(_, error)| format!("create-camera-replay-pipeline-{error:?}"))
        .map(|mut values| values.remove(0));
    device.destroy_shader_module(frag, None);
    device.destroy_shader_module(vert, None);
    result
}

unsafe fn create_shader_module(
    device: &ash::Device,
    bytes: &[u8],
) -> Result<vk::ShaderModule, String> {
    if bytes.len() % 4 != 0 {
        return Err("camera-replay-shader-byte-count-not-u32-aligned".to_string());
    }
    let words = std::slice::from_raw_parts(bytes.as_ptr().cast::<u32>(), bytes.len() / 4);
    device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(words), None)
        .map_err(|error| format!("create-camera-replay-shader-module-{error:?}"))
}

fn find_memory_type_index(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..memory_properties.memory_type_count).find(|index| {
        (memory_type_bits & (1 << *index)) != 0
            && memory_properties.memory_types[*index as usize]
                .property_flags
                .contains(required)
    })
}

fn color_subresource_range() -> vk::ImageSubresourceRange {
    vk::ImageSubresourceRange {
        aspect_mask: vk::ImageAspectFlags::COLOR,
        base_mip_level: 0,
        level_count: 1,
        base_array_layer: 0,
        layer_count: 1,
    }
}

fn valid_output_dir(value: &str) -> bool {
    let path = Path::new(value);
    path.is_absolute()
        && value.contains("/files/camera-replay/")
        && !value.contains('\0')
        && !value.contains("..")
}

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .try_into()
        .unwrap_or(u64::MAX)
}

fn jstring_to_string(env: *mut jni::sys::JNIEnv, value: jni::sys::jstring) -> String {
    use jni::objects::JString;

    if env.is_null() || value.is_null() {
        return String::new();
    }
    let Ok(mut env) = (unsafe { jni::JNIEnv::from_raw(env) }) else {
        return String::new();
    };
    let value = unsafe { JString::from_raw(value) };
    env.get_string(&value)
        .map(|text| text.to_string_lossy().into_owned())
        .unwrap_or_default()
}
