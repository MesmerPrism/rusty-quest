//! Android manifest projection for accepted Manifold broker product locks.

use rusty_manifold_broker_product::{ManifoldBrokerPermission, ManifoldBrokerProductLock};
use rusty_manifold_model::DottedId;
use serde::{Deserialize, Serialize};

/// Quest Android manifest projection schema.
pub const QUEST_BROKER_MANIFEST_SCHEMA: &str = "rusty.quest.broker.android_manifest_projection.v1";

/// One exact Android permission projection.
#[derive(Clone, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(deny_unknown_fields)]
pub struct QuestBrokerAndroidPermission {
    /// Android permission name.
    pub name: String,
    /// Explicit `usesPermissionFlags` tokens.
    pub uses_permission_flags: Vec<String>,
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

/// Projection or audit failure.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum QuestBrokerProductError {
    /// Manifold lock has invalid runtime mode.
    InvalidRuntimeMode,
    /// Projection is stale or contains extra/missing permissions.
    ManifestProjectionMismatch,
}

/// Projects a Manifold lock into exact Android manifest permissions.
pub fn project_android_manifest(
    lock: &ManifoldBrokerProductLock,
) -> Result<QuestBrokerAndroidManifestProjection, QuestBrokerProductError> {
    let runtime_mode = match (lock.standalone_enabled, lock.embedded_enabled) {
        (true, false) => QuestBrokerRuntimeMode::Standalone,
        (false, true) => QuestBrokerRuntimeMode::Embedded,
        _ => return Err(QuestBrokerProductError::InvalidRuntimeMode),
    };
    let mut permissions = lock
        .permissions
        .iter()
        .map(android_permission)
        .collect::<Vec<_>>();
    permissions.sort();
    Ok(QuestBrokerAndroidManifestProjection {
        schema_id: QUEST_BROKER_MANIFEST_SCHEMA.to_owned(),
        product_id: lock.product_id.clone(),
        manifold_lock_fingerprint: lock.spec_fingerprint.clone(),
        runtime_mode,
        permissions,
    })
}

/// Audits a committed manifest projection against the accepted Manifold lock.
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

fn android_permission(permission: &ManifoldBrokerPermission) -> QuestBrokerAndroidPermission {
    let (name, flags) = match permission {
        ManifoldBrokerPermission::Internet => ("android.permission.INTERNET", vec![]),
        ManifoldBrokerPermission::Camera => ("android.permission.CAMERA", vec![]),
        ManifoldBrokerPermission::NearbyWifiDevices => (
            "android.permission.NEARBY_WIFI_DEVICES",
            vec!["neverForLocation"],
        ),
        ManifoldBrokerPermission::ChangeWifiState => {
            ("android.permission.CHANGE_WIFI_STATE", vec![])
        }
        ManifoldBrokerPermission::AccessWifiState => {
            ("android.permission.ACCESS_WIFI_STATE", vec![])
        }
        ManifoldBrokerPermission::BluetoothScan => (
            "android.permission.BLUETOOTH_SCAN",
            vec!["neverForLocation"],
        ),
        ManifoldBrokerPermission::BluetoothConnect => {
            ("android.permission.BLUETOOTH_CONNECT", vec![])
        }
        ManifoldBrokerPermission::BluetoothAdvertise => {
            ("android.permission.BLUETOOTH_ADVERTISE", vec![])
        }
    };
    QuestBrokerAndroidPermission {
        name: name.to_owned(),
        uses_permission_flags: flags.into_iter().map(str::to_owned).collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roots(
        name: &str,
    ) -> (
        ManifoldBrokerProductLock,
        QuestBrokerAndroidManifestProjection,
    ) {
        let active = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let lock = active.join(format!(
            "rusty-manifold/fixtures/broker-product/{name}.lock.json"
        ));
        let projection = active.join(format!(
            "rusty-quest/fixtures/broker-products/{name}.manifest.json"
        ));
        (
            serde_json::from_str(&std::fs::read_to_string(lock).expect("lock")).expect("lock"),
            serde_json::from_str(&std::fs::read_to_string(projection).expect("projection"))
                .expect("projection"),
        )
    }

    #[test]
    fn committed_profile_projections_are_exact() {
        for name in [
            "base-standalone",
            "camera-embedded",
            "direct-p2p-standalone",
            "ble-embedded",
        ] {
            let (lock, projection) = roots(name);
            assert_eq!(
                validate_android_manifest_projection(&lock, &projection),
                Ok(()),
                "{name}"
            );
        }
    }

    #[test]
    fn base_is_camera_p2p_and_ble_free_and_union_expansion_rejects() {
        let (lock, mut projection) = roots("base-standalone");
        assert_eq!(projection.permissions.len(), 1);
        assert_eq!(
            projection.permissions[0].name,
            "android.permission.INTERNET"
        );
        projection.permissions.push(QuestBrokerAndroidPermission {
            name: "android.permission.CAMERA".to_owned(),
            uses_permission_flags: vec![],
        });
        assert_eq!(
            validate_android_manifest_projection(&lock, &projection),
            Err(QuestBrokerProductError::ManifestProjectionMismatch)
        );
    }

    #[test]
    fn sensitive_discovery_permissions_carry_never_for_location() {
        let (_, p2p) = roots("direct-p2p-standalone");
        let nearby = p2p
            .permissions
            .iter()
            .find(|permission| permission.name.ends_with("NEARBY_WIFI_DEVICES"))
            .expect("nearby");
        assert_eq!(nearby.uses_permission_flags, vec!["neverForLocation"]);
        let (_, ble) = roots("ble-embedded");
        let scan = ble
            .permissions
            .iter()
            .find(|permission| permission.name.ends_with("BLUETOOTH_SCAN"))
            .expect("scan");
        assert_eq!(scan.uses_permission_flags, vec!["neverForLocation"]);
    }
}
