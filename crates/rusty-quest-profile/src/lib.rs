//! Quest runtime profile contracts and dry-run validation helpers.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// Quest runtime profile schema id.
pub const RUNTIME_PROFILE_SCHEMA: &str = "rusty.quest.runtime_profile.v1";

/// Android system property value limit in bytes for `setprop` values.
pub const ANDROID_PROPERTY_VALUE_MAX_BYTES: usize = 92;

const NATIVE_RENDERER_PROP_PREFIX: &str = "debug.rustyquest.native_renderer.";
const NATIVE_RENDERER_PROPERTY_MANIFEST_SCHEMA: &str =
    "rusty.quest.native_renderer_property_manifest.v2";
const NATIVE_RENDERER_PROPERTY_MANIFEST_JSON: &str =
    include_str!("../../../fixtures/native-renderer/native-renderer-property-manifest.json");
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
const ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED: &str =
    "debug.rustyquest.native_renderer.environment_depth.native_passthrough.required";
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
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.component_mode";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_source";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence";
const ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.small_component_policy";
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
const STIMULUS_VOLUME_CENTRAL_FOV_FRACTION: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction";
const STIMULUS_VOLUME_GRADIENT_SMOOTHING: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.gradient_smoothing";
const STIMULUS_VOLUME_PATTERN_FAMILY: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.pattern_family";
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

#[derive(Debug, Deserialize)]
struct NativeRendererPropertyManifest {
    schema: String,
    prefix: String,
    property_count: usize,
    properties: Vec<NativeRendererPropertyManifestEntry>,
}

#[derive(Debug, Deserialize)]
struct NativeRendererPropertyManifestEntry {
    name: String,
    lifecycle: String,
    clear_behavior: String,
    default_behavior: String,
    value_kind: String,
    #[serde(default)]
    allowed_values: Vec<String>,
    #[serde(default)]
    range: Option<NativeRendererManifestRange>,
    #[serde(default)]
    non_empty: bool,
}

#[derive(Debug, Deserialize)]
struct NativeRendererManifestRange {
    min: f64,
    max: f64,
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
    validate_native_renderer_profile_against_manifest(profile, &set_properties, &mut errors);
    validate_environment_depth_profile(&set_properties, &mut errors);
    validate_stimulus_volume_profile(&set_properties, &mut errors);
    validate_native_projection_target_profile(profile, &owned_lookup, &set_properties, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_native_renderer_profile_against_manifest(
    profile: &RuntimeProfile,
    set_properties: &BTreeMap<&str, &PropertyValue>,
    errors: &mut Vec<ValidationError>,
) {
    let Some(manifest) = native_renderer_property_manifest(errors) else {
        return;
    };
    let manifest_by_name: BTreeMap<&str, &NativeRendererPropertyManifestEntry> = manifest
        .properties
        .iter()
        .map(|entry| (entry.name.as_str(), entry))
        .collect();

    for property in &profile.owned_android_properties {
        if property.starts_with(NATIVE_RENDERER_PROP_PREFIX)
            && !manifest_by_name.contains_key(property.as_str())
        {
            errors.push(ValidationError::new(format!(
                "native renderer owned property {} is missing from property manifest",
                property
            )));
        }
    }

    for property in set_properties.values() {
        if !property.name.starts_with(NATIVE_RENDERER_PROP_PREFIX) {
            continue;
        }
        match manifest_by_name.get(property.name.as_str()) {
            Some(entry) => validate_native_renderer_manifest_value(property, entry, errors),
            None => errors.push(ValidationError::new(format!(
                "native renderer set property {} is missing from property manifest",
                property.name
            ))),
        }
    }
}

fn native_renderer_property_manifest(
    errors: &mut Vec<ValidationError>,
) -> Option<NativeRendererPropertyManifest> {
    let manifest: NativeRendererPropertyManifest =
        match serde_json::from_str(NATIVE_RENDERER_PROPERTY_MANIFEST_JSON) {
            Ok(manifest) => manifest,
            Err(error) => {
                errors.push(ValidationError::new(format!(
                    "native renderer property manifest must parse as JSON: {error}"
                )));
                return None;
            }
        };

    if manifest.schema != NATIVE_RENDERER_PROPERTY_MANIFEST_SCHEMA {
        errors.push(ValidationError::new(format!(
            "native renderer property manifest schema {} is not supported",
            manifest.schema
        )));
    }
    if manifest.prefix != NATIVE_RENDERER_PROP_PREFIX {
        errors.push(ValidationError::new(format!(
            "native renderer property manifest prefix {} must be {}",
            manifest.prefix, NATIVE_RENDERER_PROP_PREFIX
        )));
    }
    if manifest.property_count != manifest.properties.len() {
        errors.push(ValidationError::new(format!(
            "native renderer property manifest property_count {} does not match {} entries",
            manifest.property_count,
            manifest.properties.len()
        )));
    }

    let mut seen = BTreeSet::new();
    let mut previous_name: Option<&str> = None;
    for entry in &manifest.properties {
        if !entry.name.starts_with(NATIVE_RENDERER_PROP_PREFIX) {
            errors.push(ValidationError::new(format!(
                "native renderer property manifest entry {} must use {}",
                entry.name, NATIVE_RENDERER_PROP_PREFIX
            )));
        }
        if !seen.insert(entry.name.as_str()) {
            errors.push(ValidationError::new(format!(
                "native renderer property manifest contains duplicate entry {}",
                entry.name
            )));
        }
        if previous_name.is_some_and(|previous| previous > entry.name.as_str()) {
            errors.push(ValidationError::new(
                "native renderer property manifest entries must be sorted by name",
            ));
        }
        previous_name = Some(entry.name.as_str());
        validate_native_renderer_manifest_entry(entry, errors);
    }

    Some(manifest)
}

fn validate_native_renderer_manifest_entry(
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    if entry.lifecycle != "startup-effective" {
        errors.push(ValidationError::new(format!(
            "{} manifest lifecycle {} is not supported",
            entry.name, entry.lifecycle
        )));
    }
    if entry.clear_behavior != "profile-owned-explicit-set" {
        errors.push(ValidationError::new(format!(
            "{} manifest clear_behavior {} is not supported",
            entry.name, entry.clear_behavior
        )));
    }
    if entry.default_behavior != "runtime-owner-default-when-unset" {
        errors.push(ValidationError::new(format!(
            "{} manifest default_behavior {} is not supported",
            entry.name, entry.default_behavior
        )));
    }

    match entry.value_kind.as_str() {
        "bool" | "f32" | "f32_pair" | "string" | "u16" | "u32" | "u64" => {}
        "token" => {
            if entry.allowed_values.is_empty() {
                errors.push(ValidationError::new(format!(
                    "{} manifest token entry must declare allowed_values",
                    entry.name
                )));
            }
        }
        _ => errors.push(ValidationError::new(format!(
            "{} manifest value_kind {} is not supported",
            entry.name, entry.value_kind
        ))),
    }

    if let Some(range) = &entry.range {
        if !range.min.is_finite() || !range.max.is_finite() {
            errors.push(ValidationError::new(format!(
                "{} manifest range must be finite",
                entry.name
            )));
        } else if range.min > range.max {
            errors.push(ValidationError::new(format!(
                "{} manifest range min {} is greater than max {}",
                entry.name, range.min, range.max
            )));
        }
    }
}

fn validate_native_renderer_manifest_value(
    property: &PropertyValue,
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    match entry.value_kind.as_str() {
        "bool" => validate_native_renderer_manifest_bool(property, errors),
        "token" => validate_native_renderer_manifest_token(property, entry, errors),
        "u16" => validate_native_renderer_manifest_uint(property, entry, u16::MAX as u64, errors),
        "u32" => validate_native_renderer_manifest_uint(property, entry, u32::MAX as u64, errors),
        "u64" => validate_native_renderer_manifest_uint(property, entry, u64::MAX, errors),
        "f32" => validate_native_renderer_manifest_f32(property, entry, errors),
        "f32_pair" => validate_native_renderer_manifest_f32_pair(property, entry, errors),
        "string" => {
            if entry.non_empty && property.value.trim().is_empty() {
                errors.push(ValidationError::new(format!(
                    "{} value must be a non-empty manifest string",
                    property.name
                )));
            }
        }
        _ => {}
    }
}

fn validate_native_renderer_manifest_bool(
    property: &PropertyValue,
    errors: &mut Vec<ValidationError>,
) {
    let normalized = property.value.trim().to_ascii_lowercase();
    if !matches!(normalized.as_str(), "true" | "false") {
        errors.push(ValidationError::new(format!(
            "{} value {} must be a manifest bool true/false",
            property.name, property.value
        )));
    }
}

fn validate_native_renderer_manifest_token(
    property: &PropertyValue,
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    if !entry
        .allowed_values
        .iter()
        .any(|allowed| allowed == &property.value)
    {
        errors.push(ValidationError::new(format!(
            "{} value {} is not in manifest allowed_values",
            property.name, property.value
        )));
    }
}

fn validate_native_renderer_manifest_uint(
    property: &PropertyValue,
    entry: &NativeRendererPropertyManifestEntry,
    max_for_kind: u64,
    errors: &mut Vec<ValidationError>,
) {
    let text = property.value.trim();
    if text.is_empty()
        || !text.chars().all(|character| character.is_ascii_digit())
        || (text.len() > 1 && text.starts_with('0'))
    {
        errors.push(ValidationError::new(format!(
            "{} value {} must be a manifest canonical unsigned integer",
            property.name, property.value
        )));
        return;
    }

    let value = match text.parse::<u64>() {
        Ok(value) => value,
        Err(_) => {
            errors.push(ValidationError::new(format!(
                "{} value {} must fit in a manifest unsigned integer",
                property.name, property.value
            )));
            return;
        }
    };
    if value > max_for_kind {
        errors.push(ValidationError::new(format!(
            "{} value {value} exceeds manifest kind {}",
            property.name, entry.value_kind
        )));
    }
    validate_native_renderer_manifest_range(property, value as f64, entry, errors);
}

fn validate_native_renderer_manifest_f32(
    property: &PropertyValue,
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() => {
            validate_native_renderer_manifest_range(property, f64::from(value), entry, errors);
        }
        _ => errors.push(ValidationError::new(format!(
            "{} value {} must be a manifest finite f32",
            property.name, property.value
        ))),
    }
}

fn validate_native_renderer_manifest_f32_pair(
    property: &PropertyValue,
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    let parts: Vec<_> = property.value.split(',').map(str::trim).collect();
    if parts.len() != 2 {
        errors.push(ValidationError::new(format!(
            "{} value {} must be a manifest f32 pair",
            property.name, property.value
        )));
        return;
    }
    for part in parts {
        match part.parse::<f32>() {
            Ok(value) if value.is_finite() => {
                validate_native_renderer_manifest_range(property, f64::from(value), entry, errors);
            }
            _ => errors.push(ValidationError::new(format!(
                "{} value {} must be a manifest finite f32 pair",
                property.name, property.value
            ))),
        }
    }
}

fn validate_native_renderer_manifest_range(
    property: &PropertyValue,
    value: f64,
    entry: &NativeRendererPropertyManifestEntry,
    errors: &mut Vec<ValidationError>,
) {
    let Some(range) = &entry.range else {
        return;
    };
    if value < range.min || value > range.max {
        errors.push(ValidationError::new(format!(
            "{} value {value} must be between manifest range {} and {}",
            property.name, range.min, range.max
        )));
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

    let surface_radius_cells = environment_depth_u32(
        set_properties,
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS,
        errors,
    );
    let surface_min_neighbors = environment_depth_u32(
        set_properties,
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS,
        errors,
    );
    if let (Some(radius_cells), Some(min_neighbors)) = (surface_radius_cells, surface_min_neighbors)
    {
        let max_neighbors = max_surface_support_neighbors(radius_cells);
        if min_neighbors > max_neighbors {
            errors.push(ValidationError::new(format!(
                "environment depth surface_support.min_neighbors {min_neighbors} cannot exceed {max_neighbors} for radius_cells {radius_cells}",
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
        if min_hz < 3.0 {
            errors.push(ValidationError::new(
                "stimulus volume randomize min_hz must be greater than or equal to 3",
            ));
        }
        if max_hz > 40.0 {
            errors.push(ValidationError::new(
                "stimulus volume randomize max_hz must be less than or equal to 40",
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
        STIMULUS_VOLUME_PATTERN_FAMILY => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "randomized-trevor-vocabulary"
                    | "randomized"
                    | "random"
                    | "trevor-vocabulary"
                    | "trevor-mix"
                    | "mixed"
                    | "interference-mix"
                    | "stripes"
                    | "stripe"
                    | "ripples"
                    | "ripple"
                    | "rings"
                    | "rays"
                    | "ray"
                    | "radial-rays"
                    | "checker"
                    | "checkerboard"
                    | "checkers"
                    | "spiral"
                    | "spirals"
                    | "noise-field"
                    | "noise"
                    | "blobs"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "stimulus volume pattern_family {} is not supported",
                    property.value
                )));
            }
        }
        STIMULUS_VOLUME_RENDER_TARGET => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "512x512x2-rgba16f"
                    | "512x512x2-rgba8-unorm"
                    | "512x512x2-rgba8"
                    | "rgba8"
                    | "rgba8-unorm"
                    | "768x768x2-rgba16f"
                    | "768x768"
                    | "rgba16f-768"
                    | "1024x1024x2-rgba16f"
                    | "1024x1024"
                    | "rgba16f-1024"
                    | "limit-1024"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "stimulus volume render_target {} is not supported",
                    property.value
                )));
            }
        }
        STIMULUS_VOLUME_RAYMARCH_SAMPLES => {
            validate_stimulus_volume_u32(property, 1, 48, errors);
        }
        STIMULUS_VOLUME_CENTRAL_FOV_FRACTION => {
            validate_stimulus_volume_f32_range(property, 0.45, 1.0, errors);
        }
        STIMULUS_VOLUME_GRADIENT_SMOOTHING => {
            validate_stimulus_volume_f32_range(property, 0.0, 1.0, errors);
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

fn validate_stimulus_volume_f32_range(
    property: &PropertyValue,
    min_value: f32,
    max_value: f32,
    errors: &mut Vec<ValidationError>,
) {
    match property.value.trim().parse::<f32>() {
        Ok(value) if value.is_finite() && value >= min_value && value <= max_value => {}
        Ok(value) if value.is_finite() => errors.push(ValidationError::new(format!(
            "{} value {value} must be between {min_value} and {max_value}",
            property.name
        ))),
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
                    | "projection-sampler"
                    | "sampled-provider"
                    | "provider-sampler"
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
                    | "normal-coherence"
                    | "coherence"
                    | "debug-normal-coherence"
                    | "support-count"
                    | "surface-support-count"
                    | "debug-support-count"
                    | "surface-residual"
                    | "residual"
                    | "debug-surface-residual"
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
        ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED => {
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
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "off"
                    | "local-hint"
                    | "local"
                    | "hint"
                    | "local-neighborhood"
                    | "connected-labels"
                    | "connected"
                    | "labels"
                    | "connected-components"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_support.component_mode {} is not supported",
                    property.value
                )));
            }
        }
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "off"
                    | "depth-neighborhood"
                    | "depth"
                    | "depth-view"
                    | "cell-neighborhood"
                    | "cell"
                    | "scene-cell"
                    | "retained-cell"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_support.normal_source {} is not supported",
                    property.value
                )));
            }
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
        ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY => {
            let normalized = normalized_value(&property.value);
            let valid = matches!(
                normalized.as_str(),
                "dim" | "hide" | "hidden" | "debug-only" | "debug" | "diagnostic-only"
            );
            if !valid {
                errors.push(ValidationError::new(format!(
                    "environment depth surface_support.small_component_policy {} is not supported",
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

fn environment_depth_u32(
    set_properties: &BTreeMap<&str, &PropertyValue>,
    name: &str,
    errors: &mut Vec<ValidationError>,
) -> Option<u32> {
    let property = set_properties.get(name)?;
    match property.value.trim().parse::<u32>() {
        Ok(value) => Some(value),
        Err(_) => {
            errors.push(ValidationError::new(format!(
                "{} value {} must be an integer",
                property.name, property.value
            )));
            None
        }
    }
}

fn max_surface_support_neighbors(radius_cells: u32) -> u32 {
    let diameter = radius_cells.saturating_mul(2).saturating_add(1);
    diameter.saturating_mul(diameter).saturating_sub(1)
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
    use super::{
        build_write_plan, validate_runtime_profile, PropertyValue, RuntimeProfile,
        STIMULUS_VOLUME_PATTERN_FAMILY, STIMULUS_VOLUME_RAYMARCH_SAMPLES,
        STIMULUS_VOLUME_RENDER_TARGET,
    };

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
                "debug.rustyquest.native_renderer.environment_depth.surface_support.small_component_policy",
                "debug-only",
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
    fn environment_depth_impossible_neighbor_threshold_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-impossible-neighbor-threshold.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject impossible threshold");
        assert!(errors.iter().any(|error| error
            .message
            .contains("surface_support.min_neighbors 9 cannot exceed 8 for radius_cells 1")));
    }

    #[test]
    fn environment_depth_invalid_source_layers_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-environment-depth-invalid-source-layers.profile.json"
        ))
        .expect("damaged profile JSON");
        let errors = validate_runtime_profile(&damaged).expect_err("must reject source layers");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("min_source_layers value 3")));
    }

    #[test]
    fn stimulus_volume_profiles_validate() {
        for (profile_json, expected_mode, expected_target, expected_samples) in [
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume.profile.json"
                ),
                "solid-black-stimulus-volume",
                "1024x1024x2-rgba16f",
                "18",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume-balanced.profile.json"
                ),
                "solid-black-stimulus-volume",
                "768x768x2-rgba16f",
                "12",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume-performance.profile.json"
                ),
                "solid-black-stimulus-volume",
                "512x512x2-rgba16f",
                "12",
            ),
            (
                include_str!(
                    "../../../fixtures/runtime-profiles/quest-native-renderer-native-passthrough-stimulus-volume.profile.json"
                ),
                "native-passthrough-stimulus-volume",
                "768x768x2-rgba16f",
                "14",
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
            assert!(plan.operations.iter().any(|operation| {
                operation.name == STIMULUS_VOLUME_RENDER_TARGET
                    && operation.value.as_deref() == Some(expected_target)
            }));
            assert!(plan.operations.iter().any(|operation| {
                operation.name == STIMULUS_VOLUME_RAYMARCH_SAMPLES
                    && operation.value.as_deref() == Some(expected_samples)
            }));
        }
    }

    #[test]
    fn stimulus_volume_pattern_family_profile_value_validates() {
        let mut profile: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/runtime-profiles/quest-native-renderer-solid-black-stimulus-volume-performance.profile.json"
        ))
        .expect("stimulus volume profile JSON");
        profile
            .owned_android_properties
            .push(STIMULUS_VOLUME_PATTERN_FAMILY.to_string());
        profile.set_properties.push(PropertyValue {
            name: STIMULUS_VOLUME_PATTERN_FAMILY.to_string(),
            value: "spiral".to_string(),
            source_setting_id: "native_renderer.stimulus_volume.pattern_family".to_string(),
        });
        validate_runtime_profile(&profile).expect("stimulus pattern family validates");

        let mut damaged = profile;
        damaged
            .set_properties
            .last_mut()
            .expect("pattern family property")
            .value = "not-a-family".to_string();
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject unknown pattern family");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("pattern_family")));
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
    fn stimulus_volume_invalid_quality_range_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-stimulus-volume-invalid-quality-range.profile.json"
        ))
        .expect("damaged stimulus quality profile JSON");
        let errors =
            validate_runtime_profile(&damaged).expect_err("must reject invalid quality range");
        assert!(errors.iter().any(|error| {
            error
                .message
                .contains("stimulus_volume.central_fov_fraction")
        }));
        assert!(errors
            .iter()
            .any(|error| { error.message.contains("stimulus_volume.gradient_smoothing") }));
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
    fn native_renderer_manifest_invalid_camera_output_is_rejected() {
        let damaged: RuntimeProfile = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-manifest-invalid-camera-output.profile.json"
        ))
        .expect("damaged native renderer camera profile JSON");
        let errors = validate_runtime_profile(&damaged)
            .expect_err("must reject camera output outside manifest allowed_values");
        assert!(errors.iter().any(|error| {
            error.message.contains("camera.output")
                && error.message.contains("manifest allowed_values")
        }));
    }

    #[test]
    fn native_renderer_manifest_unknown_property_is_rejected() {
        let mut damaged = valid_profile();
        let name = "debug.rustyquest.native_renderer.unlisted.setting".to_string();
        damaged.owned_android_properties.push(name.clone());
        damaged.set_properties.push(PropertyValue {
            name,
            value: "true".to_string(),
            source_setting_id: "native_renderer.unlisted.setting".to_string(),
        });

        let errors = validate_runtime_profile(&damaged)
            .expect_err("must reject native renderer property missing from manifest");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("missing from property manifest")));
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
