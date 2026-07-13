//! Android package projection for accepted Manifold broker product locks.

use rusty_manifold_broker_product::{
    validate_broker_product_lock, ManifoldBrokerFeature, ManifoldBrokerPermission,
    ManifoldBrokerProductError as ManifoldProductError, ManifoldBrokerProductLock,
    ManifoldBrokerProductSpec,
};
use rusty_manifold_model::DottedId;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fmt::Write as _;

/// Quest Android manifest projection schema.
pub const QUEST_BROKER_MANIFEST_SCHEMA: &str = "rusty.quest.broker.android_manifest_projection.v1";
/// Exact packaged command-registry schema.
pub const QUEST_BROKER_COMMAND_REGISTRY_SCHEMA: &str = "rusty.quest.broker.command_registry.v1";
/// Deterministic package-input receipt schema.
pub const QUEST_BROKER_PACKAGE_INPUTS_SCHEMA: &str = "rusty.quest.broker.android_package_inputs.v1";
/// Standalone Android package name.
pub const QUEST_BROKER_PACKAGE_NAME: &str = "io.github.mesmerprism.rustymanifold.broker";
/// Signature-scoped admission permission.
pub const QUEST_BROKER_ADMISSION_PERMISSION: &str =
    "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";

/// One exact Android permission projection.
#[derive(Clone, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAndroidPermission {
    /// Android permission name.
    pub name: String,
    /// Explicit `usesPermissionFlags` tokens.
    pub uses_permission_flags: Vec<String>,
    /// Optional maximum Android SDK for a compatibility permission.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_sdk_version: Option<u32>,
}

/// Permission-minimal Android manifest projection.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAndroidManifestProjection {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Manifold product identity.
    pub product_id: DottedId,
    /// Accepted Manifold lock fingerprint.
    pub manifold_lock_fingerprint: String,
    /// Runtime packaging mode.
    pub runtime_mode: QuestBrokerRuntimeMode,
    /// Exact sorted Android permission projection.
    pub permissions: Vec<QuestBrokerAndroidPermission>,
}

/// Android broker packaging mode.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum QuestBrokerRuntimeMode {
    /// Standalone background application.
    Standalone,
    /// Embedded in-process broker library.
    Embedded,
}

/// Command registry packaged beside the accepted Manifold lock.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerCommandRegistry {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Accepted Manifold lock identity.
    pub manifold_lock_id: DottedId,
    /// Accepted Manifold product identity.
    pub product_id: DottedId,
    /// Accepted Manifold lock fingerprint.
    pub manifold_lock_fingerprint: String,
    /// Exact sorted command closure.
    pub command_ids: Vec<DottedId>,
}

/// Deterministic receipt for all generated Android package inputs.
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAndroidPackageInputsReceipt {
    /// Schema identifier.
    #[serde(rename = "$schema")]
    pub schema_id: String,
    /// Accepted Manifold lock identity.
    pub manifold_lock_id: DottedId,
    /// Accepted Manifold product identity.
    pub product_id: DottedId,
    /// Accepted Manifold lock fingerprint.
    pub manifold_lock_fingerprint: String,
    /// SHA-256 of the canonical accepted Manifold lock JSON.
    pub manifold_lock_sha256: String,
    /// SHA-256 of the canonical product-spec JSON.
    pub product_spec_sha256: String,
    /// Runtime packaging mode.
    pub runtime_mode: QuestBrokerRuntimeMode,
    /// Exact resolved feature closure.
    pub features: Vec<ManifoldBrokerFeature>,
    /// SHA-256 of the generated manifest projection JSON.
    pub manifest_projection_sha256: String,
    /// SHA-256 of the generated Android manifest.
    pub android_manifest_sha256: String,
    /// SHA-256 of the generated command registry.
    pub command_registry_sha256: String,
}

/// In-memory deterministic package artifacts written by the preparation CLI.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QuestBrokerAndroidPackageArtifacts {
    /// Canonical accepted product spec.
    pub product_spec_json: String,
    /// Canonical accepted product lock.
    pub accepted_lock_json: String,
    /// Exact manifest projection.
    pub manifest_projection_json: String,
    /// Exact command registry.
    pub command_registry_json: String,
    /// Actual Android package manifest.
    pub android_manifest_xml: String,
    /// Generated Java constants consumed by product-sensitive app code.
    pub generated_product_config_java: String,
    /// Package input receipt.
    pub receipt_json: String,
}

/// Projection or package-preparation failure.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum QuestBrokerProductError {
    /// Manifold lock has invalid runtime mode.
    InvalidRuntimeMode,
    /// Projection is stale or contains extra/missing permissions.
    ManifestProjectionMismatch,
    /// Product spec and accepted lock do not form an exact Manifold closure.
    InvalidManifoldLock(ManifoldProductError),
    /// This APK builder packages standalone products only.
    EmbeddedProductUnsupported,
    /// A deterministic JSON artifact could not be encoded.
    Serialization(String),
}

/// Projects a Manifold lock into exact Android manifest permissions.
///
/// # Errors
///
/// Returns [`QuestBrokerProductError::InvalidRuntimeMode`] when the accepted
/// lock does not select exactly one runtime placement.
pub fn project_android_manifest(
    lock: &ManifoldBrokerProductLock,
) -> Result<QuestBrokerAndroidManifestProjection, QuestBrokerProductError> {
    let runtime_mode = match (lock.standalone_enabled, lock.embedded_enabled) {
        (true, false) => QuestBrokerRuntimeMode::Standalone,
        (false, true) => QuestBrokerRuntimeMode::Embedded,
        _ => return Err(QuestBrokerProductError::InvalidRuntimeMode),
    };
    let mut by_name = BTreeMap::<String, QuestBrokerAndroidPermission>::new();
    for permission in &lock.permissions {
        for android in android_permissions(permission) {
            merge_permission(&mut by_name, android);
        }
    }
    Ok(QuestBrokerAndroidManifestProjection {
        schema_id: QUEST_BROKER_MANIFEST_SCHEMA.to_owned(),
        product_id: lock.product_id.clone(),
        manifold_lock_fingerprint: lock.spec_fingerprint.clone(),
        runtime_mode,
        permissions: by_name.into_values().collect(),
    })
}

/// Audits a committed manifest projection against the accepted Manifold lock.
///
/// # Errors
///
/// Returns an invalid-mode or projection-mismatch error when the projection is
/// not the exact Android rendering of the lock.
pub fn validate_android_manifest_projection(
    lock: &ManifoldBrokerProductLock,
    projection: &QuestBrokerAndroidManifestProjection,
) -> Result<(), QuestBrokerProductError> {
    let expected = project_android_manifest(lock)?;
    if projection == &expected {
        Ok(())
    } else {
        Err(QuestBrokerProductError::ManifestProjectionMismatch)
    }
}

/// Prepares an exact standalone Android package from one accepted Manifold lock.
///
/// # Errors
///
/// Rejects stale or expanded locks, embedded products, invalid runtime modes,
/// and deterministic JSON serialization failures.
pub fn prepare_standalone_android_package(
    spec: &ManifoldBrokerProductSpec,
    lock: &ManifoldBrokerProductLock,
) -> Result<QuestBrokerAndroidPackageArtifacts, QuestBrokerProductError> {
    validate_broker_product_lock(spec, lock)
        .map_err(QuestBrokerProductError::InvalidManifoldLock)?;
    if !lock.standalone_enabled || lock.embedded_enabled {
        return Err(QuestBrokerProductError::EmbeddedProductUnsupported);
    }

    let projection = project_android_manifest(lock)?;
    let registry = QuestBrokerCommandRegistry {
        schema_id: QUEST_BROKER_COMMAND_REGISTRY_SCHEMA.to_owned(),
        manifold_lock_id: lock.lock_id.clone(),
        product_id: lock.product_id.clone(),
        manifold_lock_fingerprint: lock.spec_fingerprint.clone(),
        command_ids: lock.command_ids.clone(),
    };
    let product_spec_json = pretty_json(spec)?;
    let accepted_lock_json = pretty_json(lock)?;
    let manifest_projection_json = pretty_json(&projection)?;
    let command_registry_json = pretty_json(&registry)?;
    let android_manifest_xml = render_standalone_android_manifest(lock, &projection);
    let generated_product_config_java = render_generated_product_config(lock);
    let receipt = QuestBrokerAndroidPackageInputsReceipt {
        schema_id: QUEST_BROKER_PACKAGE_INPUTS_SCHEMA.to_owned(),
        manifold_lock_id: lock.lock_id.clone(),
        product_id: lock.product_id.clone(),
        manifold_lock_fingerprint: lock.spec_fingerprint.clone(),
        manifold_lock_sha256: sha256_hex(&accepted_lock_json),
        product_spec_sha256: sha256_hex(&product_spec_json),
        runtime_mode: QuestBrokerRuntimeMode::Standalone,
        features: lock.features.clone(),
        manifest_projection_sha256: sha256_hex(&manifest_projection_json),
        android_manifest_sha256: sha256_hex(&android_manifest_xml),
        command_registry_sha256: sha256_hex(&command_registry_json),
    };
    Ok(QuestBrokerAndroidPackageArtifacts {
        product_spec_json,
        accepted_lock_json,
        manifest_projection_json,
        command_registry_json,
        android_manifest_xml,
        generated_product_config_java,
        receipt_json: pretty_json(&receipt)?,
    })
}

fn android_permissions(permission: &ManifoldBrokerPermission) -> Vec<QuestBrokerAndroidPermission> {
    match permission {
        ManifoldBrokerPermission::Internet => vec![android("android.permission.INTERNET")],
        ManifoldBrokerPermission::UserNotifications => {
            vec![android("android.permission.POST_NOTIFICATIONS")]
        }
        ManifoldBrokerPermission::BackgroundService => {
            vec![android("android.permission.FOREGROUND_SERVICE")]
        }
        ManifoldBrokerPermission::BackgroundDataSync => {
            vec![android("android.permission.FOREGROUND_SERVICE_DATA_SYNC")]
        }
        ManifoldBrokerPermission::BackgroundCamera => {
            vec![android("android.permission.FOREGROUND_SERVICE_CAMERA")]
        }
        ManifoldBrokerPermission::Camera => vec![
            android("android.permission.CAMERA"),
            android("horizonos.permission.HEADSET_CAMERA"),
            android("horizonos.permission.SPATIAL_CAMERA"),
        ],
        ManifoldBrokerPermission::NetworkStateObservation => {
            vec![android("android.permission.ACCESS_NETWORK_STATE")]
        }
        ManifoldBrokerPermission::NearbyWifiDevices => vec![
            android_with_flags(
                "android.permission.NEARBY_WIFI_DEVICES",
                &["neverForLocation"],
            ),
            android_max_sdk("android.permission.ACCESS_FINE_LOCATION", 32),
        ],
        ManifoldBrokerPermission::ChangeWifiState => {
            vec![android("android.permission.CHANGE_WIFI_STATE")]
        }
        ManifoldBrokerPermission::AccessWifiState => {
            vec![android("android.permission.ACCESS_WIFI_STATE")]
        }
        ManifoldBrokerPermission::BluetoothScan => vec![
            android_with_flags("android.permission.BLUETOOTH_SCAN", &["neverForLocation"]),
            android_max_sdk("android.permission.ACCESS_FINE_LOCATION", 30),
        ],
        ManifoldBrokerPermission::BluetoothConnect => {
            vec![android("android.permission.BLUETOOTH_CONNECT")]
        }
        ManifoldBrokerPermission::BluetoothAdvertise => {
            vec![android("android.permission.BLUETOOTH_ADVERTISE")]
        }
    }
}

fn android(name: &str) -> QuestBrokerAndroidPermission {
    QuestBrokerAndroidPermission {
        name: name.to_owned(),
        uses_permission_flags: Vec::new(),
        max_sdk_version: None,
    }
}

fn android_with_flags(name: &str, flags: &[&str]) -> QuestBrokerAndroidPermission {
    QuestBrokerAndroidPermission {
        name: name.to_owned(),
        uses_permission_flags: flags.iter().map(|flag| (*flag).to_owned()).collect(),
        max_sdk_version: None,
    }
}

fn android_max_sdk(name: &str, max_sdk_version: u32) -> QuestBrokerAndroidPermission {
    QuestBrokerAndroidPermission {
        name: name.to_owned(),
        uses_permission_flags: Vec::new(),
        max_sdk_version: Some(max_sdk_version),
    }
}

fn merge_permission(
    permissions: &mut BTreeMap<String, QuestBrokerAndroidPermission>,
    permission: QuestBrokerAndroidPermission,
) {
    if let Some(existing) = permissions.get_mut(&permission.name) {
        let mut flags = existing
            .uses_permission_flags
            .iter()
            .chain(&permission.uses_permission_flags)
            .cloned()
            .collect::<Vec<_>>();
        flags.sort();
        flags.dedup();
        existing.uses_permission_flags = flags;
        existing.max_sdk_version = match (existing.max_sdk_version, permission.max_sdk_version) {
            (None, _) | (_, None) => None,
            (Some(left), Some(right)) => Some(left.max(right)),
        };
    } else {
        permissions.insert(permission.name.clone(), permission);
    }
}

fn render_standalone_android_manifest(
    lock: &ManifoldBrokerProductLock,
    projection: &QuestBrokerAndroidManifestProjection,
) -> String {
    let camera = has_feature(lock, &ManifoldBrokerFeature::CameraMedia);
    let service_types = if camera {
        "dataSync|camera"
    } else {
        "dataSync"
    };
    let mut xml = format!(
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    package=\"{QUEST_BROKER_PACKAGE_NAME}\">\n\n    <permission\n        android:name=\"{QUEST_BROKER_ADMISSION_PERMISSION}\"\n        android:protectionLevel=\"signature\" />\n\n"
    );
    for permission in &projection.permissions {
        let mut attributes = format!("android:name=\"{}\"", xml_escape(&permission.name));
        if !permission.uses_permission_flags.is_empty() {
            let flags = permission.uses_permission_flags.join("|");
            let _ = write!(
                attributes,
                " android:usesPermissionFlags=\"{}\"",
                xml_escape(&flags)
            );
        }
        if let Some(max_sdk) = permission.max_sdk_version {
            let _ = write!(attributes, " android:maxSdkVersion=\"{max_sdk}\"");
        }
        let _ = writeln!(xml, "    <uses-permission {attributes} />");
    }
    let _ = write!(
        xml,
        "\n    <application\n        android:allowBackup=\"false\"\n        android:hasCode=\"true\"\n        android:label=\"Rusty Manifold Broker\"\n        android:theme=\"@android:style/Theme.Material.Light.NoActionBar\"\n        android:usesCleartextTraffic=\"false\">\n        <activity\n            android:name=\".BrokerStartActivity\"\n            android:exported=\"true\"\n            android:launchMode=\"singleTask\">\n            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n        </activity>\n        <service\n            android:name=\".BrokerStartService\"\n            android:exported=\"false\"\n            android:foregroundServiceType=\"{service_types}\"\n            android:stopWithTask=\"false\" />\n        <service\n            android:name=\".ManifoldAdmissionService\"\n            android:exported=\"true\"\n            android:permission=\"{QUEST_BROKER_ADMISSION_PERMISSION}\"\n            android:stopWithTask=\"false\" />\n    </application>\n</manifest>\n"
    );
    xml
}

fn render_generated_product_config(lock: &ManifoldBrokerProductLock) -> String {
    let commands = lock
        .command_ids
        .iter()
        .map(|command| format!("\"{}\"", command.as_str()))
        .collect::<Vec<_>>()
        .join(", ");
    format!(
        "package io.github.mesmerprism.rustymanifold.broker;\n\nfinal class GeneratedBrokerProductConfig {{\n    static final String LOCK_ID = \"{}\";\n    static final String PRODUCT_ID = \"{}\";\n    static final String LOCK_FINGERPRINT = \"{}\";\n    static final boolean MEDIA_SESSION_ENABLED = {};\n    static final boolean CAMERA_MEDIA_ENABLED = {};\n    static final boolean DIRECT_P2P_ENABLED = {};\n    static final boolean BLE_RENDEZVOUS_ENABLED = {};\n    static final String[] COMMAND_IDS = new String[] {{{commands}}};\n\n    private GeneratedBrokerProductConfig() {{}}\n}}\n",
        lock.lock_id,
        lock.product_id,
        lock.spec_fingerprint,
        has_feature(lock, &ManifoldBrokerFeature::MediaSession),
        has_feature(lock, &ManifoldBrokerFeature::CameraMedia),
        has_feature(lock, &ManifoldBrokerFeature::DirectP2p),
        has_feature(lock, &ManifoldBrokerFeature::BleRendezvous),
    )
}

fn has_feature(lock: &ManifoldBrokerProductLock, feature: &ManifoldBrokerFeature) -> bool {
    lock.features.contains(feature)
}

fn pretty_json<T: Serialize>(value: &T) -> Result<String, QuestBrokerProductError> {
    serde_json::to_string_pretty(value)
        .map(|json| format!("{json}\n"))
        .map_err(|error| QuestBrokerProductError::Serialization(error.to_string()))
}

fn sha256_hex(text: &str) -> String {
    let digest = Sha256::digest(text.as_bytes());
    digest.iter().fold(String::new(), |mut output, byte| {
        let _ = write!(output, "{byte:02x}");
        output
    })
}

fn xml_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roots(
        name: &str,
    ) -> (
        ManifoldBrokerProductSpec,
        ManifoldBrokerProductLock,
        QuestBrokerAndroidManifestProjection,
    ) {
        let active = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let spec = active.join(format!(
            "rusty-manifold/fixtures/broker-product/{name}.json"
        ));
        let lock = active.join(format!(
            "rusty-manifold/fixtures/broker-product/{name}.lock.json"
        ));
        let projection = active.join(format!(
            "rusty-quest/fixtures/broker-products/{name}.manifest.json"
        ));
        (
            serde_json::from_str(&std::fs::read_to_string(spec).expect("spec")).expect("spec"),
            serde_json::from_str(&std::fs::read_to_string(lock).expect("lock")).expect("lock"),
            serde_json::from_str(&std::fs::read_to_string(projection).expect("projection"))
                .expect("projection"),
        )
    }

    #[test]
    fn committed_profile_projections_are_exact() {
        for name in [
            "base-standalone",
            "media-session-standalone",
            "camera-embedded",
            "direct-p2p-standalone",
            "ble-embedded",
            "legacy-camera-p2p-standalone",
        ] {
            let (spec, lock, projection) = roots(name);
            assert_eq!(
                validate_android_manifest_projection(&lock, &projection),
                Ok(()),
                "{name}"
            );
            if lock.standalone_enabled {
                let artifacts =
                    prepare_standalone_android_package(&spec, &lock).expect("standalone package");
                let fixture = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("../..")
                    .join(format!(
                        "fixtures/broker-products/{name}.AndroidManifest.xml"
                    ));
                assert_eq!(
                    artifacts.android_manifest_xml,
                    std::fs::read_to_string(fixture).expect("manifest fixture"),
                    "{name} actual manifest"
                );
            }
        }
    }

    #[test]
    fn base_and_generic_media_are_camera_p2p_and_ble_free() {
        let (_, base_lock, mut base) = roots("base-standalone");
        let (_, _, media) = roots("media-session-standalone");
        assert_eq!(base.permissions, media.permissions);
        let names = base
            .permissions
            .iter()
            .map(|permission| permission.name.as_str())
            .collect::<Vec<_>>();
        assert_eq!(
            names,
            vec![
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
            ]
        );
        base.permissions.push(android("android.permission.CAMERA"));
        assert_eq!(
            validate_android_manifest_projection(&base_lock, &base),
            Err(QuestBrokerProductError::ManifestProjectionMismatch)
        );
    }

    #[test]
    fn sensitive_discovery_permissions_carry_flags_and_legacy_bounds() {
        let (_, _, p2p) = roots("direct-p2p-standalone");
        let nearby = p2p
            .permissions
            .iter()
            .find(|permission| permission.name.ends_with("NEARBY_WIFI_DEVICES"))
            .expect("nearby");
        assert_eq!(nearby.uses_permission_flags, vec!["neverForLocation"]);
        let fine = p2p
            .permissions
            .iter()
            .find(|permission| permission.name.ends_with("ACCESS_FINE_LOCATION"))
            .expect("legacy location");
        assert_eq!(fine.max_sdk_version, Some(32));
        let (_, _, ble) = roots("ble-embedded");
        let scan = ble
            .permissions
            .iter()
            .find(|permission| permission.name.ends_with("BLUETOOTH_SCAN"))
            .expect("scan");
        assert_eq!(scan.uses_permission_flags, vec!["neverForLocation"]);
    }

    #[test]
    fn camera_projection_includes_quest_camera_permissions() {
        let (_, _, camera) = roots("camera-embedded");
        let names = camera
            .permissions
            .iter()
            .map(|permission| permission.name.as_str())
            .collect::<Vec<_>>();
        assert!(names.contains(&"android.permission.CAMERA"));
        assert!(names.contains(&"horizonos.permission.HEADSET_CAMERA"));
        assert!(names.contains(&"horizonos.permission.SPATIAL_CAMERA"));
    }

    #[test]
    fn package_inputs_are_lock_stamped_and_camera_free_for_generic_media() {
        let (spec, lock, _) = roots("media-session-standalone");
        let artifacts = prepare_standalone_android_package(&spec, &lock).expect("package");
        let receipt: QuestBrokerAndroidPackageInputsReceipt =
            serde_json::from_str(&artifacts.receipt_json).expect("receipt");
        let registry: QuestBrokerCommandRegistry =
            serde_json::from_str(&artifacts.command_registry_json).expect("registry");
        assert_eq!(receipt.manifold_lock_id, lock.lock_id);
        assert_eq!(receipt.manifold_lock_fingerprint, lock.spec_fingerprint);
        assert_eq!(registry.command_ids, lock.command_ids);
        assert!(receipt
            .features
            .contains(&ManifoldBrokerFeature::MediaSession));
        assert!(!receipt
            .features
            .contains(&ManifoldBrokerFeature::CameraMedia));
        assert!(!artifacts.android_manifest_xml.contains("permission.CAMERA"));
        assert!(!artifacts
            .android_manifest_xml
            .contains("NEARBY_WIFI_DEVICES"));
        assert!(artifacts
            .android_manifest_xml
            .contains("android:foregroundServiceType=\"dataSync\""));
        assert!(artifacts
            .generated_product_config_java
            .contains("CAMERA_MEDIA_ENABLED = false"));
    }

    #[test]
    fn legacy_camera_p2p_package_is_explicit_and_camera_enabled() {
        let (spec, lock, _) = roots("legacy-camera-p2p-standalone");
        let artifacts = prepare_standalone_android_package(&spec, &lock).expect("package");
        assert!(artifacts.android_manifest_xml.contains("permission.CAMERA"));
        assert!(artifacts
            .android_manifest_xml
            .contains("NEARBY_WIFI_DEVICES"));
        assert!(artifacts
            .android_manifest_xml
            .contains("android:foregroundServiceType=\"dataSync|camera\""));
        assert!(artifacts
            .generated_product_config_java
            .contains("CAMERA_MEDIA_ENABLED = true"));
    }

    #[test]
    fn standalone_start_service_is_private_and_admission_remains_signature_scoped() {
        let (spec, lock, _) = roots("media-session-standalone");
        let manifest = prepare_standalone_android_package(&spec, &lock)
            .expect("package")
            .android_manifest_xml;
        assert!(manifest.contains(
            "android:name=\".BrokerStartService\"\n            android:exported=\"false\""
        ));
        assert!(manifest.contains(
            "android:name=\".BrokerStartActivity\"\n            android:exported=\"true\""
        ));
        assert!(manifest.contains(
            "android:name=\".ManifoldAdmissionService\"\n            android:exported=\"true\"\n            android:permission=\"io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION\""
        ));
    }

    #[test]
    fn stale_or_embedded_package_inputs_fail_closed() {
        let (spec, mut lock, _) = roots("media-session-standalone");
        lock.permissions.push(ManifoldBrokerPermission::Camera);
        assert_eq!(
            prepare_standalone_android_package(&spec, &lock),
            Err(QuestBrokerProductError::InvalidManifoldLock(
                ManifoldProductError::StaleOrExpandedLock
            ))
        );
        let (embedded_spec, embedded_lock, _) = roots("camera-embedded");
        assert_eq!(
            prepare_standalone_android_package(&embedded_spec, &embedded_lock),
            Err(QuestBrokerProductError::EmbeddedProductUnsupported)
        );
    }
}
