//! Embedded native app-build settings fallback for generated APK variants.

use std::collections::BTreeMap;

const NATIVE_APP_SETTINGS_SCHEMA: &str = "rusty.quest.native_app_settings.v1";
const NATIVE_APP_SETTINGS_ASSET: &str = "native-app-settings.json";

#[derive(Clone, Debug, Default)]
pub(crate) struct NativeAppSettingsDefaults {
    status: &'static str,
    reader: &'static str,
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
            "status={} source=apk-asset reader={} asset={} schema={} appId={} settingCount={} androidPropertiesOverride=true reason={}",
            self.status,
            marker_token(self.reader),
            marker_token(NATIVE_APP_SETTINGS_ASSET),
            marker_token(NATIVE_APP_SETTINGS_SCHEMA),
            marker_token(&self.app_id),
            self.values_by_android_property.len(),
            marker_token(&self.reason),
        )
    }

    fn from_json_str(json: &str) -> Result<Self, String> {
        let json = json.trim_start_matches('\u{feff}');
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
            reader: "json-string",
            reason: "ok".to_string(),
            app_id,
            values_by_android_property,
        })
    }

    fn with_reader(mut self, reader: &'static str) -> Self {
        self.reader = reader;
        self
    }
}

#[cfg(target_os = "android")]
impl NativeAppSettingsDefaults {
    pub(crate) fn load_from_apk_asset(app: &android_activity::AndroidApp) -> Self {
        let json = match read_asset_via_java(app, NATIVE_APP_SETTINGS_ASSET) {
            Ok(json) => json,
            Err(error) => return Self::missing(format!("java-asset-read-error-{error}")),
        };
        if json.trim().is_empty() {
            return Self {
                status: "error",
                reader: "java-asset",
                reason: "asset-empty-json".to_string(),
                ..Self::default()
            };
        };
        match Self::from_json_str(&json) {
            Ok(settings) => settings.with_reader("java-asset"),
            Err(error) => Self {
                status: "error",
                reader: "java-asset",
                reason: error,
                ..Self::default()
            },
        }
    }
}

#[cfg(target_os = "android")]
fn read_asset_via_java(
    app: &android_activity::AndroidApp,
    asset_name: &str,
) -> Result<String, String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JString, JValue},
        JavaVM,
    };

    const READER_CLASS_NAME: &str =
        "io.github.mesmerprism.rustyquest.native_renderer.NativeAppSettingsReader";

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    vm.attach_current_thread(|env| -> jni::errors::Result<String> {
        let activity = unsafe { env.as_cast_raw::<JObject>(&activity)? };
        let class_loader = env
            .call_method(
                &activity,
                jni_str!("getClassLoader"),
                jni_sig!("()Ljava/lang/ClassLoader;"),
                &[],
            )?
            .l()?;
        let class_loader: JClassLoader = env.cast_local::<JClassLoader>(class_loader)?;
        let reader_class_name = env.new_string(READER_CLASS_NAME)?;
        let reader_class =
            JClass::for_name_with_loader(env, reader_class_name, true, class_loader)?;
        let asset_name = env.new_string(asset_name)?;
        let json = env
            .call_static_method(
                reader_class,
                jni_str!("readAsset"),
                jni_sig!("(Landroid/app/Activity;Ljava/lang/String;)Ljava/lang/String;"),
                &[
                    JValue::Object(&activity),
                    JValue::Object(&JObject::from(asset_name)),
                ],
            )?
            .l()?;
        let json: JString = env.cast_local::<JString>(json)?;
        Ok(json.to_string())
    })
    .map_err(|error| format!("read native app settings asset failed: {error}"))
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

    #[test]
    fn accepts_utf8_bom() {
        let settings = NativeAppSettingsDefaults::from_json_str(
            "\u{feff}{\"schema\":\"rusty.quest.native_app_settings.v1\",\"app_id\":\"bom\",\"values\":{}}",
        )
        .expect("settings with UTF-8 BOM should parse");
        assert!(settings.marker_fields().contains("appId=bom"));
    }
}
