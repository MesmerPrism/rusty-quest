//! Prepare exact Android package inputs from an accepted Manifold product lock.

use rusty_manifold_broker_product::{ManifoldBrokerProductLock, ManifoldBrokerProductSpec};
use rusty_quest_broker_product::prepare_standalone_android_package;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

fn main() -> ExitCode {
    let args = env::args().skip(1).collect::<Vec<_>>();
    match run(&args) {
        Ok(receipt) => {
            println!("{}", receipt.display());
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn run(args: &[String]) -> Result<PathBuf, String> {
    if args.len() != 3 {
        return Err(
            "usage: prepare_android_broker_product <spec.json> <lock.json> <out-dir>".to_owned(),
        );
    }
    let spec_path = PathBuf::from(&args[0]);
    let lock_path = PathBuf::from(&args[1]);
    let out_dir = PathBuf::from(&args[2]);
    let spec: ManifoldBrokerProductSpec = read_json(&spec_path, "product spec")?;
    let lock: ManifoldBrokerProductLock = read_json(&lock_path, "product lock")?;
    let artifacts = prepare_standalone_android_package(&spec, &lock)
        .map_err(|error| format!("broker product preparation rejected: {error:?}"))?;

    fs::create_dir_all(&out_dir)
        .map_err(|error| format!("create {}: {error}", out_dir.display()))?;
    write(
        &out_dir.join("product-spec.json"),
        &artifacts.product_spec_json,
    )?;
    write(
        &out_dir.join("accepted-product-lock.json"),
        &artifacts.accepted_lock_json,
    )?;
    write(
        &out_dir.join("manifest-projection.json"),
        &artifacts.manifest_projection_json,
    )?;
    write(
        &out_dir.join("command-registry.json"),
        &artifacts.command_registry_json,
    )?;
    write(
        &out_dir.join("AndroidManifest.xml"),
        &artifacts.android_manifest_xml,
    )?;
    let java = out_dir
        .join("generated")
        .join("io")
        .join("github")
        .join("mesmerprism")
        .join("rustymanifold")
        .join("broker")
        .join("GeneratedBrokerProductConfig.java");
    write(&java, &artifacts.generated_product_config_java)?;
    let receipt = out_dir.join("product-package-inputs.json");
    write(&receipt, &artifacts.receipt_json)?;
    Ok(receipt)
}

fn read_json<T: serde::de::DeserializeOwned>(path: &Path, label: &str) -> Result<T, String> {
    let text = fs::read_to_string(path)
        .map_err(|error| format!("read {label} {}: {error}", path.display()))?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(&text);
    serde_json::from_str(text)
        .map_err(|error| format!("decode {label} {}: {error}", path.display()))
}

fn write(path: &Path, contents: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("create {}: {error}", parent.display()))?;
    }
    fs::write(path, contents).map_err(|error| format!("write {}: {error}", path.display()))
}
