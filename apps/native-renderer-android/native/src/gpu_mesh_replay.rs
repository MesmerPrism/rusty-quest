//! Native Vulkan storage-buffer residency for recorded hand replay meshes.

use ash::vk;

use crate::recorded_hand_replay::RecordedHandReplaySummary;

#[derive(Clone, Debug, Default)]
pub(crate) struct GpuMeshReplayStats {
    pub(crate) source_mesh_buffers_resident: bool,
    pub(crate) source_mesh_buffers_reused: bool,
    pub(crate) source_mesh_buffer_generation: u64,
    pub(crate) source_vertex_buffer_bytes: u64,
    pub(crate) topology_vertex_count: u64,
    pub(crate) topology_triangle_count: u64,
    pub(crate) topology_index_count: u64,
    pub(crate) cpu_sdf_per_frame: bool,
}

impl GpuMeshReplayStats {
    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "gpuMeshPath=native-vulkan-storage-buffer sourceMeshBuffersResident={} sourceMeshBuffersReused={} sourceMeshBufferGeneration={} sourceVertexBufferBytes={} topologyVertexCount={} topologyTriangleCount={} topologyIndexCount={} sourceMeshToSdfKernel=false cpuSdfPerFrame={} highRateJsonPayload=false",
            self.source_mesh_buffers_resident,
            self.source_mesh_buffers_reused,
            self.source_mesh_buffer_generation,
            self.source_vertex_buffer_bytes,
            self.topology_vertex_count,
            self.topology_triangle_count,
            self.topology_index_count,
            self.cpu_sdf_per_frame,
        )
    }
}

#[derive(Default)]
pub(crate) struct GpuMeshReplayResources {
    buffer: vk::Buffer,
    memory: vk::DeviceMemory,
    source_vertex_buffer_bytes: u64,
    source_mesh_buffer_generation: u64,
}

impl GpuMeshReplayResources {
    pub(crate) unsafe fn prepare_source_mesh(
        &mut self,
        device: &ash::Device,
        memory_properties: &vk::PhysicalDeviceMemoryProperties,
        replay: &RecordedHandReplaySummary,
    ) -> Result<GpuMeshReplayStats, String> {
        if replay.bind_vertices.is_empty() {
            return Ok(GpuMeshReplayStats {
                topology_vertex_count: replay.vertex_count,
                topology_triangle_count: replay.triangle_count,
                topology_index_count: replay.index_count,
                cpu_sdf_per_frame: false,
                ..Default::default()
            });
        }

        let bytes = replay.source_vertex_buffer_bytes();
        if self.buffer != vk::Buffer::null() && self.source_vertex_buffer_bytes == bytes {
            return Ok(GpuMeshReplayStats {
                source_mesh_buffers_resident: true,
                source_mesh_buffers_reused: true,
                source_mesh_buffer_generation: self.source_mesh_buffer_generation,
                source_vertex_buffer_bytes: self.source_vertex_buffer_bytes,
                topology_vertex_count: replay.vertex_count,
                topology_triangle_count: replay.triangle_count,
                topology_index_count: replay.index_count,
                cpu_sdf_per_frame: false,
                ..Default::default()
            });
        }

        self.destroy(device);
        let buffer = device
            .create_buffer(
                &vk::BufferCreateInfo::default()
                    .size(bytes)
                    .usage(
                        vk::BufferUsageFlags::STORAGE_BUFFER | vk::BufferUsageFlags::TRANSFER_DST,
                    )
                    .sharing_mode(vk::SharingMode::EXCLUSIVE),
                None,
            )
            .map_err(|error| format!("create recorded hand mesh storage buffer: {error}"))?;
        let requirements = device.get_buffer_memory_requirements(buffer);
        let memory_type_index = match find_memory_type(
            memory_properties,
            requirements.memory_type_bits,
            vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
        ) {
            Ok(index) => index,
            Err(error) => {
                device.destroy_buffer(buffer, None);
                return Err(error);
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
                return Err(format!(
                    "allocate recorded hand mesh storage memory: {error}"
                ));
            }
        };
        if let Err(error) = device.bind_buffer_memory(buffer, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_buffer(buffer, None);
            return Err(format!("bind recorded hand mesh storage memory: {error}"));
        }

        let mapped = match device.map_memory(memory, 0, bytes, vk::MemoryMapFlags::empty()) {
            Ok(mapped) => mapped.cast::<[f32; 4]>(),
            Err(error) => {
                device.free_memory(memory, None);
                device.destroy_buffer(buffer, None);
                return Err(format!("map recorded hand mesh storage memory: {error}"));
            }
        };
        mapped.copy_from_nonoverlapping(replay.bind_vertices.as_ptr(), replay.bind_vertices.len());
        device.unmap_memory(memory);

        self.buffer = buffer;
        self.memory = memory;
        self.source_vertex_buffer_bytes = bytes;
        self.source_mesh_buffer_generation = self.source_mesh_buffer_generation.saturating_add(1);

        Ok(GpuMeshReplayStats {
            source_mesh_buffers_resident: true,
            source_mesh_buffers_reused: false,
            source_mesh_buffer_generation: self.source_mesh_buffer_generation,
            source_vertex_buffer_bytes: bytes,
            topology_vertex_count: replay.vertex_count,
            topology_triangle_count: replay.triangle_count,
            topology_index_count: replay.index_count,
            cpu_sdf_per_frame: false,
            ..Default::default()
        })
    }

    pub(crate) unsafe fn destroy(&mut self, device: &ash::Device) {
        if self.buffer != vk::Buffer::null() {
            device.destroy_buffer(self.buffer, None);
        }
        if self.memory != vk::DeviceMemory::null() {
            device.free_memory(self.memory, None);
        }
        self.buffer = vk::Buffer::null();
        self.memory = vk::DeviceMemory::null();
        self.source_vertex_buffer_bytes = 0;
    }
}

fn find_memory_type(
    memory_properties: &vk::PhysicalDeviceMemoryProperties,
    memory_type_bits: u32,
    required: vk::MemoryPropertyFlags,
) -> Result<u32, String> {
    for index in 0..memory_properties.memory_type_count {
        let supported = (memory_type_bits & (1 << index)) != 0;
        let flags = memory_properties.memory_types[index as usize].property_flags;
        if supported && flags.contains(required) {
            return Ok(index);
        }
    }
    Err(format!(
        "no Vulkan memory type supports {required:?} for recorded hand mesh storage buffer"
    ))
}
