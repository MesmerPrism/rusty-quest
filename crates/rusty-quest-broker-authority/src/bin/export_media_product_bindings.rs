//! Exports canonical display-composite and Camera2 product media bindings.

use std::{env, fs, path::PathBuf, process};

use rusty_manifold_media_session::{
    canonical_media_session_sha256, ManifoldMediaSessionProductBinding,
    MANIFOLD_MEDIA_SESSION_BINDING_SCHEMA,
};
use rusty_manifold_model::{
    DottedId, ManifoldMediaSessionDescriptor, Revision, SchemaId, MANIFOLD_MEDIA_SESSION_SCHEMA,
};
use rusty_quest_broker_authority::QuestBrokerMediaSessionProductBinding;
use rusty_quest_media_stream as media;

fn main() {
    let Some(output_dir) = env::args_os().nth(1).map(PathBuf::from) else {
        eprintln!("usage: export_media_product_bindings <output-directory>");
        process::exit(2);
    };
    if let Err(error) = export(&output_dir) {
        eprintln!("{error}");
        process::exit(1);
    }
}

fn export(output_dir: &PathBuf) -> Result<(), String> {
    fs::create_dir_all(output_dir).map_err(|error| error.to_string())?;
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..");
    let plan_path = root
        .join("fixtures/media-stream-sessions/display-composite-mediaprojection-h264.plan.json");
    let plan_json = fs::read_to_string(plan_path).map_err(|error| error.to_string())?;
    let display_plan: media::MediaStreamSessionPlan =
        serde_json::from_str(&plan_json).map_err(|error| error.to_string())?;
    let camera = camera_binding(display_plan.clone())?;
    let spatial_camera = spatial_camera_binding(camera.quest.spec.plan.clone())?;
    for (name, binding) in [
        (
            "display-composite.binding.json",
            display_binding(display_plan.clone())?,
        ),
        ("camera2-surface.binding.json", camera),
        (
            "native-renderer-display.binding.json",
            app_render_binding(display_plan.clone(), AppRenderTarget::NativeRenderer)?,
        ),
        (
            "spatial-camera-panel-display.binding.json",
            app_render_binding(display_plan, AppRenderTarget::SpatialCameraPanel)?,
        ),
        ("spatial-camera-panel-camera2.binding.json", spatial_camera),
    ] {
        let mut json = serde_json::to_string_pretty(&binding).map_err(|error| error.to_string())?;
        json.push('\n');
        fs::write(output_dir.join(name), json).map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn spatial_camera_binding(
    mut plan: media::MediaStreamSessionPlan,
) -> Result<QuestBrokerMediaSessionProductBinding, String> {
    let session_id = "session.media_stream.quest_camera2_to_spatial_camera_panel";
    let lane_id = "quest-a-camera2-to-spatial-camera-panel";
    let stream_id = "stream.spatial-sdk.camera2.h264";
    let sink_id = "sink.spatial-sdk.camera2";
    let sink_device_id = "quest-spatial-camera-panel";
    let runtime_id = "runtime.media.spatial-camera-panel-camera2";
    plan.session_id = session_id.to_owned();
    plan.topology_id = "quest_camera_to_spatial_camera_panel".to_owned();
    plan.devices[1].device_id = sink_device_id.to_owned();
    plan.devices[1].device_kind = "quest".to_owned();
    plan.devices[1].role = "receiver".to_owned();
    plan.lanes[0].lane_id = lane_id.to_owned();
    plan.lanes[0].sink_device_id = sink_device_id.to_owned();
    plan.lanes[0].media.track_id = stream_id.to_owned();
    plan.runtime_endpoints[1].device_id = sink_device_id.to_owned();
    plan.runtime_endpoints[1].adapter_kind = "quest_meta_spatial_sdk_renderer".to_owned();
    plan.transport_routes[0].lane_id = lane_id.to_owned();
    plan.transport_routes[0].sink_device_id = sink_device_id.to_owned();
    plan.observability.required_markers = vec![
        "media-stream-session-started".to_owned(),
        "receiver-armed".to_owned(),
        "camera2-source-bound".to_owned(),
        "spatial-camera-panel-camera2-sink-bound".to_owned(),
        "spatial-camera-panel-camera2-frame-adopted".to_owned(),
        "lane-closed".to_owned(),
    ];
    let source_id = plan.sources[0].source_id.clone();
    let processor_id = "processor.camera2.spatial-camera-panel";
    let spec = runtime_spec(
        plan,
        runtime_id,
        processor_id,
        "camera",
        sink_id,
        "android_camera2_mediacodec_surface",
        "owner.quest.spatial-sdk-camera2-sink",
        "meta_spatial_sdk_camera2_h264_sink",
    )?;
    cross_binding(spec, &source_id, processor_id, lane_id, sink_id)
}

fn display_binding(
    mut plan: media::MediaStreamSessionPlan,
) -> Result<QuestBrokerMediaSessionProductBinding, String> {
    plan.transport_routes[0].connect_host = "192.168.49.2".to_owned();
    let lane_id = plan.lanes[0].lane_id.clone();
    let source_id = plan.sources[0].source_id.clone();
    let processor_id = "processor.display.passthrough";
    let sink_id = "sink.pc.display";
    let runtime_id = "runtime.media.quest-display-composite";
    let spec = runtime_spec(
        plan,
        runtime_id,
        processor_id,
        "display",
        sink_id,
        "android_mediaprojection_surface",
        "owner.hostess.h264-sink",
        "hostess_h264_receiver",
    )?;
    cross_binding(spec, &source_id, processor_id, &lane_id, sink_id)
}

fn camera_binding(
    mut plan: media::MediaStreamSessionPlan,
) -> Result<QuestBrokerMediaSessionProductBinding, String> {
    plan.session_id = "session.media_stream.quest_camera2_to_pc".to_owned();
    plan.topology_id = "quest_camera_to_pc".to_owned();
    plan.sources[0] = media::MediaStreamSource {
        source_id: "quest-a-camera2-main".to_owned(),
        device_id: "quest-a".to_owned(),
        source_family: "quest-camera2".to_owned(),
        source_kind: media::SOURCE_KIND_CAMERA2_MEDIACODEC_SURFACE.to_owned(),
        capture_route: "camera2-mediacodec-surface".to_owned(),
        capture_authority: media::CAPTURE_AUTHORITY_ANDROID_CAMERA_PERMISSION.to_owned(),
        deployment_classification: media::DEPLOYMENT_PRODUCTION_CANDIDATE.to_owned(),
        track_role: "camera".to_owned(),
        developer_shell_required: false,
        consent_required: false,
        display: None,
        camera: Some(media::CameraCaptureDescriptor {
            camera_id: "50".to_owned(),
            camera_ids: Vec::new(),
            camera_facing: "external".to_owned(),
            permission_policy: media::CAMERA_PERMISSION_REQUIRED.to_owned(),
        }),
    };
    let lane = &mut plan.lanes[0];
    lane.lane_id = "quest-a-camera2-to-pc-host".to_owned();
    lane.source_id = "quest-a-camera2-main".to_owned();
    lane.media.track_id = "quest-a.camera2.h264".to_owned();
    lane.media.track_role = "camera".to_owned();
    lane.media.timestamp_domain = "android-camera-sensor-time".to_owned();
    let sender = &mut plan.runtime_endpoints[0];
    sender.source_bindings[0].source_id = "quest-a-camera2-main".to_owned();
    sender.source_bindings[0].track_role = "camera".to_owned();
    sender.source_bindings[0].source_port = 8878;
    let receiver = &mut plan.runtime_endpoints[1];
    receiver.receiver_ports[0].track_role = "camera".to_owned();
    receiver.receiver_ports[0].port = 8978;
    receiver.transport_receive_ports[0].track_role = "camera".to_owned();
    receiver.transport_receive_ports[0].port = 9078;
    let route = &mut plan.transport_routes[0];
    route.lane_id = "quest-a-camera2-to-pc-host".to_owned();
    route.track_role = "camera".to_owned();
    route.connect_host = "192.168.49.2".to_owned();
    route.connect_port = 9078;
    plan.observability.required_markers = vec![
        "media-stream-session-started".to_owned(),
        "receiver-armed".to_owned(),
        "camera2-source-bound".to_owned(),
        "sender-started".to_owned(),
        "frame-painted".to_owned(),
        "lane-closed".to_owned(),
    ];
    let source_id = plan.sources[0].source_id.clone();
    let lane_id = plan.lanes[0].lane_id.clone();
    let processor_id = "processor.camera2.passthrough";
    let sink_id = "sink.pc.camera2";
    let spec = runtime_spec(
        plan,
        "runtime.media.quest-camera2",
        processor_id,
        "camera",
        sink_id,
        "android_camera2_mediacodec_surface",
        "owner.hostess.h264-sink",
        "hostess_h264_receiver",
    )?;
    cross_binding(spec, &source_id, processor_id, &lane_id, sink_id)
}

#[derive(Clone, Copy)]
enum AppRenderTarget {
    NativeRenderer,
    SpatialCameraPanel,
}

fn app_render_binding(
    mut plan: media::MediaStreamSessionPlan,
    target: AppRenderTarget,
) -> Result<QuestBrokerMediaSessionProductBinding, String> {
    let (
        app,
        session_id,
        topology_id,
        lane_id,
        stream_id,
        sink_id,
        sink_device_id,
        runtime_id,
        sink_owner_id,
        sink_provider,
        adapter_kind,
    ) = match target {
        AppRenderTarget::NativeRenderer => (
            "native-renderer",
            "session.media_stream.quest_display_to_native_renderer",
            "quest_display_to_native_renderer",
            "quest-a-display-to-native-renderer",
            "stream.native-openxr.h264",
            "sink.native-openxr",
            "quest-native-renderer",
            "runtime.media.native-renderer-display",
            "owner.quest.native-openxr-sink",
            "native_openxr_h264_sink",
            "quest_native_openxr_renderer",
        ),
        AppRenderTarget::SpatialCameraPanel => (
            "spatial-camera-panel",
            "session.media_stream.quest_display_to_spatial_camera_panel",
            "quest_display_to_spatial_camera_panel",
            "quest-a-display-to-spatial-camera-panel",
            "stream.spatial-sdk.h264",
            "sink.spatial-sdk",
            "quest-spatial-camera-panel",
            "runtime.media.spatial-camera-panel-display",
            "owner.quest.spatial-sdk-sink",
            "meta_spatial_sdk_h264_sink",
            "quest_meta_spatial_sdk_renderer",
        ),
    };
    plan.session_id = session_id.to_owned();
    plan.topology_id = topology_id.to_owned();
    plan.transport_routes[0].connect_host = "192.168.49.2".to_owned();
    plan.devices[1].device_id = sink_device_id.to_owned();
    plan.devices[1].device_kind = "quest".to_owned();
    plan.devices[1].role = "receiver".to_owned();
    plan.lanes[0].lane_id = lane_id.to_owned();
    plan.lanes[0].sink_device_id = sink_device_id.to_owned();
    plan.lanes[0].media.track_id = stream_id.to_owned();
    plan.runtime_endpoints[1].device_id = sink_device_id.to_owned();
    plan.runtime_endpoints[1].adapter_kind = adapter_kind.to_owned();
    plan.transport_routes[0].lane_id = lane_id.to_owned();
    plan.transport_routes[0].sink_device_id = sink_device_id.to_owned();
    plan.observability.required_markers = vec![
        "media-stream-session-started".to_owned(),
        "receiver-armed".to_owned(),
        format!("{app}-sink-bound"),
        "sender-started".to_owned(),
        format!("{app}-frame-adopted"),
        "lane-closed".to_owned(),
    ];
    let source_id = plan.sources[0].source_id.clone();
    let processor_id = format!("processor.display.{app}");
    let spec = runtime_spec(
        plan,
        runtime_id,
        &processor_id,
        "display",
        sink_id,
        "android_mediaprojection_surface",
        sink_owner_id,
        sink_provider,
    )?;
    cross_binding(spec, &source_id, &processor_id, lane_id, sink_id)
}

#[allow(clippy::too_many_arguments)]
fn runtime_spec(
    plan: media::MediaStreamSessionPlan,
    runtime_id: &str,
    processor_id: &str,
    role: &str,
    sink_id: &str,
    source_provider: &str,
    sink_owner_id: &str,
    sink_provider: &str,
) -> Result<media::MediaStreamRuntimeSpec, String> {
    let source_id = plan.sources[0].source_id.clone();
    let lane_id = plan.lanes[0].lane_id.clone();
    let sink_device_id = plan.lanes[0].sink_device_id.clone();
    let mut owners = vec![
        owner(
            media::MediaStreamOwnerKind::Source,
            "owner.quest.source",
            &source_id,
            Some(&lane_id),
            source_provider,
        ),
        owner(
            media::MediaStreamOwnerKind::Processor,
            "owner.rust.processor",
            processor_id,
            Some(&lane_id),
            "rust_passthrough_h264",
        ),
        owner(
            media::MediaStreamOwnerKind::Route,
            "owner.manifold.route",
            &lane_id,
            Some(&lane_id),
            "manifold_accepted_route",
        ),
        owner(
            media::MediaStreamOwnerKind::Socket,
            "owner.rust.lan-tcp-socket",
            &lane_id,
            Some(&lane_id),
            "rust_lan_tcp_socket",
        ),
        owner(
            media::MediaStreamOwnerKind::Codec,
            "owner.android.h264-codec",
            &lane_id,
            Some(&lane_id),
            "android_mediacodec_h264",
        ),
        owner(
            media::MediaStreamOwnerKind::Sink,
            sink_owner_id,
            sink_id,
            Some(&lane_id),
            sink_provider,
        ),
        owner(
            media::MediaStreamOwnerKind::Cleanup,
            "owner.quest.media-cleanup",
            runtime_id,
            None,
            "quest_media_cleanup",
        ),
    ];
    owners.sort();
    let spec = media::MediaStreamRuntimeSpec {
        schema: media::MEDIA_STREAM_RUNTIME_SPEC_SCHEMA.to_owned(),
        runtime_spec_id: runtime_id.to_owned(),
        manifold_session_revision: 4,
        plan,
        processors: vec![media::MediaStreamProcessorDescriptor {
            processor_id: processor_id.to_owned(),
            processor_kind: "passthrough_h264".to_owned(),
            input_track_roles: vec![role.to_owned()],
            output_track_roles: vec![role.to_owned()],
            owns_codec: false,
            cpu_pixel_copy: false,
            application_policy_fields: Vec::new(),
        }],
        sinks: vec![media::MediaStreamSinkDescriptor {
            sink_id: sink_id.to_owned(),
            device_id: sink_device_id,
            sink_kind: sink_provider.to_owned(),
            required_permissions: Vec::new(),
            application_policy_fields: Vec::new(),
        }],
        lane_bindings: vec![media::MediaStreamLaneRuntimeBinding {
            lane_id,
            processor_ids: vec![processor_id.to_owned()],
            sink_id: sink_id.to_owned(),
        }],
        direct_p2p_routes: Vec::new(),
        owner_selections: owners,
        compatibility_adapter_id: None,
    };
    media::validate_media_stream_runtime_spec(&spec)
        .map_err(|errors| format!("runtime spec invalid: {errors:?}"))?;
    Ok(spec)
}

fn owner(
    owner_kind: media::MediaStreamOwnerKind,
    owner_id: &str,
    resource_id: &str,
    lane_id: Option<&str>,
    provider_kind: &str,
) -> media::MediaStreamOwnerSelection {
    media::MediaStreamOwnerSelection {
        owner_kind,
        owner_id: owner_id.to_owned(),
        resource_id: resource_id.to_owned(),
        lane_id: lane_id.map(str::to_owned),
        provider_kind: provider_kind.to_owned(),
    }
}

fn cross_binding(
    spec: media::MediaStreamRuntimeSpec,
    source_id: &str,
    processor_id: &str,
    route_id: &str,
    sink_id: &str,
) -> Result<QuestBrokerMediaSessionProductBinding, String> {
    let descriptor = ManifoldMediaSessionDescriptor {
        schema_id: schema(MANIFOLD_MEDIA_SESSION_SCHEMA),
        session_id: id(&spec.plan.session_id),
        authority_revision: Revision::new(spec.manifold_session_revision).expect("nonzero"),
        platform_runtime_spec_id: id(&spec.runtime_spec_id),
        source_ids: vec![id(source_id)],
        processor_ids: vec![id(processor_id)],
        route_ids: vec![id(route_id)],
        sink_ids: vec![id(sink_id)],
        stream_ids: vec![id(&spec.plan.lanes[0].media.track_id)],
        payload_plane: "binary-media".to_owned(),
        inline_media_payloads_allowed: false,
        remote_camera_compatibility: false,
    };
    let descriptor_canonical_sha256 =
        canonical_media_session_sha256(&descriptor).map_err(|error| error.to_string())?;
    let runtime_spec_canonical_sha256 =
        media::canonical_media_stream_runtime_sha256(&spec).map_err(|error| error.to_string())?;
    Ok(QuestBrokerMediaSessionProductBinding {
        manifold: ManifoldMediaSessionProductBinding {
            schema_id: MANIFOLD_MEDIA_SESSION_BINDING_SCHEMA.to_owned(),
            descriptor,
            descriptor_canonical_sha256,
        },
        quest: media::MediaStreamRuntimeProductBinding {
            schema_id: media::MEDIA_STREAM_RUNTIME_PRODUCT_BINDING_SCHEMA.to_owned(),
            spec,
            runtime_spec_canonical_sha256,
        },
    })
}

fn id(value: &str) -> DottedId {
    DottedId::new(value).expect("export fixture id")
}

fn schema(value: &str) -> SchemaId {
    SchemaId::new(value).expect("export fixture schema")
}
