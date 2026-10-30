//! Endpoint-only derivation beneath an already verified packaged input closure.
// The build-fixed Context loader owns installed signer/manifest attestation.
// This pure operation opens no resource and accepts no trust or policy override.
use super::packaged_route::{
    decode_and_validate_packaged_route, ExactRouteDocument, PackagedRouteExpectations,
};
use rusty_quest_broker_authority::{packaged_json_sha256, QuestBrokerMediaSessionProductBinding};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    net::Ipv4Addr,
};

const MANIFEST: &str = "product-input-manifest.json";
const ROUTE: &str = "route-configuration.json";
const BINDINGS: [&str; 2] = [
    "peer_a_to_peer_b.media-binding.json",
    "peer_b_to_peer_a.media-binding.json",
];

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    #[serde(rename = "$schema")]
    schema: String,
    base_manifest_sha256: String,
    installed_role_id: String,
    documents: BTreeMap<String, String>,
    profile_json: Option<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Endpoint {
    protocol: String,
    host: String,
    port: u16,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PeerSelection {
    peer_id: String,
    media_endpoint: Endpoint,
    control_endpoint: Endpoint,
}

fn raw<'a>(docs: &'a BTreeMap<String, String>, name: &str) -> Result<&'a str, String> {
    docs.get(name)
        .map(String::as_str)
        .ok_or_else(|| "effective input document absent".into())
}

fn document(docs: &BTreeMap<String, String>, name: &str) -> Result<Value, String> {
    serde_json::from_str(raw(docs, name)?).map_err(|_| "effective input document invalid".into())
}

fn encode(value: &Value) -> Result<String, String> {
    serde_json::to_string(value).map_err(|_| "effective input serialization failed".into())
}

fn validate_base(request: &Request) -> Result<Value, String> {
    let manifest_raw = raw(&request.documents, MANIFEST)?;
    if manifest_raw.len() > 128 * 1024
        || packaged_json_sha256(manifest_raw) != request.base_manifest_sha256
    {
        return Err("effective base manifest differs".into());
    }
    let manifest = document(&request.documents, MANIFEST)?;
    if manifest["schema"] != "rusty.quest.embedded_duplex.product_input_manifest.v1" {
        return Err("effective base manifest schema differs".into());
    }
    let artifacts = manifest["artifacts"]
        .as_array()
        .ok_or("effective base artifacts absent")?;
    let mut names = BTreeSet::from([MANIFEST.to_owned()]);
    let mut closure = Vec::new();
    let mut total = manifest_raw.len();
    for artifact in artifacts {
        let name = artifact["path"]
            .as_str()
            .ok_or("effective base artifact path absent")?;
        if !names.insert(name.to_owned()) {
            return Err("effective base artifact repeated".into());
        }
        let bytes = raw(&request.documents, name)?;
        total = total
            .checked_add(bytes.len())
            .ok_or("effective base size overflow")?;
        let hash = packaged_json_sha256(bytes);
        if bytes.is_empty()
            || bytes.len() > 2 * 1024 * 1024
            || hash != artifact["sha256"]
            || Some(bytes.len() as u64) != artifact["size_bytes"].as_u64()
        {
            return Err("effective base artifact bytes differ".into());
        }
        closure.push(format!("{name}={hash}"));
    }
    if total > 8 * 1024 * 1024
        || names != request.documents.keys().cloned().collect()
        || packaged_json_sha256(&closure.join("\n")) != manifest["closure_sha256"]
    {
        return Err("effective base closure differs".into());
    }
    Ok(manifest)
}

fn validate_route(
    docs: &BTreeMap<String, String>,
    manifest: &Value,
    role: &str,
) -> Result<(), String> {
    let route = raw(docs, ROUTE)?;
    let profile = raw(docs, "packed-stereo-profile.json")?;
    let bindings = [raw(docs, BINDINGS[0])?, raw(docs, BINDINGS[1])?];
    let route_hash = packaged_json_sha256(route);
    let profile_hash = packaged_json_sha256(profile);
    let hashes = bindings.map(packaged_json_sha256);
    let lock = document(docs, "accepted-product-lock.json")?;
    decode_and_validate_packaged_route(
        ExactRouteDocument {
            json: route,
            sha256: &route_hash,
        },
        ExactRouteDocument {
            json: profile,
            sha256: &profile_hash,
        },
        [0, 1].map(|i| ExactRouteDocument {
            json: bindings[i],
            sha256: &hashes[i],
        }),
        PackagedRouteExpectations {
            route_schema: "rusty.morphovision.embedded_duplex.route_configuration.v1",
            profile_schema: "rusty.morphovision.embedded_duplex.packed_profile.v1",
            product_id: lock["product_id"]
                .as_str()
                .ok_or("effective product absent")?,
            package_name: manifest["package"]["application_id"]
                .as_str()
                .ok_or("effective package absent")?,
            installed_role_id: role,
        },
    )?;
    Ok(())
}

fn validate_endpoint(endpoint: &Endpoint, protocol: &Value) -> Result<(), String> {
    let host: Ipv4Addr = endpoint
        .host
        .parse()
        .map_err(|_| "effective endpoint IPv4 required")?;
    let first = host.octets()[0];
    if endpoint.protocol != protocol.as_str().ok_or("effective protocol absent")?
        || host.to_string() != endpoint.host
        || !(1..=223).contains(&first)
        || first == 127
        || endpoint.port == 0
    {
        return Err("effective endpoint outside supported bounds".into());
    }
    Ok(())
}

pub(crate) fn derive_effective_inputs_request_json(request: &str) -> Result<String, String> {
    if request.len() > 18 * 1024 * 1024 {
        return Err("effective input request too large".into());
    }
    let request: Request =
        serde_json::from_str(request).map_err(|_| "invalid effective input request")?;
    if request.schema != "rusty.quest.embedded_duplex.effective_inputs_request.v1" {
        return Err("effective input request schema differs".into());
    }
    let manifest = validate_base(&request)?;
    validate_route(&request.documents, &manifest, &request.installed_role_id)?;
    let base_route = raw(&request.documents, ROUTE)?;
    let mut docs = request.documents.clone();
    if let Some(profile) = &request.profile_json {
        if profile.is_empty() || profile.len() > 64 * 1024 {
            return Err("effective profile byte bounds".into());
        }
        // Existing Java profile ingress validates the complete control profile.
        // Native independently owns endpoint coverage, protocol, address and port containment.
        let profile: Value =
            serde_json::from_str(profile).map_err(|_| "effective profile JSON invalid")?;
        let selections: Vec<PeerSelection> =
            serde_json::from_value(profile["peer_network_endpoints"].clone())
                .map_err(|_| "effective endpoint selection invalid")?;
        if selections.len() != 2 || selections[0].peer_id == selections[1].peer_id {
            return Err("effective peer coverage differs".into());
        }
        let mut route = document(&docs, ROUTE)?;
        let peers = route["peers"]
            .as_array_mut()
            .ok_or("effective route peers absent")?;
        if peers.len() != 2 {
            return Err("effective route peer count differs".into());
        }
        let mut by_device = BTreeMap::new();
        let mut occupied = BTreeSet::new();
        for peer in peers {
            let selected = selections
                .iter()
                .find(|s| Some(s.peer_id.as_str()) == peer["peer_id"].as_str())
                .ok_or("effective endpoint peer not enrolled")?;
            for (field, endpoint) in [
                ("media_endpoint", &selected.media_endpoint),
                ("control_endpoint", &selected.control_endpoint),
            ] {
                validate_endpoint(endpoint, &peer[field]["protocol"])?;
                if !occupied.insert((endpoint.host.clone(), endpoint.port)) {
                    return Err("effective endpoint collision".into());
                }
                peer[field]["host"] = json!(endpoint.host);
                peer[field]["port"] = json!(endpoint.port);
            }
            by_device.insert(
                peer["device_id"]
                    .as_str()
                    .ok_or("effective device absent")?
                    .to_owned(),
                selected,
            );
        }
        docs.insert(ROUTE.into(), encode(&route)?);
        for name in BINDINGS {
            let mut binding = document(&docs, name)?;
            let plan = &mut binding["quest"]["spec"]["plan"];
            let lanes = plan["lanes"].as_array().ok_or("effective lanes absent")?;
            if lanes.len() != 1 {
                return Err("effective lane shape unsupported".into());
            }
            let source = lanes[0]["source_device_id"]
                .as_str()
                .ok_or("effective source absent")?
                .to_owned();
            let sink = lanes[0]["sink_device_id"]
                .as_str()
                .ok_or("effective sink absent")?
                .to_owned();
            for endpoint in plan["runtime_endpoints"]
                .as_array_mut()
                .ok_or("effective runtime endpoints absent")?
            {
                let device = endpoint["device_id"]
                    .as_str()
                    .ok_or("effective runtime device absent")?
                    .to_owned();
                let selected = by_device
                    .get(&device)
                    .ok_or("effective runtime device foreign")?;
                endpoint["transport_bind_host"] = json!(selected.media_endpoint.host);
                let fields = if device == source {
                    vec!["source_bindings"]
                } else if device == sink {
                    vec!["receiver_ports", "transport_receive_ports"]
                } else {
                    return Err("effective runtime device outside lane".into());
                };
                for field in fields {
                    for row in endpoint[field]
                        .as_array_mut()
                        .ok_or("effective runtime bindings absent")?
                    {
                        row[if field == "source_bindings" {
                            "source_port"
                        } else {
                            "port"
                        }] = json!(selected.media_endpoint.port);
                    }
                }
            }
            let selected = by_device.get(&sink).ok_or("effective sink foreign")?;
            for transport in plan["transport_routes"]
                .as_array_mut()
                .ok_or("effective transports absent")?
            {
                transport["connect_host"] = json!(selected.media_endpoint.host);
                transport["connect_port"] = json!(selected.media_endpoint.port);
            }
            let mut typed: QuestBrokerMediaSessionProductBinding =
                serde_json::from_value(binding.clone())
                    .map_err(|_| "effective binding shape invalid")?;
            typed.quest.runtime_spec_canonical_sha256 =
                rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&typed.quest.spec)
                    .map_err(|_| "effective runtime digest failed")?;
            typed
                .quest
                .validate()
                .map_err(|_| "effective Quest binding invalid")?;
            typed
                .manifold
                .validate()
                .map_err(|_| "effective Manifold binding invalid")?;
            binding["quest"]["runtime_spec_canonical_sha256"] =
                json!(typed.quest.runtime_spec_canonical_sha256);
            docs.insert(name.into(), encode(&binding)?);
        }
        for direction in manifest["directional_bindings"]
            .as_array()
            .ok_or("effective directions absent")?
        {
            let lifecycle = direction["lifecycle_lock_path"]
                .as_str()
                .ok_or("effective lifecycle absent")?;
            let binding = document(
                &docs,
                direction["media_binding_path"]
                    .as_str()
                    .ok_or("effective binding absent")?,
            )?;
            let mut value = document(&docs, lifecycle)?;
            value["runtime_spec_canonical_sha256"] =
                binding["quest"]["runtime_spec_canonical_sha256"].clone();
            docs.insert(lifecycle.into(), encode(&value)?);
        }
        validate_route(&docs, &manifest, &request.installed_role_id)?;
    }
    let effective_route = raw(&docs, ROUTE)?;
    let lineage = json!({"$schema":"rusty.quest.embedded_duplex.effective_input_lineage.v1",
        "base_manifest_sha256":request.base_manifest_sha256,
        "base_route_sha256":packaged_json_sha256(base_route),
        "profile_sha256":request.profile_json.as_deref().map(packaged_json_sha256),
        "effective_route_sha256":packaged_json_sha256(effective_route)});
    encode(
        &json!({"$schema":"rusty.quest.embedded_duplex.effective_inputs_result.v1",
        "documents":docs,"lineage":lineage}),
    )
}
