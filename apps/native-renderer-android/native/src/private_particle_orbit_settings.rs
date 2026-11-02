//! Bounded orbit control transport. The private payload owns visual placement.
use serde_json::{json, Value};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OrbitDistanceSource {
    StaticMinimum,
    Oscillator,
    PolarHeartbeat,
    SyntheticHeartbeat,
}

impl OrbitDistanceSource {
    pub(crate) const fn code(self) -> u32 {
        match self {
            Self::StaticMinimum => 0,
            Self::Oscillator => 1,
            Self::PolarHeartbeat => 2,
            Self::SyntheticHeartbeat => 3,
        }
    }
    pub(crate) const fn name(self) -> &'static str {
        match self {
            Self::StaticMinimum => "static-minimum",
            Self::Oscillator => "oscillator",
            Self::PolarHeartbeat => "polar-heartbeat",
            Self::SyntheticHeartbeat => "synthetic-heartbeat",
        }
    }
    fn parse(value: &Value) -> Result<Self, String> {
        match value.as_str() {
            Some("static-minimum") => Ok(Self::StaticMinimum),
            Some("oscillator") => Ok(Self::Oscillator),
            Some("polar-heartbeat") => Ok(Self::PolarHeartbeat),
            Some("synthetic-heartbeat") => Ok(Self::SyntheticHeartbeat),
            _ => Err("unsupported_orbit_distance_source".into()),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct OrbitDistanceSettings {
    pub(crate) enabled: bool,
    pub(crate) source: OrbitDistanceSource,
    pub(crate) scale_with_particle_size: bool,
    pub(crate) reference_radius_m: f32,
    pub(crate) synthetic_bpm: f32,
}

impl OrbitDistanceSettings {
    pub(crate) const fn defaults(enabled: bool) -> Self {
        Self {
            enabled,
            source: OrbitDistanceSource::StaticMinimum,
            scale_with_particle_size: false,
            reference_radius_m: 0.03,
            synthetic_bpm: 60.0,
        }
    }
    pub(crate) fn parse_patch(value: &Value) -> Result<(Self, [bool; 4]), String> {
        let object = value.as_object().ok_or("orbit_distance_must_be_object")?;
        let fields = [
            "source",
            "scale_with_particle_size",
            "reference_radius_m",
            "synthetic_bpm",
        ];
        if object.is_empty() || object.keys().any(|key| !fields.contains(&key.as_str())) {
            return Err("orbit_distance_fields".into());
        }
        let mut settings = Self::defaults(true);
        let mask = fields.map(|key| object.contains_key(key));
        if let Some(value) = object.get("source") {
            settings.source = OrbitDistanceSource::parse(value)?;
        }
        if let Some(value) = object.get("scale_with_particle_size") {
            settings.scale_with_particle_size = value
                .as_bool()
                .ok_or("orbit_distance_scaling_must_be_boolean")?;
        }
        if let Some(value) = object.get("reference_radius_m") {
            settings.reference_radius_m =
                bounded(value, 0.001, 0.25, "orbit_distance_reference_radius_m")?;
        }
        if let Some(value) = object.get("synthetic_bpm") {
            settings.synthetic_bpm = bounded(value, 30.0, 240.0, "orbit_distance_synthetic_bpm")?;
        }
        Ok((settings, mask))
    }
    pub(crate) fn apply_mask(&mut self, newer: Self, mask: [bool; 4]) {
        if !mask.iter().any(|selected| *selected) {
            return;
        }
        self.enabled = true;
        if mask[0] {
            self.source = newer.source;
        }
        if mask[1] {
            self.scale_with_particle_size = newer.scale_with_particle_size;
        }
        if mask[2] {
            self.reference_radius_m = newer.reference_radius_m;
        }
        if mask[3] {
            self.synthetic_bpm = newer.synthetic_bpm;
        }
    }
    pub(crate) fn aux_row(self) -> [f32; 4] {
        [
            u8::from(self.enabled) as f32,
            self.source.code() as f32,
            u8::from(self.scale_with_particle_size) as f32,
            self.reference_radius_m,
        ]
    }
    pub(crate) fn status(self) -> Value {
        json!({"enabled": self.enabled, "source": self.source.name(), "scale_with_particle_size": self.scale_with_particle_size, "reference_radius_m": self.reference_radius_m, "synthetic_bpm": self.synthetic_bpm})
    }
    pub(crate) fn marker_fields(self) -> String {
        format!("privateParticleOrbitControlEnabled={} privateParticleOrbitDistanceSource={} privateParticleOrbitScaleWithParticleSize={} privateParticleOrbitReferenceRadiusM={:.4} privateParticleOrbitSyntheticBpm={:.2}", self.enabled, self.source.name(), self.scale_with_particle_size, self.reference_radius_m, self.synthetic_bpm)
    }
}

fn bounded(value: &Value, min: f64, max: f64, name: &str) -> Result<f32, String> {
    match value.as_f64() {
        Some(number) if number.is_finite() && (min..=max).contains(&number) => Ok(number as f32),
        _ => Err(format!("{name}_out_of_range")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_are_explicitly_opt_in() {
        assert!(!OrbitDistanceSettings::defaults(false).enabled);
        let defaults = OrbitDistanceSettings::defaults(true);
        assert_eq!(defaults.aux_row(), [1.0, 0.0, 0.0, 0.03]);
        assert_eq!(defaults.synthetic_bpm, 60.0);
    }
    #[test]
    fn individual_fields_preserve_all_siblings() {
        let mut current = OrbitDistanceSettings {
            source: OrbitDistanceSource::SyntheticHeartbeat,
            synthetic_bpm: 123.0,
            reference_radius_m: 0.07,
            ..OrbitDistanceSettings::defaults(true)
        };
        let (patch, mask) =
            OrbitDistanceSettings::parse_patch(&json!({"scale_with_particle_size": true})).unwrap();
        assert_eq!(mask, [false, true, false, false]);
        current.apply_mask(patch, mask);
        assert_eq!(current.source, OrbitDistanceSource::SyntheticHeartbeat);
        assert_eq!(current.synthetic_bpm, 123.0);
        assert_eq!(current.reference_radius_m, 0.07);
        assert!(current.scale_with_particle_size);
    }
    #[test]
    fn closed_fields_and_numeric_bounds_are_enforced() {
        for bad in [
            json!({}),
            json!({"source":"automatic"}),
            json!({"source":null}),
            json!({"scale_with_particle_size":1}),
            json!({"synthetic_bpm":29.9}),
            json!({"synthetic_bpm":240.1}),
            json!({"synthetic_bpm":"60"}),
            json!({"reference_radius_m":0}),
            json!({"reference_radius_m":0.251}),
            json!({"source":"oscillator","unknown":true}),
        ] {
            assert!(OrbitDistanceSettings::parse_patch(&bad).is_err(), "{bad}");
        }
        for bpm in [30.0, 240.0] {
            assert!(OrbitDistanceSettings::parse_patch(&json!({"synthetic_bpm":bpm})).is_ok());
        }
    }
}
