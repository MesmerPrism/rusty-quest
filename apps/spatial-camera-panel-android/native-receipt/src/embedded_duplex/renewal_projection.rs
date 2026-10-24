//! Lossless transport encoding for repeated, completed renewal authority.
//!
//! Provider/Manifold state and private archives retain the complete receipt.
//! Only the two named checkpoint copies may refer to the same receipt's media
//! authority. The digest is over the complete original receipt, not this view.
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

const RECEIPT: &str = "rusty.quest.concurrent.authority_renewal_receipt.v3";
const CHECKPOINT: &str = "rusty.manifold.peer.concurrent_media_checkpoint.v2";
const PROJECTION: &str = "rusty.quest.concurrent.authority_renewal_projection.v1";
const MEDIA_REFERENCE: &str = "rusty.quest.concurrent.media_authority_reference.v1";
const CHECKPOINT_REFERENCE: &str = "rusty.quest.concurrent.checkpoint_reference.v1";

fn full_schema(value: &Value) -> bool {
    matches!(
        value.get("$schema").and_then(Value::as_str),
        Some(
            "rusty.quest.concurrent.authority_renewal_receipt.v1"
                | "rusty.quest.concurrent.authority_renewal_receipt.v2"
                | RECEIPT
        )
    )
}

fn digest(value: &Value) -> Result<String, String> {
    let bytes = serde_json::to_vec(value).map_err(|_| "renewal projection JSON")?;
    // The provider already enforces this bound on its complete receipt. Never
    // use transport projection to admit a larger private authority record.
    if bytes.len() >= 1_048_576 {
        return Err("renewal projection complete receipt bounds".into());
    }
    Ok(format!("sha256:{:x}", Sha256::digest(bytes)))
}

pub(super) fn is_projected(value: &Value) -> bool {
    value.get("$schema").and_then(Value::as_str) == Some(PROJECTION)
}

pub(super) fn project(receipt: &Value) -> Result<Value, String> {
    if !full_schema(receipt) {
        return Err("renewal projection receipt schema".into());
    }
    if receipt.get("$schema").and_then(Value::as_str) != Some(RECEIPT)
        || receipt
            .get("continuation_checkpoint")
            .is_none_or(Value::is_null)
    {
        return Ok(receipt.clone());
    }
    let media = receipt
        .get("media_authority")
        .filter(|v| v.is_object())
        .ok_or("renewal projection media authority absent")?;
    let mut body = receipt.clone();
    let checkpoint = body["continuation_checkpoint"]
        .as_object_mut()
        .ok_or("renewal projection checkpoint shape")?;
    if checkpoint.get("$schema").and_then(Value::as_str) != Some(CHECKPOINT) {
        return Err("renewal projection checkpoint schema".into());
    }
    let mut references = 0;
    for field in ["original", "baseline"] {
        let value = checkpoint
            .get_mut(field)
            .filter(|v| v.is_object())
            .ok_or("renewal projection checkpoint authority absent")?;
        if value == media {
            *value = json!({"$schema":MEDIA_REFERENCE});
            references += 1;
        }
    }
    if references == 0 {
        return Ok(receipt.clone());
    }
    Ok(json!({"$schema":PROJECTION,"receipt_sha256":digest(receipt)?,"receipt":body}))
}

/// Restore before any existing semantic/current-owner validation. This does
/// not authenticate a sender or grant authority; the signed frame and actual
/// provider guards keep those responsibilities.
pub(super) fn expand(projection: &Value) -> Result<Value, String> {
    if !is_projected(projection) {
        if !full_schema(projection) {
            return Err("renewal projection receipt schema".into());
        }
        for field in ["original", "baseline"] {
            if projection
                .pointer(&format!("/continuation_checkpoint/{field}/$schema"))
                .and_then(Value::as_str)
                .is_some_and(|s| s.starts_with("rusty.quest.concurrent.media_authority_reference."))
            {
                return Err("renewal projection unwrapped reference".into());
            }
        }
        return Ok(projection.clone());
    }
    let object = projection.as_object().ok_or("renewal projection shape")?;
    if object.len() != 3
        || !object.contains_key("receipt_sha256")
        || !object.contains_key("receipt")
    {
        return Err("renewal projection closed fields".into());
    }
    let mut receipt = projection["receipt"].clone();
    if receipt.get("$schema").and_then(Value::as_str) != Some(RECEIPT) {
        return Err("renewal projection receipt schema".into());
    }
    let media = receipt
        .get("media_authority")
        .filter(|v| v.is_object())
        .cloned()
        .ok_or("renewal projection media authority absent")?;
    let checkpoint = receipt["continuation_checkpoint"]
        .as_object_mut()
        .ok_or("renewal projection checkpoint shape")?;
    for field in ["original", "baseline"] {
        let value = checkpoint
            .get_mut(field)
            .ok_or("renewal projection checkpoint authority absent")?;
        let schema = value.get("$schema").and_then(Value::as_str);
        if schema
            .is_some_and(|s| s.starts_with("rusty.quest.concurrent.media_authority_reference."))
            && schema != Some(MEDIA_REFERENCE)
        {
            return Err("renewal projection reference schema".into());
        }
        if schema == Some(MEDIA_REFERENCE) {
            if value.as_object().is_none_or(|v| v.len() != 1) {
                return Err("renewal projection closed reference".into());
            }
            *value = media.clone();
        }
    }
    if projection.get("receipt_sha256").and_then(Value::as_str) != Some(digest(&receipt)?.as_str())
        || project(&receipt)? != *projection
    {
        return Err("renewal projection exact receipt differs".into());
    }
    Ok(receipt)
}

pub(super) fn checkpoint_reference(receipt: &Value) -> Result<Value, String> {
    if !is_projected(&project(receipt)?) {
        return Err("renewal checkpoint reference requires projected receipt".into());
    }
    Ok(json!({"$schema":CHECKPOINT_REFERENCE,"receipt_sha256":digest(receipt)?}))
}

#[cfg(test)]
#[path = "../../../host-tests/embedded_duplex/renewal_projection.rs"]
mod tests;
