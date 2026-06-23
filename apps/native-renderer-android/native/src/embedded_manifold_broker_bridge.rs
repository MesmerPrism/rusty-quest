//! JNI startup bridge for the same-APK embedded Manifold WebSocket broker.

use serde_json::json;

use crate::{
    native_app_settings::NativeAppSettingsDefaults,
    native_renderer_properties::{
        PROP_MANIFOLD_EMBEDDED_BROKER_BIND_HOST, PROP_MANIFOLD_EMBEDDED_BROKER_ENABLED,
        PROP_MANIFOLD_EMBEDDED_BROKER_LAN_ENABLED, PROP_MANIFOLD_EMBEDDED_BROKER_MAX_FRAME_BYTES,
        PROP_MANIFOLD_EMBEDDED_BROKER_PATH, PROP_MANIFOLD_EMBEDDED_BROKER_PORT,
        PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN,
        PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN_REQUIRED,
    },
    native_renderer_property_values::{bool_value, u32_value},
};

const DEFAULT_BIND_HOST: &str = "127.0.0.1";
const DEFAULT_PORT: u16 = 8765;
const DEFAULT_PATH: &str = "/manifold/v1/events";
const DEFAULT_MAX_FRAME_BYTES: u32 = 65_536;
const EMBEDDED_BROKER_BINARY_CLASS_NAME: &str =
    "io.github.mesmerprism.rustyquest.native_renderer.EmbeddedManifoldBrokerServer";

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct EmbeddedManifoldBrokerSettings {
    pub(crate) enabled: bool,
    pub(crate) bind_host: String,
    pub(crate) port: u16,
    pub(crate) path: String,
    pub(crate) max_frame_bytes: u32,
    pub(crate) lan_enabled: bool,
    pub(crate) session_token_required: bool,
    pub(crate) session_token: String,
}

impl EmbeddedManifoldBrokerSettings {
    pub(crate) fn from_property_lookup(mut lookup: impl FnMut(&str) -> Option<String>) -> Self {
        let enabled = bool_value(lookup(PROP_MANIFOLD_EMBEDDED_BROKER_ENABLED), false);
        let bind_host = string_value(
            lookup(PROP_MANIFOLD_EMBEDDED_BROKER_BIND_HOST),
            DEFAULT_BIND_HOST,
        );
        let port = u16::try_from(u32_value(
            lookup(PROP_MANIFOLD_EMBEDDED_BROKER_PORT),
            u32::from(DEFAULT_PORT),
            1,
            u32::from(u16::MAX),
        ))
        .unwrap_or(DEFAULT_PORT);
        let path = string_value(lookup(PROP_MANIFOLD_EMBEDDED_BROKER_PATH), DEFAULT_PATH);
        let max_frame_bytes = u32_value(
            lookup(PROP_MANIFOLD_EMBEDDED_BROKER_MAX_FRAME_BYTES),
            DEFAULT_MAX_FRAME_BYTES,
            1024,
            1024 * 1024,
        );
        let lan_enabled = bool_value(lookup(PROP_MANIFOLD_EMBEDDED_BROKER_LAN_ENABLED), false);
        let session_token_required = bool_value(
            lookup(PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN_REQUIRED),
            lan_enabled,
        );
        let session_token = lookup(PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN)
            .unwrap_or_default()
            .trim()
            .to_owned();

        Self {
            enabled,
            bind_host,
            port,
            path,
            max_frame_bytes,
            lan_enabled,
            session_token_required,
            session_token,
        }
    }

    #[cfg(target_os = "android")]
    pub(crate) fn load_from_android_properties_with_defaults(
        defaults: &NativeAppSettingsDefaults,
    ) -> Self {
        Self::from_property_lookup(|name| android_property(name).or_else(|| defaults.lookup(name)))
    }

    #[cfg(not(target_os = "android"))]
    pub(crate) fn load_from_android_properties_with_defaults(
        defaults: &NativeAppSettingsDefaults,
    ) -> Self {
        Self::from_property_lookup(|name| defaults.lookup(name))
    }

    pub(crate) fn marker_fields(&self) -> String {
        format!(
            "embeddedManifoldBrokerEnabled={} bindHost={} port={} path={} maxFrameBytes={} lanEnabled={} sessionTokenRequired={}",
            self.enabled,
            marker_token(&self.bind_host),
            self.port,
            marker_token(&self.path),
            self.max_frame_bytes,
            self.lan_enabled,
            self.session_token_required
        )
    }

    fn settings_json(&self) -> String {
        json!({
            "enabled": self.enabled,
            "bind_host": self.bind_host,
            "port": self.port,
            "path": self.path,
            "max_frame_bytes": self.max_frame_bytes,
            "lan_enabled": self.lan_enabled,
            "session_token_required": self.session_token_required,
            "session_token": self.session_token,
        })
        .to_string()
    }
}

#[cfg(target_os = "android")]
pub(crate) fn start_if_enabled(
    app: &android_activity::AndroidApp,
    settings: &EmbeddedManifoldBrokerSettings,
) {
    if !settings.enabled {
        crate::marker(
            "manifold-embedded-broker",
            format!(
                "status=disabled reason=feature-disabled {}",
                settings.marker_fields()
            ),
        );
        return;
    }

    crate::marker(
        "manifold-embedded-broker",
        format!("status=starting {}", settings.marker_fields()),
    );
    match start_impl(app, settings) {
        Ok(()) => crate::marker(
            "manifold-embedded-broker",
            format!("status=start-request-sent {}", settings.marker_fields()),
        ),
        Err(error) => crate::marker(
            "manifold-embedded-broker",
            format!(
                "status=error reason={} {}",
                crate::sanitize(&error),
                settings.marker_fields()
            ),
        ),
    }
}

#[cfg(not(target_os = "android"))]
pub(crate) fn start_if_enabled(_app: &(), settings: &EmbeddedManifoldBrokerSettings) {
    let _ = settings;
}

#[cfg(target_os = "android")]
fn start_impl(
    app: &android_activity::AndroidApp,
    settings: &EmbeddedManifoldBrokerSettings,
) -> Result<(), String> {
    use jni::{
        jni_sig, jni_str,
        objects::{JClass, JClassLoader, JObject, JValue},
        JavaVM,
    };

    let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) };
    let activity = app.activity_as_ptr() as jni::sys::jobject;
    let settings_json = settings.settings_json();
    vm.attach_current_thread(|env| -> jni::errors::Result<()> {
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
        let broker_class_name = env.new_string(EMBEDDED_BROKER_BINARY_CLASS_NAME)?;
        let broker_class =
            JClass::for_name_with_loader(env, broker_class_name, true, class_loader)?;
        let settings_json = env.new_string(settings_json.as_str())?;
        env.call_static_method(
            broker_class,
            jni_str!("startFromNative"),
            jni_sig!("(Landroid/app/Activity;Ljava/lang/String;)V"),
            &[
                JValue::Object(&activity),
                JValue::Object(&JObject::from(settings_json)),
            ],
        )?;
        Ok(())
    })
    .map_err(|error| format!("start embedded Manifold broker failed: {error}"))
}

fn string_value(value: Option<String>, default_value: &str) -> String {
    value
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default_value.to_owned())
}

fn marker_token(value: &str) -> String {
    crate::sanitize(value.trim())
}

#[cfg(target_os = "android")]
fn android_property(name: &str) -> Option<String> {
    let mut property = android_properties::getprop(name);
    property.value().and_then(|value| {
        let trimmed = value.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    })
}

#[cfg(test)]
mod tests {
    use super::EmbeddedManifoldBrokerSettings;

    #[test]
    fn defaults_to_disabled_loopback_broker() {
        let settings = EmbeddedManifoldBrokerSettings::from_property_lookup(|_| None);
        assert!(!settings.enabled);
        assert_eq!(settings.bind_host, "127.0.0.1");
        assert_eq!(settings.port, 8765);
        assert_eq!(settings.path, "/manifold/v1/events");
        assert_eq!(settings.max_frame_bytes, 65_536);
        assert!(!settings.lan_enabled);
        assert!(!settings.session_token_required);
    }

    #[test]
    fn parses_enabled_bounded_settings() {
        let settings = EmbeddedManifoldBrokerSettings::from_property_lookup(|name| match name {
            "debug.rustyquest.native_renderer.manifold.embedded_broker.enabled" => {
                Some("true".to_owned())
            }
            "debug.rustyquest.native_renderer.manifold.embedded_broker.bind_host" => {
                Some("0.0.0.0".to_owned())
            }
            "debug.rustyquest.native_renderer.manifold.embedded_broker.port" => {
                Some("9876".to_owned())
            }
            "debug.rustyquest.native_renderer.manifold.embedded_broker.max_frame_bytes" => {
                Some("999999999".to_owned())
            }
            "debug.rustyquest.native_renderer.manifold.embedded_broker.lan_enabled" => {
                Some("true".to_owned())
            }
            _ => None,
        });
        assert!(settings.enabled);
        assert_eq!(settings.bind_host, "0.0.0.0");
        assert_eq!(settings.port, 9876);
        assert_eq!(settings.max_frame_bytes, 1024 * 1024);
        assert!(settings.lan_enabled);
        assert!(settings.session_token_required);
        assert!(settings
            .marker_fields()
            .contains("embeddedManifoldBrokerEnabled=true"));
    }
}
