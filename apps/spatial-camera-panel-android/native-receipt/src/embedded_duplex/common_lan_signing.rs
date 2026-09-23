//! Constrained validation before an app-private key signs a Common-LAN context.

use super::packaged_route::PackagedDuplexRoute;
use rusty_manifold_peer::{
    common_lan_reciprocal_ed25519_context_sha256,
    common_lan_reciprocal_ed25519_context_signing_bytes, ManifoldCommonLanPairRole,
    ManifoldCommonLanReciprocalEd25519Context, COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID,
    COMMON_LAN_RECIPROCAL_ED25519_CONTEXT_SCHEMA, COMMON_LAN_TCP_TRANSPORT_CONTRACT_ID,
};
use serde::Serialize;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct EnrolledSigningPeer {
    pub(crate) peer_id: String,
    pub(crate) device_id: String,
    pub(crate) key_id: String,
    pub(crate) key_generation: u64,
    pub(crate) public_key_sha256: String,
}

/// Read-only signing facts from the live process authority, never from the
/// caller's context or enrollment request.
pub(crate) struct LiveCommonLanSigningAuthority {
    pub(crate) trust_policy_id: String,
    pub(crate) trust_policy_revision: u64,
    pub(crate) enrolled: Vec<EnrolledSigningPeer>,
}

pub(crate) fn signing_authority_from_live_snapshot(
    snapshot_json: &str,
    route: &PackagedDuplexRoute,
    now_ms: u64,
) -> Result<LiveCommonLanSigningAuthority, String> {
    if snapshot_json.is_empty() || snapshot_json.len() > 16 * 1024 * 1024 || now_ms == 0 {
        return Err("live signing snapshot bounds".into());
    }
    let snapshot: Value =
        serde_json::from_str(snapshot_json).map_err(|_| "live signing snapshot JSON")?;
    let trust = snapshot
        .get("trust_policy")
        .ok_or("live signing trust policy absent")?;
    let trust_policy_id = trust
        .get("policy_id")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or("live signing trust policy id")?
        .to_owned();
    let trust_policy_revision = trust
        .get("revision")
        .and_then(Value::as_u64)
        .filter(|value| *value > 0)
        .ok_or("live signing trust policy revision")?;
    let credentials = snapshot
        .pointer("/enrollment/credentials")
        .and_then(Value::as_array)
        .ok_or("live signing enrollment absent")?;
    let mut enrolled = Vec::with_capacity(2);
    for packaged in &route.peers {
        let matching = credentials
            .iter()
            .filter(|record| {
                record.get("peer_id").and_then(Value::as_str) == Some(packaged.peer_id.as_str())
                    && record.get("status").and_then(Value::as_str) == Some("active")
            })
            .collect::<Vec<_>>();
        if matching.len() != 1 {
            return Err("live signing enrolled peer cardinality".into());
        }
        let record = matching[0];
        let read = |name| -> Result<String, String> {
            record
                .get(name)
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .map(str::to_owned)
                .ok_or_else(|| format!("live signing credential {name}"))
        };
        let key_generation = record
            .get("key_generation")
            .and_then(Value::as_u64)
            .filter(|value| *value > 0)
            .ok_or("live signing key generation")?;
        let valid_from = record
            .get("valid_from_ms")
            .and_then(Value::as_u64)
            .ok_or("live signing credential validity")?;
        let expires = record
            .get("expires_at_ms")
            .and_then(Value::as_u64)
            .ok_or("live signing credential expiry")?;
        if valid_from > now_ms || expires <= now_ms {
            return Err("live signing credential is not current".into());
        }
        enrolled.push(EnrolledSigningPeer {
            peer_id: packaged.peer_id.clone(),
            device_id: packaged.device_id.clone(),
            key_id: read("key_id")?,
            key_generation,
            public_key_sha256: read("public_key_sha256")?,
        });
    }
    if credentials.iter().any(|record| {
        record.get("status").and_then(Value::as_str) == Some("active")
            && !route.peers.iter().any(|peer| {
                record.get("peer_id").and_then(Value::as_str) == Some(peer.peer_id.as_str())
            })
    }) {
        return Err("foreign active signing credential".into());
    }
    Ok(LiveCommonLanSigningAuthority {
        trust_policy_id,
        trust_policy_revision,
        enrolled,
    })
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct CommonLanSigningPolicy<'a> {
    pub(crate) local_peer_id: &'a str,
    pub(crate) trust_policy_id: &'a str,
    pub(crate) trust_policy_revision: u64,
    pub(crate) network_scope_id: &'a str,
    pub(crate) now_ms: u64,
    pub(crate) max_context_age_ms: u64,
    pub(crate) max_future_skew_ms: u64,
    pub(crate) max_context_ttl_ms: u64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ValidatedCommonLanSigning {
    #[serde(rename = "$schema")]
    pub(crate) schema_id: String,
    pub(crate) signer_peer_id: String,
    pub(crate) signer_key_id: String,
    pub(crate) context_runtime_host_id: String,
    pub(crate) context_sha256: String,
    #[serde(skip)]
    pub(crate) signing_bytes: Vec<u8>,
}

/// Validates either host's exact context. The caller may invoke the private-key
/// callback only with the returned bytes and signer identity.
pub(crate) fn validate_common_lan_context_for_signing(
    route: &PackagedDuplexRoute,
    enrolled: &[EnrolledSigningPeer],
    context: &ManifoldCommonLanReciprocalEd25519Context,
    policy: CommonLanSigningPolicy<'_>,
) -> Result<ValidatedCommonLanSigning, String> {
    if policy.now_ms == 0
        || policy.max_context_age_ms == 0
        || policy.max_future_skew_ms > policy.max_context_age_ms
        || policy.max_context_ttl_ms == 0
        || policy.max_context_ttl_ms > 120_000
        || context.schema_id.as_str() != COMMON_LAN_RECIPROCAL_ED25519_CONTEXT_SCHEMA
        || context.trust_policy_id.as_str() != policy.trust_policy_id
        || context.trust_policy_revision.get() != policy.trust_policy_revision
        || context.transport.topology_contract_id.as_str() != COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID
        || context.transport.transport_contract_id.as_str() != COMMON_LAN_TCP_TRANSPORT_CONTRACT_ID
        || context.transport.network_scope_id.as_str() != policy.network_scope_id
        || context.transport.route_configuration_sha256 != route.route_configuration_sha256
        || context.coordinator_epoch == 0
        || context.expires_at_ms <= context.issued_at_ms
        || context.expires_at_ms - context.issued_at_ms > policy.max_context_ttl_ms
        || context.issued_at_ms
            > policy
                .now_ms
                .checked_add(policy.max_future_skew_ms)
                .ok_or("context time overflow")?
        || policy.now_ms.saturating_sub(context.issued_at_ms) > policy.max_context_age_ms
        || context.expires_at_ms <= policy.now_ms
    {
        return Err("Common-LAN signing authority mismatch".into());
    }

    let context_host = context.runtime_host_id.as_str();
    let host_peer = route
        .peers
        .iter()
        .find(|peer| route.runtime_host_id(&peer.peer_id).as_deref() == Ok(context_host))
        .ok_or("foreign context Runtime Host")?;
    if host_peer.peer_id != context.initiator.peer_id.as_str() {
        return Err("context host/initiator mismatch".into());
    }
    let local = route
        .peer(policy.local_peer_id)
        .ok_or("local signing peer is not packaged")?;
    if route.local_peer().peer_id != policy.local_peer_id {
        return Err("local signing peer does not match installed role".into());
    }
    let enrolled = enrolled_map(enrolled, route)?;
    let local_key = enrolled
        .get(policy.local_peer_id)
        .ok_or("local enrolled signing key absent")?;

    if context.initiator.role != ManifoldCommonLanPairRole::Initiator
        || context.responder.role != ManifoldCommonLanPairRole::Responder
        || context.initiator.peer_id == context.responder.peer_id
        || context.initiator.device_nonce_hex == context.responder.device_nonce_hex
        || !nonce(&context.initiator.device_nonce_hex)
        || !nonce(&context.responder.device_nonce_hex)
    {
        return Err("Common-LAN peer role or nonce mismatch".into());
    }
    for binding in [&context.initiator, &context.responder] {
        let exact = enrolled
            .get(binding.peer_id.as_str())
            .ok_or("context peer is not enrolled")?;
        if binding.key_id.as_str() != exact.key_id
            || binding.key_generation != exact.key_generation
            || binding.public_key_sha256 != exact.public_key_sha256
        {
            return Err("context enrolled key mismatch".into());
        }
    }
    if context.transport.endpoints.len() != 2 {
        return Err("Common-LAN endpoint cardinality".into());
    }
    let mut prior: Option<String> = None;
    let mut observed = BTreeSet::new();
    for endpoint in &context.transport.endpoints {
        let peer = route
            .peer(endpoint.peer_id.as_str())
            .ok_or("foreign Common-LAN endpoint")?;
        if prior.as_ref().is_some_and(|value| value >= &peer.peer_id)
            || !observed.insert(peer.peer_id.clone())
            || endpoint.endpoint_id.as_str() != route.media_endpoint_id(&peer.peer_id)?
            || endpoint.listen_ip_address != peer.media.host
            || endpoint.listen_port != peer.media.port
            || (endpoint.listen_ip_address == peer.control.host
                && endpoint.listen_port == peer.control.port)
        {
            return Err("Common-LAN media endpoint mismatch".into());
        }
        prior = Some(peer.peer_id.clone());
    }
    if observed
        != route
            .peers
            .iter()
            .map(|peer| peer.peer_id.clone())
            .collect()
    {
        return Err("Common-LAN endpoint closure".into());
    }

    Ok(ValidatedCommonLanSigning {
        schema_id: "rusty.quest.embedded_duplex.validated_common_lan_signing.v1".to_owned(),
        signer_peer_id: local.peer_id.clone(),
        signer_key_id: local_key.key_id.clone(),
        context_runtime_host_id: context_host.to_owned(),
        context_sha256: common_lan_reciprocal_ed25519_context_sha256(context),
        signing_bytes: common_lan_reciprocal_ed25519_context_signing_bytes(context),
    })
}

fn enrolled_map<'a>(
    enrolled: &'a [EnrolledSigningPeer],
    route: &PackagedDuplexRoute,
) -> Result<BTreeMap<&'a str, &'a EnrolledSigningPeer>, String> {
    if enrolled.len() != 2 {
        return Err("enrolled peer cardinality".into());
    }
    let mut result = BTreeMap::new();
    for peer in enrolled {
        if route
            .peer(&peer.peer_id)
            .map_or(true, |packaged| packaged.device_id != peer.device_id)
            || peer.key_generation == 0
            || !key_id(&peer.key_id)
            || !prefixed_sha256(&peer.public_key_sha256)
            || result.insert(peer.peer_id.as_str(), peer).is_some()
        {
            return Err("enrolled peer key closure".into());
        }
    }
    Ok(result)
}

fn key_id(value: &str) -> bool {
    value.len() == 72 && value.starts_with("ed25519.") && raw_hex(&value[8..], 64)
}

fn prefixed_sha256(value: &str) -> bool {
    value.len() == 71 && value.starts_with("sha256:") && raw_hex(&value[7..], 64)
}

fn nonce(value: &str) -> bool {
    raw_hex(value, 64)
}

fn raw_hex(value: &str, len: usize) -> bool {
    value.len() == len
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::embedded_duplex::packaged_route::{
        PackagedEndpoint, PackagedPeer, PackedStereoProfile,
    };
    use serde_json::{json, Value};

    #[test]
    fn both_host_contexts_use_distinct_authority_bytes() {
        let route = route();
        let enrolled = enrolled();
        let first = validate_common_lan_context_for_signing(
            &route,
            &enrolled,
            &context("peer.a", "peer.b"),
            policy("peer.a"),
        )
        .expect("A-host context");
        let second = validate_common_lan_context_for_signing(
            &route,
            &enrolled,
            &context("peer.b", "peer.a"),
            policy("peer.a"),
        )
        .expect("B-host context signed by A");
        assert_eq!(first.signer_peer_id, "peer.a");
        assert_eq!(second.signer_peer_id, "peer.a");
        assert_ne!(
            first.context_runtime_host_id,
            second.context_runtime_host_id
        );
        assert_ne!(first.context_sha256, second.context_sha256);
        assert_ne!(first.signing_bytes, second.signing_bytes);
    }

    #[test]
    fn endpoint_key_host_role_and_expiry_damage_reject() {
        let route = route();
        let enrolled = enrolled();
        for damage in [
            "endpoint", "key", "host", "role", "expiry", "control", "scope",
        ] {
            let mut value = context_value("peer.a", "peer.b");
            match damage {
                "endpoint" => value["transport"]["endpoints"][0]["listen_port"] = json!(41001),
                "key" => value["initiator"]["key_id"] = json!(format!("ed25519.{}", hex('9'))),
                "host" => value["runtime_host_id"] = json!("host.peer.foreign"),
                "role" => value["initiator"]["role"] = json!("responder"),
                "expiry" => value["expires_at_ms"] = json!(10_000),
                "control" => {
                    value["transport"]["endpoints"][0]["listen_ip_address"] = json!("10.0.0.1");
                    value["transport"]["endpoints"][0]["listen_port"] = json!(42000);
                }
                "scope" => value["transport"]["network_scope_id"] = json!("network.scope.foreign"),
                _ => unreachable!(),
            }
            let context: ManifoldCommonLanReciprocalEd25519Context =
                serde_json::from_value(value).expect("damaged context shape");
            assert!(
                validate_common_lan_context_for_signing(
                    &route,
                    &enrolled,
                    &context,
                    policy("peer.a")
                )
                .is_err(),
                "damage accepted: {damage}"
            );
        }
    }

    #[test]
    fn foreign_or_duplicate_enrollment_never_reaches_signing_bytes() {
        let route = route();
        let mut damaged = enrolled();
        damaged[1].peer_id = "peer.foreign".into();
        assert!(validate_common_lan_context_for_signing(
            &route,
            &damaged,
            &context("peer.a", "peer.b"),
            policy("peer.a")
        )
        .is_err());
        let duplicate = vec![enrolled()[0].clone(), enrolled()[0].clone()];
        assert!(validate_common_lan_context_for_signing(
            &route,
            &duplicate,
            &context("peer.a", "peer.b"),
            policy("peer.a")
        )
        .is_err());
    }

    #[test]
    fn live_snapshot_is_the_only_enrollment_and_trust_source() {
        let route = route();
        let record = |peer: &str, key: char, hash: char, generation: u64| {
            json!({"peer_id":peer,"status":"active",
                "key_id":format!("ed25519.{}",hex(key)),
                "key_generation":generation,
                "public_key_sha256":format!("sha256:{}",hex(hash)),
                "valid_from_ms":100,"expires_at_ms":20_000})
        };
        let snapshot = json!({"trust_policy":{"policy_id":"trust.duplex","revision":3},
            "enrollment":{"credentials":[
                record("peer.a",'a','1',1),record("peer.b",'b','2',2)]}});
        let live = signing_authority_from_live_snapshot(&snapshot.to_string(), &route, 10_000)
            .expect("live signing authority");
        assert_eq!(live.trust_policy_id, "trust.duplex");
        assert_eq!(live.trust_policy_revision, 3);
        let approved = validate_common_lan_context_for_signing(
            &route,
            &live.enrolled,
            &context("peer.a", "peer.b"),
            CommonLanSigningPolicy {
                trust_policy_id: &live.trust_policy_id,
                trust_policy_revision: live.trust_policy_revision,
                network_scope_id: &route.network_scope_id,
                ..policy("peer.a")
            },
        )
        .expect("live context");
        assert_eq!(approved.signer_key_id, live.enrolled[0].key_id);
        for damage in ["revoked", "expired", "duplicate", "foreign", "trust"] {
            let mut changed = snapshot.clone();
            match damage {
                "revoked" => changed["enrollment"]["credentials"][0]["status"] = json!("revoked"),
                "expired" => {
                    changed["enrollment"]["credentials"][1]["expires_at_ms"] = json!(9_999)
                }
                "duplicate" => {
                    let duplicate = changed["enrollment"]["credentials"][0].clone();
                    changed["enrollment"]["credentials"]
                        .as_array_mut()
                        .unwrap()
                        .push(duplicate);
                }
                "foreign" => {
                    let mut foreign = changed["enrollment"]["credentials"][0].clone();
                    foreign["peer_id"] = json!("peer.foreign");
                    changed["enrollment"]["credentials"]
                        .as_array_mut()
                        .unwrap()
                        .push(foreign);
                }
                "trust" => changed["trust_policy"]["revision"] = json!(0),
                _ => unreachable!(),
            }
            assert!(
                signing_authority_from_live_snapshot(&changed.to_string(), &route, 10_000).is_err(),
                "damaged live snapshot accepted: {damage}"
            );
        }
    }

    fn route() -> PackagedDuplexRoute {
        PackagedDuplexRoute {
            product_id: "product.duplex".into(),
            package_name: "io.example.duplex".into(),
            route_configuration_sha256: format!("sha256:{}", hex('8')),
            network_scope_id: "network.scope.fixed".into(),
            profile: PackedStereoProfile {
                schema: "rusty.quest.test.packed_profile.v1".into(),
                profile_id: "qcl100-packed-sbs".into(),
                rmanvid_schema_version: 4,
                frame_layout: "side_by_side_left_right".into(),
                eye_order: vec!["left".into(), "right".into()],
                codec: "h264".into(),
                packed_width: 2560,
                packed_height: 1280,
                per_eye_width: 1280,
                per_eye_height: 1280,
                frame_rate_hz: 15,
                bitrate_bps: 12_000_000,
                max_packet_bytes: 4_194_304,
                pair_timestamp_source: "camera2_sensor_timestamp".into(),
                pairing_policy: "nearest_timestamp_bounded".into(),
                max_pair_delta_ns: 20_000_000,
                stale_eye_reuse_allowed: false,
                cpu_pixel_copy: false,
            },
            peers: [
                peer("a", "10.0.0.1", 41000, 42000),
                peer("b", "10.0.0.2", 41000, 42000),
            ],
            installed_peer_index: 0,
            runtime_spec_ids: ["runtime.a-b".into(), "runtime.b-a".into()],
        }
    }

    fn peer(token: &str, host: &str, media_port: u16, control_port: u16) -> PackagedPeer {
        PackagedPeer {
            peer_id: format!("peer.{token}"),
            installed_role_id: format!("role.{token}"),
            device_id: format!("device.{token}"),
            left_camera_id: format!("camera.{token}.left"),
            right_camera_id: format!("camera.{token}.right"),
            media: PackagedEndpoint {
                protocol: "rmanvid-v4-packed-stereo".into(),
                host: host.into(),
                port: media_port,
            },
            control: PackagedEndpoint {
                protocol: "rqod1".into(),
                host: host.into(),
                port: control_port,
            },
        }
    }

    fn enrolled() -> Vec<EnrolledSigningPeer> {
        vec![
            EnrolledSigningPeer {
                peer_id: "peer.a".into(),
                device_id: "device.a".into(),
                key_id: format!("ed25519.{}", hex('a')),
                key_generation: 1,
                public_key_sha256: format!("sha256:{}", hex('1')),
            },
            EnrolledSigningPeer {
                peer_id: "peer.b".into(),
                device_id: "device.b".into(),
                key_id: format!("ed25519.{}", hex('b')),
                key_generation: 2,
                public_key_sha256: format!("sha256:{}", hex('2')),
            },
        ]
    }

    fn policy(local: &str) -> CommonLanSigningPolicy<'_> {
        CommonLanSigningPolicy {
            local_peer_id: local,
            trust_policy_id: "trust.duplex",
            trust_policy_revision: 3,
            network_scope_id: "network.scope.fixed",
            now_ms: 10_000,
            max_context_age_ms: 5_000,
            max_future_skew_ms: 500,
            max_context_ttl_ms: 120_000,
        }
    }

    fn context(initiator: &str, responder: &str) -> ManifoldCommonLanReciprocalEd25519Context {
        serde_json::from_value(context_value(initiator, responder)).expect("context")
    }

    fn context_value(initiator: &str, responder: &str) -> Value {
        let local_revision = if initiator == "peer.a" { 1 } else { 2 };
        let bindings = BTreeMap::from([
            (
                "peer.a",
                (
                    format!("ed25519.{}", hex('a')),
                    1,
                    format!("sha256:{}", hex('1')),
                ),
            ),
            (
                "peer.b",
                (
                    format!("ed25519.{}", hex('b')),
                    2,
                    format!("sha256:{}", hex('2')),
                ),
            ),
        ]);
        let peer = |id: &str, role: &str, nonce: char| {
            let binding = &bindings[id];
            json!({"peer_id":id,"key_id":binding.0,"key_generation":binding.1,
                "public_key_sha256":binding.2,"role":role,"device_nonce_hex":hex(nonce)})
        };
        let mut endpoints = vec![
            json!({"peer_id":"peer.a","endpoint_id":"endpoint.media.peer.a","listen_ip_address":"10.0.0.1","listen_port":41000}),
            json!({"peer_id":"peer.b","endpoint_id":"endpoint.media.peer.b","listen_ip_address":"10.0.0.2","listen_port":41000}),
        ];
        endpoints.sort_by_key(|value| value["peer_id"].as_str().unwrap().to_owned());
        json!({
            "$schema":COMMON_LAN_RECIPROCAL_ED25519_CONTEXT_SCHEMA,
            "runtime_host_id":format!("host.{initiator}"),"trust_policy_id":"trust.duplex",
            "trust_policy_revision":3,"correlation_id":format!("correlation.{initiator}"),
            "revisions":{"peer_authority_revision":local_revision + 1,
                "enrollment_authority_revision":local_revision + 1,
                "rendezvous_authority_revision":local_revision,
                "reciprocal_authority_revision":local_revision,
                "peer_session_authority_revision":local_revision,
                "peer_mesh_authority_revision":local_revision,
                "direct_lane_lease_authority_revision":local_revision},
            "initiator":peer(initiator,"initiator",'3'),"responder":peer(responder,"responder",'4'),
            "transport":{"topology_contract_id":COMMON_LAN_PAIR_TOPOLOGY_CONTRACT_ID,
                "transport_contract_id":COMMON_LAN_TCP_TRANSPORT_CONTRACT_ID,
                "network_scope_id":"network.scope.fixed","endpoints":endpoints,
                "route_configuration_sha256":format!("sha256:{}",hex('8'))},
            "coordinator_epoch":1,"issued_at_ms":9_500,"expires_at_ms":20_000
        })
    }

    fn hex(value: char) -> String {
        std::iter::repeat(value).take(64).collect()
    }
}
