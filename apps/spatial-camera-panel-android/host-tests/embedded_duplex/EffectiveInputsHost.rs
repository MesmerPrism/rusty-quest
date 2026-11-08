use rusty_quest_broker_authority::packaged_json_sha256;
use serde_json::{json, Value};
use std::{collections::BTreeMap, fs, path::Path};

fn base(root: &Path) -> Value {
    let mut documents = BTreeMap::new();
    for entry in fs::read_dir(root).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|s| s == "json") {
            documents.insert(
                path.file_name().unwrap().to_str().unwrap().to_owned(),
                fs::read_to_string(path).unwrap(),
            );
        }
    }
    let manifest: Value = serde_json::from_str(&documents["product-input-manifest.json"]).unwrap();
    json!({"$schema":"rusty.quest.embedded_duplex.effective_inputs_request.v1",
        "base_manifest_sha256":packaged_json_sha256(&documents["product-input-manifest.json"]),
        "installed_role_id":manifest["directional_bindings"][0]["installed_role_id"],
        "documents":documents,"profile_json":null})
}

fn profile(request: &Value, subnet: u16, port: u16) -> String {
    let route: Value = serde_json::from_str(
        request["documents"]["route-configuration.json"]
            .as_str()
            .unwrap(),
    )
    .unwrap();
    let peers: Vec<Value>=route["peers"].as_array().unwrap().iter().enumerate().map(|(i,p)|
        json!({"peer_id":p["peer_id"],
          "media_endpoint":{"protocol":"rmanvid-v4-packed-stereo","host":format!("10.{subnet}.0.{}",11+i),"port":port},
          "control_endpoint":{"protocol":"rqod1","host":format!("10.{subnet}.0.{}",11+i),"port":port+1}})).collect();
    json!({"schema":"rusty.quest.spatial_camera_panel.control_profile.v1","peer_network_endpoints":peers}).to_string()
}

fn derive(request: &Value) -> Value {
    let raw = crate::effective_inputs::derive_effective_inputs_request_json(&request.to_string())
        .expect("valid base/profile must derive effective inputs");
    serde_json::from_str(&raw).unwrap()
}

fn usb_profile(request: &Value) -> Value {
    let mut value: Value = serde_json::from_str(&profile(request, 20, 41000)).unwrap();
    value["peer_network_transport"] = json!("development-usb-adb-relay-v1");
    for (i, peer) in value["peer_network_endpoints"]
        .as_array_mut()
        .unwrap()
        .iter_mut()
        .enumerate()
    {
        peer["media_endpoint"]["host"] = json!("127.0.0.1");
        peer["control_endpoint"]["host"] = json!("127.0.0.1");
        peer["media_endpoint"]["port"] = json!(46061 + i);
        peer["control_endpoint"]["port"] = json!(46071 + i);
        peer["local_source_port"] = json!(47061 + i);
    }
    value
}

fn decode_documents(
    documents: &Value,
    role: &str,
) -> Result<crate::packaged_route::PackagedDuplexRoute, String> {
    use crate::packaged_route::{
        decode_and_validate_packaged_route, ExactRouteDocument, PackagedRouteExpectations,
    };
    let route = documents["route-configuration.json"].as_str().unwrap();
    let profile = documents["packed-stereo-profile.json"].as_str().unwrap();
    let a = documents["peer_a_to_peer_b.media-binding.json"]
        .as_str()
        .unwrap();
    let b = documents["peer_b_to_peer_a.media-binding.json"]
        .as_str()
        .unwrap();
    let manifest: Value =
        serde_json::from_str(documents["product-input-manifest.json"].as_str().unwrap()).unwrap();
    let lock: Value =
        serde_json::from_str(documents["accepted-product-lock.json"].as_str().unwrap()).unwrap();
    let digests = [route, profile, a, b].map(packaged_json_sha256);
    decode_and_validate_packaged_route(
        ExactRouteDocument {
            json: route,
            sha256: &digests[0],
        },
        ExactRouteDocument {
            json: profile,
            sha256: &digests[1],
        },
        [
            ExactRouteDocument {
                json: a,
                sha256: &digests[2],
            },
            ExactRouteDocument {
                json: b,
                sha256: &digests[3],
            },
        ],
        PackagedRouteExpectations {
            route_schema: "rusty.morphovision.embedded_duplex.route_configuration.v1",
            profile_schema: "rusty.morphovision.embedded_duplex.packed_profile.v1",
            product_id: lock["product_id"].as_str().unwrap(),
            package_name: manifest["package"]["application_id"].as_str().unwrap(),
            installed_role_id: role,
        },
    )
}

fn usb_controls(original: &Value) {
    let mut request = original.clone();
    let selected = usb_profile(original);
    request["profile_json"] = json!(selected.to_string());
    let result =
        crate::effective_inputs::derive_effective_inputs_request_json(&request.to_string());
    if !cfg!(feature = "development-usb-adb-relay") {
        assert!(result.is_err(), "feature-off build admitted USB relay");
        return;
    }
    let result: Value = serde_json::from_str(&result.expect("explicit USB relay profile")).unwrap();
    let route: Value = serde_json::from_str(
        result["documents"]["route-configuration.json"]
            .as_str()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(
        route["transport_contract_id"],
        "rusty.manifold.peer.development_loopback_relay_tcp_transport.v1"
    );
    for (i, peer) in route["peers"].as_array().unwrap().iter().enumerate() {
        assert_eq!(peer["local_source_port"], 47061 + i);
    }
    for name in [
        "peer_a_to_peer_b.media-binding.json",
        "peer_b_to_peer_a.media-binding.json",
    ] {
        let binding: Value =
            serde_json::from_str(result["documents"][name].as_str().unwrap()).unwrap();
        let plan = &binding["quest"]["spec"]["plan"];
        let source = &plan["lanes"][0]["source_device_id"];
        let peer = route["peers"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| &p["device_id"] == source)
            .unwrap();
        let endpoint = plan["runtime_endpoints"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| &p["device_id"] == source)
            .unwrap();
        assert_eq!(
            endpoint["source_bindings"][0]["source_port"],
            peer["local_source_port"]
        );
        assert_ne!(
            endpoint["source_bindings"][0]["source_port"],
            peer["media_endpoint"]["port"]
        );
    }
    let role = original["installed_role_id"].as_str().unwrap();
    decode_documents(&result["documents"], role)
        .expect("derived USB route has exact media bindings");
    let mut changed = result["documents"].clone();
    let mut changed_route = route.clone();
    changed_route["peers"][0]["local_source_port"] = json!(47063);
    changed["route-configuration.json"] = json!(changed_route.to_string());
    assert!(
        decode_documents(&changed, role).is_err(),
        "disjoint but foreign local producer binding accepted"
    );
    let mut changed = result["documents"].clone();
    let name = "peer_a_to_peer_b.media-binding.json";
    let mut binding: Value = serde_json::from_str(changed[name].as_str().unwrap()).unwrap();
    let source = binding["quest"]["spec"]["plan"]["lanes"][0]["source_device_id"].clone();
    let peer = route["peers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["device_id"] == source)
        .unwrap();
    for endpoint in binding["quest"]["spec"]["plan"]["runtime_endpoints"]
        .as_array_mut()
        .unwrap()
    {
        if endpoint["device_id"] == source {
            endpoint["source_bindings"][0]["source_port"] = peer["media_endpoint"]["port"].clone();
        }
    }
    let typed: rusty_quest_broker_authority::QuestBrokerMediaSessionProductBinding =
        serde_json::from_value(binding.clone()).unwrap();
    binding["quest"]["runtime_spec_canonical_sha256"] = json!(
        rusty_quest_media_stream::canonical_media_stream_runtime_sha256(&typed.quest.spec).unwrap()
    );
    changed[name] = json!(binding.to_string());
    assert!(
        decode_documents(&changed, role).is_err(),
        "rehashed binding substituted an external port for local producer"
    );
    let manifest: Value = serde_json::from_str(
        result["documents"]["product-input-manifest.json"]
            .as_str()
            .unwrap(),
    )
    .unwrap();
    for direction in manifest["directional_bindings"].as_array().unwrap() {
        let binding: Value = serde_json::from_str(
            result["documents"][direction["media_binding_path"].as_str().unwrap()]
                .as_str()
                .unwrap(),
        )
        .unwrap();
        let lifecycle: Value = serde_json::from_str(
            result["documents"][direction["lifecycle_lock_path"].as_str().unwrap()]
                .as_str()
                .unwrap(),
        )
        .unwrap();
        assert_eq!(
            lifecycle["runtime_spec_canonical_sha256"],
            binding["quest"]["runtime_spec_canonical_sha256"]
        );
    }
    for damage in [
        "external-host",
        "loopback-alias",
        "external-collision",
        "producer-collision",
        "producer-external-collision",
        "missing-producer",
        "zero-producer",
        "zero-media",
        "zero-control",
        "mode",
        "absent-mode",
    ] {
        let mut bad = selected.clone();
        match damage {
            "external-host" => {
                bad["peer_network_endpoints"][0]["media_endpoint"]["host"] = json!("10.0.0.1")
            }
            "loopback-alias" => {
                bad["peer_network_endpoints"][0]["media_endpoint"]["host"] = json!("127.0.0.2")
            }
            "external-collision" => {
                bad["peer_network_endpoints"][1]["control_endpoint"]["port"] = json!(46061)
            }
            "producer-collision" => {
                bad["peer_network_endpoints"][1]["local_source_port"] = json!(47061)
            }
            "producer-external-collision" => {
                bad["peer_network_endpoints"][1]["local_source_port"] = json!(46061)
            }
            "missing-producer" => {
                bad["peer_network_endpoints"][0]
                    .as_object_mut()
                    .unwrap()
                    .remove("local_source_port");
            }
            "zero-producer" => bad["peer_network_endpoints"][0]["local_source_port"] = json!(0),
            "zero-media" => bad["peer_network_endpoints"][0]["media_endpoint"]["port"] = json!(0),
            "zero-control" => {
                bad["peer_network_endpoints"][0]["control_endpoint"]["port"] = json!(0)
            }
            "mode" => bad["peer_network_transport"] = json!("development-usb-adb-relay-v2"),
            "absent-mode" => {
                bad.as_object_mut()
                    .unwrap()
                    .remove("peer_network_transport");
            }
            _ => unreachable!(),
        }
        request["profile_json"] = json!(bad.to_string());
        assert!(
            crate::effective_inputs::derive_effective_inputs_request_json(&request.to_string())
                .is_err(),
            "USB damage accepted: {damage}"
        );
    }
    let mut lan: Value = serde_json::from_str(&profile(original, 20, 41000)).unwrap();
    lan["peer_network_transport"] = json!("unknown");
    request["profile_json"] = json!(lan.to_string());
    assert!(
        crate::effective_inputs::derive_effective_inputs_request_json(&request.to_string())
            .is_err(),
        "unknown transport accepted"
    );
}

fn main() {
    let root = std::env::args().nth(1).unwrap();
    if root == "--derive" || root == "--assemble" {
        use std::io::Read;
        let mut request = String::new();
        std::io::stdin().read_to_string(&mut request).unwrap();
        let result = if root == "--derive" {
            crate::effective_inputs::derive_effective_inputs_request_json(&request)
        } else {
            crate::packaged_config::assemble_packaged_config_request_json(&request).map(|v| v.0)
        };
        match result {
            Ok(value) => println!("{value}"),
            Err(error) => {
                eprintln!("{error}");
                std::process::exit(2);
            }
        }
        return;
    }
    let original = base(Path::new(&root));
    usb_controls(&original);
    let baseline = derive(&original);
    assert_eq!(baseline["documents"], original["documents"]);
    assert!(baseline["lineage"]["profile_sha256"].is_null());
    let mut a = original.clone();
    a["profile_json"] = json!(profile(&a, 20, 41000));
    let accepted_a = derive(&a);
    let mut b = original.clone();
    b["profile_json"] = json!(profile(&b, 21, 43000));
    let accepted_b = derive(&b);
    assert_ne!(
        accepted_a["lineage"]["effective_route_sha256"],
        accepted_b["lineage"]["effective_route_sha256"]
    );
    assert_eq!(
        derive(&a),
        accepted_a,
        "exact A restoration is deterministic"
    );
    for name in [
        "product-input-manifest.json",
        "product-spec.json",
        "accepted-product-lock.json",
        "client-lock.json",
        "planning-feature-lock.json",
        "packed-stereo-profile.json",
    ] {
        assert_eq!(
            accepted_b["documents"][name], original["documents"][name],
            "immutable {name}"
        );
    }
    assert_eq!(
        accepted_a["lineage"]["base_manifest_sha256"],
        accepted_b["lineage"]["base_manifest_sha256"]
    );
    assert_eq!(
        accepted_a["lineage"]["base_route_sha256"],
        accepted_b["lineage"]["base_route_sha256"]
    );
    let mut failures = 0;
    let mut refuse = |bad: Value| {
        assert!(
            crate::effective_inputs::derive_effective_inputs_request_json(&bad.to_string())
                .is_err()
        );
        failures += 1;
    };
    let mut bad = b.clone();
    bad["base_manifest_sha256"] = json!("0".repeat(64));
    refuse(bad);
    let mut bad = b.clone();
    bad["documents"]["client-lock.json"] = json!("{}");
    refuse(bad);
    let mut bad = b.clone();
    bad["documents"]["extra.json"] = json!("{}");
    refuse(bad);
    let mut bad = b.clone();
    bad["unexpected_authority"] = json!(true);
    refuse(bad);
    let mut bad = b.clone();
    bad["installed_role_id"] = json!("foreign.role");
    refuse(bad);
    for (field, value) in [
        ("peer_id", json!("foreign.peer")),
        (
            "control_endpoint",
            json!({"protocol":"rqod1","host":"127.0.0.1","port":43001}),
        ),
        (
            "media_endpoint",
            json!({"protocol":"rmanvid-v4-packed-stereo","host":"10.21.0.11","port":0}),
        ),
    ] {
        let mut bad = b.clone();
        let mut p: Value = serde_json::from_str(bad["profile_json"].as_str().unwrap()).unwrap();
        p["peer_network_endpoints"][0][field] = value;
        bad["profile_json"] = json!(p.to_string());
        refuse(bad);
    }
    let mut bad = b.clone();
    bad["profile_json"] = json!(" ".repeat(65537));
    refuse(bad);
    assert_eq!(
        derive(&b),
        accepted_b,
        "refusals cannot modify accepted derivation"
    );
    println!(
        "{}",
        json!({"passed":true,"controls":failures+8,"baseline_preserved":true,"roundtrip":"A->B->A","lineage":accepted_b["lineage"]})
    );
}
