// Host-only endpoint derivation probe. The runner declares the production route module.
use std::io::{self, Read};
use rusty_quest_broker_authority::{packaged_json_sha256, QuestBrokerMediaSessionProductBinding};
use rusty_quest_media_stream::canonical_media_stream_runtime_sha256;
use serde_json::{json, Value};

fn execute(input: Value) -> Result<Value, String> {
    if input["action"] == "canonicalize_binding" {
        let mut original: Value = serde_json::from_str(input["binding"].as_str().ok_or("binding absent")?)
            .map_err(|e| e.to_string())?;
        let mut binding: QuestBrokerMediaSessionProductBinding =
            serde_json::from_str(input["binding"].as_str().ok_or("binding absent")?)
                .map_err(|e| e.to_string())?;
        binding.quest.runtime_spec_canonical_sha256 =
            canonical_media_stream_runtime_sha256(&binding.quest.spec).map_err(|e| e.to_string())?;
        binding.quest.validate().map_err(|e| e.to_string())?;
        binding.manifold.validate().map_err(|e| e.to_string())?;
        original["quest"]["runtime_spec_canonical_sha256"] = json!(binding.quest.runtime_spec_canonical_sha256);
        return Ok(json!({"binding": serde_json::to_string(&original).map_err(|e| e.to_string())?}));
    }
    let route = input["route"].as_str().ok_or("route absent")?;
    let profile = input["profile"].as_str().ok_or("profile absent")?;
    let bindings = input["bindings"].as_array().ok_or("bindings absent")?;
    if bindings.len() != 2 { return Err("two bindings required".into()); }
    let a = bindings[0].as_str().ok_or("binding A absent")?;
    let b = bindings[1].as_str().ok_or("binding B absent")?;
    let hashes = [route, profile, a, b].map(packaged_json_sha256);
    let exact = |index: usize, raw| packaged_route::ExactRouteDocument {json: raw, sha256: hashes[index].as_str()};
    let expected = &input["expected"];
    let selected = packaged_route::decode_and_validate_packaged_route(
        exact(0, route), exact(1, profile), [exact(2, a), exact(3, b)],
        packaged_route::PackagedRouteExpectations {
            route_schema: expected["route_schema"].as_str().ok_or("route schema absent")?,
            profile_schema: expected["profile_schema"].as_str().ok_or("profile schema absent")?,
            product_id: expected["product_id"].as_str().ok_or("product absent")?,
            package_name: expected["package_name"].as_str().ok_or("package absent")?,
            installed_role_id: expected["installed_role_id"].as_str().ok_or("role absent")?,
        },
    )?;
    Ok(json!({
        "route_configuration_sha256": selected.route_configuration_sha256,
        "local_peer_id": selected.local_peer().peer_id,
        "remote_peer_id": selected.peers[1-selected.installed_peer_index].peer_id,
        "outgoing_runtime_spec_id": selected.runtime_spec_ids[selected.installed_peer_index],
        "incoming_runtime_spec_id": selected.runtime_spec_ids[1-selected.installed_peer_index],
        "max_pair_delta_ns": selected.profile.max_pair_delta_ns,
        "local_control": {"host": selected.local_peer().control.host, "port": selected.local_peer().control.port},
        "remote_control": {"host": selected.peers[1-selected.installed_peer_index].control.host,
                           "port": selected.peers[1-selected.installed_peer_index].control.port},
        "peers": selected.peers.iter().map(|p| json!({"peer_id": p.peer_id,
            "media_host": p.media.host, "media_port": p.media.port,
            "control_host": p.control.host, "control_port": p.control.port})).collect::<Vec<_>>(),
    }))
}

fn main() {
    let mut raw = String::new();
    io::stdin().read_to_string(&mut raw).unwrap();
    let result = serde_json::from_str(&raw).map_err(|e| e.to_string()).and_then(execute);
    match result {
        Ok(value) => println!("{}", json!({"accepted": true, "readback": value})),
        Err(error) => println!("{}", json!({"accepted": false, "error": error})),
    }
}
