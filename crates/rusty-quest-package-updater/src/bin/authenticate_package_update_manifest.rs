//! Publisher-side authentication for a prior immutable update envelope.

use std::{env, fs, path::PathBuf};

use rusty_quest_package_updater::{authenticate_manifest, ReleaseKeyRecord, ReleaseKeyRegistry};

fn main() {
    if let Err(error) = run() {
        eprintln!("package update manifest authentication failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = env::args().skip(1);
    let mut envelope_path = None;
    let mut key_id = None;
    let mut public_key = None;
    while let Some(argument) = arguments.next() {
        let value = arguments
            .next()
            .ok_or_else(|| format!("missing value for {argument}"))?;
        match argument.as_str() {
            "--envelope" => envelope_path = Some(PathBuf::from(value)),
            "--key-id" => key_id = Some(value),
            "--public-key" => public_key = Some(value),
            _ => return Err(format!("unsupported option {argument}").into()),
        }
    }
    let envelope_path = envelope_path.ok_or("--envelope is required")?;
    let key_id = key_id.ok_or("--key-id is required")?;
    let public_key = public_key.ok_or("--public-key is required")?;
    let envelope = fs::read(envelope_path)?;
    let registry = ReleaseKeyRegistry::from_records([ReleaseKeyRecord { key_id, public_key }])?;
    authenticate_manifest(&envelope, &registry)?;
    println!("Package update manifest signature authenticated.");
    Ok(())
}
