//! Validate a BLE rendezvous wire message or sidecar receipt.

use std::env;
use std::fs;
use std::process::ExitCode;

use rusty_quest_device_link::{
    validate_ble_rendezvous_message, validate_ble_rendezvous_pair_receipt,
    validate_ble_rendezvous_sidecar_receipt, BleRendezvousMessage, BleRendezvousPairReceipt,
    BleRendezvousSidecarReceipt,
};
use serde_json::json;

fn main() -> ExitCode {
    let args = env::args().collect::<Vec<_>>();
    if args.len() != 3 || !matches!(args[1].as_str(), "message" | "receipt" | "pair") {
        eprintln!("usage: validate_ble_rendezvous <message|receipt|pair> <path>");
        return ExitCode::from(2);
    }
    let path = &args[2];
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(error) => {
            eprintln!(
                "{}",
                json!({"status":"fail","issue":"read_failed","detail":error.to_string()})
            );
            return ExitCode::from(1);
        }
    };
    let validation = if args[1] == "message" {
        serde_json::from_str::<BleRendezvousMessage>(&text)
            .map_err(|error| vec![error.to_string()])
            .and_then(|message| {
                validate_ble_rendezvous_message(&message)
                    .map_err(|errors| errors.into_iter().map(|error| error.message).collect())
            })
    } else if args[1] == "receipt" {
        serde_json::from_str::<BleRendezvousSidecarReceipt>(&text)
            .map_err(|error| vec![error.to_string()])
            .and_then(|receipt| {
                validate_ble_rendezvous_sidecar_receipt(&receipt)
                    .map_err(|errors| errors.into_iter().map(|error| error.message).collect())
            })
    } else {
        serde_json::from_str::<BleRendezvousPairReceipt>(&text)
            .map_err(|error| vec![error.to_string()])
            .and_then(|pair| {
                validate_ble_rendezvous_pair_receipt(&pair)
                    .map_err(|errors| errors.into_iter().map(|error| error.message).collect())
            })
    };
    match validation {
        Ok(()) => {
            println!(
                "{}",
                json!({
                    "schema": "rusty.quest.ble_rendezvous_validation.v1",
                    "kind": args[1],
                    "status": "pass",
                    "path": path
                })
            );
            ExitCode::SUCCESS
        }
        Err(issues) => {
            eprintln!(
                "{}",
                json!({
                    "schema": "rusty.quest.ble_rendezvous_validation.v1",
                    "kind": args[1],
                    "status": "fail",
                    "path": path,
                    "issues": issues
                })
            );
            ExitCode::from(1)
        }
    }
}
