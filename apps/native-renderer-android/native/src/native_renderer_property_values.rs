//! Shared Android property value parsers for native renderer settings modules.

pub(crate) fn bool_value(value: Option<String>, default_value: bool) -> bool {
    value.map_or(default_value, |value| {
        matches!(
            value.trim().to_ascii_lowercase().as_str(),
            "1" | "true" | "yes" | "on"
        )
    })
}

pub(crate) fn u64_value(
    value: Option<String>,
    default_value: u64,
    min_value: u64,
    max_value: u64,
) -> u64 {
    value
        .and_then(|value| value.trim().parse::<u64>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

pub(crate) fn u32_value(
    value: Option<String>,
    default_value: u32,
    min_value: u32,
    max_value: u32,
) -> u32 {
    value
        .and_then(|value| value.trim().parse::<u32>().ok())
        .filter(|value| *value >= min_value)
        .unwrap_or(default_value)
        .min(max_value)
}

pub(crate) fn f32_value(value: Option<String>, default_value: f32) -> f32 {
    value
        .and_then(|value| value.trim().parse::<f32>().ok())
        .unwrap_or(default_value)
}

pub(crate) fn f32_clamped_value(
    value: Option<String>,
    default_value: f32,
    min_value: f32,
    max_value: f32,
) -> f32 {
    f32_value(value, default_value).clamp(min_value, max_value)
}

pub(crate) fn f32_pair_value(value: Option<String>, default_value: [f32; 2]) -> [f32; 2] {
    let Some(value) = value else {
        return default_value;
    };
    let parts = value
        .split(|character: char| character == ',' || character == ';' || character.is_whitespace())
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() != 2 {
        return default_value;
    }
    let Some(x) = parts[0].trim().parse::<f32>().ok() else {
        return default_value;
    };
    let Some(y) = parts[1].trim().parse::<f32>().ok() else {
        return default_value;
    };
    [x, y]
}

pub(crate) fn normalized_property(value: Option<String>) -> String {
    value
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .replace('_', "-")
}
