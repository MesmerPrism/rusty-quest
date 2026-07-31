//! Prints the canonical SHA-256 for one packaged broker runtime config.

use rusty_quest_broker_authority::{canonical_runtime_config_sha256, QuestBrokerAuthorityRuntime};
use std::{
    env, fs,
    process::ExitCode,
    time::{SystemTime, UNIX_EPOCH},
};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let Some(path) = args.next() else {
        eprintln!("usage: runtime_config_digest <runtime-config.json>");
        return ExitCode::FAILURE;
    };
    if args.next().is_some() {
        eprintln!("usage: runtime_config_digest <runtime-config.json>");
        return ExitCode::FAILURE;
    }
    match fs::read_to_string(&path)
        .map_err(|error| error.to_string())
        .and_then(|json| {
            let digest =
                canonical_runtime_config_sha256(&json).map_err(|error| error.to_string())?;
            let now_ms = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|error| error.to_string())?
                .as_millis()
                .try_into()
                .map_err(|_| "system clock exceeds i64 milliseconds".to_owned())?;
            QuestBrokerAuthorityRuntime::from_config_json(&json, &"00".repeat(32), now_ms, 0)
                .map_err(|error| error.to_string())?;
            Ok(digest)
        }) {
        Ok(digest) => {
            println!("{digest}");
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
