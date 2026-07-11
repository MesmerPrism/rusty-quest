//! Validate one product Wi-Fi Direct run receipt from a JSON file.

use std::{env, fs, process::ExitCode};

use rusty_quest_device_link::{validate_product_wifi_direct_run, ProductWifiDirectRunReceipt};

fn main() -> ExitCode {
    let Some(path) = env::args().nth(1) else {
        eprintln!("usage: validate_product_wifi_direct_run <receipt.json>");
        return ExitCode::FAILURE;
    };
    let text = match fs::read_to_string(&path) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot read {path}: {error}");
            return ExitCode::FAILURE;
        }
    };
    let receipt: ProductWifiDirectRunReceipt = match serde_json::from_str(&text) {
        Ok(value) => value,
        Err(error) => {
            eprintln!("cannot parse {path}: {error}");
            return ExitCode::FAILURE;
        }
    };
    match validate_product_wifi_direct_run(&receipt) {
        Ok(()) => {
            println!("product Wi-Fi Direct receipt validates: {path}");
            ExitCode::SUCCESS
        }
        Err(errors) => {
            for error in errors {
                eprintln!("{}", error.message);
            }
            ExitCode::FAILURE
        }
    }
}
