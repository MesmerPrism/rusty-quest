//! Closed packaged route/profile decoding for one reciprocal embedded media product.

use rusty_quest_broker_authority::{packaged_json_sha256, QuestBrokerMediaSessionProductBinding};
use serde::Deserialize;
use std::{collections::BTreeSet, net::IpAddr};

const MAX_DOCUMENT_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Copy, Debug)]
pub(crate) struct ExactRouteDocument<'a> {
    pub(crate) json: &'a str,
    pub(crate) sha256: &'a str,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct PackagedRouteExpectations<'a> {
    pub(crate) route_schema: &'a str,
    pub(crate) profile_schema: &'a str,
    pub(crate) product_id: &'a str,
    pub(crate) package_name: &'a str,
    pub(crate) installed_role_id: &'a str,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct PackagedDuplexRoute {
    pub(crate) product_id: String,
    pub(crate) package_name: String,
    pub(crate) route_configuration_sha256: String,
    pub(crate) network_scope_id: String,
    pub(crate) profile: PackedStereoProfile,
    pub(crate) peers: [PackagedPeer; 2],
    pub(crate) installed_peer_index: usize,
    pub(crate) runtime_spec_ids: [String; 2],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct PackagedPeer {
    pub(crate) peer_id: String,
    pub(crate) installed_role_id: String,
    pub(crate) device_id: String,
    pub(crate) left_camera_id: String,
    pub(crate) right_camera_id: String,
    pub(crate) media: PackagedEndpoint,
    pub(crate) control: PackagedEndpoint,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub(crate) struct PackagedEndpoint {
    pub(crate) protocol: String,
    pub(crate) host: String,
    pub(crate) port: u16,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq)]
#[serde(deny_unknown_fields)]
pub(crate) struct PackedStereoProfile {
    pub(crate) schema: String,
    pub(crate) profile_id: String,
    pub(crate) rmanvid_schema_version: u32,
    pub(crate) frame_layout: String,
    pub(crate) eye_order: Vec<String>,
    pub(crate) codec: String,
    pub(crate) packed_width: u32,
    pub(crate) packed_height: u32,
    pub(crate) per_eye_width: u32,
    pub(crate) per_eye_height: u32,
    pub(crate) frame_rate_hz: u32,
    pub(crate) bitrate_bps: u32,
    pub(crate) max_packet_bytes: u32,
    pub(crate) pair_timestamp_source: String,
    pub(crate) pairing_policy: String,
    pub(crate) max_pair_delta_ns: u64,
    pub(crate) stale_eye_reuse_allowed: bool,
    pub(crate) cpu_pixel_copy: bool,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RouteConfiguration {
    schema: String,
    product_id: String,
    package_name: String,
    packed_profile_sha256: String,
    network_scope_id: String,
    peers: Vec<RoutePeer>,
    runtime_local_identity_source: String,
    device_local_private_key_source: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RoutePeer {
    peer_id: String,
    installed_role_id: String,
    device_id: String,
    camera2: CameraPair,
    media_endpoint: PackagedEndpoint,
    control_endpoint: PackagedEndpoint,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CameraPair {
    left_id: String,
    right_id: String,
}

pub(crate) fn decode_and_validate_packaged_route(
    route: ExactRouteDocument<'_>,
    profile: ExactRouteDocument<'_>,
    binding_json: [ExactRouteDocument<'_>; 2],
    expected: PackagedRouteExpectations<'_>,
) -> Result<PackagedDuplexRoute, String> {
    validate_exact(route, "route")?;
    validate_exact(profile, "profile")?;
    for binding in binding_json {
        validate_exact(binding, "binding")?;
    }
    let route_value: RouteConfiguration =
        serde_json::from_str(route.json).map_err(|_| "packaged route JSON")?;
    let profile_value: PackedStereoProfile =
        serde_json::from_str(profile.json).map_err(|_| "packed profile JSON")?;
    if route_value.schema != expected.route_schema
        || profile_value.schema != expected.profile_schema
        || route_value.product_id != expected.product_id
        || route_value.package_name != expected.package_name
        || route_value.packed_profile_sha256 != profile.sha256
        || !dotted(&route_value.network_scope_id)
        || route_value.runtime_local_identity_source != "installed-role-bootstrap"
        || route_value.device_local_private_key_source != "app-private-no-backup-identity"
        || route_value.peers.len() != 2
    {
        return Err("packaged route identity".into());
    }
    validate_profile(&profile_value)?;
    let peers: [PackagedPeer; 2] = route_value
        .peers
        .into_iter()
        .map(validate_peer)
        .collect::<Result<Vec<_>, _>>()?
        .try_into()
        .map_err(|_| "packaged peer cardinality")?;
    validate_peer_pair(&peers)?;
    let installed = peers
        .iter()
        .position(|peer| peer.installed_role_id == expected.installed_role_id)
        .ok_or("installed role absent")?;
    if peers
        .iter()
        .filter(|peer| peer.installed_role_id == expected.installed_role_id)
        .count()
        != 1
    {
        return Err("installed role ambiguous".into());
    }

    let bindings = binding_json
        .into_iter()
        .map(|exact| {
            serde_json::from_str::<QuestBrokerMediaSessionProductBinding>(exact.json)
                .map_err(|_| "packaged media binding JSON".to_owned())
        })
        .collect::<Result<Vec<_>, _>>()?;
    let runtime_spec_ids = validate_bindings(&bindings, &peers, &profile_value)?;
    Ok(PackagedDuplexRoute {
        product_id: route_value.product_id,
        package_name: route_value.package_name,
        route_configuration_sha256: format!("sha256:{}", route.sha256),
        network_scope_id: route_value.network_scope_id,
        profile: profile_value,
        peers,
        installed_peer_index: installed,
        runtime_spec_ids,
    })
}

impl PackagedDuplexRoute {
    pub(crate) fn local_peer(&self) -> &PackagedPeer {
        &self.peers[self.installed_peer_index]
    }

    pub(crate) fn peer(&self, peer_id: &str) -> Option<&PackagedPeer> {
        self.peers.iter().find(|peer| peer.peer_id == peer_id)
    }

    pub(crate) fn runtime_host_id(&self, peer_id: &str) -> Result<String, String> {
        self.peer(peer_id).ok_or("foreign packaged peer")?;
        Ok(format!("host.{peer_id}"))
    }

    pub(crate) fn media_endpoint_id(&self, peer_id: &str) -> Result<String, String> {
        self.peer(peer_id).ok_or("foreign packaged peer")?;
        Ok(format!("endpoint.media.{peer_id}"))
    }
}

fn validate_exact(document: ExactRouteDocument<'_>, name: &'static str) -> Result<(), String> {
    if document.json.is_empty()
        || document.json.len() > MAX_DOCUMENT_BYTES
        || !raw_sha256(document.sha256)
        || packaged_json_sha256(document.json) != document.sha256
    {
        return Err(format!("exact packaged {name} bytes"));
    }
    Ok(())
}

fn validate_profile(profile: &PackedStereoProfile) -> Result<(), String> {
    if profile.profile_id.is_empty()
        || profile.rmanvid_schema_version != 4
        || profile.frame_layout != "side_by_side_left_right"
        || profile.eye_order != ["left", "right"]
        || profile.codec != "h264"
        || profile.packed_width == 0
        || profile.packed_height == 0
        || profile.per_eye_width == 0
        || profile.per_eye_height == 0
        || profile.per_eye_width.checked_mul(2) != Some(profile.packed_width)
        || profile.per_eye_height != profile.packed_height
        || !(1..=90).contains(&profile.frame_rate_hz)
        || profile.bitrate_bps == 0
        || profile.max_packet_bytes == 0
        || profile.max_packet_bytes > 8 * 1024 * 1024
        || profile.pair_timestamp_source != "camera2_sensor_timestamp"
        || profile.pairing_policy != "nearest_timestamp_bounded"
        || profile.max_pair_delta_ns == 0
        || profile.stale_eye_reuse_allowed
        || profile.cpu_pixel_copy
    {
        return Err("packed profile semantics".into());
    }
    Ok(())
}

fn validate_peer(peer: RoutePeer) -> Result<PackagedPeer, String> {
    if !dotted(&peer.peer_id)
        || !dotted(&peer.installed_role_id)
        || !dotted(&peer.device_id)
        || peer.camera2.left_id.is_empty()
        || peer.camera2.right_id.is_empty()
        || peer.camera2.left_id == peer.camera2.right_id
    {
        return Err("packaged peer identity".into());
    }
    validate_endpoint(&peer.media_endpoint, "rmanvid-v4-packed-stereo")?;
    validate_endpoint(&peer.control_endpoint, "rqod1")?;
    if peer.media_endpoint.host == peer.control_endpoint.host
        && peer.media_endpoint.port == peer.control_endpoint.port
    {
        return Err("media/control endpoint collision".into());
    }
    Ok(PackagedPeer {
        peer_id: peer.peer_id,
        installed_role_id: peer.installed_role_id,
        device_id: peer.device_id,
        left_camera_id: peer.camera2.left_id,
        right_camera_id: peer.camera2.right_id,
        media: peer.media_endpoint,
        control: peer.control_endpoint,
    })
}

fn validate_endpoint(endpoint: &PackagedEndpoint, protocol: &str) -> Result<(), String> {
    let parsed = endpoint
        .host
        .parse::<IpAddr>()
        .map_err(|_| "endpoint must be numeric")?;
    if endpoint.protocol != protocol
        || endpoint.port == 0
        || parsed.is_unspecified()
        || parsed.is_loopback()
        || parsed.is_multicast()
        || parsed.to_string() != endpoint.host
    {
        return Err("endpoint semantics".into());
    }
    Ok(())
}

fn validate_peer_pair(peers: &[PackagedPeer; 2]) -> Result<(), String> {
    if peers[0].peer_id == peers[1].peer_id
        || peers[0].installed_role_id == peers[1].installed_role_id
        || peers[0].device_id == peers[1].device_id
        || peers[0].media == peers[1].media
        || peers[0].control == peers[1].control
    {
        return Err("reciprocal peer closure".into());
    }
    Ok(())
}

fn validate_bindings(
    bindings: &[QuestBrokerMediaSessionProductBinding],
    peers: &[PackagedPeer; 2],
    profile: &PackedStereoProfile,
) -> Result<[String; 2], String> {
    if bindings.len() != 2 {
        return Err("binding cardinality".into());
    }
    let mut directions = BTreeSet::new();
    let mut runtime_ids = Vec::new();
    for binding in bindings {
        binding
            .manifold
            .validate()
            .map_err(|_| "invalid Manifold media binding")?;
        binding
            .quest
            .validate()
            .map_err(|_| "invalid Quest media binding")?;
        let spec = &binding.quest.spec;
        if spec.plan.devices.len() != 2
            || spec.plan.sources.len() != 1
            || spec.plan.lanes.len() != 1
            || spec.plan.transport_routes.len() != 1
            || spec.plan.runtime_endpoints.len() != 2
            || spec.processors.len() != 1
            || spec.sinks.len() != 1
            || spec.owner_selections.len() != 7
        {
            return Err("binding closed media shape".into());
        }
        let lane = &spec.plan.lanes[0];
        let source = peers
            .iter()
            .find(|peer| peer.device_id == lane.source_device_id)
            .ok_or("binding source device")?;
        let sink = peers
            .iter()
            .find(|peer| peer.device_id == lane.sink_device_id)
            .ok_or("binding sink device")?;
        if source.peer_id == sink.peer_id
            || !directions.insert((source.peer_id.clone(), sink.peer_id.clone()))
        {
            return Err("binding direction collision".into());
        }
        let camera = spec.plan.sources[0]
            .camera
            .as_ref()
            .ok_or("binding Camera2 source")?;
        let camera_rows = camera
            .camera_ids
            .iter()
            .map(|row| (row.track_role.as_str(), row.camera_id.as_str()))
            .collect::<BTreeSet<_>>();
        let source_id = &spec.plan.sources[0].source_id;
        let processor_id = &spec.processors[0].processor_id;
        let sink_id = &spec.sinks[0].sink_id;
        if binding
            .manifold
            .descriptor
            .platform_runtime_spec_id
            .as_str()
            != spec.runtime_spec_id
            || binding.manifold.descriptor.session_id.as_str() != spec.plan.session_id
            || binding.manifold.descriptor.authority_revision.get()
                != spec.manifold_session_revision
            || !exact_dotted_single(&binding.manifold.descriptor.source_ids, source_id)
            || !exact_dotted_single(&binding.manifold.descriptor.processor_ids, processor_id)
            || !exact_dotted_single(&binding.manifold.descriptor.route_ids, &lane.lane_id)
            || !exact_dotted_single(&binding.manifold.descriptor.sink_ids, sink_id)
            || !exact_dotted_single(
                &binding.manifold.descriptor.stream_ids,
                &lane.media.track_id,
            )
            || binding.manifold.descriptor.payload_plane != "binary-media"
            || binding.manifold.descriptor.inline_media_payloads_allowed
            || binding.manifold.descriptor.remote_camera_compatibility
            || spec.plan.sources[0].device_id != source.device_id
            || camera_rows
                != BTreeSet::from([
                    ("left", source.left_camera_id.as_str()),
                    ("right", source.right_camera_id.as_str()),
                ])
            || lane.direction != "outgoing"
            || lane.media.stream_framing != "rmanvid-v4-packed-stereo"
            || lane.media.codec != profile.codec
            || lane.media.width != profile.packed_width
            || lane.media.height != profile.packed_height
            || lane.media.frame_rate_hz != profile.frame_rate_hz
            || lane.media.bitrate_bps != profile.bitrate_bps
            || lane.media.max_packet_bytes != profile.max_packet_bytes
            || !lane.receiver_first_required
            || spec.processors[0].processor_kind != "packed_sbs_left_right"
            || spec.processors[0].input_track_roles != ["left", "right"]
            || spec.processors[0].output_track_roles != ["stereo"]
            || spec.processors[0].owns_codec
            || spec.processors[0].cpu_pixel_copy
            || !spec.processors[0].application_policy_fields.is_empty()
            || spec.sinks[0].device_id != sink.device_id
            || spec.sinks[0].sink_kind != "meta_spatial_sdk_packed_stereo_sink"
            || !spec.sinks[0].required_permissions.is_empty()
            || !spec.sinks[0].application_policy_fields.is_empty()
            || spec.lane_bindings[0].lane_id != lane.lane_id
            || spec.lane_bindings[0].processor_ids != [processor_id.clone()]
            || spec.lane_bindings[0].sink_id != *sink_id
        {
            return Err("binding media/profile closure".into());
        }
        let route = &spec.plan.transport_routes[0];
        if route.source_device_id != source.device_id
            || route.sink_device_id != sink.device_id
            || route.connect_host != sink.media.host
            || route.connect_port != sink.media.port
            || route.route_kind != "direct_tcp_connect"
        {
            return Err("binding media endpoint closure".into());
        }
        for peer in peers {
            let endpoint = spec
                .plan
                .runtime_endpoints
                .iter()
                .find(|endpoint| endpoint.device_id == peer.device_id)
                .ok_or("binding runtime endpoint")?;
            if peer.device_id == source.device_id {
                if endpoint.source_bindings.len() != 1
                    || endpoint.source_bindings[0].source_host != "127.0.0.1"
                    || endpoint.source_bindings[0].source_port != source.media.port
                    || endpoint.source_bindings[0].track_role != "stereo"
                    || endpoint.transport_bind_host != source.media.host
                    || !endpoint.receiver_ports.is_empty()
                    || !endpoint.transport_receive_ports.is_empty()
                {
                    return Err("binding source endpoint closure".into());
                }
            } else if !endpoint.source_bindings.is_empty()
                || endpoint.receiver_ports.len() != 1
                || endpoint.transport_receive_ports.len() != 1
                || endpoint.receiver_ports[0].port != sink.media.port
                || endpoint.receiver_ports[0].track_role != "stereo"
                || endpoint.receiver_bind_host != "127.0.0.1"
                || endpoint.transport_receive_ports[0].port != sink.media.port
                || endpoint.transport_receive_ports[0].track_role != "stereo"
                || endpoint.transport_bind_host != sink.media.host
            {
                return Err("binding sink endpoint closure".into());
            }
        }
        let owners = spec
            .owner_selections
            .iter()
            .map(|selection| {
                let kind = serde_json::to_value(selection.owner_kind)
                    .ok()
                    .and_then(|value| value.as_str().map(str::to_owned))
                    .ok_or("binding owner kind")?;
                Ok((
                    kind,
                    selection.owner_id.clone(),
                    selection.resource_id.clone(),
                    selection.lane_id.clone(),
                    selection.provider_kind.clone(),
                ))
            })
            .collect::<Result<BTreeSet<_>, String>>()?;
        let lane_id = Some(lane.lane_id.clone());
        let expected_owners = BTreeSet::from([
            (
                "source".into(),
                "owner.quest.camera2-source".into(),
                source_id.clone(),
                lane_id.clone(),
                "android_camera2_mediacodec_surface".into(),
            ),
            (
                "processor".into(),
                "owner.rust.packed-sbs-processor".into(),
                processor_id.clone(),
                lane_id.clone(),
                "rust_packed_sbs_left_right".into(),
            ),
            (
                "route".into(),
                "owner.manifold.route".into(),
                lane.lane_id.clone(),
                lane_id.clone(),
                "manifold_accepted_route".into(),
            ),
            (
                "socket".into(),
                "owner.rust.lan-tcp-socket".into(),
                lane.lane_id.clone(),
                lane_id.clone(),
                "rust_lan_tcp_socket".into(),
            ),
            (
                "codec".into(),
                "owner.android.h264-codec".into(),
                lane.lane_id.clone(),
                lane_id.clone(),
                "android_mediacodec_h264".into(),
            ),
            (
                "sink".into(),
                "owner.quest.spatial-sdk-packed-sink".into(),
                sink_id.clone(),
                lane_id,
                "meta_spatial_sdk_packed_stereo_sink".into(),
            ),
            (
                "cleanup".into(),
                "owner.quest.media-cleanup".into(),
                spec.runtime_spec_id.clone(),
                None,
                "quest_media_cleanup".into(),
            ),
        ]);
        if owners != expected_owners {
            return Err("binding exact owner closure".into());
        }
        runtime_ids.push(spec.runtime_spec_id.clone());
    }
    if !directions.contains(&(peers[0].peer_id.clone(), peers[1].peer_id.clone()))
        || !directions.contains(&(peers[1].peer_id.clone(), peers[0].peer_id.clone()))
        || runtime_ids[0] == runtime_ids[1]
    {
        return Err("reciprocal binding closure".into());
    }
    runtime_ids
        .try_into()
        .map_err(|_| "runtime identity closure".into())
}

fn exact_dotted_single(values: &[impl std::fmt::Display], expected: &str) -> bool {
    values.len() == 1 && values[0].to_string() == expected
}

fn dotted(value: &str) -> bool {
    !value.is_empty()
        && value.split('.').all(|segment| {
            let bytes = segment.as_bytes();
            !bytes.is_empty()
                && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
                && (bytes[bytes.len() - 1].is_ascii_lowercase()
                    || bytes[bytes.len() - 1].is_ascii_digit())
                && bytes.iter().all(|byte| {
                    byte.is_ascii_lowercase()
                        || byte.is_ascii_digit()
                        || *byte == b'_'
                        || *byte == b'-'
                })
        })
}

fn raw_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::{json, Value};
    use std::{env, fs, path::Path};

    #[test]
    #[ignore = "requires an exact generated embedded-duplex input directory"]
    fn generated_reciprocal_closure_and_damage() {
        let root = env::var("RQ_EMBEDDED_INPUT_ROOT").expect("RQ_EMBEDDED_INPUT_ROOT");
        let route_schema = env::var("RQ_EMBEDDED_ROUTE_SCHEMA").expect("route schema");
        let profile_schema = env::var("RQ_EMBEDDED_PROFILE_SCHEMA").expect("profile schema");
        let product_id = env::var("RQ_EMBEDDED_PRODUCT_ID").expect("product id");
        let package_name = env::var("RQ_EMBEDDED_PACKAGE_NAME").expect("package name");
        let installed_role_id = env::var("RQ_EMBEDDED_ROLE_ID").expect("role id");
        let route = read(&root, "route-configuration.json");
        let profile = read(&root, "packed-stereo-profile.json");
        let binding_a = read(&root, "peer_a_to_peer_b.media-binding.json");
        let binding_b = read(&root, "peer_b_to_peer_a.media-binding.json");
        let expectation = PackagedRouteExpectations {
            route_schema: &route_schema,
            profile_schema: &profile_schema,
            product_id: &product_id,
            package_name: &package_name,
            installed_role_id: &installed_role_id,
        };
        let decoded = decode(&route, &profile, &binding_a, &binding_b, expectation)
            .expect("generated reciprocal closure");
        assert_eq!(decoded.local_peer().installed_role_id, installed_role_id);

        let mut swapped: Value = serde_json::from_str(&route).expect("route value");
        swapped["peers"][0]["installed_role_id"] = swapped["peers"][1]["installed_role_id"].clone();
        let swapped = serde_json::to_string(&swapped).expect("swapped route");
        assert!(decode(&swapped, &profile, &binding_a, &binding_b, expectation).is_err());

        let mut endpoint: Value = serde_json::from_str(&route).expect("route value");
        let old_port = endpoint["peers"][1]["media_endpoint"]["port"]
            .as_u64()
            .expect("media port");
        endpoint["peers"][1]["media_endpoint"]["port"] = json!(old_port + 1);
        let endpoint = serde_json::to_string(&endpoint).expect("endpoint route");
        assert!(decode(&endpoint, &profile, &binding_a, &binding_b, expectation).is_err());

        let mut extra: Value = serde_json::from_str(&route).expect("route value");
        extra["operator_completion"] = json!(true);
        let extra = serde_json::to_string(&extra).expect("extra route");
        assert!(decode(&extra, &profile, &binding_a, &binding_b, expectation).is_err());

        let mut missing_scope: Value = serde_json::from_str(&route).expect("route value");
        missing_scope
            .as_object_mut()
            .expect("route object")
            .remove("network_scope_id");
        assert!(decode(
            &serde_json::to_string(&missing_scope).expect("missing scope"),
            &profile,
            &binding_a,
            &binding_b,
            expectation
        )
        .is_err());
        let mut malformed_scope: Value = serde_json::from_str(&route).expect("route value");
        malformed_scope["network_scope_id"] = json!("network scope foreign");
        assert!(decode(
            &serde_json::to_string(&malformed_scope).expect("malformed scope"),
            &profile,
            &binding_a,
            &binding_b,
            expectation
        )
        .is_err());
    }

    fn decode<'a>(
        route: &'a str,
        profile: &'a str,
        binding_a: &'a str,
        binding_b: &'a str,
        expected: PackagedRouteExpectations<'a>,
    ) -> Result<PackagedDuplexRoute, String> {
        let route_sha = packaged_json_sha256(route);
        let profile_sha = packaged_json_sha256(profile);
        let binding_a_sha = packaged_json_sha256(binding_a);
        let binding_b_sha = packaged_json_sha256(binding_b);
        decode_and_validate_packaged_route(
            ExactRouteDocument {
                json: route,
                sha256: &route_sha,
            },
            ExactRouteDocument {
                json: profile,
                sha256: &profile_sha,
            },
            [
                ExactRouteDocument {
                    json: binding_a,
                    sha256: &binding_a_sha,
                },
                ExactRouteDocument {
                    json: binding_b,
                    sha256: &binding_b_sha,
                },
            ],
            expected,
        )
    }

    fn read(root: &str, name: &str) -> String {
        fs::read_to_string(Path::new(root).join(name)).expect("generated UTF-8 input")
    }
}
