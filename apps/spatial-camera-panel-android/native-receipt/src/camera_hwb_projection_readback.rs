//! Bounded, one-shot readback of the image actually selected for presentation.
//!
//! This deliberately copies a small set of pixels from the WSI swapchain image
//! after the final compositor pass.  It is not an offscreen replay and it does
//! not reinterpret the returned bytes as linear or sRGB values.

#[cfg(target_os = "android")]
use super::ProjectionZoneCompositorSettings;
use super::PROJECTION_COMPOSITION_READBACK_CAPTURE;

#[cfg(target_os = "android")]
use ash::vk;

#[cfg(target_os = "android")]
use crate::camera_hwb_marker::log_camera_hwb_marker as log_marker;

pub(crate) const PROJECTION_READBACK_TOGGLE: u32 = PROJECTION_COMPOSITION_READBACK_CAPTURE;
const SAMPLE_RADIUS_PIXELS: i32 = 64;
const SAMPLE_COUNT_PER_STRIP: usize = (SAMPLE_RADIUS_PIXELS as usize * 2) + 1;
const TILE_CENTER_COUNT_PER_EYE: usize = 25;
const MAX_STAGING_BYTES: u64 = 16 * 1024 * 1024;
const SAMPLE_LOG_CHUNK_ENTRIES: usize = 8;
// `log_camera_hwb_marker` adds its tag/timestamp prefix before Android's
// 1024-byte payload ceiling. Keep this deliberately conservative budget in a
// host test so the complete sample chunk survives logcat transport.
const CAMERA_HWB_MARKER_PREFIX_BUDGET_BYTES: usize = 80;

#[derive(Default)]
struct CaptureToggleEdges {
    last_toggle: bool,
    next_serial: u64,
}

impl CaptureToggleEdges {
    fn observe(&mut self, toggle: bool) -> Option<u64> {
        if toggle == self.last_toggle {
            return None;
        }
        self.last_toggle = toggle;
        self.next_serial = self.next_serial.saturating_add(1);
        Some(self.next_serial)
    }
}

#[derive(Default)]
struct BoundedCaptureRequests {
    pending: Option<u64>,
    in_flight: bool,
}

impl BoundedCaptureRequests {
    fn offer(&mut self, serial: u64) -> bool {
        let coalesced = self.in_flight || self.pending.is_some();
        self.pending = Some(serial);
        coalesced
    }

    fn submit_next(&mut self) -> Option<u64> {
        if self.in_flight {
            None
        } else {
            let serial = self.pending.take()?;
            self.in_flight = true;
            Some(serial)
        }
    }

    fn retire(&mut self) {
        self.in_flight = false;
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum StoredPixelFormat {
    Rgba8,
    Bgra8,
}

impl StoredPixelFormat {
    #[cfg(target_os = "android")]
    fn from_vk(format: vk::Format) -> Option<Self> {
        match format {
            vk::Format::R8G8B8A8_UNORM => Some(Self::Rgba8),
            vk::Format::B8G8R8A8_UNORM => Some(Self::Bgra8),
            _ => None,
        }
    }

    fn token(self) -> &'static str {
        match self {
            Self::Rgba8 => "rgba8-unorm",
            Self::Bgra8 => "bgra8-unorm",
        }
    }

    fn decode(self, bytes: [u8; 4]) -> [u8; 4] {
        match self {
            Self::Rgba8 => bytes,
            Self::Bgra8 => [bytes[2], bytes[1], bytes[0], bytes[3]],
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum SampleKind {
    LeftTopNormal,
    LeftEdgeNormal,
    RightTopNormal,
    RightEdgeNormal,
    LeftCornerSdfNormal,
    TileCenter,
}

impl SampleKind {
    fn token(self) -> &'static str {
        match self {
            Self::LeftTopNormal => "left-top-normal",
            Self::LeftEdgeNormal => "left-edge-normal",
            Self::RightTopNormal => "right-top-normal",
            Self::RightEdgeNormal => "right-edge-normal",
            Self::LeftCornerSdfNormal => "left-corner-sdf-normal",
            Self::TileCenter => "tile-center",
        }
    }
}

fn format_sample_entry(point: SamplePoint, stored: [u8; 4], rgba: [u8; 4]) -> String {
    format!(
        "{}:{}:{}={:02x}{:02x}{:02x}{:02x}/{:02x}{:02x}{:02x}{:02x}",
        point.x,
        point.y,
        point.kind.token(),
        stored[0],
        stored[1],
        stored[2],
        stored[3],
        rgba[0],
        rgba[1],
        rgba[2],
        rgba[3]
    )
}

fn format_sample_chunk_line(
    serial: u64,
    frame_id: u64,
    surface_generation: u64,
    chunk_ordinal: usize,
    chunk_count: usize,
    kind: SampleKind,
    entries: &[String],
) -> String {
    format!(
        "status=projection-producer-readback-samples serial={} frameId={} surfaceGeneration={} chunk={}/{} kind={} values=[{}] runtimeCrash=false",
        serial,
        frame_id,
        surface_generation,
        chunk_ordinal,
        chunk_count,
        kind.token(),
        entries.join(","),
    )
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct SamplePoint {
    x: u32,
    y: u32,
    kind: SampleKind,
}

#[derive(Clone, Debug)]
struct SamplePlan {
    points: Vec<SamplePoint>,
}

impl SamplePlan {
    fn from_target_rects(
        width: u32,
        height: u32,
        rects: [[f32; 4]; 2],
        corner_radius_uv: f32,
    ) -> Self {
        let width = width.max(1);
        let height = height.max(1);
        let left = rect_to_pixels(rects[0], width, height);
        let right = rect_to_pixels(rects[1], width, height);
        let left_top_mid_x = left.0.saturating_add(left.2 / 2).min(width - 1);
        let left_mid_y = left.1.saturating_add(left.3 / 2).min(height - 1);
        let right_top_mid_x = right.0.saturating_add(right.2 / 2).min(width - 1);
        let right_mid_y = right.1.saturating_add(right.3 / 2).min(height - 1);
        let mut points =
            Vec::with_capacity(SAMPLE_COUNT_PER_STRIP * 5 + TILE_CENTER_COUNT_PER_EYE * 2);
        // These are contiguous normal crossings, centered exactly on the
        // rasterized rectangle boundary. They are intentionally not sparse
        // screen-wide rows: a one-pixel dark fringe must have a sample.
        append_vertical_crossing(
            &mut points,
            height,
            left_top_mid_x,
            left.1,
            SampleKind::LeftTopNormal,
        );
        append_horizontal_crossing(
            &mut points,
            width,
            left_mid_y,
            left.0,
            SampleKind::LeftEdgeNormal,
        );
        append_vertical_crossing(
            &mut points,
            height,
            right_top_mid_x,
            right.1,
            SampleKind::RightTopNormal,
        );
        append_horizontal_crossing(
            &mut points,
            width,
            right_mid_y,
            right.0,
            SampleKind::RightEdgeNormal,
        );
        append_corner_sdf_normal(&mut points, width, height, rects[0], corner_radius_uv);
        for rect in rects {
            let (x, y, rect_width, rect_height) = rect_to_pixels(rect, width, height);
            // Five by five centers per eye cover every fixture tile interior
            // while avoiding edge rasterization ambiguity.
            for row in 0..5 {
                for column in 0..5 {
                    points.push(SamplePoint {
                        x: interior_coordinate(x, rect_width, column, 5, width),
                        y: interior_coordinate(y, rect_height, row, 5, height),
                        kind: SampleKind::TileCenter,
                    });
                }
            }
        }
        Self { points }
    }

    fn bytes(&self) -> u64 {
        (self.points.len() as u64).saturating_mul(4)
    }
}

fn rect_to_pixels(rect: [f32; 4], width: u32, height: u32) -> (u32, u32, u32, u32) {
    let to_start = |value: f32, limit: u32| {
        (value.clamp(0.0, 1.0) * limit as f32)
            .round()
            .clamp(0.0, limit.saturating_sub(1) as f32) as u32
    };
    let to_end = |value: f32, limit: u32, start: u32| {
        (value.clamp(0.0, 1.0) * limit as f32)
            .round()
            .clamp(start.saturating_add(1).min(limit) as f32, limit as f32) as u32
    };
    let x = to_start(rect[0], width);
    let y = to_start(rect[1], height);
    let end_x = to_end(rect[0] + rect[2], width, x);
    let end_y = to_end(rect[1] + rect[3], height, y);
    (
        x,
        y,
        end_x.saturating_sub(x).max(1),
        end_y.saturating_sub(y).max(1),
    )
}

fn interior_coordinate(origin: u32, span: u32, ordinal: u32, count: u32, limit: u32) -> u32 {
    let numerator = ordinal.saturating_mul(2).saturating_add(1);
    origin
        .saturating_add(span.saturating_mul(numerator) / count.saturating_mul(2))
        .min(limit.saturating_sub(1))
}

fn append_horizontal_crossing(
    points: &mut Vec<SamplePoint>,
    width: u32,
    y: u32,
    boundary_x: u32,
    kind: SampleKind,
) {
    for offset in -SAMPLE_RADIUS_PIXELS..=SAMPLE_RADIUS_PIXELS {
        points.push(SamplePoint {
            x: (boundary_x as i64 + i64::from(offset)).clamp(0, i64::from(width.saturating_sub(1)))
                as u32,
            y,
            kind,
        });
    }
}

fn append_vertical_crossing(
    points: &mut Vec<SamplePoint>,
    height: u32,
    x: u32,
    boundary_y: u32,
    kind: SampleKind,
) {
    for offset in -SAMPLE_RADIUS_PIXELS..=SAMPLE_RADIUS_PIXELS {
        points.push(SamplePoint {
            x,
            y: (boundary_y as i64 + i64::from(offset)).clamp(0, i64::from(height.saturating_sub(1)))
                as u32,
            kind,
        });
    }
}

fn append_corner_sdf_normal(
    points: &mut Vec<SamplePoint>,
    width: u32,
    height: u32,
    rect: [f32; 4],
    corner_radius_uv: f32,
) {
    let radius = corner_radius_uv.clamp(0.0, 0.499);
    let local_pixels = (rect[2].abs() * width as f32)
        .min(rect[3].abs() * height as f32)
        .max(1.0);
    let inverse_root_two = std::f32::consts::FRAC_1_SQRT_2;
    for offset in -SAMPLE_RADIUS_PIXELS..=SAMPLE_RADIUS_PIXELS {
        let signed_radius = radius + offset as f32 / local_pixels;
        let local_x = radius - signed_radius * inverse_root_two;
        let local_y = radius - signed_radius * inverse_root_two;
        points.push(SamplePoint {
            x: ((rect[0] + local_x * rect[2]) * width as f32)
                .round()
                .clamp(0.0, width.saturating_sub(1) as f32) as u32,
            y: ((rect[1] + local_y * rect[3]) * height as f32)
                .round()
                .clamp(0.0, height.saturating_sub(1) as f32) as u32,
            kind: SampleKind::LeftCornerSdfNormal,
        });
    }
}

#[cfg(target_os = "android")]
#[derive(Clone, Copy)]
struct CaptureIdentity {
    serial: u64,
    frame_id: u64,
    surface_generation: u64,
    requested_flags: u32,
    recorded_flags: u32,
    region_contract_version: u32,
    settings: ProjectionZoneCompositorSettings,
    core_rects: [[f32; 4]; 2],
}

#[cfg(target_os = "android")]
struct Staging {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    coherent: bool,
    bytes: u64,
    allocation_bytes: u64,
}

#[cfg(target_os = "android")]
pub(crate) struct ProjectionReadback {
    format: Option<StoredPixelFormat>,
    vk_format: vk::Format,
    color_space: vk::ColorSpaceKHR,
    composite_alpha: vk::CompositeAlphaFlagsKHR,
    transfer_src_supported: bool,
    width: u32,
    height: u32,
    memory_properties: vk::PhysicalDeviceMemoryProperties,
    toggle_edges: CaptureToggleEdges,
    pending: Option<CaptureIdentity>,
    in_flight: Option<(CaptureIdentity, SamplePlan)>,
    staging: Option<Staging>,
}

#[cfg(target_os = "android")]
impl ProjectionReadback {
    pub(crate) fn new(
        surface_format: vk::SurfaceFormatKHR,
        composite_alpha: vk::CompositeAlphaFlagsKHR,
        extent: vk::Extent2D,
        supported_usage: vk::ImageUsageFlags,
        memory_properties: vk::PhysicalDeviceMemoryProperties,
    ) -> Self {
        Self {
            format: StoredPixelFormat::from_vk(surface_format.format),
            vk_format: surface_format.format,
            color_space: surface_format.color_space,
            composite_alpha,
            transfer_src_supported: supported_usage.contains(vk::ImageUsageFlags::TRANSFER_SRC),
            width: extent.width,
            height: extent.height,
            memory_properties,
            // The public default is zero. A first observed zero must be inert,
            // while a control accepted before the first frame can still request.
            toggle_edges: CaptureToggleEdges::default(),
            pending: None,
            in_flight: None,
            staging: None,
        }
    }

    pub(crate) fn observe_control(&mut self, settings: ProjectionZoneCompositorSettings) {
        let toggle = settings.outer_stretch_option_flags & PROJECTION_READBACK_TOGGLE != 0;
        let Some(serial) = self.toggle_edges.observe(toggle) else {
            return;
        };
        if self.in_flight.is_some() {
            log_marker(format!(
                "status=projection-producer-readback-request-coalesced serial={} reason=prior-capture-awaits-natural-frame-fence toggle={} runtimeCrash=false",
                serial, toggle,
            ));
        }
        self.pending = Some(CaptureIdentity {
            serial,
            frame_id: 0,
            surface_generation: 0,
            requested_flags: settings.outer_stretch_option_flags,
            recorded_flags: settings.composition_option_flags(),
            region_contract_version: settings.region_contract_version,
            settings,
            core_rects: [[0.0; 4], [0.0; 4]],
        });
        log_marker(format!(
            "status=projection-producer-readback-requested serial={} toggle={} swapchainUsageTransferSrcSupported={} selectedFormat={:?} selectedColorSpace={:?} selectedCompositeAlpha={:?} requestPolicy=one-edge-one-capture-no-per-frame-repeat runtimeCrash=false",
            serial, toggle, self.transfer_src_supported, self.vk_format, self.color_space, self.composite_alpha,
        ));
    }

    pub(crate) unsafe fn record_after_draw(
        &mut self,
        device: &ash::Device,
        command_buffer: vk::CommandBuffer,
        swapchain_image: vk::Image,
        frame_id: u64,
        surface_generation: u64,
        settings: ProjectionZoneCompositorSettings,
        core_rects: [[f32; 4]; 2],
    ) {
        let Some(mut identity) = self.pending.take() else {
            return;
        };
        identity.recorded_flags = settings.composition_option_flags();
        identity.region_contract_version = settings.region_contract_version;
        identity.settings = settings;
        identity.frame_id = frame_id;
        identity.surface_generation = surface_generation;
        identity.core_rects = core_rects;
        let Some(format) = self.format else {
            self.log_unavailable(identity, "unsupported-selected-swapchain-format");
            return;
        };
        if !self.transfer_src_supported {
            self.log_unavailable(identity, "surface-usage-missing-transfer-src");
            return;
        }
        if self.in_flight.is_some() {
            self.log_unavailable(identity, "prior-capture-awaits-natural-frame-fence");
            return;
        }
        let plan = SamplePlan::from_target_rects(
            self.width,
            self.height,
            core_rects,
            settings.center_corner_radius_uv,
        );
        if plan.bytes() == 0 || plan.bytes() > MAX_STAGING_BYTES {
            self.log_unavailable(identity, "sample-plan-outside-staging-bound");
            return;
        }
        if let Err(reason) = self.ensure_staging(device, plan.bytes()) {
            self.log_unavailable(identity, &reason);
            return;
        }
        let staging = self
            .staging
            .as_ref()
            .expect("staging created on successful ensure");
        let staging_buffer = staging.buffer;
        let staging_bytes = staging.bytes;
        let to_transfer = vk::ImageMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
            .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
            .old_layout(vk::ImageLayout::PRESENT_SRC_KHR)
            .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .image(swapchain_image)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .subresource_range(
                vk::ImageSubresourceRange::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .base_mip_level(0)
                    .level_count(1)
                    .base_array_layer(0)
                    .layer_count(1),
            );
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
            vk::PipelineStageFlags::TRANSFER,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &[to_transfer],
        );
        let copies: Vec<_> = plan
            .points
            .iter()
            .enumerate()
            .map(|(index, point)| {
                vk::BufferImageCopy::default()
                    .buffer_offset((index as u64) * 4)
                    .buffer_row_length(0)
                    .buffer_image_height(0)
                    .image_subresource(
                        vk::ImageSubresourceLayers::default()
                            .aspect_mask(vk::ImageAspectFlags::COLOR)
                            .mip_level(0)
                            .base_array_layer(0)
                            .layer_count(1),
                    )
                    .image_offset(vk::Offset3D {
                        x: point.x as i32,
                        y: point.y as i32,
                        z: 0,
                    })
                    .image_extent(vk::Extent3D {
                        width: 1,
                        height: 1,
                        depth: 1,
                    })
            })
            .collect();
        device.cmd_copy_image_to_buffer(
            command_buffer,
            swapchain_image,
            vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
            staging_buffer,
            &copies,
        );
        let copy_to_host = vk::BufferMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
            .dst_access_mask(vk::AccessFlags::HOST_READ)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(staging_buffer)
            .offset(0)
            .size(staging_bytes);
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::HOST,
            vk::DependencyFlags::empty(),
            &[],
            &[copy_to_host],
            &[],
        );
        let to_present = vk::ImageMemoryBarrier::default()
            .src_access_mask(vk::AccessFlags::TRANSFER_READ)
            .dst_access_mask(vk::AccessFlags::empty())
            .old_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
            .new_layout(vk::ImageLayout::PRESENT_SRC_KHR)
            .image(swapchain_image)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .subresource_range(
                vk::ImageSubresourceRange::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .base_mip_level(0)
                    .level_count(1)
                    .base_array_layer(0)
                    .layer_count(1),
            );
        device.cmd_pipeline_barrier(
            command_buffer,
            vk::PipelineStageFlags::TRANSFER,
            vk::PipelineStageFlags::BOTTOM_OF_PIPE,
            vk::DependencyFlags::empty(),
            &[],
            &[],
            &[to_present],
        );
        self.in_flight = Some((identity, plan));
        log_marker(format!(
            "status=projection-producer-readback-recorded serial={} selectedImage=true source=selected-swapchain-after-final-draw-before-present format={} colorSpace={:?} compositeAlpha={:?} viewport={}x{} samples={} transferBarrier=present-transfer-src-present stagingBytes={} runtimeCrash=false",
            identity.serial, format.token(), self.color_space, self.composite_alpha, self.width, self.height,
            self.in_flight.as_ref().map(|(_, plan)| plan.points.len()).unwrap_or(0), staging_bytes,
        ));
    }

    pub(crate) unsafe fn retire_after_fence(&mut self, device: &ash::Device) {
        let Some((identity, plan)) = self.in_flight.take() else {
            return;
        };
        let Some(staging) = self.staging.as_ref() else {
            self.log_unavailable(identity, "staging-lost-before-retirement");
            return;
        };
        let mapped = match device.map_memory(
            staging.memory,
            0,
            staging.allocation_bytes,
            vk::MemoryMapFlags::empty(),
        ) {
            Ok(mapped) => mapped.cast::<u8>(),
            Err(error) => {
                self.log_unavailable(identity, &format!("map-host-readback-{error:?}"));
                return;
            }
        };
        if !staging.coherent {
            let range = vk::MappedMemoryRange::default()
                .memory(staging.memory)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            if let Err(error) = device.invalidate_mapped_memory_ranges(&[range]) {
                device.unmap_memory(staging.memory);
                self.log_unavailable(identity, &format!("invalidate-host-readback-{error:?}"));
                return;
            }
        }
        let bytes = std::slice::from_raw_parts(mapped, staging.bytes as usize);
        let format = self.format.expect("format validated before submission");
        emit_samples(
            identity,
            &plan,
            bytes,
            format,
            self.width,
            self.height,
            self.color_space,
            self.composite_alpha,
        );
        device.unmap_memory(staging.memory);
    }

    pub(crate) fn cancel_unsubmitted(&mut self, reason: &str) {
        if let Some((identity, _)) = self.in_flight.take() {
            log_marker(format!(
                "status=projection-producer-readback-cancelled serial={} frameId={} surfaceGeneration={} reason={} completionLogged=false runtimeCrash=false",
                identity.serial, identity.frame_id, identity.surface_generation, reason,
            ));
        }
    }

    pub(crate) fn has_in_flight(&self) -> bool {
        self.in_flight.is_some()
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        // The render loop retires its last submitted fence before teardown, so
        // there is no live copy when this bounded resource is released.
        self.in_flight = None;
        if let Some(staging) = self.staging.take() {
            device.destroy_buffer(staging.buffer, None);
            device.free_memory(staging.memory, None);
        }
    }

    unsafe fn ensure_staging(&mut self, device: &ash::Device, bytes: u64) -> Result<(), String> {
        if self
            .staging
            .as_ref()
            .is_some_and(|staging| staging.bytes >= bytes)
        {
            return Ok(());
        }
        if self.in_flight.is_some() {
            return Err("staging-resize-while-capture-in-flight".to_string());
        }
        if let Some(staging) = self.staging.take() {
            device.destroy_buffer(staging.buffer, None);
            device.free_memory(staging.memory, None);
        }
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(bytes)
                    .usage(vk::BufferUsageFlags::TRANSFER_DST)
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create-readback-staging-buffer-{error:?}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let desired =
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT;
        let memory_type_index = find_memory_type(
            &self.memory_properties,
            requirements.memory_type_bits,
            desired,
        )
        .or_else(|| {
            find_memory_type(
                &self.memory_properties,
                requirements.memory_type_bits,
                vk::MemoryPropertyFlags::HOST_VISIBLE,
            )
        })
        .ok_or_else(|| "no-host-visible-readback-memory-type".to_string());
        let memory_type_index = match memory_type_index {
            Ok(index) => index,
            Err(reason) => {
                device.destroy_buffer(buffer, None);
                return Err(reason);
            }
        };
        let memory = match device.allocate_memory(
            &vk::MemoryAllocateInfo::default()
                .allocation_size(requirements.size)
                .memory_type_index(memory_type_index),
            None,
        ) {
            Ok(memory) => memory,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(format!("allocate-readback-staging-memory-{error:?}"));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind-readback-staging-memory-{error:?}"));
        }
        let coherent = self.memory_properties.memory_types[memory_type_index as usize]
            .property_flags
            .contains(vk::MemoryPropertyFlags::HOST_COHERENT);
        self.staging = Some(Staging {
            buffer,
            memory,
            coherent,
            bytes,
            allocation_bytes: requirements.size,
        });
        Ok(())
    }

    fn log_unavailable(&self, identity: CaptureIdentity, reason: &str) {
        log_marker(format!(
            "status=projection-producer-readback-unavailable serial={} reason={} selectedFormat={:?} selectedColorSpace={:?} selectedCompositeAlpha={:?} surfaceUsageTransferSrcSupported={} nonfatal=true runtimeCrash=false",
            identity.serial, reason, self.vk_format, self.color_space, self.composite_alpha, self.transfer_src_supported,
        ));
    }
}

#[cfg(target_os = "android")]
fn find_memory_type(
    properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Option<u32> {
    (0..properties.memory_type_count).find(|index| {
        memory_type_bits & (1u32 << *index) != 0
            && properties.memory_types[*index as usize]
                .property_flags
                .contains(required)
    })
}

// The Android implementation needs a physical-device memory type, supplied by
// the probe at construction. Keep allocation logic below the pure planning and
// decoding code so its bounds are host-testable.

#[cfg(target_os = "android")]
fn emit_samples(
    identity: CaptureIdentity,
    plan: &SamplePlan,
    bytes: &[u8],
    format: StoredPixelFormat,
    width: u32,
    height: u32,
    color_space: vk::ColorSpaceKHR,
    composite_alpha: vk::CompositeAlphaFlagsKHR,
) {
    let mut groups = [
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
        Vec::new(),
    ];
    for (index, point) in plan.points.iter().enumerate() {
        let offset = index * 4;
        let stored = [
            bytes[offset],
            bytes[offset + 1],
            bytes[offset + 2],
            bytes[offset + 3],
        ];
        let rgba = format.decode(stored);
        let entry = format_sample_entry(*point, stored, rgba);
        let group = match point.kind {
            SampleKind::LeftTopNormal => 0,
            SampleKind::LeftEdgeNormal => 1,
            SampleKind::RightTopNormal => 2,
            SampleKind::RightEdgeNormal => 3,
            SampleKind::LeftCornerSdfNormal => 4,
            SampleKind::TileCenter => 5,
        };
        groups[group].push(entry);
    }
    let chunk_count = groups
        .iter()
        .map(|group| group.chunks(SAMPLE_LOG_CHUNK_ENTRIES).len())
        .sum::<usize>();
    let mut chunk_ordinal = 0;
    for (group_index, group) in groups.iter().enumerate() {
        for entries in group.chunks(SAMPLE_LOG_CHUNK_ENTRIES) {
            let kind = match group_index {
                0 => SampleKind::LeftTopNormal,
                1 => SampleKind::LeftEdgeNormal,
                2 => SampleKind::RightTopNormal,
                3 => SampleKind::RightEdgeNormal,
                4 => SampleKind::LeftCornerSdfNormal,
                _ => SampleKind::TileCenter,
            };
            log_marker(format_sample_chunk_line(
                identity.serial,
                identity.frame_id,
                identity.surface_generation,
                chunk_ordinal,
                chunk_count,
                kind,
                entries,
            ));
            chunk_ordinal += 1;
        }
    }
    log_marker(format!(
        "status=projection-producer-readback-complete serial={} frameId={} surfaceGeneration={} exactCapturedFlags=0x{:x} originallyRequestedFlags=0x{:x} regionContract=v{} format={} colorSpace={:?} compositeAlpha={:?} viewport={}x{} coreRects={:.6};{:.6};{:.6};{:.6}|{:.6};{:.6};{:.6};{:.6} coverageMode={} centerCornerRadiusUv={:.6} innerWidthUv={:.6} innerCurve={:.6} outerWidthUv={:.6} outerCurve={:.6} storedBytes=no-gamma-reinterpretation sampleGeometry=dense-129px-normal-crossings-plus-5x5-centers-per-eye cornerNormal=target-local-rounded-sdf-approximation pixelPhaseUncertainty=half-pixel runtimeCrash=false",
        identity.serial, identity.frame_id, identity.surface_generation, identity.recorded_flags,
        identity.requested_flags, identity.region_contract_version, format.token(), color_space, composite_alpha,
        width, height, identity.core_rects[0][0], identity.core_rects[0][1], identity.core_rects[0][2], identity.core_rects[0][3],
        identity.core_rects[1][0], identity.core_rects[1][1], identity.core_rects[1][2], identity.core_rects[1][3],
        identity.settings.coverage_mode, identity.settings.center_corner_radius_uv, identity.settings.inner_width_uv,
        identity.settings.inner_curve, identity.settings.outer_width_uv, identity.settings.outer_curve,
    ));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rect(x: f32, y: f32, width: f32, height: f32) -> [f32; 4] {
        [x, y, width, height]
    }

    #[test]
    fn sample_plan_is_bounded_and_coordinates_stay_inside_the_selected_image() {
        let plan = SamplePlan::from_target_rects(
            1920,
            1080,
            [rect(0.1, 0.2, 0.3, 0.4), rect(0.6, 0.2, 0.3, 0.4)],
            0.08,
        );
        assert_eq!(
            plan.points.len(),
            SAMPLE_COUNT_PER_STRIP * 5 + TILE_CENTER_COUNT_PER_EYE * 2
        );
        assert!(plan.bytes() < MAX_STAGING_BYTES);
        assert!(plan
            .points
            .iter()
            .all(|point| point.x < 1920 && point.y < 1080));
        assert_eq!(
            plan.points[0],
            SamplePoint {
                x: 480,
                y: 152,
                kind: SampleKind::LeftTopNormal
            }
        );
        assert!(plan
            .points
            .iter()
            .any(|point| point.x == 480 && point.y == 216));
        assert!(plan
            .points
            .iter()
            .any(|point| point.x == 192 && point.y == 432));
        assert!(
            plan.points
                .iter()
                .filter(|point| point.kind == SampleKind::TileCenter)
                .count()
                == 50
        );
    }

    #[test]
    fn format_decode_preserves_underlying_bytes_without_gamma_conversion() {
        assert_eq!(
            StoredPixelFormat::Rgba8.decode([0x10, 0x20, 0x30, 0x40]),
            [0x10, 0x20, 0x30, 0x40]
        );
        assert_eq!(
            StoredPixelFormat::Bgra8.decode([0x10, 0x20, 0x30, 0x40]),
            [0x30, 0x20, 0x10, 0x40]
        );
    }

    #[test]
    fn worst_case_production_sample_chunk_fits_android_log_payload() {
        let point = SamplePoint {
            x: u32::MAX,
            y: u32::MAX,
            kind: SampleKind::LeftCornerSdfNormal,
        };
        let entry = format_sample_entry(point, [0xff; 4], [0xff; 4]);
        let entries = vec![entry; SAMPLE_LOG_CHUNK_ENTRIES];
        let line = format_sample_chunk_line(
            u64::MAX,
            u64::MAX,
            u64::MAX,
            usize::MAX,
            usize::MAX,
            SampleKind::LeftCornerSdfNormal,
            &entries,
        );
        assert!(
            line.len() + CAMERA_HWB_MARKER_PREFIX_BUDGET_BYTES <= 1000,
            "sample marker may truncate: marker={} prefixBudget={}",
            line.len(),
            CAMERA_HWB_MARKER_PREFIX_BUDGET_BYTES,
        );
    }

    #[test]
    fn capture_toggle_edges_are_one_shot_and_initial_zero_is_inert() {
        assert_eq!(PROJECTION_READBACK_TOGGLE, 0x8000);
        let mut edges = CaptureToggleEdges::default();
        assert_eq!(edges.observe(false), None);
        assert_eq!(edges.observe(false), None);
        assert_eq!(edges.observe(true), Some(1));
        assert_eq!(edges.observe(true), None);
        assert_eq!(edges.observe(false), Some(2));
    }

    #[test]
    fn pending_capture_requests_coalesce_to_one_bounded_follow_up() {
        let mut requests = BoundedCaptureRequests::default();
        assert!(!requests.offer(1));
        assert!(requests.offer(2));
        assert_eq!(requests.submit_next(), Some(2));
        assert!(requests.offer(3));
        assert_eq!(requests.submit_next(), None);
        requests.retire();
        assert_eq!(requests.submit_next(), Some(3));
        assert_eq!(
            SAMPLE_COUNT_PER_STRIP * 5 + TILE_CENTER_COUNT_PER_EYE * 2,
            695
        );
    }
}
