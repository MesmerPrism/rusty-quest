//! Fold a QCL100 validation artifact into the generic media-session evidence shape.

use std::fs;
use std::path::PathBuf;

use rusty_quest_broker_client::{fold_qcl100_media_evidence, Qcl100MediaEvidenceInput};

fn main() {
    let mut args = std::env::args_os().skip(1).map(PathBuf::from);
    let input_path = args
        .next()
        .expect("usage: fold_qcl100_media <input.json> <output.json>");
    let output_path = args
        .next()
        .expect("usage: fold_qcl100_media <input.json> <output.json>");
    assert!(args.next().is_none(), "unexpected extra arguments");
    let input: Qcl100MediaEvidenceInput =
        serde_json::from_slice(&fs::read(&input_path).expect("read input")).expect("parse input");
    let receipt = fold_qcl100_media_evidence(&input)
        .unwrap_or_else(|errors| panic!("evidence rejected: {}", errors.join("; ")));
    fs::write(
        &output_path,
        serde_json::to_vec_pretty(&receipt).expect("serialize receipt"),
    )
    .expect("write output");
    println!("{}", output_path.display());
}
