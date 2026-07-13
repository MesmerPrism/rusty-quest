//! Assembles one NET-016 lifecycle evidence file from Rust-owned completion
//! responses plus app/runtime render and recovery observations.

use std::{env, fs, process};

use rusty_quest_broker_client::{
    assemble_media_lifecycle_evidence, BrokerMediaLifecycleAssemblyEvidence,
    BrokerMediaLifecycleCompletionResponse, BrokerMediaLifecyclePackageBinding,
};

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
            "usage: {} <package.json> <start-completion.json> <stop-completion.json> <assembly-evidence.json>",
            args.first()
                .map(String::as_str)
                .unwrap_or("assemble_media_lifecycle_evidence")
        )]);
    }
    let package = read_json::<BrokerMediaLifecyclePackageBinding>(&args[1], "package")?;
    let start = read_json::<BrokerMediaLifecycleCompletionResponse>(&args[2], "start completion")?;
    let stop = read_json::<BrokerMediaLifecycleCompletionResponse>(&args[3], "stop completion")?;
    let assembly =
        read_json::<BrokerMediaLifecycleAssemblyEvidence>(&args[4], "assembly evidence")?;
    let evidence = assemble_media_lifecycle_evidence(&package, &start, &stop, assembly)?;
    println!(
        "{}",
        serde_json::to_string_pretty(&evidence)
            .map_err(|error| vec![format!("evidence serialization failed: {error}")])?
    );
    Ok(())
}

fn read_json<T: serde::de::DeserializeOwned>(path: &str, label: &str) -> Result<T, Vec<String>> {
    let text =
        fs::read_to_string(path).map_err(|error| vec![format!("{label} read failed: {error}")])?;
    serde_json::from_str(&text).map_err(|error| vec![format!("{label} JSON invalid: {error}")])
}
