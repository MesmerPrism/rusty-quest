//! Quest runtime profile contracts and dry-run validation helpers.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// Quest runtime profile schema id.
pub const RUNTIME_PROFILE_SCHEMA: &str = "rusty.quest.runtime_profile.v1";

/// Android system property value limit in bytes for `setprop` values.
pub const ANDROID_PROPERTY_VALUE_MAX_BYTES: usize = 92;

const ENVIRONMENT_DEPTH_PROP_PREFIX: &str = "debug.rustyquest.native_renderer.environment_depth.";
const ENVIRONMENT_DEPTH_MODE: &str = "debug.rustyquest.native_renderer.environment_depth.mode";
const ENVIRONMENT_DEPTH_SOURCE: &str = "debug.rustyquest.native_renderer.environment_depth.source";
const ENVIRONMENT_DEPTH_LAYER_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.layer_policy";
const ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.depth_units_policy";
const ENVIRONMENT_DEPTH_DEBUG_VIEW: &str =
    "debug.rustyquest.native_renderer.environment_depth.debug_view";
const ENVIRONMENT_DEPTH_REFERENCE_SPACE: &str =
    "debug.rustyquest.native_renderer.environment_depth.reference_space";
const ENVIRONMENT_DEPTH_PARTICLE_CAPACITY: &str =
    "debug.rustyquest.native_renderer.environment_depth.particle_capacity";
const ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS: &str =
    "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels";
const ENVIRONMENT_DEPTH_NEAR_M: &str = "debug.rustyquest.native_renderer.environment_depth.near_m";
const ENVIRONMENT_DEPTH_FAR_M: &str = "debug.rustyquest.native_renderer.environment_depth.far_m";
const ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload";

/// Quest runtime profile.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeProfile {
    /// Schema id.
    pub schema: String,
    /// Stable profile id.
    pub profile_id: String,
    /// Target platform.
    pub target_platform: String,
    /// Android properties owned by this profile.
    pub owned_android_properties: Vec<String>,
    /// Properties to set after clear operations.
    pub set_properties: Vec<PropertyValue>,
    /// Expected log/readback markers.
    #[serde(default)]
    pub expected_markers: Vec<String>,
    /// Validation commands for this profile.
    #[serde(default)]
    pub validation_commands: Vec<String>,
}

/// Android property value derived from a canonical source setting.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PropertyValue {
    /// Android property name.
    pub name: String,
    /// String property value.
    pub value: String,
    /// Canonical setting id that produced this property.
    pub source_setting_id: String,
}

/// Dry-run write plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PropertyWritePlan {
    /// Schema id.
    pub schema: String,
    /// Source profile id.
    pub profile_id: String,
    /// Operations in deterministic order.
    pub operations: Vec<PropertyOperation>,
}

/// One property operation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PropertyOperation {
    /// Operation kind: clear or set.
    pub kind: String,
    /// Android property name.
    pub name: String,
    /// Optional value.
    pub value: Option<String>,
    /// Optional canonical source setting.
    pub source_setting_id: Option<String>,
}

/// Validation failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError {
    /// Human-readable message.
    pub message: String,
}

impl ValidationError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl std::fmt::Display for ValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ValidationError {}

/// Validate a Quest runtime profile.
pub fn validate_runtime_profile(profile: &RuntimeProfile) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if profile.schema != RUNTIME_PROFILE_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported runtime profile schema {}",
            profile.schema
        )));
    }
    if profile.profile_id.trim().is_empty() {
        errors.push(ValidationError::new("profile_id must not be empty"));
    }
    if profile.target_platform != "quest" {
        errors.push(ValidationError::new(format!(
            "unsupported target_platform {}",
            profile.target_platform
        )));
    }

    let mut owned = BTreeSet::new();
    for property in &profile.owned_android_properties {
        validate_property_name(property, "owned_android_properties", &mut errors);
        if !owned.insert(property.clone()) {
            errors.push(ValidationError::new(format!(
                "duplicate owned Android property {}",
                property
            )));
        }
    }

    let owned_lookup: BTreeSet<&str> = profile
        .owned_android_properties
        .iter()
        .map(String::as_str)
        .collect();
    let mut set_properties: BTreeMap<&str, &PropertyValue> = BTreeMap::new();
    for property in &profile.set_properties {
        validate_property_name(&property.name, "set_properties", &mut errors);
        if !owned_lookup.contains(property.name.as_str()) {
            errors.push(ValidationError::new(format!(
                "set property {} is not declared as profile-owned",
                property.name
            )));
        }
        if property.source_setting_id.trim().is_empty() {
            errors.push(ValidationError::new(format!(
                "set property {} must declare source_setting_id",
                property.name
            )));
        }
        let value_bytes = property.value.as_bytes().len();
        if value_bytes > ANDROID_PROPERTY_VALUE_MAX_BYTES {
            errors.push(ValidationError::new(format!(
                "set property {} value is {value_bytes} bytes, above Android setprop limit {ANDROID_PROPERTY_VALUE_MAX_BYTES}",
                property.name
            )));
        }
        if set_properties
            .insert(property.name.as_str(), property)
            .is_some()
        {
            errors.push(ValidationError::new(format!(
                "duplicate set property {}",
                property.name
            )));
        }
    }
    validate_environment_depth_profile(&set_properties, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_environment_depth_profile(
    set_properties: &BTreeMap<&str, &PropertyValue>,
    errors: &mut Vec<ValidationError>,
) {
    for property in set_properties.values() {
        if property.name.starts_with(ENVIRONMENT_DEPTH_PROP_PREFIX) {
            validate_environment_depth_property(property, errors);
        }
    }

    let near_m = environment_depth_f32(set_properties, ENVIRONMENT_DEPTH_NEAR_M, errors);
    let far_m = environment_depth_f32(set_properties, ENVIRONMENT_DEPTH_FAR_M, errors);
    if let (Some(near_m), Some(far_m)) = (near_m, far_m) {
        if near_m <= 0.0 {
            errors.push(ValidationError::new(
                "environment depth near_m must be greater than 0",
            ));
        }
        if far_m <= near_m {
            errors.push(ValidationError::new(format!(
                "environment depth far_m {far_m} must be greater than near_m {near_m}",
            )));
        }
    }
}

fn validate_environment_depth_property(
    property: &PropertyValue,
    errors: &mut Vec<ValidationError>,
) {
    match property.name.as_str() {
        ENVIRONMENT_DEPTH_MODE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "disabled"
                    | "off"
                    | "status"
                    | "status-only"
                    | "provider-status"
                    | "retained-particles"
                    | "retained-particle-map"
                    | "scene-particle-map"
                    | "scene-map"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth mode {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_SOURCE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "runtime-provider"
                    | "provider"
                    | "xr-meta-environment-depth"
                    | "meta-environment-depth"
                    | "meta-provider"
                    | "synthetic-gpu-proof"
                    | "synthetic-proof"
                    | "synthetic-depth-grid"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth source {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_LAYER_POLICY => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "mono-layer0"
                    | "layer0"
                    | "view0"
                    | "left"
                    | "mono-layer1"
                    | "layer1"
                    | "view1"
                    | "right"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth layer_policy {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "projected-depth-from-near-far" | "projected-near-far" | "near-far-projection"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth depth_units_policy {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_DEBUG_VIEW => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "normal"
                    | "off"
                    | "disabled"
                    | "raw-d16"
                    | "raw-depth"
                    | "debug-raw-d16"
                    | "confidence"
                    | "debug-confidence"
                    | "confidence-filter"
                    | "age"
                    | "particle-age"
                    | "cell-age"
                    | "debug-age"
                    | "source-layer"
                    | "source-layer-mask"
                    | "layer"
                    | "debug-source-layer"
                    | "hash-probe"
                    | "probe"
                    | "hash"
                    | "debug-hash-probe"
                    | "free-space-state"
                    | "free-space"
                    | "retired-state"
                    | "debug-free-space-state"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth debug_view {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_REFERENCE_SPACE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "local" | "stage" | "openxr-local" | "openxr-stage"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth reference_space {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_PARTICLE_CAPACITY => {
            validate_environment_depth_u32(property, 64, 262_144, errors);
        }
        ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS => {
            validate_environment_depth_u32(property, 1, 128, errors);
        }
        ENVIRONMENT_DEPTH_NEAR_M | ENVIRONMENT_DEPTH_FAR_M => {
            validate_environment_depth_f32(property, errors);
        }
        ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD => {
            let normalized = normalized_value(&property.value);
            let valid_false = matches!(normalized.as_str(), "0" | "false" | "no" | "off");
            if !valid_false {
                errors.push(ValidationError::new(
                    "environment depth high_rate_json_payload must be false",
                ));
            }
        }
        _ => errors.push(ValidationError::new(format!(
            "unknown environment depth property {}",
            property.name
        ))),
    }
}

fn validate_environment_depth_u32(
    property: &PropertyValue,
    min_value: u32,
    max_value: u32,
    errors: &mut Vec<ValidationError>,
) {
    match property.value.trim().parse::<u32>() {
        Ok(value) if value >= min_value && value <= max_value => {}
        Ok(value) => errors.push(ValidationError::new(format!(
            "{} value {value} must be between {min_value} and {max_value}",
            property.name
        ))),
        Err(_) => errors.push(ValidationError::new(format!(
            "{} value {} must be an integer",
            property.name, property.value
        ))),
    }
}

fn validate_environment_depth_f32(property: &PropertyValue, errors: &mut Vec<ValidationError>) {
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() => {}
        _ => errors.push(ValidationError::new(format!(
            "{} value {} must be a finite number",
            property.name, property.value
        ))),
    }
}

fn environment_depth_f32(
    set_properties: &BTreeMap<&str, &PropertyValue>,
    name: &str,
    errors: &mut Vec<ValidationError>,
) -> Option<f32> {
    let property = set_properties.get(name)?;
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() => Some(value),
        _ => {
            errors.push(ValidationError::new(format!(
                "{} value {} must be a finite number",
                property.name, property.value
            )));
            None
        }
    }
}

fn normalized_value(value: &str) -> String {
    value.trim().to_ascii_lowercase().replace('_', "-")
}

/// Build a deterministic clear-then-set dry-run plan.
pub fn build_write_plan(
    profile: &RuntimeProfile,
) -> Result<PropertyWritePlan, Vec<ValidationError>> {
    validate_runtime_profile(profile)?;
    let mut operations = Vec::new();
    for name in &profile.owned_android_properties {
        operations.push(PropertyOperation {
            kind: "clear".to_string(),
            name: name.clone(),
            value: Some(" ".to_string()),
            source_setting_id: None,
        });
    }
    for property in &profile.set_properties {
        operations.push(PropertyOperation {
            kind: "set".to_string(),
            name: property.name.clone(),
            value: Some(property.value.clone()),
            source_setting_id: Some(property.source_setting_id.clone()),
        });
    }
    Ok(PropertyWritePlan {
        schema: "rusty.quest.property_write_plan.v1".to_string(),
        profile_id: profile.profile_id.clone(),
        operations,
    })
}

fn validate_property_name(property: &str, label: &str, errors: &mut Vec<ValidationError>) {
    if property.trim().is_empty() {
        errors.push(ValidationError::new(format!(
            "{label} contains an empty property name"
        )));
    }
    if property.contains("rustyxr") || property.contains("rusty.xr") {
        errors.push(ValidationError::new(format!(
            "{label} contains legacy property name {}",
            property
        )));
    }
    if !property.starts_with("debug.rustyquest.") {
        errors.push(ValidationError::new(format!(
            "{label} property {} must use debug.rustyquest.*",
            property
        )));
    }
}

#[cfg(test)]
mod tests {
    use super::{build_write_plan, validate_runtime_profile, RuntimeProfile};

    fn valid_profile() -> RuntimeProfile {
        serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-makepad-mesh-replay.profile.json"
        ))
        .expect("valid profile JSON")
    }

    #[test]
    fn valid_runtime_profile_builds_write_plan() {
        let profile = valid_profile();
        validate_runtime_profile(&profile).expect("profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert_eq!(plan.operations.len(), 6);
        assert_eq!(plan.operations[0].kind, "clear");
        assert_eq!(plan.operations[3].kind, "set");
    }

    #[test]
    fn duplicate_owned_property_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/duplicate-owned-property.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject duplicate");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("duplicate owned")));
    }

    #[test]
    fn legacy_property_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/legacy-property.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject legacy");
        assert!(errors.iter().any(|error| error.message.contains("legacy")));
    }

    #[test]
    fn environment_depth_status_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-environment-depth-status.profile.json"
        ))
        .expect("environment depth status profile JSON");
        validate_runtime_profile(&profile).expect("environment depth profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == "debug.rustyquest.native_renderer.environment_depth.mode"
                && operation.value.as_deref() == Some("status-only")
        }));
    }

    #[test]
    fn environment_depth_native_passthrough_particle_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-environment-depth-particles.profile.json"
        ))
        .expect("environment depth native passthrough particle profile JSON");
        validate_runtime_profile(&profile).expect("environment depth particle profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == "debug.rustyquest.native_renderer.environment_depth.source"
                && operation.value.as_deref() == Some("synthetic-gpu-proof")
        }));
    }

    #[test]
    fn environment_depth_meta_layer1_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-layer1.profile.json"
        ))
        .expect("environment depth Meta layer-1 profile JSON");
        validate_runtime_profile(&profile).expect("environment depth layer-1 profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == "debug.rustyquest.native_renderer.environment_depth.layer_policy"
                && operation.value.as_deref() == Some("mono-layer1")
        }));
    }

    #[test]
    fn environment_depth_meta_low_capacity_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-low-capacity.profile.json"
        ))
        .expect("environment depth Meta low-capacity profile JSON");
        validate_runtime_profile(&profile)
            .expect("environment depth low-capacity profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == "debug.rustyquest.native_renderer.environment_depth.particle_capacity"
                && operation.value.as_deref() == Some("64")
        }));
        assert!(plan.operations.iter().any(|operation| {
            operation.name
                == "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels"
                && operation.value.as_deref() == Some("4")
        }));
    }

    #[test]
    fn environment_depth_meta_debug_colors_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-meta-environment-depth-particles-debug-colors.profile.json"
        ))
        .expect("environment depth Meta debug-colors profile JSON");
        validate_runtime_profile(&profile)
            .expect("environment depth debug-colors profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == "debug.rustyquest.native_renderer.environment_depth.debug_view"
                && operation.value.as_deref() == Some("free-space-state")
        }));
    }

    #[test]
    fn environment_depth_high_rate_json_payload_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-high-rate-json.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject high-rate JSON payload");
        assert!(errors.iter().any(|error| error
            .message
            .contains("high_rate_json_payload must be false")));
    }

    #[test]
    fn environment_depth_invalid_range_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-invalid-range.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject invalid range");
        assert!(errors.iter().any(|error| error
            .message
            .contains("far_m 1 must be greater than near_m 2")));
    }

    #[test]
    fn environment_depth_invalid_capacity_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-invalid-capacity.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject invalid capacity");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("particle_capacity value 0")));
    }

    #[test]
    fn environment_depth_invalid_depth_units_policy_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-invalid-depth-units-policy.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject invalid depth units policy");
        assert!(errors.iter().any(|error| error
            .message
            .contains("depth_units_policy metric-axial-meters is not supported")));
    }

    #[test]
    fn oversized_property_value_is_rejected() {
        let mut damaged = valid_profile();
        damaged.set_properties[0].value = "x".repeat(93);

        let errors = validate_runtime_profile(&damaged).expect_err("must reject oversized value");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("above Android setprop limit")));
    }
}
