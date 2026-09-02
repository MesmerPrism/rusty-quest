#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RQ_DEPTH_GPU_ABI_V1 1U
#define RQ_SPATIAL_DEPTH_HANDOFF_ABI_V2 2U
#define RQ_DEPTH_GPU_EYE_ORDER_LAYER0_LEFT_LAYER1_RIGHT 1U

typedef enum rq_depth_gpu_status_v1 {
  RQ_DEPTH_GPU_STATUS_OK = 0,
  RQ_DEPTH_GPU_STATUS_NOT_READY = 1,
  RQ_DEPTH_GPU_STATUS_BUSY = 2,
  RQ_DEPTH_GPU_STATUS_PENDING = 3,
  RQ_DEPTH_GPU_STATUS_INVALID_ARGUMENT = -1,
  RQ_DEPTH_GPU_STATUS_ABI_MISMATCH = -2,
  RQ_DEPTH_GPU_STATUS_DEVICE_MISMATCH = -3,
  RQ_DEPTH_GPU_STATUS_SESSION_MISMATCH = -4,
  RQ_DEPTH_GPU_STATUS_STALE_LEASE = -5,
  RQ_DEPTH_GPU_STATUS_UNSUPPORTED = -6,
  RQ_DEPTH_GPU_STATUS_VULKAN_FAILURE = -7,
  RQ_DEPTH_GPU_STATUS_QUEUE_FULL = -8,
  RQ_DEPTH_GPU_STATUS_SHUTDOWN = -9,
  RQ_DEPTH_GPU_STATUS_OUT_OF_WINDOW = -10,
} rq_depth_gpu_status_v1;

typedef enum rq_depth_gpu_request_kind_v1 {
  RQ_DEPTH_GPU_REQUEST_PRESENT = 1,
  RQ_DEPTH_GPU_REQUEST_QUEUE_STRESS = 2,
  RQ_DEPTH_GPU_REQUEST_AHB_YCBCR_SAMPLE = 3,
} rq_depth_gpu_request_kind_v1;

typedef enum rq_depth_gpu_ahb_producer_v1 {
  RQ_DEPTH_GPU_AHB_PRODUCER_SYNTHETIC = 1,
  RQ_DEPTH_GPU_AHB_PRODUCER_MEDIA_CODEC = 2,
  RQ_DEPTH_GPU_AHB_PRODUCER_CAMERA2 = 3,
  RQ_DEPTH_GPU_AHB_PRODUCER_SYNC_FD_ROUND_TRIP = 4,
} rq_depth_gpu_ahb_producer_v1;

typedef enum rq_depth_gpu_ahb_qualification_flag_v1 {
  RQ_DEPTH_GPU_AHB_PROPERTIES_QUERIED = 1U << 0U,
  RQ_DEPTH_GPU_AHB_EXTERNAL_IMAGE_IMPORTED = 1U << 1U,
  RQ_DEPTH_GPU_AHB_YCBCR_CONVERSION_CREATED = 1U << 2U,
  RQ_DEPTH_GPU_AHB_IMAGE_VIEW_SAMPLER_CREATED = 1U << 3U,
  RQ_DEPTH_GPU_AHB_ACQUIRE_SYNC_RESOLVED = 1U << 4U,
  RQ_DEPTH_GPU_AHB_FRAGMENT_SAMPLE_SUBMITTED = 1U << 5U,
  RQ_DEPTH_GPU_AHB_RELEASE_SYNC_EXPORTED = 1U << 6U,
  RQ_DEPTH_GPU_AHB_GPU_COMPLETED = 1U << 7U,
  RQ_DEPTH_GPU_AHB_RESOURCES_TORN_DOWN = 1U << 8U,
  RQ_DEPTH_GPU_AHB_SAMPLE_VALUES_READ = 1U << 9U,
  RQ_DEPTH_GPU_AHB_ACQUIRE_SYNC_FD_IMPORTED = 1U << 10U,
} rq_depth_gpu_ahb_qualification_flag_v1;

typedef enum rq_depth_gpu_request_state_v1 {
  RQ_DEPTH_GPU_REQUEST_STATE_UNKNOWN = 0,
  RQ_DEPTH_GPU_REQUEST_STATE_QUEUED = 1,
  RQ_DEPTH_GPU_REQUEST_STATE_SUBMITTED = 2,
  RQ_DEPTH_GPU_REQUEST_STATE_COMPLETE = 3,
  RQ_DEPTH_GPU_REQUEST_STATE_FAILED = 4,
} rq_depth_gpu_request_state_v1;

typedef enum rq_depth_gpu_lease_state_v1 {
  RQ_DEPTH_GPU_LEASE_STATE_UNKNOWN = 0,
  RQ_DEPTH_GPU_LEASE_STATE_PINNED = 1,
  RQ_DEPTH_GPU_LEASE_STATE_GPU_RELEASE_PENDING = 2,
  RQ_DEPTH_GPU_LEASE_STATE_RELEASED = 3,
  RQ_DEPTH_GPU_LEASE_STATE_INVALIDATED = 4,
} rq_depth_gpu_lease_state_v1;

typedef struct rq_depth_gpu_view_v1 {
  float fov[4];
  float orientation[4];
  float position[3];
} rq_depth_gpu_view_v1;

typedef struct rq_depth_gpu_frame_v1 {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t device_token;
  uint64_t session_generation;
  uint64_t generation;
  uint64_t frame_ordinal;
  uint64_t lease_id;
  uint64_t image_handle;
  uint64_t image_view_handle;
  uint32_t width;
  uint32_t height;
  uint32_t layer_count;
  uint32_t eye_order;
  uint32_t vk_format;
  uint32_t vk_layout;
  uint32_t valid_mask;
  uint32_t freshness_state;
  int64_t capture_time;
  int64_t display_time;
  int64_t render_view_display_time;
  uint64_t render_view_state_flags;
  float near_z;
  float far_z;
  rq_depth_gpu_view_v1 depth_views[2];
  rq_depth_gpu_view_v1 render_views[2];
  uint64_t render_view_space_token;
  uint64_t render_view_space_generation;
  uint64_t render_view_session_generation;
  uint32_t render_view_configuration_type;
  int32_t render_view_locate_result;
  uint32_t render_view_source;
  uint32_t reserved_metadata;
} rq_depth_gpu_frame_v1;

typedef struct rq_depth_gpu_snapshot_v1 {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t device_token;
  uint64_t session_generation;
  uint64_t latest_generation;
  uint64_t submitted_request_count;
  uint64_t completed_request_count;
  uint64_t failed_request_count;
  uint32_t queue_family_index;
  uint32_t queue_index;
  uint32_t wsi_qualified;
  uint32_t ahb_ycbcr_qualified;
  uint32_t surface_format;
  uint32_t present_mode;
  uint32_t swapchain_image_count;
  int32_t last_vk_result;
} rq_depth_gpu_snapshot_v1;

typedef struct rq_depth_gpu_request_result_v1 {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t request_id;
  uint64_t lease_id;
  uint64_t generation;
  uint64_t queued_monotonic_ns;
  uint64_t submitted_monotonic_ns;
  uint64_t completed_monotonic_ns;
  uint64_t queue_submit_cpu_ns;
  uint64_t gpu_consumer_ns;
  uint32_t kind;
  uint32_t state;
  uint32_t lane;
  int32_t status;
  int32_t vk_result;
  uint32_t producer_kind;
  uint32_t qualification_flags;
  uint64_t external_format;
  int32_t release_fence_fd;
  uint32_t reserved_result;
  float sample_rgba[4];
} rq_depth_gpu_request_result_v1;

typedef struct rq_depth_gpu_api_v1 {
  uint32_t struct_size;
  uint32_t abi_version;
  int32_t (*snapshot)(rq_depth_gpu_snapshot_v1* out_snapshot);
  int32_t (*qualify_android_surface)(void* native_window, uint32_t width, uint32_t height);
  int32_t (*qualify_ahardware_buffer_ycbcr)(void);
  int32_t (*acquire_latest)(
      uint64_t expected_device_token,
      uint64_t expected_session_generation,
      rq_depth_gpu_frame_v1* out_frame);
  int32_t (*enqueue_present)(
      uint64_t expected_device_token,
      uint64_t expected_session_generation,
      uint64_t lease_id,
      uint64_t request_id);
  int32_t (*enqueue_queue_stress)(
      uint64_t expected_device_token,
      uint64_t expected_session_generation,
      uint32_t lane,
      uint64_t request_id);
  int32_t (*enqueue_ahardware_buffer_ycbcr_sample)(
      void* ahardware_buffer,
      int32_t acquire_fence_fd,
      uint32_t producer_kind,
      uint64_t request_id);
  int32_t (*poll_request)(uint64_t request_id, rq_depth_gpu_request_result_v1* out_result);
  int32_t (*poll_lease)(uint64_t lease_id, uint32_t* out_lease_state);
  void (*destroy_consumer)(void);
} rq_depth_gpu_api_v1;

__attribute__((visibility("default"))) const rq_depth_gpu_api_v1* rq_depth_gpu_get_api_v1(void);

typedef enum rq_spatial_depth_submit_flag_v2 {
  RQ_SPATIAL_DEPTH_SUBMIT_PRESENT = 1U << 0U,
} rq_spatial_depth_submit_flag_v2;

typedef enum rq_spatial_depth_result_qualification_flag_v1 {
  RQ_SPATIAL_DEPTH_RESULT_QUEUE_SUBMIT_ACCEPTED = 1U << 0U,
  RQ_SPATIAL_DEPTH_RESULT_PRESENT_ACCEPTED = 1U << 1U,
} rq_spatial_depth_result_qualification_flag_v1;

typedef struct rq_spatial_depth_device_binding_v2 {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t context_token;
  uint64_t device_token;
  uint64_t session_generation;
  uint64_t instance_handle;
  uint64_t physical_device_handle;
  uint64_t device_handle;
  uint64_t queue_handle;
  uint32_t queue_family_index;
  uint32_t queue_index;
  uint32_t enabled_capability_mask;
  uint32_t reserved;
} rq_spatial_depth_device_binding_v2;

typedef struct rq_spatial_depth_submit_present_v2 {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t expected_context_token;
  uint64_t expected_device_token;
  uint64_t expected_session_generation;
  uint64_t request_id;
  uint64_t lease_id;
  uint64_t surface_generation;
  uint64_t media_source_generation;
  uint64_t command_buffer_handle;
  uint64_t wait_semaphore_handle;
  uint64_t signal_semaphore_handle;
  uint64_t fence_handle;
  uint64_t swapchain_handle;
  uint32_t wait_stage_mask;
  uint32_t image_index;
  uint32_t flags;
  uint32_t reserved;
} rq_spatial_depth_submit_present_v2;

typedef struct rq_spatial_depth_api_v2 {
  uint32_t struct_size;
  uint32_t abi_version;
  int32_t (*get_device_binding)(rq_spatial_depth_device_binding_v2* out_binding);
  int32_t (*acquire_latest)(
      uint64_t expected_device_token,
      uint64_t expected_session_generation,
      rq_depth_gpu_frame_v1* out_frame);
  int32_t (*enqueue_submit_present)(const rq_spatial_depth_submit_present_v2* request);
  int32_t (*poll_request)(uint64_t request_id, rq_depth_gpu_request_result_v1* out_result);
  int32_t (*release_lease)(
      uint64_t expected_session_generation,
      uint64_t lease_id);
  void (*request_shutdown)(uint64_t expected_session_generation);
} rq_spatial_depth_api_v2;

__attribute__((visibility("default"))) const rq_spatial_depth_api_v2*
rq_spatial_depth_get_api_v2(void);

#ifdef __cplusplus
}
#endif
