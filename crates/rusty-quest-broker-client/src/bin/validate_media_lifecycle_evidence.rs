//! Validates paired NET-016 broker media lifecycle evidence files.

use std::{env, fs, process};

use rusty_quest_broker_client::{
    validate_media_lifecycle_evidence, validate_media_lifecycle_package_pair,
    BrokerMediaLifecycleEvidence, BrokerMediaLifecyclePackageBinding,
};
use serde::Serialize;

#[derive(Serialize)]
struct PairReceipt<'a> {
    #[serde(rename = "$schema")]
    schema: &'a str,
    status: &'a str,
    native: rusty_quest_broker_client::BrokerMediaLifecycleReceipt,
    spatial: rusty_quest_broker_client::BrokerMediaLifecycleReceipt,
    package_pair_no_bleed: bool,
}

fn main() {
    if let Err(errors) = run() {
        for error in errors {
            eprintln!("{error}");
        }
        process::exit(1);
    }
}

fn run() -> Result<(), Vec<String>> {
    let args = env::args().collect::<Vec<_>>();
    if args.len() != 5 {
        return Err(vec![format!(
            "usage: {} <native-package.json> <native-evidence.json> <spatial-package.json> <spatial-evidence.json>",
            args.first()
                .map(String::as_str)
                .unwrap_or("validate_media_lifecycle_evidence")
        )]);
    }
    let native_package =
        read_json::<BrokerMediaLifecyclePackageBinding>(&args[1], "native package")?;
    let native_evidence = read_json::<BrokerMediaLifecycleEvidence>(&args[2], "native evidence")?;
    let spatial_package =
        read_json::<BrokerMediaLifecyclePackageBinding>(&args[3], "spatial package")?;
    let spatial_evidence = read_json::<BrokerMediaLifecycleEvidence>(&args[4], "spatial evidence")?;

    validate_media_lifecycle_package_pair(&native_package, &spatial_package)
        .map_err(|errors| prefix("package pair", errors))?;
    let native = validate_media_lifecycle_evidence(&native_package, &native_evidence)
        .map_err(|errors| prefix("native lifecycle", errors))?;
    let spatial = validate_media_lifecycle_evidence(&spatial_package, &spatial_evidence)
        .map_err(|errors| prefix("spatial lifecycle", errors))?;
    let receipt = PairReceipt {
        schema: "rusty.quest.broker_media_lifecycle_pair_receipt.v1",
        status: "pass",
        native,
        spatial,
        package_pair_no_bleed: true,
    };
    println!(
        "{}",
        serde_json::to_string_pretty(&receipt)
            .map_err(|error| vec![format!("receipt serialization failed: {error}")])?
    );
    Ok(())
}

fn read_json<T: serde::de::DeserializeOwned>(path: &str, label: &str) -> Result<T, Vec<String>> {
    let text =
        fs::read_to_string(path).map_err(|error| vec![format!("{label} read failed: {error}")])?;
    serde_json::from_str(&text).map_err(|error| vec![format!("{label} JSON invalid: {error}")])
}

fn prefix(label: &str, errors: Vec<String>) -> Vec<String> {
    errors
        .into_iter()
        .map(|error| format!("{label}: {error}"))
        .collect()
}
