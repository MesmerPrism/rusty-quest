//! Lossless transport encoding for repeated, completed renewal authority.
//!
//! Provider/Manifold state and private archives retain the complete receipt.
//! Only the two named checkpoint copies may refer to the same receipt's media
//! authority. The digest is over the complete original receipt, not this view.
use crate::WireError;
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

/// Hash complete canonical JSON, preserving the original exclusive 1 MiB bound.
///
/// # Errors
/// Returns a JSON serialization or complete-receipt size refusal.
pub fn receipt_sha256(value: &Value) -> Result<String, WireError> {
    let bytes = serde_json::to_vec(value).map_err(|_| WireError::Json)?;
    // The provider already enforces this bound on its complete receipt. Never
    // use transport projection to admit a larger private authority record.
    if bytes.len() >= 1_048_576 {
        return Err(WireError::CompleteReceiptBounds);
    }
    Ok(format!("sha256:{:x}", Sha256::digest(bytes)))
}

/// Identify only the exact supported projection schema; this does not validate it.
#[must_use]
pub fn is_projected(value: &Value) -> bool {
    value.get("$schema").and_then(Value::as_str) == Some(PROJECTION)
}

/// Losslessly replace the two equal, named checkpoint copies with references.
/// Legacy, uncheckpointed and unequal-slot pass-through behavior is unchanged.
///
/// # Errors
/// Returns the original schema, shape, authority or complete-receipt size refusal.
pub fn project_renewal(receipt: &Value) -> Result<Value, WireError> {
    if !full_schema(receipt) {
        return Err(WireError::ReceiptSchema);
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
        .ok_or(WireError::MediaAuthorityAbsent)?;
    let mut body = receipt.clone();
    let checkpoint = body["continuation_checkpoint"]
        .as_object_mut()
        .ok_or(WireError::CheckpointShape)?;
    if checkpoint.get("$schema").and_then(Value::as_str) != Some(CHECKPOINT) {
        return Err(WireError::CheckpointSchema);
    }
    let mut references = 0;
    for field in ["original", "baseline"] {
        let value = checkpoint
            .get_mut(field)
            .filter(|v| v.is_object())
            .ok_or(WireError::CheckpointAuthorityAbsent)?;
        if value == media {
            *value = json!({"$schema":MEDIA_REFERENCE});
            references += 1;
        }
    }
    if references == 0 {
        return Ok(receipt.clone());
    }
    Ok(json!({"$schema":PROJECTION,"receipt_sha256":receipt_sha256(receipt)?,"receipt":body}))
}

/// Restore before any existing semantic/current-owner validation. This does
/// not authenticate a sender or grant authority; the signed frame and actual
/// provider guards keep those responsibilities.
///
/// # Errors
/// Returns the original schema, reference, digest, size or exact-reprojection refusal.
pub fn expand_renewal(projection: &Value) -> Result<Value, WireError> {
    if !is_projected(projection) {
        if !full_schema(projection) {
            return Err(WireError::ReceiptSchema);
        }
        for field in ["original", "baseline"] {
            if projection
                .pointer(&format!("/continuation_checkpoint/{field}/$schema"))
                .and_then(Value::as_str)
                .is_some_and(|s| s.starts_with("rusty.quest.concurrent.media_authority_reference."))
            {
                return Err(WireError::UnwrappedReference);
            }
        }
        return Ok(projection.clone());
    }
    let object = projection.as_object().ok_or(WireError::ProjectionShape)?;
    if object.len() != 3
        || !object.contains_key("receipt_sha256")
        || !object.contains_key("receipt")
    {
        return Err(WireError::ClosedFields);
    }
    let mut receipt = projection["receipt"].clone();
    if receipt.get("$schema").and_then(Value::as_str) != Some(RECEIPT) {
        return Err(WireError::ReceiptSchema);
    }
    let media = receipt
        .get("media_authority")
        .filter(|v| v.is_object())
        .cloned()
        .ok_or(WireError::MediaAuthorityAbsent)?;
    let checkpoint = receipt["continuation_checkpoint"]
        .as_object_mut()
        .ok_or(WireError::CheckpointShape)?;
    for field in ["original", "baseline"] {
        let value = checkpoint
            .get_mut(field)
            .ok_or(WireError::CheckpointAuthorityAbsent)?;
        let schema = value.get("$schema").and_then(Value::as_str);
        if schema
            .is_some_and(|s| s.starts_with("rusty.quest.concurrent.media_authority_reference."))
            && schema != Some(MEDIA_REFERENCE)
        {
            return Err(WireError::ReferenceSchema);
        }
        if schema == Some(MEDIA_REFERENCE) {
            if value.as_object().is_none_or(|v| v.len() != 1) {
                return Err(WireError::ClosedReference);
            }
            *value = media.clone();
        }
    }
    if projection.get("receipt_sha256").and_then(Value::as_str)
        != Some(receipt_sha256(&receipt)?.as_str())
        || project_renewal(&receipt)? != *projection
    {
        return Err(WireError::ExactReceiptDiffers);
    }
    Ok(receipt)
}

/// Produce a digest reference only for a full receipt which can be projected.
///
/// # Errors
/// Returns the original projection refusal or a nonprojectable-receipt refusal.
pub fn checkpoint_reference(receipt: &Value) -> Result<Value, WireError> {
    if !is_projected(&project_renewal(receipt)?) {
        return Err(WireError::CheckpointReferenceRequiresProjectedReceipt);
    }
    Ok(json!({"$schema":CHECKPOINT_REFERENCE,"receipt_sha256":receipt_sha256(receipt)?}))
}
