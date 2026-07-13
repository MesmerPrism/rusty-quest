//! Standalone Android JNI transport to one stateful Manifold broker runtime.

mod admission_jni;

/// Initializes or rebinds the process-local runtime in host tests.
pub fn initialize_for_host_test(
    config_json: &str,
    expected_config_sha256: &str,
    epoch_entropy_hex: &str,
) -> Result<String, String> {
    admission_jni::initialize(config_json, expected_config_sha256, epoch_entropy_hex)
}

/// Executes signature-scoped admission through the shared runtime in host tests.
pub fn admit_for_host_test(operation_json: &str) -> Result<String, String> {
    admission_jni::execute_admission(operation_json)
}

/// Executes one admitted real server mutation in host tests.
pub fn mutate_for_host_test(mutation_json: &str, now_ms: u64) -> Result<String, String> {
    admission_jni::mutate(mutation_json, now_ms)
}

/// Applies exact platform owner completion through Rust in host tests.
/// Returns integrated runtime evidence in host tests.
pub fn evidence_for_host_test() -> Result<String, String> {
    admission_jni::evidence()
}

/// Returns the accepted admission snapshot in host tests.
pub fn admission_snapshot_for_host_test() -> Result<String, String> {
    admission_jni::admission_snapshot()
}
