//! Selected fresh authority history transfers only after actual Native phase
//! custody and a separately authenticated Manifold durable-archive transaction.
use super::*;

pub(super) fn archive_setup(host: &Host, attempt: &Attempt) -> Result<Option<Value>, String> {
    let Some(context) = attempt.context.as_ref() else {
        return Ok(None);
    };
    let binding=serde_json::from_value(json!({"$schema":"rusty.quest.embedded_duplex.fresh_phase_operation_binding.v1",
        "native_request_id":attempt.id,"phase_request_id":attempt.phase_id,"manifold_transaction_id":context.request_id,
        "kind":"pair_setup","context_sha256":fresh_pair_setup_digest("rusty.manifold.peer.fresh_pair_setup_context.v3",context)?,
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,"original_binding":attempt.original_binding})).map_err(safe_decode)?;
    archive(host, binding, attempt.local_setup.is_some())
}

pub(super) fn archive_preparation(
    host: &Host,
    preparation: &preparation::Preparation,
) -> Result<Option<Value>, String> {
    let Some(receipt) = preparation.local_receipt.as_ref() else {
        return Ok(None);
    };
    let binding=serde_json::from_value(json!({"$schema":"rusty.quest.embedded_duplex.fresh_phase_operation_binding.v1",
        "native_request_id":receipt.request.context.setup_request_id,"phase_request_id":preparation.phase_id,
        "manifold_transaction_id":receipt.request.context.request_id,"kind":"enrollment_preparation","context_sha256":receipt.context_sha256,
        "app_process_generation":host.capability.binding.generation,"app_record_sha256":host.capability.binding.record_sha256,
        "native_executor_generation":host.capability.generation,"config_sha256":host.config_sha256,"original_binding":preparation.original_binding})).map_err(safe_decode)?;
    archive(host, binding, true)
}

fn archive(
    host: &Host,
    binding: rusty_manifold_peer::ManifoldFreshArchiveOperationBinding,
    required: bool,
) -> Result<Option<Value>, String> {
    host.capability.require_live()?;
    // An exact retained signed cursor makes a post-commit retry idempotent. It
    // does not claim any Native/remote effect completed or restore a new writer.
    let snapshot: rusty_manifold_peer_runtime_host::ManifoldPeerRuntimeHostSnapshot =
        serde_json::from_str(&host.authority.snapshot_json()?).map_err(safe_decode)?;
    if let Some(cursor) = snapshot
        .fresh_pair_setup
        .as_ref()
        .and_then(|s| s.archive.as_ref())
    {
        if cursor.last_ack.context.operation_binding == binding {
            return Ok(Some(serde_json::to_value(cursor).map_err(safe_decode)?));
        }
    }
    let location = host
        .authority
        .fresh_history_receipt_location(&binding.manifold_transaction_id, binding.kind.clone())?;
    match location {
        rusty_manifold_peer_runtime_host::ManifoldFreshReceiptLocation::Live { .. } => {}
        rusty_manifold_peer_runtime_host::ManifoldFreshReceiptLocation::NotRetained {
            archive_cursor: None,
        } => {
            // There is no fresh transaction to prune. This result grants no
            // replay/absence permission and never clears pending Native custody.
            if required {
                return Err("earned actual phase receipt is absent from current owner".into());
            }
            return Ok(None);
        }
        rusty_manifold_peer_runtime_host::ManifoldFreshReceiptLocation::NotRetained { .. } => {
            return Err("phase receipt requires authenticated historical archive lookup".into());
        }
    }
    let cursor = host.authority.archive_fresh_history(binding, |raw| {
        host.callbacks.persist_manifold_fresh_history_archive(raw)
    })?;
    Ok(Some(serde_json::to_value(cursor).map_err(safe_decode)?))
}
