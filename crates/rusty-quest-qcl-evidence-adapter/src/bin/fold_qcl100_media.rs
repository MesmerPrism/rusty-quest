//! Fold one typed, fresh QCL100 receipt into generic media evidence.

use std::{fs, path::PathBuf};

use rusty_quest_qcl_evidence_adapter::{fold_qcl100_media_evidence, Qcl100EvidenceAdapterRequest};

fn main() {
    let mut args = std::env::args_os().skip(1).map(PathBuf::from);
    let input = args
        .next()
        .expect("usage: fold_qcl100_media <request.json> <output.json>");
    let output = args
        .next()
        .expect("usage: fold_qcl100_media <request.json> <output.json>");
    assert!(args.next().is_none(), "unexpected extra arguments");
    let request: Qcl100EvidenceAdapterRequest =
        serde_json::from_slice(&fs::read(input).expect("read request")).expect("parse request");
    let receipt = fold_qcl100_media_evidence(&request)
        .unwrap_or_else(|errors| panic!("QCL evidence rejected: {}", errors.join("; ")));
    fs::write(
        output,
        serde_json::to_vec_pretty(&receipt).expect("serialize receipt"),
    )
    .expect("write receipt");
}
