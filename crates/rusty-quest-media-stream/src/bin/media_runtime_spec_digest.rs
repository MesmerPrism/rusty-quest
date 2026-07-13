//! Prints the canonical typed SHA-256 for one Quest media runtime spec.

use std::{env, fs, process};

use rusty_quest_media_stream::{
    canonical_media_stream_runtime_sha256, validate_media_stream_runtime_spec,
    MediaStreamRuntimeSpec,
};

fn main() {
    let Some(path) = env::args().nth(1) else {
        eprintln!("usage: media_runtime_spec_digest <runtime-spec.json>");
        process::exit(2);
    };
    let result = fs::read_to_string(path)
        .map_err(|error| error.to_string())
        .and_then(|json| {
            let value: serde_json::Value =
                serde_json::from_str(&json).map_err(|error| error.to_string())?;
            let spec = value
                .get("quest")
                .and_then(|quest| quest.get("spec"))
                .cloned()
                .unwrap_or(value);
            serde_json::from_value::<MediaStreamRuntimeSpec>(spec)
                .map_err(|error| error.to_string())
        })
        .and_then(|spec| {
            validate_media_stream_runtime_spec(&spec)
                .map_err(|errors| format!("runtime spec invalid: {errors:?}"))?;
            canonical_media_stream_runtime_sha256(&spec).map_err(|error| error.to_string())
        });
    match result {
        Ok(digest) => println!("{digest}"),
        Err(error) => {
            eprintln!("{error}");
            process::exit(1);
        }
    }
}
