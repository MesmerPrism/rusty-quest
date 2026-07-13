//! One-way, typed QCL evidence adapter.
//!
//! QCL is a compatibility/evidence source. It is not a dependency of the
//! reusable broker-client SDK or generic media runtime.

use std::{fs, path::Path};

use rusty_quest_broker_client::{
    GenericMediaSessionEvidence, GENERIC_MEDIA_RECEIPT_SCHEMA, MEDIA_SESSION_CONTRACT,
};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Typed QCL run-receipt schema accepted by this compatibility adapter.
pub const QCL100_MEDIA_RUN_RECEIPT_SCHEMA: &str = "rusty.quest.qcl100_media_run_receipt.v1";
/// Typed QCL network evidence schema.
pub const QCL100_NETWORK_EVIDENCE_SCHEMA: &str = "rusty.quest.qcl100_network_evidence.v1";
/// Typed product render evidence schema.
pub const QCL100_RENDER_EVIDENCE_SCHEMA: &str = "rusty.quest.qcl100_render_evidence.v1";
const MAX_EVIDENCE_AGE_MS: u64 = 24 * 60 * 60 * 1_000;
const MAX_FUTURE_SKEW_MS: u64 = 5 * 60 * 1_000;

/// Absolute, SHA-bound external artifact reference.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QclArtifactBinding {
    /// Absolute non-fixture path.
    pub path: String,
    /// Exact SHA-256 of file bytes.
    pub sha256: String,
}

/// Closed adapter invocation; all media values are derived from parsed files.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Qcl100EvidenceAdapterRequest {
    /// QCL top-level receipt artifact.
    pub artifact: QclArtifactBinding,
    /// Exact run nonce expected by the caller-owned release window.
    pub expected_run_id: String,
    /// Exact clean source revision used for the run.
    pub expected_repository_revision: String,
    /// Trusted reducer clock in Unix milliseconds.
    pub now_unix_ms: u64,
}

/// QCL top-level receipt joining network and renderer evidence.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Qcl100MediaRunReceipt {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Fresh run nonce.
    pub run_id: String,
    /// Exact clean QCL source revision.
    pub repository_revision: String,
    /// Receipt observation time.
    pub observed_at_unix_ms: u64,
    /// QCL validation profile provenance.
    pub validation_profile_ref: String,
    /// Accepted layout.
    pub media_layout: String,
    /// Exact source headset serial.
    pub source_serial: String,
    /// Exact sink headset serial.
    pub sink_serial: String,
    /// Typed network/raw evidence.
    pub network_evidence: QclArtifactBinding,
    /// Typed product-render evidence.
    pub render_evidence: QclArtifactBinding,
}

/// Raw QCL network/session evidence.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Qcl100NetworkEvidence {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Fresh run nonce.
    pub run_id: String,
    /// Exact source revision.
    pub repository_revision: String,
    /// Observation time.
    pub observed_at_unix_ms: u64,
    /// Source headset serial.
    pub source_serial: String,
    /// Sink headset serial.
    pub sink_serial: String,
    /// Live provider epoch.
    pub provider_epoch_id: String,
    /// Exact accepted media session.
    pub session_id: String,
    /// Exact accepted stream.
    pub stream_id: String,
    /// Scoped socket authority.
    pub socket_authority: String,
    /// Receiver-observed binary media bytes.
    pub receiver_observed_bytes: u64,
    /// Product route inactive after the run.
    pub route_inactive: bool,
    /// Product sockets closed after the run.
    pub sockets_closed: bool,
    /// Package fatal count in the bounded window.
    pub package_fatal_count: u32,
    /// System fatal count in the bounded window.
    pub system_fatal_count: u32,
}

/// Raw app-local renderer adoption evidence.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Qcl100RenderEvidence {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Fresh run nonce.
    pub run_id: String,
    /// Exact source revision.
    pub repository_revision: String,
    /// Observation time.
    pub observed_at_unix_ms: u64,
    /// Live provider epoch.
    pub provider_epoch_id: String,
    /// Exact media session.
    pub session_id: String,
    /// Exact media stream.
    pub stream_id: String,
    /// Exact app-local render sink.
    pub render_sink_id: String,
    /// App-local marker namespace.
    pub marker_namespace: String,
    /// Final-window rendered/submitted frames.
    pub final_window_submitted_frames: u64,
    /// App resources were released.
    pub resources_released: bool,
}

/// Parses and joins a fresh typed QCL run into the generic evidence shape.
///
/// # Errors
///
/// Returns every closed validation error. No epoch/session/count/cleanup field
/// is accepted from the invocation wrapper.
pub fn fold_qcl100_media_evidence(
    request: &Qcl100EvidenceAdapterRequest,
) -> Result<GenericMediaSessionEvidence, Vec<String>> {
    let mut errors = Vec::new();
    validate_revision(&request.expected_repository_revision, &mut errors);
    if request.expected_run_id.trim().is_empty() || request.now_unix_ms == 0 {
        errors.push("expected run/clock binding is missing".to_string());
    }
    let receipt =
        read_bound::<Qcl100MediaRunReceipt>("QCL100 run receipt", &request.artifact, &mut errors);
    let Some(receipt) = receipt else {
        return Err(errors);
    };
    validate_fresh(
        receipt.observed_at_unix_ms,
        request.now_unix_ms,
        "QCL100 run receipt",
        &mut errors,
    );
    if receipt.schema_id != QCL100_MEDIA_RUN_RECEIPT_SCHEMA
        || receipt.run_id != request.expected_run_id
        || receipt.repository_revision != request.expected_repository_revision
        || receipt.validation_profile_ref.trim().is_empty()
        || !matches!(
            receipt.media_layout.as_str(),
            "separate-eye-streams" | "side-by-side-left-right"
        )
        || receipt.source_serial.trim().is_empty()
        || receipt.sink_serial.trim().is_empty()
        || receipt.source_serial == receipt.sink_serial
    {
        errors.push("QCL100 run identity/profile/device/layout binding mismatch".to_string());
    }
    let network = read_bound::<Qcl100NetworkEvidence>(
        "QCL100 network evidence",
        &receipt.network_evidence,
        &mut errors,
    );
    let render = read_bound::<Qcl100RenderEvidence>(
        "QCL100 render evidence",
        &receipt.render_evidence,
        &mut errors,
    );
    if let (Some(network), Some(render)) = (&network, &render) {
        validate_fresh(
            network.observed_at_unix_ms,
            request.now_unix_ms,
            "QCL100 network evidence",
            &mut errors,
        );
        validate_fresh(
            render.observed_at_unix_ms,
            request.now_unix_ms,
            "QCL100 render evidence",
            &mut errors,
        );
        let exact = network.schema_id == QCL100_NETWORK_EVIDENCE_SCHEMA
            && render.schema_id == QCL100_RENDER_EVIDENCE_SCHEMA
            && network.run_id == receipt.run_id
            && render.run_id == receipt.run_id
            && network.repository_revision == receipt.repository_revision
            && render.repository_revision == receipt.repository_revision
            && network.source_serial == receipt.source_serial
            && network.sink_serial == receipt.sink_serial
            && network.provider_epoch_id == render.provider_epoch_id
            && network.session_id == render.session_id
            && network.stream_id == render.stream_id
            && !network.provider_epoch_id.trim().is_empty()
            && !network.session_id.trim().is_empty()
            && !network.stream_id.trim().is_empty()
            && !render.render_sink_id.trim().is_empty()
            && !render.marker_namespace.trim().is_empty()
            && network.socket_authority == "rusty_direct_p2p_socket_authority"
            && network.receiver_observed_bytes > 0
            && render.final_window_submitted_frames > 0
            && network.route_inactive
            && network.sockets_closed
            && render.resources_released
            && network.package_fatal_count == 0
            && network.system_fatal_count == 0;
        if !exact {
            errors.push("typed QCL network/render/run join or cleanup gate failed".to_string());
        }
    }
    if !errors.is_empty() {
        return Err(errors);
    }
    let network = network.expect("validated network evidence exists");
    let render = render.expect("validated render evidence exists");
    Ok(GenericMediaSessionEvidence {
        schema: GENERIC_MEDIA_RECEIPT_SCHEMA.to_string(),
        status: "pass".to_string(),
        media_session_contract: MEDIA_SESSION_CONTRACT.to_string(),
        route_contract: "rusty.quest.direct_p2p_socket_route.v1".to_string(),
        validation_profile_ref: receipt.validation_profile_ref,
        artifact_path: request.artifact.path.clone(),
        artifact_sha256: request.artifact.sha256.clone(),
        provider_epoch_id: network.provider_epoch_id,
        session_id: network.session_id,
        stream_id: network.stream_id,
        render_sink_id: render.render_sink_id,
        render_evidence_path: receipt.render_evidence.path,
        render_evidence_sha256: receipt.render_evidence.sha256,
        media_layout: receipt.media_layout,
        receiver_observed_bytes: network.receiver_observed_bytes,
        final_window_submitted_frames: render.final_window_submitted_frames,
        cleanup_complete: true,
    })
}

fn read_bound<T: DeserializeOwned>(
    label: &str,
    binding: &QclArtifactBinding,
    errors: &mut Vec<String>,
) -> Option<T> {
    let path = Path::new(&binding.path);
    if !path.is_absolute() || binding.path.to_ascii_lowercase().contains("fixtures") {
        errors.push(format!("{label} is not an absolute external run artifact"));
        return None;
    }
    let bytes = match fs::read(path) {
        Ok(bytes) if !bytes.is_empty() => bytes,
        _ => {
            errors.push(format!("{label} is missing or empty"));
            return None;
        }
    };
    if format!("sha256:{:x}", Sha256::digest(&bytes)) != binding.sha256 {
        errors.push(format!("{label} exact-byte digest mismatch"));
        return None;
    }
    match serde_json::from_slice(&bytes) {
        Ok(value) => Some(value),
        Err(error) => {
            errors.push(format!("{label} typed JSON rejected: {error}"));
            None
        }
    }
}

fn validate_revision(value: &str, errors: &mut Vec<String>) {
    if value.len() != 40
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        errors.push("QCL source revision must be exact lowercase Git SHA-1".to_string());
    }
}

fn validate_fresh(observed: u64, now: u64, label: &str, errors: &mut Vec<String>) {
    if observed == 0
        || observed > now.saturating_add(MAX_FUTURE_SKEW_MS)
        || now.saturating_sub(observed) > MAX_EVIDENCE_AGE_MS
    {
        errors.push(format!("{label} is outside the bounded freshness window"));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static SEQUENCE: AtomicU64 = AtomicU64::new(1);

    fn write(path: &Path, value: &impl Serialize) -> QclArtifactBinding {
        let bytes = serde_json::to_vec(value).expect("JSON");
        fs::write(path, &bytes).expect("artifact");
        QclArtifactBinding {
            path: path.to_string_lossy().to_string(),
            sha256: format!("sha256:{:x}", Sha256::digest(bytes)),
        }
    }

    fn package() -> (Qcl100EvidenceAdapterRequest, std::path::PathBuf) {
        let root = std::env::temp_dir().join(format!(
            "rusty-quest-qcl-adapter-{}-{}",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).expect("root");
        let revision = "12".repeat(20);
        let now = 1_800_000_000_000;
        let network = Qcl100NetworkEvidence {
            schema_id: QCL100_NETWORK_EVIDENCE_SCHEMA.to_string(),
            run_id: "run-qcl100-live-001".to_string(),
            repository_revision: revision.clone(),
            observed_at_unix_ms: now - 1_000,
            source_serial: "2G0YC1ZG8009W8".to_string(),
            sink_serial: "2G0YC1ZG80188R".to_string(),
            provider_epoch_id: "epoch.qcl100.live".to_string(),
            session_id: "session.qcl100.live".to_string(),
            stream_id: "stream.qcl100.stereo".to_string(),
            socket_authority: "rusty_direct_p2p_socket_authority".to_string(),
            receiver_observed_bytes: 1_048_576,
            route_inactive: true,
            sockets_closed: true,
            package_fatal_count: 0,
            system_fatal_count: 0,
        };
        let render = Qcl100RenderEvidence {
            schema_id: QCL100_RENDER_EVIDENCE_SCHEMA.to_string(),
            run_id: network.run_id.clone(),
            repository_revision: revision.clone(),
            observed_at_unix_ms: now - 900,
            provider_epoch_id: network.provider_epoch_id.clone(),
            session_id: network.session_id.clone(),
            stream_id: network.stream_id.clone(),
            render_sink_id: "sink.native-openxr".to_string(),
            marker_namespace: "RUSTY_QUEST_NATIVE_BROKER_CLIENT".to_string(),
            final_window_submitted_frames: 120,
            resources_released: true,
        };
        let network_binding = write(&root.join("network.json"), &network);
        let render_binding = write(&root.join("render.json"), &render);
        let receipt = Qcl100MediaRunReceipt {
            schema_id: QCL100_MEDIA_RUN_RECEIPT_SCHEMA.to_string(),
            run_id: network.run_id,
            repository_revision: revision.clone(),
            observed_at_unix_ms: now - 500,
            validation_profile_ref: "QCL-100-promoted-native-dual-lane".to_string(),
            media_layout: "separate-eye-streams".to_string(),
            source_serial: network.source_serial,
            sink_serial: network.sink_serial,
            network_evidence: network_binding,
            render_evidence: render_binding,
        };
        let artifact = write(&root.join("receipt.json"), &receipt);
        (
            Qcl100EvidenceAdapterRequest {
                artifact,
                expected_run_id: receipt.run_id,
                expected_repository_revision: revision,
                now_unix_ms: now,
            },
            root,
        )
    }

    #[test]
    fn typed_fresh_qcl_receipt_folds_without_caller_media_fields() {
        let (request, root) = package();
        let receipt = fold_qcl100_media_evidence(&request).expect("fold");
        assert_eq!(receipt.provider_epoch_id, "epoch.qcl100.live");
        assert_eq!(receipt.render_sink_id, "sink.native-openxr");
        fs::remove_dir_all(root).ok();
    }

    #[test]
    fn opaque_or_cross_run_qcl_artifacts_reject() {
        let (mut request, root) = package();
        fs::write(&request.artifact.path, br#"{"status":"pass","frames":120}"#).expect("damage");
        request.artifact.sha256 = format!(
            "sha256:{:x}",
            Sha256::digest(fs::read(&request.artifact.path).expect("bytes"))
        );
        assert!(fold_qcl100_media_evidence(&request).is_err());
        fs::remove_dir_all(root).ok();
    }
}
