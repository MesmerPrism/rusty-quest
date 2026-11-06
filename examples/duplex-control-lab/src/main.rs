//! Dedicated desktop consumer of the pure production renewal wire codec.
use rusty_quest_peer_wire::{expand_renewal, project_renewal, receipt_sha256, WireError};
use serde_json::{json, Value};
use std::{
    ffi::OsString,
    fs::File,
    io::{Read, Write},
    process::ExitCode,
};

// CLI admission only. This is not a signed-frame or authority eligibility bound.
const INPUT_LIMIT: u64 = 4 * 1024 * 1024;
const SCHEMA: &str = "rusty.quest.peer_wire.codec_observation.v1";

struct Refusal {
    code: &'static str,
    message: &'static str,
}

impl From<WireError> for Refusal {
    fn from(error: WireError) -> Self {
        Self {
            code: error.code(),
            message: error.as_str(),
        }
    }
}

fn sizes(value: &Value) -> Result<Value, Refusal> {
    let canonical = serde_json::to_string(value).map_err(|_| Refusal::from(WireError::Json))?;
    Ok(json!({"utf8_bytes":canonical.len(),"utf16_units":canonical.encode_utf16().count()}))
}

fn codec(arguments: &[OsString]) -> Result<Value, Refusal> {
    if arguments.len() != 3 || arguments[0] != "codec" || arguments[1] != "--input" {
        return Err(Refusal {
            code: "usage",
            message: "usage: duplex-control-lab codec --input <fixture.json>",
        });
    }
    let file = File::open(&arguments[2]).map_err(|_| Refusal {
        code: "input_open",
        message: "input file unavailable",
    })?;
    let metadata = file.metadata().map_err(|_| Refusal {
        code: "input_metadata",
        message: "input metadata unavailable",
    })?;
    if !metadata.is_file() {
        return Err(Refusal {
            code: "input_type",
            message: "input must be a regular file",
        });
    }
    let mut bytes = Vec::new();
    file.take(INPUT_LIMIT + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| Refusal {
            code: "input_read",
            message: "input read failed",
        })?;
    if bytes.len() as u64 > INPUT_LIMIT {
        return Err(Refusal {
            code: "input_bounds",
            message: "input exceeds the CLI 4 MiB limit",
        });
    }
    let input: Value = serde_json::from_slice(&bytes).map_err(|_| Refusal {
        code: "input_json",
        message: "input JSON invalid",
    })?;
    let restored = expand_renewal(&input)?;
    let projected = project_renewal(&restored)?;
    let digest = receipt_sha256(&restored)?;
    Ok(
        json!({"$schema":SCHEMA,"status":"ok","input_bytes":bytes.len(),
        "input_limit_bytes":INPUT_LIMIT,"receipt_sha256":digest,
        "sizes":{"receipt":sizes(&restored)?,"projection":sizes(&projected)?},
        "restored":restored,"projected":projected}),
    )
}

fn main() -> ExitCode {
    let (observation, status) = match codec(&std::env::args_os().skip(1).collect::<Vec<_>>()) {
        Ok(value) => (value, ExitCode::SUCCESS),
        Err(error) => (
            json!({"$schema":SCHEMA,"status":"refused",
            "error":{"code":error.code,"message":error.message}}),
            ExitCode::from(2),
        ),
    };
    let mut output = std::io::stdout().lock();
    if serde_json::to_writer(&mut output, &observation).is_err() || output.write_all(b"\n").is_err()
    {
        return ExitCode::from(1);
    }
    status
}
