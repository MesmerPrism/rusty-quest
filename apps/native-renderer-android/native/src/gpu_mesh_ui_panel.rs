//! Real Android surface on a selected scene-depth mesh or original color-only overlay.
//! All image references and descriptor sets retire only after the owning frame-slot fence.
use crate::{
    ahardware_buffer_vulkan::{
        self as ahb, AhbVulkanFormatKey, AhbVulkanSampledImage, AhbVulkanSampledImageCreateInfo,
    },
    gpu_hand_mesh_visual::HandMeshVisualEyeProjection,
    gpu_native_panel_text::GpuNativePanelText,
    gpu_native_panel_ui::GpuNativePanelUi,
    native_alternate_input::PointerOwner,
    native_hybrid_panel::{DetailMode, HybridPanelState, PanelAction},
    native_mesh_ui::{
        self,
        android::{ImageLease, Producer},
        PointerCapture,
    },
    native_panel_info::InfoPanel,
    native_panel_interaction::{CaptureState, ControlId},
    native_panel_model::{
        AttachmentMode, AttachmentState, LogicalBounds, PanelModel, Ray, TrackedPose,
    },
    native_panel_resolution::{
        projected_footprint, AdaptiveResolution, ProjectionEye, ProjectionPanel, RasterExtent,
    },
    native_panel_text::{GlyphInstance, TextAtlas},
    native_panel_toolbar::{self, ToolbarKey},
    native_panel_ui_geometry::{self, Primitive, ToolbarState},
    native_pointer_visual::{segment_pose, PointerVisual},
};
use ash::vk;
use std::{cell::Cell, ffi::CString, mem, rc::Rc};

struct TextureLease {
    device: ash::Device,
    image: Option<AhbVulkanSampledImage>,
    _lease: ImageLease,
    graphics_owned: Cell<bool>,
}
impl Drop for TextureLease {
    fn drop(&mut self) {
        if let Some(image) = self.image.take() {
            unsafe { image.destroy(&self.device) }
        }
    }
}
pub(crate) struct GpuMeshUiPanel {
    native_ui: Option<GpuNativePanelUi>,
    page_ui: Option<GpuNativePanelUi>,
    page_text: Option<GpuNativePanelText>,
    page_cache: crate::native_page::PageCache,
    page_ready: [bool; 2],
    page_batches: [Vec<crate::native_page::Batch>; 2],
    page_stamps: [Option<(u64, u64, u64, u64)>; 2],
    page_last_state: Option<bool>,
    page_last_submitted: Option<bool>,
    page_submissions: u64,
    hybrid: HybridPanelState,
    hybrid_forms_slots: [bool; 2],
    hybrid_geometry: Vec<Primitive>,
    hybrid_geometry_key: Option<(ToolbarKey, bool)>,
    info: InfoPanel,
    info_last_submitted: Option<Option<native_mesh_ui::PanelPose>>,
    info_capture: CaptureState,
    info_poses: [Option<native_mesh_ui::PanelPose>; 2],
    native_ranges: [[u32; 4]; 2],
    main_shape_count: u32,
    main_text_count: u32,
    native_text: Option<GpuNativePanelText>,
    text_atlas: Option<TextAtlas>,
    text_glyphs: Vec<GlyphInstance>,
    extra_capture: CaptureState,
    extra_drag_pose: Option<native_mesh_ui::PanelPose>,
    attachment: Option<AttachmentState>,
    wrist: Option<TrackedPose>,
    world_return: native_mesh_ui::PanelPose,
    fps_started: std::time::Instant,
    fps_frames: u16,
    fps: u16,
    raster_extent: RasterExtent,
    adaptive: AdaptiveResolution,
    adaptation_faulted: bool,
    raster_epoch: u64,
    latest_head: Option<HandMeshVisualEyeProjection>,
    hybrid_suppressed_forms_pointer: bool,
    hybrid_last_submission: Option<(bool, u64, RasterExtent, DetailMode)>,
    producer: Option<Producer>,
    ahb: ahb::AhbVulkanDevice,
    memory: vk::PhysicalDeviceMemoryProperties,
    queue_family: u32,
    registry: Vec<Rc<TextureLease>>,
    retired: [Vec<Rc<TextureLease>>; 2],
    pending_acquires: [Vec<Rc<TextureLease>>; 2],
    sampler: vk::Sampler,
    set_layout: vk::DescriptorSetLayout,
    pool: vk::DescriptorPool,
    sets: [vk::DescriptorSet; 2],
    layout: vk::PipelineLayout,
    pipeline: vk::Pipeline,
    pointer_pipeline: vk::Pipeline,
    pointer_owner: PointerOwner,
    visual: Option<PointerVisual>,
    latest: Option<Rc<TextureLease>>,
    slots: [Option<Rc<TextureLease>>; 2],
    poses: [native_mesh_ui::PanelPose; 2],
    frame_controls: native_mesh_ui::ControlSnapshot,
    pointer: PointerCapture,
    pub(crate) adopted_frames: u64,
}
#[repr(C)]
struct PanelPush {
    center_width: [f32; 4],
    right_height: [f32; 4],
    up_near: [f32; 4],
    eye_far: [f32; 4],
    eye_orientation: [f32; 4],
    fov: [f32; 4],
    color_mode: [f32; 4],
}
impl GpuMeshUiPanel {
    pub(crate) fn request_visibility(&self, visible: bool) -> bool {
        self.producer
            .as_ref()
            .and_then(|p| native_mesh_ui::request_visibility(p.generation, visible))
            .is_some()
    }
    pub(crate) fn toggle_visibility(&self) -> bool {
        self.request_visibility(!native_mesh_ui::snapshot().panel_visible)
    }
    pub(crate) fn initialize_pose(&mut self, eye: HandMeshVisualEyeProjection) -> bool {
        self.latest_head = Some(eye);
        let Some(producer) = self.producer.as_ref() else {
            return false;
        };
        let initialized = native_mesh_ui::initialize_pose(
            producer.generation,
            [eye.position[0], eye.position[1], eye.position[2]],
            eye.orientation_xyzw,
        );
        if initialized {
            let pose = native_mesh_ui::snapshot().pose;
            crate::marker(
                "native-mesh-ui",
                format!(
                    "event=world-pose-calibrated generation={} center={:.3},{:.3},{:.3} distanceMeters=1.4 poseSource=first-valid-central-eye headLocked=false",
                    producer.generation, pose.center[0], pose.center[1], pose.center[2]
                ),
            );
        }
        if self.native_ui.is_some()
            && self.attachment.is_none()
            && native_mesh_ui::snapshot().pose_initialized
        {
            self.attachment = AttachmentState::new(native_mesh_ui::snapshot().pose);
        }
        initialized
    }
    /// Opt-in owner only. Caller owns renderpass and shared device; no second XR loop.
    pub(crate) unsafe fn new(
        app: &android_activity::AndroidApp,
        instance: &ash::Instance,
        device: &ash::Device,
        memory: vk::PhysicalDeviceMemoryProperties,
        render_pass: vk::RenderPass,
        scene_depth_enabled: bool,
        generation: u64,
        queue_family: u32,
        render_mode: &str,
    ) -> Result<Self, String> {
        if !native_mesh_ui::render_configuration_matches(render_mode, scene_depth_enabled)
            || generation == 0
        {
            return Err(
                "mesh UI requires its selected mode/depth policy and nonzero generation".into(),
            );
        }
        let sampler = device
            .create_sampler(
                &vk::SamplerCreateInfo::default()
                    .min_filter(vk::Filter::LINEAR)
                    .mag_filter(vk::Filter::LINEAR)
                    .address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE)
                    .max_lod(0.0),
                None,
            )
            .map_err(|e| format!("mesh UI sampler: {e}"))?;
        let binding = [vk::DescriptorSetLayoutBinding::default()
            .binding(0)
            .descriptor_count(1)
            .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
            .stage_flags(vk::ShaderStageFlags::FRAGMENT)];
        let set_layout = match device.create_descriptor_set_layout(
            &vk::DescriptorSetLayoutCreateInfo::default().bindings(&binding),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let pool_sizes = [vk::DescriptorPoolSize {
            ty: vk::DescriptorType::COMBINED_IMAGE_SAMPLER,
            descriptor_count: 2,
        }];
        let pool = match device.create_descriptor_pool(
            &vk::DescriptorPoolCreateInfo::default()
                .max_sets(2)
                .pool_sizes(&pool_sizes),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let layouts = [set_layout; 2];
        let allocated = match device.allocate_descriptor_sets(
            &vk::DescriptorSetAllocateInfo::default()
                .descriptor_pool(pool)
                .set_layouts(&layouts),
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let push_ranges = [vk::PushConstantRange::default()
            .stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT)
            .offset(0)
            .size(mem::size_of::<PanelPush>() as u32)];
        let layout = match device.create_pipeline_layout(
            &vk::PipelineLayoutCreateInfo::default()
                .set_layouts(&[set_layout])
                .push_constant_ranges(&push_ranges),
            None,
        ) {
            Ok(v) => v,
            Err(e) => {
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e.to_string());
            }
        };
        let panel_pipeline = match pipeline(device, render_pass, layout, false, scene_depth_enabled)
        {
            Ok(v) => v,
            Err(e) => {
                device.destroy_pipeline_layout(layout, None);
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e);
            }
        };
        let pointer_pipeline =
            match pipeline(device, render_pass, layout, true, scene_depth_enabled) {
                Ok(v) => v,
                Err(e) => {
                    device.destroy_pipeline(panel_pipeline, None);
                    device.destroy_pipeline_layout(layout, None);
                    device.destroy_descriptor_pool(pool, None);
                    device.destroy_descriptor_set_layout(set_layout, None);
                    device.destroy_sampler(sampler, None);
                    return Err(e);
                }
            };
        let hybrid_enabled = crate::native_hybrid_panel::selected();
        let raster_extent = if hybrid_enabled {
            RasterExtent::MEDIUM
        } else {
            RasterExtent::BASE
        };
        let native_ui = if hybrid_enabled {
            match GpuNativePanelUi::new(device, memory, render_pass, scene_depth_enabled) {
                Ok(ui) => Some(ui),
                Err(error) => {
                    device.destroy_pipeline(panel_pipeline, None);
                    device.destroy_pipeline(pointer_pipeline, None);
                    device.destroy_pipeline_layout(layout, None);
                    device.destroy_descriptor_pool(pool, None);
                    device.destroy_descriptor_set_layout(set_layout, None);
                    device.destroy_sampler(sampler, None);
                    return Err(error);
                }
            }
        } else {
            None
        };
        let producer = match Producer::start_with_extent(app, generation, raster_extent) {
            Ok(v) => v,
            Err(e) => {
                if let Some(ui) = native_ui {
                    ui.destroy(device);
                }
                device.destroy_pipeline(panel_pipeline, None);
                device.destroy_pipeline(pointer_pipeline, None);
                device.destroy_pipeline_layout(layout, None);
                device.destroy_descriptor_pool(pool, None);
                device.destroy_descriptor_set_layout(set_layout, None);
                device.destroy_sampler(sampler, None);
                return Err(e);
            }
        };
        crate::marker(
            "native-mesh-ui",
            format!(
                "event=created generation={generation} source=android-virtual-display-presentation frameOwner=native-openxr renderMode={render_mode} nativeMeshUiOriginalScene={} depth={}",
                native_mesh_ui::original_scene_selected(),
                if scene_depth_enabled {
                    "scene-zbuffer"
                } else {
                    "color-only-overlay"
                },
            ),
        );
        if hybrid_enabled {
            crate::marker(
                "native-hybrid-ui",
                format!(
                    "event=created nativeMeshUiHybrid=true logicalWidth={} logicalHeight={} rasterWidth={} rasterHeight={} frameOwner=native-openxr drawMode=instanced-sdf makepadDependency=false adaptiveResize=true",
                    native_mesh_ui::WIDTH,
                    native_mesh_ui::HEIGHT,
                    raster_extent.width,
                    raster_extent.height
                ),
            );
        }
        let text_atlas = if hybrid_enabled {
            match crate::native_panel_text::prepare(app, crate::native_panel_text_layout::LABELS) {
                Ok(atlas) => {
                    crate::marker("native-panel-text",format!("event=atlas-prepared width={} height={} runs={} bytes={} evidence=platform-preparation visibleProof=false",atlas.width,atlas.height,atlas.runs.len(),atlas.pixels.len()));
                    Some(atlas)
                }
                Err(error) => {
                    crate::marker(
                        "native-panel-text",
                        format!("event=unavailable reason={error:?} fallback=stroke"),
                    );
                    None
                }
            }
        } else {
            None
        };
        let native_text = if text_atlas.is_some() {
            match GpuNativePanelText::new(device, memory, render_pass, scene_depth_enabled) {
                Ok(text) => Some(text),
                Err(error) => {
                    crate::marker(
                        "native-panel-text",
                        format!("event=pipeline-unavailable reason={error:?} fallback=stroke"),
                    );
                    None
                }
            }
        } else {
            None
        };
        let page_selected = crate::native_page::selected();
        let page_ui = if page_selected {
            GpuNativePanelUi::new(device, memory, render_pass, scene_depth_enabled).ok()
        } else {
            None
        };
        let page_text = if page_selected {
            GpuNativePanelText::new_page(device, memory, render_pass, scene_depth_enabled).ok()
        } else {
            None
        };
        if page_selected {
            crate::marker("native-page", format!("event=selected nativePageSelected=true generation={generation} nativePageReady=false nativePageColorTextureDrawn=false resourcesReady={} visibleProof=false", page_ui.is_some() && page_text.is_some()));
        }
        Ok(Self {
            native_ui,
            page_ui,
            page_text,
            page_cache: crate::native_page::PageCache::default(),
            page_ready: [false; 2],
            page_batches: [Vec::new(), Vec::new()],
            page_stamps: [None; 2],
            page_last_state: None,
            page_last_submitted: None,
            page_submissions: 0,
            native_text,
            text_atlas,
            text_glyphs: Vec::new(),
            extra_capture: CaptureState::default(),
            info: InfoPanel::default(),
            info_last_submitted: None,
            info_capture: CaptureState::default(),
            info_poses: [None; 2],
            native_ranges: [[0; 4]; 2],
            main_shape_count: 0,
            main_text_count: 0,
            extra_drag_pose: None,
            attachment: None,
            wrist: None,
            world_return: native_mesh_ui::PanelPose::default(),
            fps_started: std::time::Instant::now(),
            fps_frames: 0,
            fps: 0,
            hybrid: HybridPanelState::default(),
            hybrid_forms_slots: [true; 2],
            hybrid_geometry: Vec::new(),
            hybrid_geometry_key: None,
            raster_extent,
            adaptive: AdaptiveResolution::new(raster_extent),
            adaptation_faulted: false,
            raster_epoch: 1,
            latest_head: None,
            hybrid_suppressed_forms_pointer: false,
            hybrid_last_submission: None,
            producer: Some(producer),
            ahb: ahb::AhbVulkanDevice::new(instance, device),
            memory,
            queue_family,
            registry: Vec::new(),
            retired: [Vec::new(), Vec::new()],
            pending_acquires: [Vec::new(), Vec::new()],
            sampler,
            set_layout,
            pool,
            sets: [allocated[0], allocated[1]],
            layout,
            pipeline: panel_pipeline,
            pointer_pipeline,
            pointer_owner: PointerOwner::None,
            visual: None,
            latest: None,
            slots: [None, None],
            poses: [native_mesh_ui::PanelPose::default(); 2],
            frame_controls: native_mesh_ui::ControlSnapshot::default(),
            pointer: PointerCapture::default(),
            adopted_frames: 0,
        })
    }
    unsafe fn update_resolution(
        &mut self,
        app: &android_activity::AndroidApp,
        eyes: &[ProjectionEye; 2],
        frame: u64,
    ) {
        if self.native_ui.is_none() || self.adaptation_faulted {
            return;
        }
        let Some(producer) = self.producer.as_mut() else {
            return;
        };
        if let Err(error) = producer.poll_resize(app) {
            self.adaptation_faulted = true;
            crate::marker(
                "native-panel-resolution",
                format!("event=adaptation-disabled reason={error:?} previousTextureRetained=true"),
            );
            return;
        }
        // One transition at a time, including first-image adoption and old GPU retirement.
        if producer.resize_pending() || producer.raster_extent() != self.raster_extent {
            self.adaptive.hold(frame);
            return;
        }
        if !self.frame_controls.pose_initialized
            || !self.frame_controls.panel_visible
            || !self.hybrid.forms_visible
            || self.pointer.captured()
            || self.hybrid.captured.is_some()
            || self.extra_capture.captured().is_some()
            || self.info_capture.captured().is_some()
            || !self.frame_controls.tracking_valid
        {
            self.adaptive.hold(frame);
            return;
        }
        let p = self.frame_controls.pose;
        let next = match self.hybrid.detail {
            DetailMode::Auto => match projected_footprint(
                ProjectionPanel {
                    center: p.center,
                    right: p.right,
                    up: p.up,
                    width: p.width,
                    height: p.height,
                },
                eyes,
            ) {
                Some(footprint) => self.adaptive.recommend(footprint, frame),
                None => self.adaptive.hold(frame),
            },
            DetailMode::Base => RasterExtent::BASE,
            DetailMode::Medium => RasterExtent::MEDIUM,
            DetailMode::High => RasterExtent::HIGH,
        };
        if next != producer.raster_extent() {
            if let Err(error) = unsafe { producer.request_resize(app, next) } {
                self.adaptation_faulted = true;
                crate::marker(
                    "native-panel-resolution",
                    format!(
                        "event=adaptation-disabled reason={error:?} previousTextureRetained=true"
                    ),
                );
            }
        }
    }
    /// MUST run after this slot fence completed, outside renderpass. Old image remains leased
    /// by latest/other slot while referenced; no AHB identity cache or stale descriptor reuse.
    pub(crate) unsafe fn prepare_frame(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        app: &android_activity::AndroidApp,
        eyes: &[ProjectionEye; 2],
        frame: u64,
    ) -> Result<bool, String> {
        if slot >= 2 {
            return Err("mesh UI frame slot out of range".into());
        }
        self.page_ready[slot] = false;
        self.page_batches[slot].clear();
        self.page_stamps[slot] = None;
        self.retired[slot].clear();
        self.slots[slot] = None;
        // Freeze one pose for both eyes; asynchronous Android controls apply next frame.
        self.frame_controls = native_mesh_ui::snapshot();
        self.poses[slot] = self.frame_controls.pose;
        self.hybrid_forms_slots[slot] = self.hybrid.forms_visible;
        let started = std::time::Instant::now();
        self.fps_frames = self.fps_frames.saturating_add(1);
        if self.fps_started.elapsed().as_secs_f32() >= 1.0 {
            self.fps = (self.fps_frames as f32 / self.fps_started.elapsed().as_secs_f32())
                .round()
                .clamp(0.0, 999.0) as u16;
            self.fps_started = std::time::Instant::now();
            self.fps_frames = 0;
        }
        self.update_resolution(app, eyes, frame);
        if !self.frame_controls.pose_initialized {
            return Ok(false);
        }
        if let Some(lease) = self.producer.as_mut().and_then(|p| p.acquire()) {
            let desc = lease.buffer.descriptor();
            if desc.width != lease.raster_extent.width
                || desc.height != lease.raster_extent.height
                || desc.layers != 1
                || desc.format != 1
            {
                return Err("mesh UI reader descriptor mismatch".into());
            }
            let (props, format) =
                ahb::query_ahb_vulkan_import_properties(&self.ahb, &lease.buffer)?;
            if format.format == vk::Format::UNDEFINED {
                return Err("mesh UI RGBA AHB has no concrete Vulkan format".into());
            }
            let image = ahb::import_ahb_sampled_image(
                device,
                &self.memory,
                &lease.buffer,
                AhbVulkanSampledImageCreateInfo {
                    width: desc.width,
                    height: desc.height,
                    format_key: AhbVulkanFormatKey {
                        format: format.format,
                        external_format: 0,
                    },
                    allocation_size: props.allocation_size,
                    memory_type_bits: props.memory_type_bits,
                    sampler_ycbcr_conversion: None,
                    debug_label: "native mesh UI",
                },
            )?;
            ownership_barrier(device, cmd, image.image, self.queue_family, true);
            if self.raster_epoch != lease.raster_epoch {
                self.raster_extent = lease.raster_extent;
                self.raster_epoch = lease.raster_epoch;
                self.adaptive.applied(self.raster_extent, frame);
                crate::marker("native-panel-resolution", format!(
                    "event=image-adopted generation={} rasterEpoch={} rasterWidth={} rasterHeight={} frame={} evidence=import-recorded visibleProof=false",
                    self.frame_controls.generation, self.raster_epoch, desc.width, desc.height, frame));
            }
            let texture = Rc::new(TextureLease {
                device: device.clone(),
                image: Some(image),
                _lease: lease,
                graphics_owned: Cell::new(false),
            });
            self.pending_acquires[slot].push(texture.clone());
            self.registry.push(texture.clone());
            self.latest = Some(texture);
            self.adopted_frames = self.adopted_frames.saturating_add(1);
            if self.adopted_frames == 1 {
                crate::marker("native-mesh-ui", "event=first-image-adopted textureSource=android-widget-surface cpuPainted=false visibleProof=false");
            }
        }
        if let Some(ui) = &mut self.native_ui {
            let key = ToolbarKey {
                hovered: self.hybrid.hovered,
                pressed: self.hybrid.captured,
                forms: self.hybrid.forms_visible,
                width: self.raster_extent.width,
                height: self.raster_extent.height,
                detail: self.hybrid.detail,
                physical_width: self.frame_controls.pose.width,
                wrist: matches!(self.frame_controls.attachment, "WRIST"),
                anchor_valid: self.frame_controls.tracking_valid,
                wrist_available: self.wrist.is_some(),
                extra_hover: self.extra_capture.hovered().map(|id| id.control),
                extra_pressed: self.extra_capture.captured().map(|id| id.control),
                fps: self.fps,
            };
            if self.hybrid_geometry_key != Some((key, self.info.visible)) {
                let status = format!(
                    "FORMS {} DETAIL {} {}X{}",
                    if self.hybrid.forms_visible {
                        "ON"
                    } else {
                        "OFF"
                    },
                    self.hybrid.detail.label(),
                    self.raster_extent.width,
                    self.raster_extent.height
                );
                let state = ToolbarState {
                    hovered: self.hybrid.hovered,
                    pressed: self.hybrid.captured,
                    status: &status,
                };
                self.hybrid_geometry = if self.native_text.is_some() {
                    native_panel_ui_geometry::toolbar_shapes(state)
                } else {
                    native_panel_ui_geometry::toolbar_geometry(state)
                };
                if self.native_text.is_some() {
                    native_panel_toolbar::append_shapes(&mut self.hybrid_geometry, key);
                }
                if let Some(atlas) = self
                    .text_atlas
                    .as_ref()
                    .filter(|_| self.native_text.is_some())
                {
                    self.text_glyphs = crate::native_panel_text_layout::glyphs(atlas, key);
                }
                let ranges = crate::native_panel_info::append_batches(
                    self.info,
                    self.text_atlas
                        .as_ref()
                        .filter(|_| self.native_text.is_some()),
                    &key,
                    &mut self.hybrid_geometry,
                    &mut self.text_glyphs,
                );
                self.main_shape_count = ranges[0];
                self.main_text_count = ranges[2];
                self.hybrid_geometry_key = Some((key, self.info.visible));
            }
            let visible = self.frame_controls.panel_visible && self.frame_controls.pose_initialized;
            self.info_poses[slot] = (visible && self.info.visible).then_some(self.info.pose);
            self.native_ranges[slot] = if visible {
                [
                    self.main_shape_count,
                    self.hybrid_geometry.len() as u32 - self.main_shape_count,
                    self.main_text_count,
                    self.text_glyphs.len() as u32 - self.main_text_count,
                ]
            } else {
                [0; 4]
            };
            ui.prepare_frame(
                slot,
                if self.frame_controls.panel_visible && self.frame_controls.pose_initialized {
                    &self.hybrid_geometry
                } else {
                    &[]
                },
            )?;
        }
        if let (Some(text), Some(atlas)) = (&mut self.native_text, &self.text_atlas) {
            text.prepare_frame(
                device,
                cmd,
                slot,
                1,
                atlas,
                if self.frame_controls.panel_visible && self.frame_controls.pose_initialized {
                    &self.text_glyphs
                } else {
                    &[]
                },
            )?;
            if frame % 120 == 0 {
                crate::marker("native-panel-text",format!("event=prepared frame={frame} generation=1 glyphs={} atlasBytes={} fontPrepared=true uploadRecordings={} evidence=recorded-not-visible",self.text_glyphs.len(),atlas.pixels.len(),text.upload_recordings()));
            }
        }
        self.prepare_native_page(device, cmd, slot, app, frame);
        self.slots[slot] = self.latest.clone();
        // Unused imports transfer back to Android in this frame's command buffer, but their
        // Vk resources and AImages remain pinned until this slot's submission fence retires.
        let mut index = 0;
        while index < self.registry.len() {
            if Rc::strong_count(&self.registry[index]) == 1 {
                let old = self.registry.remove(index);
                ownership_barrier(
                    device,
                    cmd,
                    old.image.as_ref().unwrap().image,
                    self.queue_family,
                    false,
                );
                self.retired[slot].push(old);
            } else {
                index += 1;
            }
        }
        if let Some(texture) = self.slots[slot].as_ref() {
            let image = [vk::DescriptorImageInfo::default()
                .sampler(self.sampler)
                .image_view(texture.image.as_ref().unwrap().image_view)
                .image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL)];
            let write = [vk::WriteDescriptorSet::default()
                .dst_set(self.sets[slot])
                .dst_binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .image_info(&image)];
            device.update_descriptor_sets(&write, &[]);
            if frame % 120 == 0 {
                crate::marker("native-panel-cost", format!("frame={} prepareCpuMs={:.3} rasterWidth={} rasterHeight={} rasterEpoch={} importRegistry={} detailMode={} visibleProof=false",
                    frame, started.elapsed().as_secs_f64()*1000.0, self.raster_extent.width, self.raster_extent.height, self.raster_epoch, self.registry.len(), self.hybrid.detail.label()));
            }
            Ok(true)
        } else {
            Ok(self.page_ready[slot])
        }
    }
    /// Exact host slot fence has retired before this function. All page buffers are fixed.
    unsafe fn prepare_native_page(
        &mut self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        app: &android_activity::AndroidApp,
        frame: u64,
    ) {
        if !crate::native_page::selected() {
            return;
        }
        let (revision, epoch) = self.page_cache.seen();
        let packet = self
            .producer
            .as_ref()
            .map(|p| p.read_native_page(app, revision, epoch));
        let mut reason = "owner-unavailable";
        match packet {
            Some(Ok(Some(bytes))) if bytes.is_empty() => {
                reason = "unchanged";
            }
            Some(Ok(Some(bytes))) => {
                if let Err(error) = self
                    .page_cache
                    .adopt(&bytes, self.frame_controls.generation)
                {
                    reason = error;
                    self.page_cache.invalidate();
                } else {
                    reason = "snapshot-adopted";
                }
            }
            _ => self.page_cache.invalidate(),
        }
        if self.page_ui.is_none() || self.page_text.is_none() {
            reason = "gpu-resources-unavailable";
        }
        if self.frame_controls.panel_visible
            && self.frame_controls.pose_initialized
            && self.hybrid_forms_slots[slot]
        {
            if let (Some(page), Some(ui), Some(text)) = (
                &self.page_cache.page,
                &mut self.page_ui,
                &mut self.page_text,
            ) {
                if page.generation == self.frame_controls.generation {
                    let shapes = ui.prepare_frame(slot, &page.shapes);
                    let glyphs = text.prepare_frame(
                        device,
                        cmd,
                        slot,
                        page.atlas_epoch,
                        &page.atlas,
                        &page.glyphs,
                    );
                    if shapes.is_ok() && glyphs.is_ok() {
                        self.page_ready[slot] = true;
                        self.page_batches[slot] = page.batches.clone();
                        self.page_stamps[slot] = Some((
                            page.revision,
                            page.content_epoch,
                            page.atlas_epoch,
                            page.raster_epoch,
                        ));
                    } else {
                        reason = "gpu-prepare-rejected";
                    }
                }
            }
        }
        if self.page_last_state != Some(self.page_ready[slot]) || frame % 120 == 0 {
            crate::marker("native-page", format!("event=prepared frame={frame} generation={} nativePageSelected=true nativePageReady={} nativePageFallback={} reason={reason} batches={} glyphs={} evidence=recorded-not-visible visibleProof=false", self.frame_controls.generation, self.page_ready[slot], !self.page_ready[slot], self.page_batches[slot].len(), self.page_cache.page.as_ref().map_or(0, |p| p.glyphs.len())));
            self.page_last_state = Some(self.page_ready[slot]);
        }
    }
    pub(crate) fn frame_controls(&self) -> native_mesh_ui::ControlSnapshot {
        self.frame_controls
    }
    /// Call immediately after successful submission of the command buffer recorded for slot.
    pub(crate) fn mark_submitted(&mut self, slot: usize) {
        if crate::native_page::selected() {
            self.page_submissions = self.page_submissions.saturating_add(1);
        }
        if crate::native_page::selected()
            && (self.page_last_submitted != Some(self.page_ready[slot])
                || self.page_submissions % 120 == 0)
        {
            let stamp = self.page_stamps[slot].unwrap_or((0, 0, 0, 0));
            self.page_last_submitted = Some(self.page_ready[slot]);
            let color_drawn = !self.page_ready[slot]
                && self.frame_controls.pose_initialized
                && self.slots[slot].is_some()
                && self.hybrid_forms_slots[slot]
                && self.frame_controls.panel_visible;
            crate::marker("native-page", format!("event=submitted generation={} slot={slot} nativePageReady={} nativePageColorTextureDrawn={color_drawn} batches={} revision={} contentEpoch={} atlasEpoch={} rasterEpoch={} evidence=queue-submission visibleProof=false", self.frame_controls.generation, self.page_ready[slot], self.page_batches[slot].len(), stamp.0, stamp.1, stamp.2, stamp.3));
        }
        if self.native_ui.is_some()
            && self.frame_controls.panel_visible
            && self.frame_controls.pose_initialized
        {
            let key = (
                self.hybrid_forms_slots[slot],
                self.frame_controls.pose_revision,
                self.raster_extent,
                self.hybrid.detail,
            );
            if self.hybrid_last_submission != Some(key) {
                crate::marker(
                    "native-hybrid-ui",
                    format!(
                        "event=submitted generation={} poseRevision={} formsVisible={} rasterWidth={} rasterHeight={} nativePrimitives={} rasterEpoch={} detailMode={} nativeMainVectorRangesPerEye=1 evidence=queue-submission visibleProof=false",
                        self.frame_controls.generation,
                        key.1,
                        key.0,
                        self.raster_extent.width,
                        self.raster_extent.height,
                        self.hybrid_geometry.len(), self.raster_epoch, self.hybrid.detail.label()
                    ),
                );
                self.hybrid_last_submission = Some(key);
            }
        }
        if self.native_ui.is_some() && self.info_last_submitted != Some(self.info_poses[slot]) {
            if let Some(p) = self.info_poses[slot] {
                crate::marker("native-hybrid-ui",format!("event=auxiliary-submitted panelId=3 visible=true generation={} slot={} center={:?} right={:?} up={:?} widthMeters={} heightMeters={} evidence=queue-submission visibleProof=false",
                    self.frame_controls.generation,slot,p.center,p.right,p.up,p.width,p.height));
            } else {
                crate::marker("native-hybrid-ui",format!("event=auxiliary-submitted panelId=3 visible=false generation={} slot={} evidence=queue-submission visibleProof=false",self.frame_controls.generation,slot));
            }
            self.info_last_submitted = Some(self.info_poses[slot]);
        }
        for texture in self.pending_acquires[slot].drain(..) {
            texture.graphics_owned.set(true);
        }
        for texture in &self.retired[slot] {
            texture.graphics_owned.set(false);
        }
    }
    pub(crate) unsafe fn record_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        slot: usize,
        eye: HandMeshVisualEyeProjection,
        viewport: vk::Viewport,
        scissor: vk::Rect2D,
    ) {
        if slot >= 2 || !self.frame_controls.pose_initialized || !self.frame_controls.panel_visible
        {
            return;
        }
        device.cmd_set_viewport(cmd, 0, &[viewport]);
        device.cmd_set_scissor(cmd, 0, &[scissor]);
        let draw_main = || {
            let p = self.poses[slot];
            let push = PanelPush {
                center_width: [p.center[0], p.center[1], p.center[2], p.width],
                right_height: [p.right[0], p.right[1], p.right[2], p.height],
                up_near: [p.up[0], p.up[1], p.up[2], 0.05],
                eye_far: [eye.position[0], eye.position[1], eye.position[2], 20.0],
                eye_orientation: eye.orientation_xyzw,
                fov: eye.fov_tangents,
                color_mode: [1.0; 4],
            };
            device.cmd_set_viewport(cmd, 0, &[viewport]);
            device.cmd_set_scissor(cmd, 0, &[scissor]);
            if !self.page_ready[slot]
                && self.slots[slot].is_some()
                && (self.native_ui.is_none() || self.hybrid_forms_slots[slot])
            {
                device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipeline);
                device.cmd_bind_descriptor_sets(
                    cmd,
                    vk::PipelineBindPoint::GRAPHICS,
                    self.layout,
                    0,
                    &[self.sets[slot]],
                    &[],
                );
                let bytes = std::slice::from_raw_parts(
                    (&push as *const PanelPush).cast::<u8>(),
                    mem::size_of::<PanelPush>(),
                );
                device.cmd_push_constants(
                    cmd,
                    self.layout,
                    vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                    0,
                    bytes,
                );
                device.cmd_set_viewport(cmd, 0, &[viewport]);
                device.cmd_set_scissor(cmd, 0, &[scissor]);
                device.cmd_draw(cmd, 6, 1, 0, 0);
            }
            if self.page_ready[slot] {
                for batch in &self.page_batches[slot] {
                    match batch.kind {
                        crate::native_page::BatchKind::Shapes => {
                            if let Some(ui) = &self.page_ui {
                                ui.record_eye_range_clipped(
                                    device,
                                    cmd,
                                    slot,
                                    p,
                                    eye,
                                    batch.first,
                                    batch.count,
                                    batch.clip,
                                );
                            }
                        }
                        crate::native_page::BatchKind::Text => {
                            if let Some(text) = &self.page_text {
                                text.record_eye_range_clipped(
                                    device,
                                    cmd,
                                    slot,
                                    p,
                                    eye,
                                    batch.first,
                                    batch.count,
                                    batch.clip,
                                );
                            }
                        }
                    }
                }
            }
            if let Some(ui) = &self.native_ui {
                let r = self.native_ranges[slot];
                ui.record_eye_range(device, cmd, slot, p, eye, 0, r[0]);
            }
            if let Some(text) = &self.native_text {
                let r = self.native_ranges[slot];
                text.record_eye_range(device, cmd, slot, p, eye, 0, r[2]);
            }
        };
        let draw_info = || {
            let Some(pose) = self.info_poses[slot] else {
                return;
            };
            let r = self.native_ranges[slot];
            if let Some(ui) = &self.native_ui {
                ui.record_eye_range(device, cmd, slot, pose, eye, r[0], r[1]);
            }
            if let Some(text) = &self.native_text {
                text.record_eye_range(device, cmd, slot, pose, eye, r[2], r[3]);
            }
        };
        let info_last = self.info_poses[slot].is_some_and(|pose| {
            crate::native_panel_info::info_draws_last(
                self.poses[slot],
                pose,
                [eye.position[0], eye.position[1], eye.position[2]],
            )
        });
        if info_last {
            draw_main();
            draw_info();
        } else {
            draw_info();
            draw_main();
        }
    }

    pub(crate) fn set_pointer_owner(&mut self, owner: PointerOwner) {
        if self.pointer_owner != owner {
            self.hybrid.cancel();
            self.extra_capture.cancel();
            self.info_capture.cancel();
            self.extra_drag_pose = None;
        }
        self.pointer_owner = owner;
    }
    /// Only the source arbiter may transfer its observed-open/fresh-press proof.
    pub(crate) fn admit_fresh_press(
        &mut self,
        app: &android_activity::AndroidApp,
    ) -> Result<(), String> {
        if self.pointer.captured()
            || self.hybrid.captured.is_some()
            || self.extra_capture.captured().is_some()
            || self.info_capture.captured().is_some()
        {
            self.controller_ray(app, false, false, [0.0; 3], [0.0; 3])?;
        }
        self.pointer.admit_fresh_press();
        self.hybrid.update(true, false, None, false);
        self.extra_capture.update(true, false, None, false);
        self.info_capture.update(true, false, None, false);
        self.extra_drag_pose = None;
        Ok(())
    }
    pub(crate) fn pointer_visual_marker(&self) -> &'static str {
        match self.visual {
            Some(v) if v.hit => "ray-and-hit-reticle",
            Some(_) => "ray",
            None => "none",
        }
    }
    pub(crate) fn wrist_tracking_requested(&self) -> bool {
        self.native_ui.is_some()
    }
    pub(crate) fn update_wrist_anchor(&mut self, wrist: Option<TrackedPose>) {
        self.wrist = wrist;
        let controls = native_mesh_ui::snapshot();
        let Some(attachment) = self.attachment.as_mut() else {
            return;
        };
        if matches!(attachment.mode(), AttachmentMode::World) || controls.attachment == "WORLD" {
            attachment.recall_world(controls.pose);
            return;
        }
        if let AttachmentMode::Wrist { mut local_pose } = attachment.mode() {
            if (local_pose.width - controls.pose.width).abs() > 0.00001 {
                local_pose.width = controls.pose.width;
                local_pose.height = controls.pose.height;
                attachment.attach_wrist(local_pose);
            }
        }
        let sample = attachment.observe_wrist(wrist);
        if controls.tracking_valid && !sample.input_valid {
            self.hybrid.cancel();
            self.extra_capture.cancel();
            self.extra_drag_pose = None;
        }
        native_mesh_ui::set_attachment_pose(
            controls.generation,
            sample.pose,
            "WRIST",
            sample.input_valid,
        );
    }
    fn toggle_attachment(&mut self) -> bool {
        let controls = native_mesh_ui::snapshot();
        let Some(attachment) = self.attachment.as_mut() else {
            return false;
        };
        if matches!(attachment.mode(), AttachmentMode::Wrist { .. }) {
            let pose = self.world_return;
            if !native_mesh_ui::set_attachment_pose(controls.generation, pose, "WORLD", true) {
                return false;
            }
            return attachment.recall_world(pose);
        }
        let Some(wrist) = self.wrist else {
            return false;
        };
        let old = *attachment;
        let Some(local) = native_panel_toolbar::wrist_local_pose(controls.pose, wrist) else {
            return false;
        };
        if !attachment.attach_wrist(local) {
            return false;
        }
        let sample = attachment.observe_wrist(Some(wrist));
        if !sample.input_valid {
            *attachment = old;
            return false;
        }
        if native_mesh_ui::set_attachment_pose(controls.generation, sample.pose, "WRIST", true) {
            self.world_return = controls.pose;
            true
        } else {
            *attachment = old;
            false
        }
    }
    fn recall_world_at_head(&mut self, generation: u64) -> bool {
        if !self.latest_head.is_some_and(|eye| {
            native_mesh_ui::recenter_pose(
                generation,
                [eye.position[0], eye.position[1], eye.position[2]],
                eye.orientation_xyzw,
            )
        }) {
            return false;
        }
        let pose = native_mesh_ui::snapshot().pose;
        if let Some(attachment) = self.attachment.as_mut() {
            attachment.recall_world(pose);
        }
        true
    }
    /// Solid app-owned geometry, independent of UI image adoption and descriptors.
    /// Draw in the same pass. Scene mode tests depth; original UI is a color overlay.
    pub(crate) unsafe fn record_pointer_eye(
        &self,
        device: &ash::Device,
        cmd: vk::CommandBuffer,
        eye: HandMeshVisualEyeProjection,
        viewport: vk::Viewport,
        scissor: vk::Rect2D,
    ) {
        if !self.frame_controls.panel_visible {
            return;
        }
        let Some(visual) = self.visual else {
            return;
        };
        let q = eye.orientation_xyzw;
        let rotate = |v: [f32; 3]| {
            let u = [q[0], q[1], q[2]];
            let a = crate::native_pointer_visual::cross(u, v);
            let b = crate::native_pointer_visual::cross(u, a);
            std::array::from_fn(|i| v[i] + 2.0 * (q[3] * a[i] + b[i]))
        };
        let eye_right = rotate([1.0, 0.0, 0.0]);
        let eye_up = rotate([0.0, 1.0, 0.0]);
        let position = [eye.position[0], eye.position[1], eye.position[2]];
        let ray = segment_pose(visual.origin, visual.end, position, eye_right, 0.003);
        let reticle = visual.hit.then_some(native_mesh_ui::PanelPose {
            center: visual.end,
            right: eye_right,
            up: eye_up,
            width: 0.022,
            height: 0.022,
        });
        device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pointer_pipeline);
        device.cmd_set_viewport(cmd, 0, &[viewport]);
        device.cmd_set_scissor(cmd, 0, &[scissor]);
        for (pose, mode) in [(ray, 0.0), (reticle, 1.0)] {
            let Some(p) = pose else {
                continue;
            };
            let color = visual.color();
            let push = PanelPush {
                center_width: [p.center[0], p.center[1], p.center[2], p.width],
                right_height: [p.right[0], p.right[1], p.right[2], p.height],
                up_near: [p.up[0], p.up[1], p.up[2], 0.05],
                eye_far: [position[0], position[1], position[2], 20.0],
                eye_orientation: q,
                fov: eye.fov_tangents,
                color_mode: [color[0], color[1], color[2], mode],
            };
            let bytes = std::slice::from_raw_parts(
                (&push as *const PanelPush).cast::<u8>(),
                mem::size_of::<PanelPush>(),
            );
            device.cmd_push_constants(
                cmd,
                self.layout,
                vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT,
                0,
                bytes,
            );
            device.cmd_draw(cmd, 6, 1, 0, 0);
        }
    }
    pub(crate) fn controller_ray(
        &mut self,
        app: &android_activity::AndroidApp,
        valid: bool,
        pressed: bool,
        origin: [f32; 3],
        direction: [f32; 3],
    ) -> Result<bool, String> {
        let controls = native_mesh_ui::snapshot();
        let valid = valid && controls.pose_initialized && controls.panel_visible;
        self.visual = PointerVisual::new(
            self.pointer_owner,
            valid,
            pressed,
            origin,
            direction,
            (controls.pose_initialized && (self.native_ui.is_none() || self.hybrid.forms_visible))
                .then_some(controls.pose),
        );
        let pixels = native_mesh_ui::ray_pixels(controls.pose, origin, direction);
        if self.native_ui.is_some() {
            let point = pixels.map(|p| {
                [
                    (p[0] / native_mesh_ui::WIDTH as f32 - 0.5) * controls.pose.width
                        / controls.pose.height,
                    p[1] / native_mesh_ui::HEIGHT as f32 - 0.5,
                ]
            });
            // Separate form and native-row surfaces share logical coordinates and a single ray owner.
            let form = PanelModel {
                id: 1,
                pose: controls.pose,
                logical: LogicalBounds {
                    min: [0.0, 0.0],
                    max: [1024.0, 768.0],
                },
                visible: controls.panel_visible,
                enabled: true,
            };
            let row = form.region(
                2,
                LogicalBounds {
                    min: [0.0, 768.0],
                    max: [1024.0, 1024.0],
                },
            );
            let mut form_hit_model = form;
            form_hit_model.visible &= self.hybrid.forms_visible;
            let main_hit = pixels.and_then(|_| {
                row.and_then(|row| {
                    crate::native_panel_model::closest_hit(
                        &[form_hit_model, row],
                        Ray { origin, direction },
                    )
                })
            });
            let info_hit = self.info.hit_ray(Ray { origin, direction });
            let selected = crate::native_panel_info::nearest(main_hit, info_hit);
            let other_capture = self.pointer.captured()
                || self.hybrid.captured.is_some()
                || self.extra_capture.captured().is_some();
            let info_front =
                selected.is_some_and(|h| h.panel == crate::native_panel_info::PANEL_ID);
            let close_hit = selected
                .filter(|h| h.panel == crate::native_panel_info::PANEL_ID)
                .and_then(|h| self.info.hit_control(h.logical));
            let info_event = self.info_capture.update(
                valid && self.info.visible,
                pressed,
                close_hit,
                other_capture,
            );
            let info_consumed = info_event.consumed || (valid && info_front && !other_capture);
            if info_event.activated.is_some() {
                self.info.set_visible(false);
                self.info_capture.cancel();
            }
            if valid && info_front {
                if let (Some(v), Some(hit)) = (&mut self.visual, selected) {
                    v.hit = true;
                    v.end = std::array::from_fn(|i| {
                        origin[i]
                            + direction[i] * hit.distance_m
                                / direction.iter().map(|v| v * v).sum::<f32>().sqrt()
                    });
                }
            }
            let extra_hit = point
                .and_then(native_panel_toolbar::extra_hit)
                .filter(|_| self.native_text.is_some() && selected.is_some_and(|h| h.panel == 2))
                .filter(|id| {
                    *id != native_panel_toolbar::ATTACH_CONTROL
                        || self.wrist.is_some()
                        || controls.attachment == "WRIST"
                });
            let extra = self.extra_capture.update(
                valid
                    && (controls.tracking_valid
                        || extra_hit == Some(native_panel_toolbar::ATTACH_CONTROL)),
                pressed,
                extra_hit.map(|control| ControlId { panel: 2, control }),
                self.pointer.captured() || self.hybrid.captured.is_some() || info_consumed,
            );
            if extra
                .pressed
                .is_some_and(|id| id.control == native_panel_toolbar::SCALE_CONTROL)
            {
                self.extra_drag_pose = Some(controls.pose);
            }
            if extra
                .dragging
                .is_some_and(|id| id.control == native_panel_toolbar::SCALE_CONTROL)
            {
                let drag_point = self.extra_drag_pose.and_then(|pose| {
                    native_mesh_ui::ray_pixels(pose, origin, direction).map(|p| {
                        [
                            (p[0] / 1024.0 - 0.5) * pose.width / pose.height,
                            p[1] / 768.0 - 0.5,
                        ]
                    })
                });
                if let Some(width) = drag_point
                    .and_then(|p| native_panel_toolbar::slider(controls.pose.width).proposal(p))
                {
                    if native_mesh_ui::set_panel_width(controls.generation, width)
                        && extra.pressed.is_some()
                    {
                        crate::marker("native-hybrid-ui",format!("event=scale-drag generation={} widthMeters={width:.3} presentationOnly=true",controls.generation));
                    }
                }
            }
            if extra.released.is_some() || extra.cancelled.is_some() {
                self.extra_drag_pose = None;
            }
            if extra
                .activated
                .is_some_and(|id| id.control == native_panel_toolbar::INFO_CONTROL)
            {
                if self.info.visible {
                    self.info.set_visible(false);
                    self.info_capture.cancel();
                } else {
                    self.info.open_beside(controls.pose);
                }
            }
            if extra
                .activated
                .is_some_and(|id| id.control == native_panel_toolbar::ATTACH_CONTROL)
            {
                let accepted = self.toggle_attachment();
                crate::marker(
                    "native-hybrid-ui",
                    format!(
                        "event=attachment-action accepted={accepted} mode={} evidence=request-only",
                        native_mesh_ui::snapshot().attachment
                    ),
                );
            }
            let hit = pixels
                .filter(|_| selected.is_some_and(|h| h.panel == 2))
                .and_then(|p| {
                    native_panel_ui_geometry::hit_button([
                        (p[0] / native_mesh_ui::WIDTH as f32 - 0.5) * controls.pose.width
                            / controls.pose.height,
                        p[1] / native_mesh_ui::HEIGHT as f32 - 0.5,
                    ])
                });
            let (consumed, action) = self.hybrid.update(
                valid && (controls.tracking_valid || hit == Some(1)),
                pressed,
                hit,
                self.pointer.captured() || extra.consumed || info_consumed,
            );
            if valid && (hit.is_some() || extra_hit.is_some() || extra.consumed) {
                if let (Some(visual), Some(p)) = (&mut self.visual, pixels) {
                    let pose = controls.pose;
                    visual.hit = true;
                    visual.end = std::array::from_fn(|i| {
                        pose.center[i]
                            + (p[0] / native_mesh_ui::WIDTH as f32 - 0.5)
                                * pose.width
                                * pose.right[i]
                            + (0.5 - p[1] / native_mesh_ui::HEIGHT as f32)
                                * pose.height
                                * pose.up[i]
                    });
                }
            }
            if let Some(action) = action {
                let accepted = match action {
                    PanelAction::ToggleForms => {
                        self.hybrid.forms_visible = !self.hybrid.forms_visible;
                        true
                    }
                    PanelAction::Center => self.recall_world_at_head(controls.generation),
                    PanelAction::Size => {
                        native_mesh_ui::request_panel_action(controls.generation, 7)
                    }
                    PanelAction::CycleDetail => {
                        self.hybrid.detail = self.hybrid.detail.next();
                        true
                    }
                };
                crate::marker(
                    "native-hybrid-ui",
                    format!(
                        "event=action action={:?} accepted={} formsVisible={} generation={} evidence=request-only",
                        action, accepted, self.hybrid.forms_visible, controls.generation
                    ),
                );
            }
            if consumed
                || extra.consumed
                || info_consumed
                || !self.hybrid.forms_visible
                || !controls.tracking_valid
            {
                if !self.hybrid_suppressed_forms_pointer {
                    if let Some(producer) = &self.producer {
                        producer.pointer(app, 5, [0.0, 0.0])?;
                    }
                    self.hybrid_suppressed_forms_pointer = true;
                }
                // Keep the original pointer edge state synchronized; a held toolbar press
                // must never become a newly pressed Android control after crossing the edge.
                if let Some((action, p)) = self.pointer.update(false, pressed, None) {
                    if let Some(producer) = &self.producer {
                        producer.pointer(app, action, p)?;
                    }
                }
                return Ok(consumed || extra.consumed || info_consumed || !controls.tracking_valid);
            }
            self.hybrid_suppressed_forms_pointer = false;
        }
        let captured_before = self.pointer.captured();
        let event = self.pointer.update(valid, pressed, pixels);
        if let Some((action, p)) = event {
            if let Some(producer) = &self.producer {
                producer.pointer(app, action, p)?;
            }
        } else if !self.pointer.captured() {
            if let Some(producer) = &self.producer {
                let hovered = pixels.filter(|p| {
                    valid
                        && (0.0..native_mesh_ui::WIDTH as f32).contains(&p[0])
                        && (0.0..native_mesh_ui::HEIGHT as f32).contains(&p[1])
                });
                producer.pointer(
                    app,
                    if hovered.is_some() { 4 } else { 5 },
                    hovered.unwrap_or([0.0, 0.0]),
                )?;
            }
        }
        Ok(captured_before || self.pointer.captured() || event.is_some())
    }
    /// Quiesce the writer but retain every GPU/image owner when retirement is unproven.
    /// The caller MUST also retain the Vulkan device/instance and loader until process exit.
    pub(crate) unsafe fn retain_unretired(mut self, app: &android_activity::AndroidApp) {
        if let Some(producer) = self.producer.take() {
            producer.quiesce(app);
        }
        std::mem::forget(self);
    }
    /// Call only after ALL referenced frame fences completed and graphics queue retired.
    /// Err means this panel and its release submission remain retained; the device owner
    /// must retain its entire device closure rather than destroying it beneath them.
    pub(crate) unsafe fn destroy(
        mut self,
        app: &android_activity::AndroidApp,
        device: &ash::Device,
    ) -> Result<(), String> {
        self.slots = [None, None];
        self.latest = None;
        self.pending_acquires[0].clear();
        self.pending_acquires[1].clear();
        self.registry.append(&mut self.retired[0]);
        self.registry.append(&mut self.retired[1]);
        // Caller already retired all draw fences. Return the still-current Android images
        // with an explicit graphics→FOREIGN release before allowing ImageReader recycling.
        if !self.registry.is_empty() {
            if let Err(error) = release_at_shutdown(device, self.queue_family, &self.registry) {
                crate::marker(
                    "native-mesh-ui",
                    format!(
                        "event=image-retirement-failed reason={}",
                        crate::sanitize(&error)
                    ),
                );
                // The new release submission may itself still be pending. Retain the whole
                // panel, including its descriptors/pipeline, and propagate to the device owner.
                self.retain_unretired(app);
                return Err(error);
            }
        }
        self.registry.clear();
        if let Some(ui) = self.page_ui.take() {
            ui.destroy(device);
        }
        if let Some(text) = self.page_text.take() {
            text.destroy(device);
        }
        if let Some(ui) = self.native_ui.take() {
            ui.destroy(device);
        }
        if let Some(text) = self.native_text.take() {
            text.destroy(device);
        }
        if let Some(producer) = self.producer.take() {
            if let Err(error) = producer.stop(app) {
                crate::marker(
                    "native-mesh-ui",
                    format!("event=stop-failed reason={}", crate::sanitize(&error)),
                );
            }
        }
        device.destroy_pipeline(self.pointer_pipeline, None);
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_pool(self.pool, None);
        device.destroy_descriptor_set_layout(self.set_layout, None);
        device.destroy_sampler(self.sampler, None);
        Ok(())
    }
}
unsafe fn pipeline(
    device: &ash::Device,
    render_pass: vk::RenderPass,
    layout: vk::PipelineLayout,
    solid_pointer: bool,
    scene_depth_enabled: bool,
) -> Result<vk::Pipeline, String> {
    let vertex = words(include_bytes!(concat!(
        env!("OUT_DIR"),
        "/mesh_ui_panel.vert.spv"
    )))?;
    let fragment = if solid_pointer {
        words(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/mesh_pointer.frag.spv"
        )))?
    } else {
        words(include_bytes!(concat!(
            env!("OUT_DIR"),
            "/mesh_ui_panel.frag.spv"
        )))?
    };
    let v = device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&vertex), None)
        .map_err(|e| e.to_string())?;
    let f = match device
        .create_shader_module(&vk::ShaderModuleCreateInfo::default().code(&fragment), None)
    {
        Ok(f) => f,
        Err(e) => {
            device.destroy_shader_module(v, None);
            return Err(e.to_string());
        }
    };
    let name = CString::new("main").unwrap();
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(v)
            .name(&name),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(f)
            .name(&name),
    ];
    let input = vk::PipelineVertexInputStateCreateInfo::default();
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);
    let viewport = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);
    let raster = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .line_width(1.0);
    let samples = vk::PipelineMultisampleStateCreateInfo::default()
        .rasterization_samples(vk::SampleCountFlags::TYPE_1);
    let attachments = [vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(false)
        .color_write_mask(vk::ColorComponentFlags::RGBA)];
    let blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(&attachments);
    let depth = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(scene_depth_enabled)
        .depth_write_enable(scene_depth_enabled && !solid_pointer)
        .depth_compare_op(if scene_depth_enabled {
            vk::CompareOp::LESS_OR_EQUAL
        } else {
            vk::CompareOp::ALWAYS
        });
    let states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&states);
    let info = [vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport)
        .rasterization_state(&raster)
        .multisample_state(&samples)
        .color_blend_state(&blend)
        .depth_stencil_state(&depth)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0)];
    let result = device.create_graphics_pipelines(vk::PipelineCache::null(), &info, None);
    device.destroy_shader_module(v, None);
    device.destroy_shader_module(f, None);
    result.map(|p| p[0]).map_err(|(partial, e)| {
        for p in partial {
            device.destroy_pipeline(p, None);
        }
        e.to_string()
    })
}
/// Android imported image contents are preserved. FOREIGN ownership is independent from
/// the Vulkan frame fence, which protects application descriptors and image lifetimes.
unsafe fn ownership_barrier(
    device: &ash::Device,
    cmd: vk::CommandBuffer,
    image: vk::Image,
    queue_family: u32,
    acquire: bool,
) {
    let barrier = [vk::ImageMemoryBarrier::default()
        .image(image)
        .subresource_range(vk::ImageSubresourceRange {
            aspect_mask: vk::ImageAspectFlags::COLOR,
            base_mip_level: 0,
            level_count: 1,
            base_array_layer: 0,
            layer_count: 1,
        })
        .old_layout(if acquire {
            vk::ImageLayout::GENERAL
        } else {
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL
        })
        .new_layout(if acquire {
            vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL
        } else {
            vk::ImageLayout::GENERAL
        })
        .src_queue_family_index(if acquire {
            vk::QUEUE_FAMILY_FOREIGN_EXT
        } else {
            queue_family
        })
        .dst_queue_family_index(if acquire {
            queue_family
        } else {
            vk::QUEUE_FAMILY_FOREIGN_EXT
        })
        .src_access_mask(if acquire {
            vk::AccessFlags::empty()
        } else {
            vk::AccessFlags::SHADER_READ
        })
        .dst_access_mask(if acquire {
            vk::AccessFlags::SHADER_READ
        } else {
            vk::AccessFlags::empty()
        })];
    device.cmd_pipeline_barrier(
        cmd,
        if acquire {
            vk::PipelineStageFlags::TOP_OF_PIPE
        } else {
            vk::PipelineStageFlags::FRAGMENT_SHADER
        },
        if acquire {
            vk::PipelineStageFlags::FRAGMENT_SHADER
        } else {
            vk::PipelineStageFlags::BOTTOM_OF_PIPE
        },
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &barrier,
    );
}
unsafe fn release_at_shutdown(
    device: &ash::Device,
    queue_family: u32,
    textures: &[Rc<TextureLease>],
) -> Result<(), String> {
    let pool = device
        .create_command_pool(
            &vk::CommandPoolCreateInfo::default()
                .queue_family_index(queue_family)
                .flags(vk::CommandPoolCreateFlags::TRANSIENT),
            None,
        )
        .map_err(|e| e.to_string())?;
    let result = (|| {
        let cmd = device
            .allocate_command_buffers(
                &vk::CommandBufferAllocateInfo::default()
                    .command_pool(pool)
                    .level(vk::CommandBufferLevel::PRIMARY)
                    .command_buffer_count(1),
            )
            .map_err(|e| e.to_string())?[0];
        device
            .begin_command_buffer(
                cmd,
                &vk::CommandBufferBeginInfo::default()
                    .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
            )
            .map_err(|e| e.to_string())?;
        for texture in textures {
            if texture.graphics_owned.get() {
                ownership_barrier(
                    device,
                    cmd,
                    texture.image.as_ref().unwrap().image,
                    queue_family,
                    false,
                );
            }
        }
        device.end_command_buffer(cmd).map_err(|e| e.to_string())?;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| e.to_string())?;
        let buffers = [cmd];
        let submissions = [vk::SubmitInfo::default().command_buffers(&buffers)];
        if let Err(error) = device.queue_submit(
            device.get_device_queue(queue_family, 0),
            &submissions,
            fence,
        ) {
            device.destroy_fence(fence, None);
            return Err(error.to_string());
        }
        let waited = device.wait_for_fences(&[fence], true, 1_000_000_000);
        if let Err(error) = waited {
            // Do not destroy pending command pool/fence on timeout. Device owner can retire
            // its queue on shutdown; these bounded objects remain alive in the interim.
            return Err(error.to_string());
        }
        device.destroy_fence(fence, None);
        Ok(())
    })();
    if result.is_ok() {
        device.destroy_command_pool(pool, None);
    }
    result
}
fn words(bytes: &[u8]) -> Result<Vec<u32>, String> {
    if bytes.len() % 4 != 0 {
        return Err("misaligned mesh UI SPIR-V".into());
    }
    Ok(bytes
        .chunks_exact(4)
        .map(|p| u32::from_le_bytes([p[0], p[1], p[2], p[3]]))
        .collect())
}
