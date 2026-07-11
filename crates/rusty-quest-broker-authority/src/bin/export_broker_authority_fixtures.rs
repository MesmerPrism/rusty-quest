//! Exports deterministic Quest standalone/embedded authority bridge fixtures.

use rusty_manifold_broker_adapter::{ManifoldBrokerAdapterConfig, ManifoldBrokerAdapterMode};
use rusty_manifold_broker_product::ManifoldBrokerProductLock;
use rusty_manifold_model::{DottedId, Revision, SchemaId};
use rusty_manifold_runtime_host::{
    ManifoldRuntimeCommandRequest, ManifoldRuntimeLease, HOST_COMMAND_REQUEST_SCHEMA,
};
use rusty_quest_broker_authority::{
    evaluate_authority_invocation, QuestBrokerAuthorityBridgeKind, QuestBrokerAuthorityInvocation,
    QUEST_BROKER_AUTHORITY_INVOCATION_SCHEMA,
};
use serde::{de::DeserializeOwned, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let (out, manifold_fixtures) = arguments()?;
    fs::create_dir_all(&out)?;
    for mode in [
        ManifoldBrokerAdapterMode::Standalone,
        ManifoldBrokerAdapterMode::Embedded,
    ] {
        export_mode(&out, &manifold_fixtures, mode)?;
    }
    println!("wrote {}", out.display());
    Ok(())
}

fn arguments() -> Result<(PathBuf, PathBuf), String> {
    let mut args = std::env::args().skip(1);
    match (
        args.next().as_deref(),
        args.next(),
        args.next().as_deref(),
        args.next(),
        args.next(),
    ) {
        (Some("--out"), Some(out), Some("--manifold-fixtures"), Some(fixtures), None) => {
            Ok((PathBuf::from(out), PathBuf::from(fixtures)))
        }
        _ => Err("usage: export_broker_authority_fixtures --out <directory> --manifold-fixtures <directory>".to_owned()),
    }
}

fn export_mode(
    out: &Path,
    manifold_fixtures: &Path,
    mode: ManifoldBrokerAdapterMode,
) -> Result<(), Box<dyn std::error::Error>> {
    let name = match mode {
        ManifoldBrokerAdapterMode::Standalone => "standalone",
        ManifoldBrokerAdapterMode::Embedded => "embedded",
    };
    let config: ManifoldBrokerAdapterConfig =
        read_json(manifold_fixtures.join(format!("{name}-config.json")))?;
    let lock: ManifoldBrokerProductLock =
        read_json(manifold_fixtures.join(format!("{name}-product-lock.json")))?;
    for (suffix, request) in [
        (
            "applied",
            request(
                "request.broker.applied",
                "command.media.session.start",
                Some("lease.media.session.client"),
            ),
        ),
        (
            "unknown-rejected",
            request("request.broker.unknown", "command.unknown", None),
        ),
        (
            "unleased-rejected",
            request(
                "request.broker.unleased",
                "command.media.session.start",
                None,
            ),
        ),
    ] {
        let invocation = QuestBrokerAuthorityInvocation {
            schema_id: QUEST_BROKER_AUTHORITY_INVOCATION_SCHEMA.to_owned(),
            bridge_kind: match mode {
                ManifoldBrokerAdapterMode::Standalone => {
                    QuestBrokerAuthorityBridgeKind::StandaloneProcessJni
                }
                ManifoldBrokerAdapterMode::Embedded => {
                    QuestBrokerAuthorityBridgeKind::EmbeddedInProcessJni
                }
            },
            adapter_config: config.clone(),
            product_lock: lock.clone(),
            prior_snapshot: None,
            initial_leases: vec![lease()],
            request,
            now_ms: 2_000,
        };
        let response = evaluate_authority_invocation(&invocation)?;
        write_json(
            out.join(format!("{name}-{suffix}.invocation.json")),
            &invocation,
        )?;
        write_json(
            out.join(format!("{name}-{suffix}.response.json")),
            &response,
        )?;
    }
    Ok(())
}

fn request(
    request_id: &str,
    command_id: &str,
    lease_id: Option<&str>,
) -> ManifoldRuntimeCommandRequest {
    ManifoldRuntimeCommandRequest {
        schema_id: schema(HOST_COMMAND_REQUEST_SCHEMA),
        request_id: id(request_id),
        expected_authority_revision: Revision::new(1).expect("revision"),
        requester_id: id("client.parity"),
        command_id: id(command_id),
        lease_id: lease_id.map(id),
        issued_at_ms: 1_000,
        expires_at_ms: 10_000,
    }
}

fn lease() -> ManifoldRuntimeLease {
    ManifoldRuntimeLease {
        lease_id: id("lease.media.session.client"),
        scope: id("lease.media.session"),
        holder_id: id("client.parity"),
        expires_at_ms: 60_000,
    }
}

fn read_json<T: DeserializeOwned>(path: PathBuf) -> Result<T, Box<dyn std::error::Error>> {
    Ok(serde_json::from_str(&fs::read_to_string(path)?)?)
}

fn write_json(path: PathBuf, value: &impl Serialize) -> Result<(), Box<dyn std::error::Error>> {
    fs::write(path, format!("{}\n", serde_json::to_string_pretty(value)?))?;
    Ok(())
}

fn id(value: &str) -> DottedId {
    DottedId::new(value).expect("static id")
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("static schema")
}
