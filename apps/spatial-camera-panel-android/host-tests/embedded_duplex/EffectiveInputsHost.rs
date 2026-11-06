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
