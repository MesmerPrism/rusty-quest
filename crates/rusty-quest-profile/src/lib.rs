//! Quest runtime profile contracts and dry-run validation helpers.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// Quest runtime profile schema id.
pub const RUNTIME_PROFILE_SCHEMA: &str = "rusty.quest.runtime_profile.v1";

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

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
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
}
