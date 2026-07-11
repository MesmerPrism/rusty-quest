//! Fold live BLE pair evidence plus public advisory inputs into Manifold mesh decisions.

use std::{fs, path::PathBuf};

use rusty_manifold_model::DottedId;
use rusty_quest_device_link::BleRendezvousPairReceipt;
use rusty_quest_peer_session_adapter::{
    evaluate_configured_n_peer_mesh, QuestPeerMeshProjectionConfig,
};

fn main() {
    let mut args = std::env::args_os().skip(1).map(PathBuf::from);
    let pair_path = args.next().expect("pair receipt path");
    let termux_path = args.next().expect("Termux source profile path");
    let sidecar_path = args.next().expect("sidecar configured-peer plan path");
    let output_path = args.next().expect("output path");
    assert!(args.next().is_none(), "unexpected extra arguments");
    let pair: BleRendezvousPairReceipt = read(&pair_path);
    let termux = read(&termux_path);
    let sidecar = read(&sidecar_path);
    let bundle = evaluate_configured_n_peer_mesh(
        &pair,
        &termux,
        &sidecar,
        &QuestPeerMeshProjectionConfig {
            subject_peer_id: id("peer.alpha"),
            candidate_peer_id: id("peer.beta"),
            configured_peer_id: id("peer.gamma"),
            proposer_id: id("adapter.quest.peer-mesh"),
            now_ms: 1_000_000,
            live_status_ttl_ms: 120_000,
            configured_status_ttl_ms: 30_000,
            route_ttl_ms: 60_000,
            live_pair_latency_ms: 12,
        },
    )
    .unwrap_or_else(|error| panic!("N-peer evaluation rejected: {error}"));
    fs::write(
        output_path,
        serde_json::to_vec_pretty(&bundle).expect("serialize bundle"),
    )
    .expect("write bundle");
}

fn read<T: serde::de::DeserializeOwned>(path: &PathBuf) -> T {
    serde_json::from_slice(&fs::read(path).expect("read input")).expect("parse input")
}

fn id(value: &str) -> DottedId {
    DottedId::new(value).expect("static id")
}
