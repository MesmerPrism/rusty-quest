//! Validate a reusable direct-P2P socket-route plan.

use std::env;
use std::fs;
use std::process::ExitCode;

use rusty_quest_device_link::{validate_direct_p2p_socket_route, DirectP2pSocketRoute};
use serde_json::json;

fn main() -> ExitCode {
    let args = env::args().collect::<Vec<_>>();
    if args.len() != 2 {
        eprintln!("usage: validate_direct_p2p_socket_route <path>");
        return ExitCode::from(2);
    }
    let path = &args[1];
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
    let validation = serde_json::from_str::<DirectP2pSocketRoute>(&text)
        .map_err(|error| vec![error.to_string()])
        .and_then(|route| {
            validate_direct_p2p_socket_route(&route)
                .map(|_| ())
                .map_err(|errors| errors.into_iter().map(|error| error.message).collect())
        });
    match validation {
        Ok(()) => {
            println!(
                "{}",
                json!({
                    "schema": "rusty.quest.direct_p2p_socket_route_validation.v1",
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
                    "schema": "rusty.quest.direct_p2p_socket_route_validation.v1",
                    "status": "fail",
                    "path": path,
                    "issues": issues
                })
            );
            ExitCode::from(1)
        }
    }
}
