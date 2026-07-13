//! Evaluate a BLE pair artifact and write a Manifold peer-session bundle.

use std::{env, fs, path::Path, process::ExitCode};

use rusty_manifold_model::{DottedId, Revision};
use rusty_quest_device_link::BleRendezvousPairReceipt;
use rusty_quest_peer_session_adapter::{
    evaluate_signed_ble_pair_for_peer_session, QuestPeerSessionAuthorityEvidence,
    QuestPeerSessionProjectionConfig,
};

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();
    if args.len() != 5 {
        eprintln!("usage: evaluate_ble_pair <pair.json> <authority.json> <out.json> <now-ms>");
        return ExitCode::FAILURE;
    }
    let result = (|| -> Result<(), String> {
        let pair: BleRendezvousPairReceipt =
            serde_json::from_str(&fs::read_to_string(&args[1]).map_err(|error| error.to_string())?)
                .map_err(|error| error.to_string())?;
        let authority: QuestPeerSessionAuthorityEvidence =
            serde_json::from_str(&fs::read_to_string(&args[2]).map_err(|error| error.to_string())?)
                .map_err(|error| error.to_string())?;
        let now_ms = args[4].parse::<u64>().map_err(|error| error.to_string())?;
        let id = |value: &str| DottedId::new(value).map_err(|error| error.to_string());
        let config = QuestPeerSessionProjectionConfig {
            subject_peer_id: id("peer.alpha")?,
            candidate_peer_id: id("peer.beta")?,
            group_owner_peer_id: id("peer.alpha")?,
            client_peer_id: id("peer.beta")?,
            adapter_id: id("adapter.quest.ble-rendezvous")?,
            expected_authority_revision: Revision::INITIAL,
            now_ms,
            authorization_ttl_ms: 120_000,
        };
        let bundle = evaluate_signed_ble_pair_for_peer_session(&pair, &config, &authority)?;
        let out = Path::new(&args[3]);
        if let Some(parent) = out.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        fs::write(
            out,
            serde_json::to_vec_pretty(&bundle).map_err(|error| error.to_string())?,
        )
        .map_err(|error| error.to_string())?;
        Ok(())
    })();
    match result {
        Ok(()) => {
            println!("{}", args[3]);
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
