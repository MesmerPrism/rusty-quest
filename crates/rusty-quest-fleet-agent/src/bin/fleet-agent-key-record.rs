//! Derives the public Fleet Agent enrollment record from one private seed file.

use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;

use rusty_quest_fleet_agent::derive_key_record;

fn parse_arguments() -> Result<(String, PathBuf), String> {
    let mut arguments = env::args_os();
    let _executable = arguments.next();
    match (
        arguments.next(),
        arguments.next(),
        arguments.next(),
        arguments.next(),
        arguments.next(),
    ) {
        (Some(key_flag), Some(key_id), Some(seed_flag), Some(seed_file), None)
            if key_flag == "--key-id" && seed_flag == "--seed-file" =>
        {
            let key_id = key_id
                .into_string()
                .map_err(|_| "key id must be valid Unicode".to_owned())?;
            Ok((key_id, PathBuf::from(seed_file)))
        }
        _ => Err(
            "usage: fleet-agent-key-record --key-id <dotted-id> --seed-file <private-seed-file>"
                .to_owned(),
        ),
    }
}

fn run() -> Result<(), String> {
    let (key_id, seed_file) = parse_arguments()?;
    let seed_bytes =
        fs::read(seed_file).map_err(|_| "cannot read the private seed file".to_owned())?;
    let mut seed: [u8; 32] = seed_bytes
        .try_into()
        .map_err(|_| "private seed file must contain exactly 32 bytes".to_owned())?;
    let record = derive_key_record(&key_id, &seed);
    seed.fill(0);

    let stdout = io::stdout();
    let mut output = stdout.lock();
    serde_json::to_writer(&mut output, &record)
        .map_err(|_| "cannot serialize the public key record".to_owned())?;
    output
        .write_all(b"\n")
        .map_err(|_| "cannot write the public key record".to_owned())?;
    output
        .flush()
        .map_err(|_| "cannot flush the public key record".to_owned())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("fleet-agent-key-record: {error}");
        std::process::exit(1);
    }
}
