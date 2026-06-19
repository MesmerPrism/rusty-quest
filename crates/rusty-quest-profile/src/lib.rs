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
const ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED: &str =
    "debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled";
const ENVIRONMENT_DEPTH_PARTICLE_CAPACITY: &str =
    "debug.rustyquest.native_renderer.environment_depth.particle_capacity";
const ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS: &str =
    "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels";
const ENVIRONMENT_DEPTH_NEAR_M: &str = "debug.rustyquest.native_renderer.environment_depth.near_m";
const ENVIRONMENT_DEPTH_FAR_M: &str = "debug.rustyquest.native_renderer.environment_depth.far_m";
const ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload";
const ENVIRONMENT_DEPTH_SURFACE_MODEL: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_model";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.radius_cells";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_observations";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay";
const STIMULUS_VOLUME_PROP_PREFIX: &str = "debug.rustyquest.native_renderer.stimulus_volume.";
const STIMULUS_VOLUME_ENABLED: &str = "debug.rustyquest.native_renderer.stimulus_volume.enabled";
const STIMULUS_VOLUME_PROFILE: &str = "debug.rustyquest.native_renderer.stimulus_volume.profile";
const STIMULUS_VOLUME_COMPOSITION: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.composition";
const STIMULUS_VOLUME_RENDER_TARGET: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.render_target";
const STIMULUS_VOLUME_RAYMARCH_SAMPLES: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples";
const STIMULUS_VOLUME_RANDOMIZE_ENABLED: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled";
const STIMULUS_VOLUME_RANDOMIZE_MIN_HZ: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.min_hz";
const STIMULUS_VOLUME_RANDOMIZE_MAX_HZ: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.max_hz";
const STIMULUS_VOLUME_SAFETY_ACK: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.safety_ack";
const NATIVE_PROJECTION_TARGET_PROP_PREFIX: &str =
    "debug.rustyquest.native_renderer.projection.target.";
const NATIVE_PROJECTION_TARGET_CONTROLS: &str =
    "debug.rustyquest.native_renderer.projection.target.controls";
const NATIVE_PROJECTION_TARGET_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.scale";
const NATIVE_PROJECTION_TARGET_TUNED_MAX_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.tuned.max.scale";
const NATIVE_PROJECTION_TARGET_MIN_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.min.scale";
const NATIVE_PROJECTION_TARGET_MAX_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.target.max.scale";
const NATIVE_PROJECTION_TARGET_OFFSET_X_UV: &str =
    "debug.rustyquest.native_renderer.projection.target.offset.x.uv";
const NATIVE_PROJECTION_TARGET_OFFSET_Y_UV: &str =
    "debug.rustyquest.native_renderer.projection.target.offset.y.uv";
const NATIVE_PROJECTION_TARGET_JOYSTICK_CONTROLS: &str =
    "debug.rustyquest.native_renderer.projection.target.joystick.controls";
const NATIVE_PROJECTION_TARGET_JOYSTICK_RATE: &str =
    "debug.rustyquest.native_renderer.projection.target.joystick.scale.rate_per_second";
const NATIVE_PROJECTION_TARGET_BREATH_MODE: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.bridge.mode";
const NATIVE_PROJECTION_TARGET_BREATH_STATE_STREAM: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.state.stream";
const NATIVE_PROJECTION_TARGET_BREATH_VALUE_STREAM: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.value.stream";
const NATIVE_PROJECTION_TARGET_BREATH_INHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.inhale.seconds.min_to_max";
const NATIVE_PROJECTION_TARGET_BREATH_EXHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.exhale.seconds.max_to_min";
const NATIVE_PROJECTION_TARGET_BREATH_SYNTHETIC_PERIOD_SECONDS: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.synthetic.period.seconds";
const NATIVE_PROJECTION_TARGET_BREATH_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.projection.target.breath.high_rate_json_payload";
const NATIVE_MANIFOLD_BROKER_PROP_PREFIX: &str = "debug.rustyquest.native_renderer.manifold.";
const NATIVE_MANIFOLD_BROKER_HOST: &str = "debug.rustyquest.native_renderer.manifold.broker.host";
const NATIVE_MANIFOLD_BROKER_PORT: &str = "debug.rustyquest.native_renderer.manifold.broker.port";
const NATIVE_MANIFOLD_BROKER_PATH: &str = "debug.rustyquest.native_renderer.manifold.broker.path";
const MAKEPAD_PROP_PREFIX: &str = "debug.rustyquest.makepad.";

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
    validate_stimulus_volume_profile(&set_properties, &mut errors);
    validate_native_projection_target_profile(profile, &owned_lookup, &set_properties, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_native_projection_target_profile(
    profile: &RuntimeProfile,
    owned_properties: &BTreeSet<&str>,
    set_properties: &BTreeMap<&str, &PropertyValue>,
    errors: &mut Vec<ValidationError>,
) {
    let native_projection_target_profile = normalized_value(&profile.profile_id)
        .contains("breathing-room")
        || owned_properties
            .iter()
            .any(|property| property.starts_with(NATIVE_PROJECTION_TARGET_PROP_PREFIX))
        || set_properties
            .keys()
            .any(|property| property.starts_with(NATIVE_PROJECTION_TARGET_PROP_PREFIX));
    if !native_projection_target_profile {
        return;
    }

    for property in owned_properties {
        if property.starts_with(MAKEPAD_PROP_PREFIX) {
            errors.push(ValidationError::new(format!(
                "native projection target profile must not own Makepad property {}",
                property
            )));
        }
    }
    for property in set_properties.values() {
        if property.name.starts_with(MAKEPAD_PROP_PREFIX) {
            errors.push(ValidationError::new(format!(
                "native projection target profile must not set Makepad property {}",
                property.name
            )));
        }
        if property
            .name
            .starts_with(NATIVE_PROJECTION_TARGET_PROP_PREFIX)
            || property
                .name
                .starts_with(NATIVE_MANIFOLD_BROKER_PROP_PREFIX)
        {
            validate_native_projection_target_property(property, errors);
        }
    }
}

fn validate_native_projection_target_property(
    property: &PropertyValue,
    errors: &mut Vec<ValidationError>,
) {
    match property.name.as_str() {
        NATIVE_PROJECTION_TARGET_CONTROLS
        | NATIVE_PROJECTION_TARGET_JOYSTICK_CONTROLS
        | NATIVE_PROJECTION_TARGET_BREATH_HIGH_RATE_JSON_PAYLOAD => {
            validate_bool(property, errors);
            if property.name == NATIVE_PROJECTION_TARGET_BREATH_HIGH_RATE_JSON_PAYLOAD
                && normalized_bool_true(&property.value)
            {
                errors.push(ValidationError::new(
                    "native projection target breath high_rate_json_payload must be false",
                ));
            }
        }
        NATIVE_PROJECTION_TARGET_SCALE
        | NATIVE_PROJECTION_TARGET_TUNED_MAX_SCALE
        | NATIVE_PROJECTION_TARGET_MIN_SCALE
        | NATIVE_PROJECTION_TARGET_MAX_SCALE
        | NATIVE_PROJECTION_TARGET_OFFSET_X_UV
        | NATIVE_PROJECTION_TARGET_OFFSET_Y_UV
        | NATIVE_PROJECTION_TARGET_JOYSTICK_RATE
        | NATIVE_PROJECTION_TARGET_BREATH_INHALE_SECONDS
        | NATIVE_PROJECTION_TARGET_BREATH_EXHALE_SECONDS
        | NATIVE_PROJECTION_TARGET_BREATH_SYNTHETIC_PERIOD_SECONDS => {
            validate_native_projection_target_f32(property, errors);
        }
        NATIVE_PROJECTION_TARGET_BREATH_MODE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "disabled"
                    | "off"
                    | "manifold-state"
                    | "pmb-state"
                    | "manifold-state-value"
                    | "pmb-state-value"
                    | "synthetic"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "native projection target breath bridge mode {} is not supported",
                    property.value
                )));
            }
        }
        NATIVE_PROJECTION_TARGET_BREATH_STATE_STREAM
        | NATIVE_PROJECTION_TARGET_BREATH_VALUE_STREAM
        | NATIVE_MANIFOLD_BROKER_HOST
        | NATIVE_MANIFOLD_BROKER_PATH => {
            if property.value.trim().is_empty() {
                errors.push(ValidationError::new(format!(
                    "{} value must not be empty",
                    property.name
                )));
            }
        }
        NATIVE_MANIFOLD_BROKER_PORT => match property.value.trim().parse::<u16>() {
            Ok(value) if value > 0 => {}
            _ => errors.push(ValidationError::new(format!(
                "{} value {} must be a TCP port",
                property.name, property.value
            ))),
        },
        _ => errors.push(ValidationError::new(format!(
            "unknown native projection target property {}",
            property.name
        ))),
    }
}

fn validate_native_projection_target_f32(
    property: &PropertyValue,
    errors: &mut Vec<ValidationError>,
) {
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() => {}
        _ => errors.push(ValidationError::new(format!(
            "{} value {} must be a finite number",
            property.name, property.value
        ))),
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

fn validate_stimulus_volume_profile(
    set_properties: &BTreeMap<&str, &PropertyValue>,
    errors: &mut Vec<ValidationError>,
) {
    for property in set_properties.values() {
        if property.name.starts_with(STIMULUS_VOLUME_PROP_PREFIX) {
            validate_stimulus_volume_property(property, errors);
        }
    }

    let min_hz = stimulus_volume_f32(set_properties, STIMULUS_VOLUME_RANDOMIZE_MIN_HZ, errors);
    let max_hz = stimulus_volume_f32(set_properties, STIMULUS_VOLUME_RANDOMIZE_MAX_HZ, errors);
    if let (Some(min_hz), Some(max_hz)) = (min_hz, max_hz) {
        if min_hz < 0.0 {
            errors.push(ValidationError::new(
                "stimulus volume randomize min_hz must be greater than or equal to 0",
            ));
        }
        if max_hz > 15.0 {
            errors.push(ValidationError::new(
                "stimulus volume randomize max_hz must be less than or equal to 15",
            ));
        }
        if min_hz > max_hz {
            errors.push(ValidationError::new(format!(
                "stimulus volume randomize min_hz {min_hz} must be less than or equal to max_hz {max_hz}",
            )));
        }
    }

    if set_properties
        .get(STIMULUS_VOLUME_ENABLED)
        .is_some_and(|property| normalized_bool_true(&property.value))
    {
        let safety_acknowledged = set_properties
            .get(STIMULUS_VOLUME_SAFETY_ACK)
            .is_some_and(|property| normalized_bool_true(&property.value));
        if !safety_acknowledged {
            errors.push(ValidationError::new(
                "stimulus volume safety_ack must be true when stimulus_volume.enabled is true",
            ));
        }
    }
}

fn validate_stimulus_volume_property(property: &PropertyValue, errors: &mut Vec<ValidationError>) {
    match property.name.as_str() {
        STIMULUS_VOLUME_ENABLED
        | STIMULUS_VOLUME_RANDOMIZE_ENABLED
        | STIMULUS_VOLUME_SAFETY_ACK => {
            validate_bool(property, errors);
        }
        STIMULUS_VOLUME_PROFILE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "volume-only-bright-interference"
                    | "stimulus.profile.volume-only-bright-interference"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "stimulus volume profile {} is not supported",
                    property.value
                )));
            }
        }
        STIMULUS_VOLUME_COMPOSITION => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "opaque-black-projection" | "alpha-over-native-passthrough"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "stimulus volume composition {} is not supported",
                    property.value
                )));
            }
        }
        STIMULUS_VOLUME_RENDER_TARGET => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "512x512x2-rgba16f" | "512x512x2-rgba8-unorm" | "512x512x2-rgba8"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "stimulus volume render_target {} is not supported",
                    property.value
                )));
            }
        }
        STIMULUS_VOLUME_RAYMARCH_SAMPLES => {
            validate_stimulus_volume_u32(property, 1, 24, errors);
        }
        STIMULUS_VOLUME_RANDOMIZE_MIN_HZ | STIMULUS_VOLUME_RANDOMIZE_MAX_HZ => {
            validate_stimulus_volume_f32(property, errors);
        }
        _ => errors.push(ValidationError::new(format!(
            "unknown stimulus volume property {}",
            property.name
        ))),
    }
}

fn validate_stimulus_volume_u32(
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

fn validate_stimulus_volume_f32(property: &PropertyValue, errors: &mut Vec<ValidationError>) {
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() => {}
        _ => errors.push(ValidationError::new(format!(
            "{} value {} must be a finite number",
            property.name, property.value
        ))),
    }
}

fn stimulus_volume_f32(
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

fn validate_bool(property: &PropertyValue, errors: &mut Vec<ValidationError>) {
    let normalized = normalized_value(&property.value);
    let valid = matches!(
        normalized.as_str(),
        "0" | "1" | "true" | "false" | "yes" | "no" | "on" | "off"
    );
    if !valid {
        errors.push(ValidationError::new(format!(
            "{} value {} must be boolean",
            property.name, property.value
        )));
    }
}

fn normalized_bool_true(value: &str) -> bool {
    matches!(
        normalized_value(value).as_str(),
        "1" | "true" | "yes" | "on"
    )
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
                    | "surface-support"
                    | "surface"
                    | "support"
                    | "debug-surface-support"
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
        ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED => {
            validate_environment_depth_bool(property, errors);
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
        ENVIRONMENT_DEPTH_SURFACE_MODEL => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "particles"
                    | "particle-cloud"
                    | "legacy-particles"
                    | "local-surfels"
                    | "local-surfels-candidates"
                    | "local"
                    | "global-surfaces"
                    | "confirmed-surfaces"
                    | "global"
                    | "hybrid"
                    | "hybrid-surfaces"
                    | "local-and-global"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_model {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS => {
            validate_environment_depth_u32(property, 1, 8, errors);
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS => {
            validate_environment_depth_u32(property, 0, 26, errors);
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS => {
            validate_environment_depth_u32(property, 1, 64, errors);
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS => {
            validate_environment_depth_u32(property, 1, 2, errors);
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS => {
            validate_environment_depth_u32(property, 1, 4096, errors);
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "off" | "loose" | "low" | "strict" | "high"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_support.normal_coherence {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(normalized.as_str(), "soft" | "hard" | "immediate");
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_support.free_space_decay {} is not supported",
                    property.value
                )));
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

fn validate_environment_depth_bool(property: &PropertyValue, errors: &mut Vec<ValidationError>) {
    let normalized = normalized_value(&property.value);
    if !matches!(
        normalized.as_str(),
        "0" | "1" | "false" | "true" | "no" | "yes" | "off" | "on"
    ) {
        errors.push(ValidationError::new(format!(
            "{} value {} must be boolean",
            property.name, property.value
        )));
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

    fn assert_plan_sets(profile_text: &str, profile_id: &str, property_name: &str, value: &str) {
        let profile: RuntimeProfile =
            serde_json::from_str(profile_text).expect("runtime profile JSON");
        assert_eq!(profile.profile_id, profile_id);
        validate_runtime_profile(&profile).expect("environment depth matrix profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name == property_name && operation.value.as_deref() == Some(value)
        }));
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
    fn environment_depth_iteration8_profile_matrix_validates() {
        for (profile_text, profile_id, property_name, value) in [
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-layer0.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_layer0",
                "debug.rustyquest.native_renderer.environment_depth.layer_policy",
                "mono-layer0",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-layer1.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_layer1",
                "debug.rustyquest.native_renderer.environment_depth.layer_policy",
                "mono-layer1",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-raw-depth-debug.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_raw_depth_debug",
                "debug.rustyquest.native_renderer.environment_depth.debug_view",
                "raw-d16",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-local-space.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_local_space",
                "debug.rustyquest.native_renderer.environment_depth.reference_space",
                "openxr-local",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-stage-space.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_stage_space",
                "debug.rustyquest.native_renderer.environment_depth.reference_space",
                "openxr-stage",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-capacity-65536.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_capacity_65536",
                "debug.rustyquest.native_renderer.environment_depth.particle_capacity",
                "65536",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-stride-8.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_stride_8",
                "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels",
                "8",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-hand-removal.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_hand_removal",
                "debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled",
                "true",
            ),
        ] {
            assert_plan_sets(profile_text, profile_id, property_name, value);
        }
    }

    #[test]
    fn environment_depth_surface_support_profile_matrix_validates() {
        for (profile_text, profile_id, property_name, value) in [
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-local-surfels.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_local_surfels",
                "debug.rustyquest.native_renderer.environment_depth.surface_model",
                "local-surfels",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-global-surfaces.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_global_surfaces",
                "debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors",
                "4",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-hybrid-surfaces.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_hybrid_surfaces",
                "debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells",
                "16",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-envdepth-source-layer-agreement.profile.json"
                ),
                "profile.quest.native_renderer.envdepth_source_layer_agreement",
                "debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers",
                "2",
            ),
        ] {
            assert_plan_sets(profile_text, profile_id, property_name, value);
        }
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
    fn environment_depth_invalid_surface_support_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-invalid-surface-support.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject invalid surface support");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("min_neighbors value 99")));
    }

    #[test]
    fn stimulus_volume_profiles_validate() {
        for (profile_json, expected_mode) in [
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume.profile.json"
                ),
                "solid-black-stimulus-volume",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-stimulus-volume.profile.json"
                ),
                "native-passthrough-stimulus-volume",
            ),
        ] {
            let profile: RuntimeProfile =
                serde_json::from_str(profile_json).expect("stimulus volume profile JSON");
            validate_runtime_profile(&profile).expect("stimulus volume profile validates");
            let plan = build_write_plan(&profile).expect("write plan");
            assert!(plan.operations.iter().any(|operation| {
                operation.name == "debug.rustyquest.native_renderer.render.mode"
                    && operation.value.as_deref() == Some(expected_mode)
            }));
            assert!(plan.operations.iter().any(|operation| {
                operation.name == "debug.rustyquest.native_renderer.stimulus_volume.safety_ack"
                    && operation.value.as_deref() == Some("true")
            }));
        }
    }

    #[test]
    fn stimulus_volume_invalid_randomize_range_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-stimulus-volume-invalid-randomize-range.profile.json"
        ))
        .expect("damaged stimulus profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject invalid randomize range");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("randomize max_hz must be less")));
    }

    #[test]
    fn stimulus_volume_missing_safety_ack_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-stimulus-volume-missing-safety-ack.profile.json"
        ))
        .expect("damaged stimulus profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject missing safety ack");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("safety_ack must be true")));
    }

    #[test]
    fn native_breathing_room_profile_validates() {
        let profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-breathing-room-pmb-scale.profile.json"
        ))
        .expect("native breathing room profile JSON");
        validate_runtime_profile(&profile).expect("native breathing room profile validates");
        let plan = build_write_plan(&profile).expect("write plan");
        assert!(plan.operations.iter().any(|operation| {
            operation.name
                == "debug.rustyquest.native_renderer.projection.target.breath.bridge.mode"
                && operation.value.as_deref() == Some("manifold-state")
        }));
        for (name, value) in [
            (
                "debug.rustyquest.native_renderer.guide.resolution",
                "camera-native",
            ),
            (
                "debug.rustyquest.native_renderer.camera.ycbcr.mode",
                "forced-bt601-narrow",
            ),
            (
                "debug.rustyquest.native_renderer.camera.resolution",
                "1280x1280",
            ),
            (
                "debug.rustyquest.native_renderer.swapchain.color_format",
                "unorm",
            ),
        ] {
            assert!(
                plan.operations.iter().any(|operation| {
                    operation.name == name && operation.value.as_deref() == Some(value)
                }),
                "native breathing room profile should set {name}={value}"
            );
        }
        assert!(!plan
            .operations
            .iter()
            .any(|operation| operation.name.starts_with("debug.rustyquest.makepad.")));
    }

    #[test]
    fn native_breathing_room_makepad_property_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-breathing-room-makepad-property.profile.json"
        ))
        .expect("damaged native breathing room profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject Makepad property");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("must not set Makepad property")));
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
