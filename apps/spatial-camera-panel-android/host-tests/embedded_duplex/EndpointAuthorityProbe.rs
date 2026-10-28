// Unadopted host effective-input seam. The runner imports unchanged production
// packaged_route, packaged_config and development_lifetime_policy modules.
use rusty_manifold_model::{DottedId, Revision};
use rusty_quest_broker_admission::{QuestAndroidBinderCaller, QuestBrokerAdmissionOperation, QUEST_ADMISSION_OPERATION_SCHEMA};
use rusty_quest_broker_authority::{packaged_json_sha256, QuestBrokerAuthorityRuntime, QuestBrokerRuntimeConfig};
use serde_json::{json, Value};
use std::io::{self, Read};

fn raw<'a>(documents: &'a Value, name: &str) -> Result<&'a str, String> {
    documents[name].as_str().ok_or_else(|| format!("document absent: {name}"))
}
fn document(documents: &Value, name: &str) -> Result<Value, String> {
    serde_json::from_str(raw(documents, name)?).map_err(|_| format!("invalid document: {name}"))
}
fn mask_binding(mut binding: Value) -> Result<Value, String> {
    let quest = binding["quest"].as_object_mut().ok_or("quest binding absent")?;
    quest.remove("runtime_spec_canonical_sha256");
    let plan = &mut binding["quest"]["spec"]["plan"];
    for endpoint in plan["runtime_endpoints"].as_array_mut().ok_or("runtime endpoints absent")? {
        endpoint.as_object_mut().ok_or("runtime endpoint object absent")?.remove("transport_bind_host");
        for field in ["source_bindings", "receiver_ports", "transport_receive_ports"] {
            for row in endpoint[field].as_array_mut().ok_or("endpoint bindings absent")? {
                row.as_object_mut().ok_or("endpoint binding object absent")?.remove(if field == "source_bindings" {"source_port"} else {"port"});
            }
        }
    }
    for route in plan["transport_routes"].as_array_mut().ok_or("transport routes absent")? {
        let object=route.as_object_mut().ok_or("transport route object absent")?;
        object.remove("connect_host");
        object.remove("connect_port");
    }
    Ok(binding)
}

fn verify_derivation(input: &Value) -> Result<Value, String> {
    let base = &input["base"];
    let effective = &input["effective"];
    let manifest_raw = raw(base, "product-input-manifest.json")?;
    let manifest_sha = packaged_json_sha256(manifest_raw);
    if manifest_sha != env!("RQ_ENDPOINT_BASE_MANIFEST_SHA256") { return Err("base fixture manifest differs".into()); }
    let manifest: Value = serde_json::from_str(manifest_raw).map_err(|_| "base manifest JSON")?;
    let mut closure = Vec::new();
    for artifact in manifest["artifacts"].as_array().ok_or("base artifacts absent")? {
        let name = artifact["path"].as_str().ok_or("base path absent")?;
        let bytes = raw(base, name)?;
        let digest = packaged_json_sha256(bytes);
        if digest != artifact["sha256"] || bytes.len() as u64 != artifact["size_bytes"].as_u64().unwrap_or(0) {
            return Err("base artifact bytes differ".into());
        }
        closure.push(format!("{name}={digest}"));
    }
    if packaged_json_sha256(&closure.join("\n")) != manifest["closure_sha256"] {
        return Err("base closure differs".into());
    }
    for name in ["product-spec.json", "accepted-product-lock.json", "client-lock.json", "planning-feature-lock.json", "packed-stereo-profile.json"] {
        if raw(base, name)? != raw(effective, name)? { return Err("immutable packaged ceiling differs".into()); }
    }
    let profile_raw = input["profile"].as_str().ok_or("profile absent")?;
    let profile: Value = serde_json::from_str(profile_raw).map_err(|_| "profile JSON")?;
    let selections = profile["peer_network_endpoints"].as_array().ok_or("endpoint selection absent")?;
    if selections.len() != 2 { return Err("two endpoint selections required".into()); }
    let mut expected_route = document(base, "route-configuration.json")?;
    let peers = expected_route["peers"].as_array_mut().ok_or("base peers absent")?;
    if peers.len() != 2 || selections[0]["peer_id"] == selections[1]["peer_id"] { return Err("peer coverage differs".into()); }
    for peer in peers {
        let selected = selections.iter().find(|p| p["peer_id"] == peer["peer_id"]).ok_or("foreign endpoint peer")?;
        for field in ["media_endpoint", "control_endpoint"] {
            if selected[field]["protocol"] != peer[field]["protocol"] { return Err("compiled protocol differs".into()); }
            peer[field]["host"] = selected[field]["host"].clone();
            peer[field]["port"] = selected[field]["port"].clone();
        }
    }
    if expected_route != document(effective, "route-configuration.json")? { return Err("nonendpoint route change".into()); }
    for name in ["peer_a_to_peer_b.media-binding.json", "peer_b_to_peer_a.media-binding.json"] {
        if mask_binding(document(base, name)?)? != mask_binding(document(effective, name)?)? {
            return Err("nonendpoint binding ceiling differs".into());
        }
    }
    for direction in manifest["directional_bindings"].as_array().ok_or("directions absent")? {
        let name = direction["lifecycle_lock_path"].as_str().ok_or("lifecycle absent")?;
        let mut original = document(base, name)?;
        let mut derived = document(effective, name)?;
        let binding = document(effective, direction["media_binding_path"].as_str().ok_or("binding absent")?)?;
        if derived["runtime_spec_canonical_sha256"] != binding["quest"]["runtime_spec_canonical_sha256"] {
            return Err("lifecycle/runtime digest differs".into());
        }
        original.as_object_mut().unwrap().remove("runtime_spec_canonical_sha256");
        derived.as_object_mut().unwrap().remove("runtime_spec_canonical_sha256");
        if original != derived { return Err("nonendpoint lifecycle ceiling differs".into()); }
    }
    Ok(json!({"$schema":"rusty.quest.embedded_duplex.effective_input_lineage.v1",
        "base_manifest_sha256":manifest_sha,
        "base_route_sha256":packaged_json_sha256(raw(base,"route-configuration.json")?),
        "profile_sha256":packaged_json_sha256(profile_raw),
        "effective_route_sha256":packaged_json_sha256(raw(effective,"route-configuration.json")?)}))
}

fn execute(input: &Value) -> Result<Value, String> {
    if input["action"] == "canonicalize_binding" {
        let mut value: Value=serde_json::from_str(input["binding"].as_str().ok_or("binding absent")?).map_err(|e|e.to_string())?;
        let mut binding: rusty_quest_broker_authority::QuestBrokerMediaSessionProductBinding=
            serde_json::from_value(value.clone()).map_err(|e|e.to_string())?;
        binding.quest.runtime_spec_canonical_sha256=rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&binding.quest.spec).map_err(|e|e.to_string())?;
        binding.quest.validate().map_err(|e|e.to_string())?;
        binding.manifold.validate().map_err(|e|e.to_string())?;
        value["quest"]["runtime_spec_canonical_sha256"]=json!(binding.quest.runtime_spec_canonical_sha256);
        return Ok(json!({"binding":serde_json::to_string(&value).map_err(|e|e.to_string())?}));
    }
    let lineage = verify_derivation(input)?;
    let documents = &input["effective"];
    let base_manifest = document(&input["base"], "product-input-manifest.json")?;
    let direction = &base_manifest["directional_bindings"][0];
    let lifecycle_name = direction["lifecycle_lock_path"].as_str().ok_or("lifecycle path")?;
    let lifecycle = document(documents, lifecycle_name)?;
    let identity = &base_manifest["package"];
    let mut request = input["runtime"].clone();
    let object = request.as_object_mut().ok_or("runtime fields absent")?;
    object.insert("package_name".into(), identity["application_id"].clone());
    object.insert("signing_certificate_sha256".into(), identity["signing_certificate_sha256"].clone());
    object.insert("expected_project_id".into(), lifecycle["project_id"].clone());
    object.insert("expected_activation_marker".into(), lifecycle["activation_effective_marker"].clone());
    object.insert("installed_role_id".into(), direction["installed_role_id"].clone());
    let exact = |name| -> Result<Value, String> { let bytes=raw(documents,name)?;Ok(json!({"json":bytes,"sha256":packaged_json_sha256(bytes)})) };
    for (field,name) in [("product_spec","product-spec.json"),("product_lock","accepted-product-lock.json"),
        ("client_lock","client-lock.json"),("media_lifecycle_lock",lifecycle_name),
        ("app_feature_lock","planning-feature-lock.json"),("route_configuration","route-configuration.json"),
        ("packed_profile","packed-stereo-profile.json")] { object.insert(field.into(),exact(name)?); }
    object.insert("media_bindings".into(),json!([exact("peer_a_to_peer_b.media-binding.json")?,exact("peer_b_to_peer_a.media-binding.json")?]));
    // Exact production assembly also constructs a real broker runtime internally.
    let (envelope_raw,_,_) = packaged_config::assemble_packaged_config_request_json(&request.to_string())?;
    let envelope: Value = serde_json::from_str(&envelope_raw).map_err(|_| "assembled envelope JSON")?;
    let config: QuestBrokerRuntimeConfig = serde_json::from_str(envelope["runtime_config_json"].as_str().ok_or("config absent")?)
        .map_err(|_| "assembled config JSON")?;
    let policy = development_lifetime_policy::for_config(config.embedded_duplex.as_ref().ok_or("duplex absent")?)?;
    let wall = request["validation_wall_unix_ms"].as_i64().ok_or("wall absent")?;
    let mono = request["validation_monotonic_elapsed_ns"].as_u64().ok_or("monotonic absent")?;
    let entropy = request["validation_epoch_entropy_hex"].as_str().ok_or("entropy absent")?;
    let capability = config.admission.snapshot.grants[0].capabilities.iter()
        .find(|id| id.as_str().contains("status")).or_else(||config.admission.snapshot.grants[0].capabilities.first())
        .ok_or("no existing granted capability")?.clone();
    let ceiling = config.admission.snapshot.max_token_ttl_ms;
    let mut runtime = match policy {
        Some(p) => QuestBrokerAuthorityRuntime::from_config_with_lifetime_policy(config,entropy,wall,mono,p),
        None => QuestBrokerAuthorityRuntime::from_config(config,entropy,wall,mono),
    }.map_err(|e|e.to_string())?;
    let caller = QuestAndroidBinderCaller { sending_uid:10123,
        package_name:identity["application_id"].as_str().unwrap().into(),
        signing_certificate_sha256:identity["signing_certificate_sha256"].as_str().unwrap().into() };
    let id = |s| DottedId::new(s).map_err(|e|format!("{e:?}"));
    let revision = |v| Revision::new(v).ok_or("invalid revision");
    let now=wall as u64+10;
    let issue=runtime.execute_admission(QuestBrokerAdmissionOperation::IssueToken {
        schema_id:QUEST_ADMISSION_OPERATION_SCHEMA.into(),caller:caller.clone(),
        request_id:id("request.endpoint.host.issue")?,expected_authority_revision:revision(1)?,
        requested_capabilities:vec![capability.clone()],requested_token_ttl_ms:5000,
        issued_at_ms:now,expires_at_ms:now+5000,entropy_hex:"07".repeat(32),
    }).map_err(|e|e.to_string())?;
    if !issue.receipt.applied { return Err("real token issuance refused".into()); }
    let token_id=issue.receipt.token.as_ref().ok_or("no issued token")?.token_id.clone();
    let snapshot: Value=serde_json::from_str(&runtime.admission_snapshot_json().map_err(|e|e.to_string())?).unwrap();
    let used=runtime.execute_admission(QuestBrokerAdmissionOperation::AuthorizeUse {
        schema_id:QUEST_ADMISSION_OPERATION_SCHEMA.into(),caller,
        request_id:id("request.endpoint.host.use")?,expected_authority_revision:revision(snapshot["authority_revision"].as_u64().ok_or("revision absent")?)?,
        token_id,capability_id:capability.clone(),issued_at_ms:now+1,expires_at_ms:now+4000,
    }).map_err(|e|e.to_string())?;
    if !used.receipt.applied { return Err("real capability use refused".into()); }
    let preserved=runtime.admission_snapshot_json().map_err(|e|e.to_string())?;
    let revision_now:Value=serde_json::from_str(&preserved).map_err(|_|"admission snapshot JSON")?;
    let foreign=QuestAndroidBinderCaller {sending_uid:10124,package_name:"application.foreign.host".into(),
        signing_certificate_sha256:identity["signing_certificate_sha256"].as_str().unwrap().into()};
    let rejected=runtime.execute_admission(QuestBrokerAdmissionOperation::IssueToken {
        schema_id:QUEST_ADMISSION_OPERATION_SCHEMA.into(),caller:foreign,
        request_id:id("request.endpoint.foreign.issue")?,
        expected_authority_revision:revision(revision_now["authority_revision"].as_u64().ok_or("revision absent")?)?,
        requested_capabilities:vec![capability.clone()],requested_token_ttl_ms:5000,
        issued_at_ms:now+2,expires_at_ms:now+5000,entropy_hex:"08".repeat(32),
    });
    let rejected=rejected.map_err(|e|e.to_string())?;
    let after:Value=serde_json::from_str(&runtime.admission_snapshot_json().map_err(|e|e.to_string())?).map_err(|_|"post-refusal snapshot JSON")?;
    if rejected.receipt.applied || rejected.receipt.rejection_reason.is_none()
        || rejected.receipt.prior_authority_revision != rejected.receipt.resulting_authority_revision {
        return Err("foreign caller did not receive a typed terminal refusal".into());
    }
    for field in ["authority_revision","grants","active_tokens","revoked_token_ids","consumed_request_ids","consumed_use_request_ids"] {
        if after[field] != revision_now[field] { return Err("foreign refusal changed accepted authority state".into()); }
    }
    let audit_before=revision_now["audit_events"].as_array().ok_or("audit absent")?;
    let audit_after=after["audit_events"].as_array().ok_or("post-refusal audit absent")?;
    if audit_after.len()!=audit_before.len()+1 || &audit_after[..audit_before.len()]!=audit_before
        || audit_after.last().unwrap()["applied"]!=false {
        return Err("foreign refusal lost truthful audit history".into());
    }
    Ok(json!({"lineage":lineage,"assembly":envelope,
        "admission":{"token_issued":true,"capability_use_authorized":true,
            "capability_id":capability,"decision_owner":used.decision_owner,
            "configured_token_ttl_ceiling_ms":ceiling,"source_development_policy":policy.is_some(),
            "source_policy_horizon_ms":policy.map(|p|p.delegated_authority_horizon_ms),
            "foreign_caller_refused":true,"rejected_caller_preserved_state":true,
            "refusal_audit_retained":true},
        "limits":"Real source assembly/runtime/token admission. Host Binder identity/time/trust fixtures; no Android, process-fence, peer/session or media effects."}))
}
fn main() {
    let mut raw=String::new();io::stdin().read_to_string(&mut raw).unwrap();
    let result=serde_json::from_str(&raw).map_err(|e|e.to_string()).and_then(|input|execute(&input));
    match result { Ok(value)=>println!("{}",json!({"accepted":true,"readback":value})),
        Err(error)=>println!("{}",json!({"accepted":false,"error":error})) }
}
