//! Embedded native app-build settings fallback for generated APK variants.

use std::collections::BTreeMap;

const NATIVE_APP_SETTINGS_SCHEMA: &str = "rusty.quest.native_app_settings.v1";
const NATIVE_APP_SETTINGS_ASSET: &str = "native-app-settings.json";

#[derive(Clone, Debug, Default)]
pub(crate) struct NativeAppSettingsDefaults {
    status: &'static str,
    reason: String,
    app_id: String,
    values_by_android_property: BTreeMap<String, String>,
}

impl NativeAppSettingsDefaults {
    pub(crate) fn missing(reason: impl Into<String>) -> Self {
        Self {
            status: "missing",
            reason: reason.into(),
            ..Self::default()
        }
    }

    pub(crate) fn lookup(&self, android_property: &str) -> Option<String> {
        self.values_by_android_property
            .get(android_property)
            .cloned()
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "status={} source=apk-asset asset={} schema={} appId={} settingCount={} androidPropertiesOverride=true reason={}",
            self.status,
            marker_token(NATIVE_APP_SETTINGS_ASSET),
            marker_token(NATIVE_APP_SETTINGS_SCHEMA),
            marker_token(&self.app_id),
            self.values_by_android_property.len(),
            marker_token(&self.reason),
        )
    }

    fn from_json_str(json: &str) -> Result<Self, String> {
        let value: serde_json::Value = serde_json::from_str(json)
            .map_err(|error| format!("parse native app settings JSON: {error}"))?;
        let schema = value
            .get("schema")
            .and_then(serde_json::Value::as_str)
            .ok_or_else(|| "native app settings missing schema".to_string())?;
        if schema != NATIVE_APP_SETTINGS_SCHEMA {
            return Err(format!("unsupported native app settings schema: {schema}"));
        }
        let app_id = value
            .get("app_id")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default()
            .to_string();
        let values = value
            .get("values")
            .and_then(serde_json::Value::as_object)
            .ok_or_else(|| "native app settings missing values object".to_string())?;
        let mut values_by_android_property = BTreeMap::new();
        for setting in values.values() {
            let android_property = setting
                .get("android_property")
                .and_then(serde_json::Value::as_str)
                .unwrap_or_default()
                .trim();
            if android_property.is_empty() {
                continue;
            }
            let Some(value) = setting.get("value").and_then(setting_value_to_string) else {
                continue;
            };
            values_by_android_property.insert(android_property.to_string(), value);
        }
        Ok(Self {
            status: "loaded",
            reason: "ok".to_string(),
            app_id,
            values_by_android_property,
        })
    }
}

#[cfg(target_os = "android")]
impl NativeAppSettingsDefaults {
    pub(crate) fn load_from_apk_asset(app: &android_activity::AndroidApp) -> Self {
        use std::{ffi::CString, io::Read};

        let asset_name = match CString::new(NATIVE_APP_SETTINGS_ASSET) {
            Ok(value) => value,
            Err(error) => return Self::missing(format!("asset-name-error-{error}")),
        };
        let asset_manager = app.asset_manager();
        let Some(mut asset) = asset_manager.open(&asset_name) else {
            return Self::missing("asset-not-packaged");
        };
        let mut json = String::new();
        if let Err(error) = asset.read_to_string(&mut json) {
            return Self {
                status: "error",
                reason: format!("asset-read-error-{error}"),
                ..Self::default()
            };
        }
        match Self::from_json_str(&json) {
            Ok(settings) => settings,
            Err(error) => Self {
                status: "error",
                reason: error,
                ..Self::default()
            },
        }
    }
}

fn setting_value_to_string(value: &serde_json::Value) -> Option<String> {
    match value {
        serde_json::Value::String(value) => Some(value.clone()),
        serde_json::Value::Bool(value) => Some(value.to_string()),
        serde_json::Value::Number(value) => Some(value.to_string()),
        _ => None,
    }
}

fn marker_token(value: &str) -> String {
    let sanitized = value
        .trim()
        .replace('\0', "")
        .replace(|character: char| character.is_whitespace(), "_")
        .replace([',', ';'], "_");
    if sanitized.is_empty() {
        "none".to_string()
    } else {
        sanitized
    }
}

#[cfg(test)]
mod tests {
    use super::NativeAppSettingsDefaults;

    #[test]
    fn parses_values_by_android_property() {
        let settings = NativeAppSettingsDefaults::from_json_str(
            r#"{
                "schema": "rusty.quest.native_app_settings.v1",
                "app_id": "native_stimulus_volume_panel",
                "values": {
                    "native_renderer.render.mode": {
                        "value": "solid-black-stimulus-volume",
                        "android_property": "debug.rustyquest.native_renderer.render.mode"
                    },
                    "native_renderer.stimulus_volume.randomize.enabled": {
                        "value": true,
                        "android_property": "debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled"
                    }
                }
            }"#,
        )
        .expect("settings parse");

        assert_eq!(
            settings.lookup("debug.rustyquest.native_renderer.render.mode"),
            Some("solid-black-stimulus-volume".to_string())
        );
        assert_eq!(
            settings.lookup("debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled"),
            Some("true".to_string())
        );
        assert!(settings.marker_fields().contains("settingCount=2"));
    }

    #[test]
    fn rejects_wrong_schema() {
        let error = NativeAppSettingsDefaults::from_json_str(
            r#"{"schema":"wrong","app_id":"x","values":{}}"#,
        )
        .expect_err("schema should be rejected");
        assert!(error.contains("unsupported native app settings schema"));
    }
}
