#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Process lifetime table. Its code and storage must remain loaded until exit.
 * Every callback is non-reentrant into the core, bounded, and thread-safe.
 * Return zero only after applying/observing the named real service operation. */
typedef struct rq_experiment_service_status_v1 {
  uint32_t struct_size, abi_version;
  uint64_t service_generation, requested_revision, submitted_revision;
  uint64_t last_input_sequence, last_input_android_elapsed_ns;
  uint64_t submitted_input_sequence, submitted_input_android_elapsed_ns;
  uint64_t submitted_runtime_epoch, submitted_sdk_epoch, submitted_session_generation;
  uint32_t available, running, controller_ready, reserved;
  char activation_binding_sha256[65];
  float submitted_controls[8], submitted_visual_scale, submitted_center[4];
} rq_experiment_service_status_v1;
typedef struct rq_experiment_effective_settings_v1 {
  uint32_t struct_size, abi_version;
  uint64_t service_generation, runtime_epoch, sdk_epoch, session_generation, sample_sequence, android_elapsed_ns;
  float controls[8], visual_scale, center[4];
} rq_experiment_effective_settings_v1;
typedef struct rq_experiment_controller_invalidation_v1 {
  uint32_t struct_size, abi_version;
  uint64_t service_generation, runtime_epoch, sdk_epoch, session_generation;
  uint64_t sample_sequence, android_elapsed_ns;
  /* 1=owner/session boundary, 2=known input loss, 3=lifecycle revoke.
   * Boundary/revoke require zero sample fields; loss requires ordered fields. */
  uint32_t reason, reserved;
} rq_experiment_controller_invalidation_v1;
typedef struct rq_experiment_services_v1 {
  uint32_t struct_size, abi_version;
  int32_t (*status)(rq_experiment_service_status_v1*);
  int32_t (*ensure_running)(uint64_t expected_generation, uint64_t* revision);
  int32_t (*reset_defaults)(uint64_t expected_generation, uint64_t* revision);
  int32_t (*apply_effective)(const rq_experiment_effective_settings_v1*, uint64_t* revision);
  int32_t (*invalidate_controller)(const rq_experiment_controller_invalidation_v1*);
} rq_experiment_services_v1;
/* Getter belongs to selected workload. Installation belongs to the one core.
 * A different table cannot replace an installed table within this process. */
const rq_experiment_services_v1* rq_get_experiment_services_v1(void);
int32_t rq_session_core_install_services_v1(const rq_experiment_services_v1*);
#ifdef __cplusplus
}
#endif