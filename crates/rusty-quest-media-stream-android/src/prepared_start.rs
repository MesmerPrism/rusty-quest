//! SDK resources prepared behind a closed delivery gate before time-bound
//! media admission. These receipts do not grant media/route authority.
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::sync::Arc;

pub const ANDROID_PREPARED_START_FRAME_MAGIC: &[u8] = b"RQPS1";
const FRAME_DOMAIN: &[u8] = b"rusty.quest.embedded_duplex.prepared_start_frame.v1\0";
/// Signed metadata only: actual current source/Stop admission remains in the
/// native owner. No elapsed-age or media-validity authority is embedded here.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedStartFrameBody {
    #[serde(rename = "$schema")]
    pub schema: String,
    pub kind: String,
    pub request_id: String,
    pub sender_preparation_id: String,
    pub receiver_preparation_id: Option<String>,
    pub sender_peer_id: String,
    pub receiver_peer_id: String,
    pub route_configuration_sha256: String,
    pub issued_at_ms: u64,
    pub payload: Value,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct PreparedFrameEnvelope {
    body: AndroidPreparedStartFrameBody,
    signature_hex: String,
}
pub fn validate_prepared_start_frame(b: &AndroidPreparedStartFrameBody) -> Result<(), String> {
    let keys: &[&str] = match b.kind.as_str() {
        "view" => &["view"],
        "viewed" => &["initiator_view_sha256", "view"],
        "decoder_prepare" => &["initiator_view", "responder_view", "sender_receipt"],
        "decoder_prepared" => &[
            "initiator_view_sha256",
            "responder_view_sha256",
            "decoder_receipt",
        ],
        _ => return Err("prepared frame kind".into()),
    };
    if !hex(&b.request_id, 32)
        || !hex(&b.sender_preparation_id, 32)
        || b.receiver_preparation_id
            .as_ref()
            .is_some_and(|s| !hex(s, 32))
        || b.sender_peer_id.is_empty()
        || b.receiver_peer_id.is_empty()
        || b.sender_peer_id == b.receiver_peer_id
        || !hex(&b.route_configuration_sha256, 64)
        || b.schema != "rusty.quest.embedded_duplex.prepared_start_frame.v1"
        || !exact(&b.payload, keys)
        || (b.kind == "view") != b.receiver_preparation_id.is_none()
        || matches!(b.kind.as_str(), "view" | "decoder_prepare")
            && b.request_id != b.sender_preparation_id
        || matches!(b.kind.as_str(), "viewed" | "decoder_prepared")
            && b.receiver_preparation_id.as_deref() != Some(b.request_id.as_str())
    {
        return Err("prepared closed frame shape/identity".into());
    }
    Ok(())
}
pub fn prepared_start_frame_signing_bytes(
    b: &AndroidPreparedStartFrameBody,
) -> Result<Vec<u8>, String> {
    validate_prepared_start_frame(b)?;
    let mut out = FRAME_DOMAIN.to_vec();
    out.extend(prepared_start_canonical(b)?);
    Ok(out)
}
pub fn encode_prepared_start_frame(
    body: AndroidPreparedStartFrameBody,
    signature: [u8; 64],
) -> Result<Vec<u8>, String> {
    validate_prepared_start_frame(&body)?;
    let mut raw = ANDROID_PREPARED_START_FRAME_MAGIC.to_vec();
    raw.extend(
        serde_json::to_vec(&PreparedFrameEnvelope {
            body,
            signature_hex: signature.iter().map(|b| format!("{b:02x}")).collect(),
        })
        .map_err(|_| "prepared wire serialization")?,
    );
    if raw.len() > 128 * 1024 {
        return Err("prepared metadata structural transport bound".into());
    }
    Ok(raw)
}
pub fn decode_prepared_start_frame(
    raw: &[u8],
    peer_key: &[u8; 32],
    sender: &str,
    receiver: &str,
    route: &str,
    now: u64,
) -> Result<AndroidPreparedStartFrameBody, String> {
    use ed25519_dalek::{Signature, VerifyingKey};
    if !raw.starts_with(ANDROID_PREPARED_START_FRAME_MAGIC) || raw.len() > 128 * 1024 {
        return Err("prepared wire bounds".into());
    }
    let e: PreparedFrameEnvelope =
        serde_json::from_slice(&raw[ANDROID_PREPARED_START_FRAME_MAGIC.len()..])
            .map_err(|_| "prepared closed wire JSON")?;
    validate_prepared_start_frame(&e.body)?;
    if e.body.sender_peer_id != sender
        || e.body.receiver_peer_id != receiver
        || e.body.route_configuration_sha256 != route
        || e.body.issued_at_ms > now
        || !hex(&e.signature_hex, 128)
    {
        return Err("prepared signed peer/route/audit binding".into());
    }
    let mut signature = [0u8; 64];
    for (i, b) in signature.iter_mut().enumerate() {
        *b = u8::from_str_radix(&e.signature_hex[2 * i..2 * i + 2], 16)
            .map_err(|_| "prepared signature hex")?;
    }
    VerifyingKey::from_bytes(peer_key)
        .map_err(|_| "prepared enrolled key")?
        .verify_strict(
            &prepared_start_frame_signing_bytes(&e.body)?,
            &Signature::from_bytes(&signature),
        )
        .map_err(|_| "prepared metadata signature")?;
    Ok(e.body)
}

pub fn prepared_start_canonical<T: Serialize>(value: &T) -> Result<Vec<u8>, String> {
    let value = serde_json::to_value(value).map_err(|_| "prepared canonical serialization")?;
    fn valid(v: &Value) -> bool {
        match v {
            Value::Number(n) => n.as_i64().is_some(),
            Value::Array(a) => a.iter().all(valid),
            Value::Object(o) => {
                o.keys()
                    .all(|k| !k.is_empty() && k.bytes().all(|b| (0x20..=0x7e).contains(&b)))
                    && o.values().all(valid)
            }
            _ => true,
        }
    }
    if !valid(&value) {
        return Err("prepared canonical signed64/ASCII keys".into());
    }
    // Explicit sorting is required even if a downstream crate enables
    // serde_json/preserve_order through Cargo feature unification.
    fn emit(v: &Value, out: &mut Vec<u8>) -> Result<(), String> {
        match v {
            Value::Object(o) => {
                out.push(b'{');
                let mut keys: Vec<_> = o.keys().collect();
                keys.sort();
                for (i, k) in keys.into_iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    out.extend(serde_json::to_vec(k).map_err(|_| "prepared canonical key")?);
                    out.push(b':');
                    emit(&o[k], out)?;
                }
                out.push(b'}');
            }
            Value::Array(a) => {
                out.push(b'[');
                for (i, v) in a.iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    emit(v, out)?;
                }
                out.push(b']');
            }
            _ => out.extend(serde_json::to_vec(v).map_err(|_| "prepared canonical value")?),
        }
        Ok(())
    }
    let mut bytes = Vec::new();
    emit(&value, &mut bytes)?;
    Ok(bytes)
}
pub fn prepared_start_digest<T: Serialize>(domain: &str, value: &T) -> Result<String, String> {
    let mut h = Sha256::new();
    h.update(domain.as_bytes());
    h.update([0]);
    h.update(prepared_start_canonical(value)?);
    Ok(format!("sha256:{:x}", h.finalize()))
}
fn hex(s: &str, n: usize) -> bool {
    s.len() == n
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn hash(s: &str) -> bool {
    s.strip_prefix("sha256:").is_some_and(|s| hex(s, 64))
}
fn exact(v: &Value, keys: &[&str]) -> bool {
    v.as_object()
        .is_some_and(|o| o.len() == keys.len() && keys.iter().all(|k| o.contains_key(*k)))
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedStartRequest {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub original_id: String,
    pub app_process_generation: u64,
    pub app_record_sha256: String,
    pub native_executor_generation: u64,
    pub config_sha256: String,
    pub arm_binding: Value,
    pub configuration_binding: Value,
    pub resource_target: Value,
    pub resource_target_sha256: String,
}
impl AndroidPreparedStartRequest {
    pub fn validate(&self) -> Result<(), String> {
        let target = &self.resource_target;
        let arm = &self.arm_binding;
        let binding = &self.configuration_binding;
        if self.schema_id != "rusty.quest.embedded_duplex.prepared_start_request.v1"
            || !hex(&self.preparation_id, 32)
            || !hex(&self.original_id, 32)
            || self.app_process_generation == 0
            || self.native_executor_generation == 0
            || !hex(&self.app_record_sha256, 64)
            || !hex(&self.config_sha256, 64)
            || !exact(
                target,
                &[
                    "$schema",
                    "runtime_config_sha256",
                    "executor_generation",
                    "product_binding",
                    "source_dimensions",
                    "own_capture_selected",
                    "max_pair_delta_ns",
                    "outgoing_runtime_spec_id",
                    "incoming_runtime_spec_id",
                ],
            )
            || target["$schema"] != "rusty.quest.embedded_duplex.peer_start_resource_target.v1"
            || target["runtime_config_sha256"] != self.config_sha256
            || target["executor_generation"] != self.native_executor_generation
            || target["own_capture_selected"].as_bool().is_none()
            || target["max_pair_delta_ns"].as_u64().is_none_or(|n| n == 0)
            || self.resource_target_sha256
                != prepared_start_digest(
                    "rusty.quest.embedded_duplex.peer_start_resource_target.v1",
                    target,
                )?
            || !exact(
                arm,
                &[
                    "binding",
                    "challenge",
                    "arm_generation",
                    "native_process",
                    "process_epoch",
                    "runtime_config_sha256",
                ],
            )
            || arm["binding"] != "established"
            || arm["runtime_config_sha256"] != self.config_sha256
            || arm["arm_generation"].as_u64().is_none_or(|n| n == 0)
            || arm["native_process"].as_u64().is_none_or(|n| n == 0)
            || !exact(
                binding,
                &[
                    "$schema",
                    "config_sha256",
                    "app_process_generation",
                    "app_record_sha256",
                    "native_executor_generation",
                    "local_peer_id",
                    "fresh_setup_build_policy",
                ],
            )
            || binding["$schema"] != "rusty.quest.embedded_duplex.runtime_configuration_binding.v1"
            || binding["config_sha256"] != self.config_sha256
            || binding["app_process_generation"] != self.app_process_generation
            || binding["app_record_sha256"] != self.app_record_sha256
            || binding["native_executor_generation"] != self.native_executor_generation
            || !exact(
                &binding["fresh_setup_build_policy"],
                &[
                    "$schema",
                    "selection",
                    "policy",
                    "policy_sha256",
                    "enrollment_selection_sha256",
                ],
            )
            || binding["fresh_setup_build_policy"]["selection"] != "source-bound-v2"
        {
            return Err("prepared actual constructor/ARM/actor/target differs".into());
        }
        let b = &target["product_binding"];
        let shape = &target["source_dimensions"];
        if !exact(b, &["$schema", "product_id", "providers"])
            || b["$schema"] != "rusty.quest.android.media.preparation_target.v1"
            || b["product_id"] != target["outgoing_runtime_spec_id"]
            || b["providers"].as_array().is_none_or(|a| a.len() != 7)
            || !exact(
                shape,
                &[
                    "packed_width",
                    "packed_height",
                    "per_eye_width",
                    "per_eye_height",
                    "frame_rate_hz",
                    "bitrate_bps",
                    "left_camera_id",
                    "right_camera_id",
                ],
            )
            || [
                "packed_width",
                "packed_height",
                "per_eye_width",
                "per_eye_height",
                "frame_rate_hz",
                "bitrate_bps",
            ]
            .iter()
            .any(|k| shape[*k].as_u64().is_none_or(|n| n == 0))
            || shape["packed_width"].as_u64()
                != shape["per_eye_width"]
                    .as_u64()
                    .and_then(|w| w.checked_mul(2))
            || shape["packed_height"] != shape["per_eye_height"]
        {
            return Err("prepared actual seven-owner/source shape".into());
        }
        let rows = b["providers"].as_array().ok_or("prepared provider rows")?;
        let mut keys = std::collections::BTreeSet::new();
        for row in rows {
            if !exact(
                row,
                &["owner_kind", "owner_id", "provider_kind", "resource_id"],
            ) || ["owner_kind", "owner_id", "provider_kind", "resource_id"]
                .iter()
                .any(|k| row[*k].as_str().is_none_or(str::is_empty))
                || !keys.insert(prepared_start_canonical(row)?)
            {
                return Err("prepared duplicate/foreign provider row".into());
            }
        }
        Ok(())
    }
    pub fn digest(&self) -> Result<String, String> {
        self.validate()?;
        prepared_start_digest(
            "rusty.quest.embedded_duplex.prepared_start_request.v1",
            self,
        )
    }
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedEncodedFormat {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub mime: String,
    pub width: u32,
    pub height: u32,
    pub frame_rate_hz: u32,
    pub csd_0_hex: String,
    pub csd_1_hex: Option<String>,
    pub profile: Option<i32>,
    pub level: Option<i32>,
}
impl AndroidPreparedEncodedFormat {
    pub fn validate(&self) -> Result<(), String> {
        if self.schema_id != "rusty.quest.android.media.prepared_encoded_format.v1"
            || self.mime != "video/avc"
            || self.width == 0
            || self.height == 0
            || self.frame_rate_hz == 0
            || self.csd_0_hex.is_empty()
            || self.csd_0_hex.len() > 65536
            || self.csd_0_hex.len() % 2 != 0
            || !hex(&self.csd_0_hex, self.csd_0_hex.len())
            || self.csd_1_hex.as_ref().is_some_and(|s| {
                s.is_empty() || s.len() > 65536 || s.len() % 2 != 0 || !hex(s, s.len())
            })
        {
            return Err("prepared actual H264 output format/CSD absent".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedSenderReadback {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub original_id: String,
    pub executor_generation: u64,
    pub request_sha256: String,
    pub resource_target_sha256: String,
    pub provider_handle_id: String,
    pub provider_state_revision: u64,
    pub stage: String,
    pub outgoing_format: AndroidPreparedEncodedFormat,
    pub delivery_gate_closed: bool,
    pub peer_delivery_count: u64,
}
impl AndroidPreparedSenderReadback {
    pub fn validate(&self, request: &AndroidPreparedStartRequest) -> Result<(), String> {
        self.outgoing_format.validate()?;
        let shape = &request.resource_target["source_dimensions"];
        if self.schema_id != "rusty.quest.android.media.prepared_sender_readback.v1"
            || self.preparation_id != request.preparation_id
            || self.original_id != request.original_id
            || self.executor_generation != request.native_executor_generation
            || self.request_sha256 != request.digest()?
            || self.resource_target_sha256 != request.resource_target_sha256
            || self.provider_handle_id.is_empty()
            || self.provider_state_revision == 0
            || self.stage != "sender_ready"
            || !self.delivery_gate_closed
            || self.peer_delivery_count != 0
            || json!(self.outgoing_format.width) != shape["packed_width"]
            || json!(self.outgoing_format.height) != shape["packed_height"]
            || json!(self.outgoing_format.frame_rate_hz) != shape["frame_rate_hz"]
        {
            return Err("prepared sender is not exact gated actual owner".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedDecoderRequest {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub request_sha256: String,
    pub peer_preparation_id: String,
    pub peer_sender_receipt_sha256: String,
    pub peer_format: AndroidPreparedEncodedFormat,
}
impl AndroidPreparedDecoderRequest {
    pub fn validate(&self, request: &AndroidPreparedStartRequest) -> Result<(), String> {
        self.peer_format.validate()?;
        if self.schema_id != "rusty.quest.android.media.prepared_decoder_request.v1"
            || self.preparation_id != request.preparation_id
            || self.request_sha256 != request.digest()?
            || !hex(&self.peer_preparation_id, 32)
            || !hash(&self.peer_sender_receipt_sha256)
        {
            return Err("prepared decoder actual source binding differs".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedDecoderReadback {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub executor_generation: u64,
    pub request_sha256: String,
    pub resource_target_sha256: String,
    pub provider_handle_id: String,
    pub provider_state_revision: u64,
    pub stage: String,
    pub peer_sender_receipt_sha256: String,
    pub delivery_gate_closed: bool,
    pub peer_delivery_count: u64,
}
impl AndroidPreparedDecoderReadback {
    pub fn validate(
        &self,
        request: &AndroidPreparedStartRequest,
        decoder: &AndroidPreparedDecoderRequest,
    ) -> Result<(), String> {
        decoder.validate(request)?;
        if self.schema_id != "rusty.quest.android.media.prepared_decoder_readback.v1"
            || self.preparation_id != request.preparation_id
            || self.executor_generation != request.native_executor_generation
            || self.request_sha256 != request.digest()?
            || self.resource_target_sha256 != request.resource_target_sha256
            || self.provider_handle_id.is_empty()
            || self.provider_state_revision == 0
            || self.stage != "decoder_ready"
            || self.peer_sender_receipt_sha256 != decoder.peer_sender_receipt_sha256
            || !self.delivery_gate_closed
            || self.peer_delivery_count != 0
        {
            return Err("prepared decoder is not exact gated actual owner".into());
        }
        Ok(())
    }
}
/// Private ownership identity is carried by the compiled Native callback facade;
/// receipts alone never select or reconstruct the underlying Java SDK owner.
pub trait AndroidPreparedResourceExecutor {
    fn preparation_identity(&self) -> &Arc<()>;
    fn prepare_sender(
        &mut self,
        request: &AndroidPreparedStartRequest,
    ) -> Result<AndroidPreparedSenderReadback, String>;
    fn verify_sender(
        &self,
        request: &AndroidPreparedStartRequest,
        receipt: &AndroidPreparedSenderReadback,
    ) -> Result<(), String>;
    fn prepare_decoder(
        &mut self,
        request: &AndroidPreparedStartRequest,
        decoder: &AndroidPreparedDecoderRequest,
    ) -> Result<AndroidPreparedDecoderReadback, String>;
    fn verify_decoder(
        &self,
        request: &AndroidPreparedStartRequest,
        decoder: &AndroidPreparedDecoderRequest,
        receipt: &AndroidPreparedDecoderReadback,
    ) -> Result<(), String>;
}
/// One nonclone reservation retained BEFORE the first fallible SDK effect.
/// Failure retains entered-without-return and forbids replay. Reconciliation
/// must be a genuine owner lookup; a missing readback grants no retry.
pub struct AndroidPreparedStartOwner {
    identity: Arc<()>,
    request: AndroidPreparedStartRequest,
    sender_entered: bool,
    decoder_entered: bool,
    sender: Option<AndroidPreparedSenderReadback>,
    decoder_request: Option<AndroidPreparedDecoderRequest>,
    decoder: Option<AndroidPreparedDecoderReadback>,
}
impl AndroidPreparedStartOwner {
    pub fn reserve(
        executor: &impl AndroidPreparedResourceExecutor,
        request: AndroidPreparedStartRequest,
    ) -> Result<Self, String> {
        request.validate()?;
        Ok(Self {
            identity: executor.preparation_identity().clone(),
            request,
            sender_entered: false,
            decoder_entered: false,
            sender: None,
            decoder_request: None,
            decoder: None,
        })
    }
    fn same(&self, e: &impl AndroidPreparedResourceExecutor) -> Result<(), String> {
        if !Arc::ptr_eq(&self.identity, e.preparation_identity()) {
            return Err("foreign prepared registry owner".into());
        }
        Ok(())
    }
    pub fn request(&self) -> &AndroidPreparedStartRequest {
        &self.request
    }
    pub fn sender(&self) -> Option<&AndroidPreparedSenderReadback> {
        self.sender.as_ref()
    }
    pub fn decoder(&self) -> Option<&AndroidPreparedDecoderReadback> {
        self.decoder.as_ref()
    }
    pub fn prepare_sender(
        &mut self,
        e: &mut impl AndroidPreparedResourceExecutor,
    ) -> Result<AndroidPreparedSenderReadback, String> {
        self.same(e)?;
        if self.sender_entered {
            return Err("sender entered; no SDK replay".into());
        }
        self.sender_entered = true;
        let receipt = e.prepare_sender(&self.request)?;
        receipt.validate(&self.request)?;
        e.verify_sender(&self.request, &receipt)?;
        self.sender = Some(receipt.clone());
        Ok(receipt)
    }
    pub fn prepare_decoder(
        &mut self,
        e: &mut impl AndroidPreparedResourceExecutor,
        decoder: AndroidPreparedDecoderRequest,
    ) -> Result<AndroidPreparedDecoderReadback, String> {
        self.same(e)?;
        decoder.validate(&self.request)?;
        if let Some(prior) = &self.decoder_request {
            if prior == &decoder {
                if let Some(receipt) = &self.decoder {
                    e.verify_decoder(&self.request, prior, receipt)?;
                    return Ok(receipt.clone());
                }
            }
            return Err("decoder entered/changed; no SDK replay".into());
        }
        self.decoder_entered = true;
        self.decoder_request = Some(decoder.clone());
        let receipt = e.prepare_decoder(&self.request, &decoder)?;
        receipt.validate(&self.request, &decoder)?;
        e.verify_decoder(&self.request, &decoder, &receipt)?;
        self.decoder = Some(receipt.clone());
        Ok(receipt)
    }
    pub fn require_ready(&self, e: &impl AndroidPreparedResourceExecutor) -> Result<(), String> {
        self.same(e)?;
        let sender = self.sender.as_ref().ok_or("actual sender not ready")?;
        let request = self
            .decoder_request
            .as_ref()
            .ok_or("actual peer format absent")?;
        let decoder = self.decoder.as_ref().ok_or("actual decoder not ready")?;
        e.verify_sender(&self.request, sender)?;
        e.verify_decoder(&self.request, request, decoder)
    }
    pub fn projection(&self) -> Value {
        json!({"$schema":"rusty.quest.android.media.prepared_start_owner.v1","request":self.request,
        "sender_entered":self.sender_entered,"decoder_entered":self.decoder_entered,"sender":self.sender,"decoder_request":self.decoder_request,"decoder":self.decoder})
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedAbortRequest {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub cleanup_original_id: String,
    pub app_process_generation: u64,
    pub app_record_sha256: String,
    pub native_executor_generation: u64,
    pub config_sha256: String,
    pub request_sha256: String,
    pub resource_target_sha256: String,
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedOwnerTerminal {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub owner_kind: String,
    pub owner_id: String,
    pub provider_kind: String,
    pub resource_id: String,
    pub construction_state: String,
    pub terminal_receipt: Value,
}
#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub struct AndroidPreparedAbortReadback {
    #[serde(rename = "$schema")]
    pub schema_id: String,
    pub preparation_id: String,
    pub original_id: String,
    pub cleanup_original_id: String,
    pub executor_generation: u64,
    pub request_sha256: String,
    pub resource_target_sha256: String,
    pub provider_handle_id: String,
    pub provider_state_revision: u64,
    pub stage: String,
    pub owner_receipts: Vec<AndroidPreparedOwnerTerminal>,
    pub delivery_gate_closed: bool,
    pub peer_delivery_count: u64,
}
impl AndroidPreparedAbortReadback {
    pub fn validate(
        &self,
        r: &AndroidPreparedStartRequest,
        cleanup: &AndroidPreparedAbortRequest,
    ) -> Result<(), String> {
        r.validate()?;
        if cleanup.schema_id != "rusty.quest.android.media.prepared_abort_request.v1"
            || cleanup.preparation_id != r.preparation_id
            || !hex(&cleanup.cleanup_original_id, 32)
            || cleanup.app_process_generation != r.app_process_generation
            || cleanup.app_record_sha256 != r.app_record_sha256
            || cleanup.native_executor_generation != r.native_executor_generation
            || cleanup.config_sha256 != r.config_sha256
            || cleanup.request_sha256 != r.digest()?
            || cleanup.resource_target_sha256 != r.resource_target_sha256
            || self.schema_id != "rusty.quest.android.media.prepared_abort_readback.v1"
            || self.preparation_id != r.preparation_id
            || self.original_id != r.original_id
            || self.cleanup_original_id != cleanup.cleanup_original_id
            || self.executor_generation != r.native_executor_generation
            || self.request_sha256 != r.digest()?
            || self.resource_target_sha256 != r.resource_target_sha256
            || self.provider_handle_id.is_empty()
            || self.provider_state_revision == 0
            || self.stage != "terminal"
            || !self.delivery_gate_closed
            || self.peer_delivery_count != 0
            || self.owner_receipts.len() != 7
        {
            return Err("prepared actual abort Original/source/owner differs".into());
        }
        let providers = r.resource_target["product_binding"]["providers"]
            .as_array()
            .ok_or("prepared providers absent")?;
        let mut seen = std::collections::BTreeSet::new();
        for row in &self.owner_receipts {
            let identity = json!({"owner_kind":row.owner_kind,"owner_id":row.owner_id,"provider_kind":row.provider_kind,"resource_id":row.resource_id});
            if row.schema_id != "rusty.quest.android.media.prepared_owner_terminal.v1"
                || !matches!(
                    row.construction_state.as_str(),
                    "never_entered" | "closed_joined"
                )
                || !providers.contains(&identity)
                || !seen.insert(prepared_start_canonical(&identity)?)
                || !row.terminal_receipt.is_object()
            {
                return Err("prepared actual terminal provider coverage differs".into());
            }
        }
        Ok(())
    }
}

#[cfg(test)]
#[path = "prepared_start_tests.rs"]
mod tests;
