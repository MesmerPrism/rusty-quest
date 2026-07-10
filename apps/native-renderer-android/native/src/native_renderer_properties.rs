//! Android property-name registry for the native renderer runtime profiles.
//!
//! Keep names here so profile fixtures, host tooling, and runtime parsing agree on
//! one low-rate settings surface.

pub(crate) const PROP_ENABLE_SDF_VISUAL: &str =
    "debug.rustyquest.native_renderer.sdf.visual.enabled";
pub(crate) const PROP_PARTICLE_ADAPTER_ENABLED: &str =
    "debug.rustyquest.native_renderer.particle_adapter.enabled";
pub(crate) const PROP_SDF_FIELD_VISUAL_ENABLED: &str =
    "debug.rustyquest.native_renderer.sdf.field_visual.enabled";
pub(crate) const PROP_RENDER_MODE: &str = "debug.rustyquest.native_renderer.render.mode";
pub(crate) const PROP_CAMERA_OUTPUT_MODE: &str = "debug.rustyquest.native_renderer.camera.output";
pub(crate) const PROP_GUIDE_BLUR_ENABLED: &str =
    "debug.rustyquest.native_renderer.guide.blur.enabled";
pub(crate) const PROP_GUIDE_RESOLUTION: &str = "debug.rustyquest.native_renderer.guide.resolution";
pub(crate) const PROP_CAMERA_YCBCR_MODE: &str =
    "debug.rustyquest.native_renderer.camera.ycbcr.mode";
pub(crate) const PROP_CAMERA_RESOLUTION_PROFILE: &str =
    "debug.rustyquest.native_renderer.camera.resolution";
pub(crate) const PROP_CAMERA_READER_MAX_IMAGES: &str =
    "debug.rustyquest.native_renderer.camera.reader_max_images";
pub(crate) const PROP_CAMERA_QUALITY_PROFILE: &str =
    "debug.rustyquest.native_renderer.camera.quality_profile";
pub(crate) const PROP_CAMERA_SYNC_MODE: &str = "debug.rustyquest.native_renderer.camera.sync_mode";
pub(crate) const PROP_CAMERA_LUMA_DIAGNOSTIC_ENABLED: &str =
    "debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled";
pub(crate) const PROP_CAMERA_STEREO_PAIRING: &str =
    "debug.rustyquest.native_renderer.camera.stereo_pairing";
pub(crate) const PROP_CAMERA_DIRECT_BORDER_OPACITY: &str =
    "debug.rustyquest.native_renderer.camera.direct_border.opacity";
pub(crate) const PROP_DISPLAY_COMPOSITE_ENABLED: &str =
    "debug.rustyquest.native_renderer.display_composite.enabled";
pub(crate) const PROP_DISPLAY_COMPOSITE_SOURCE: &str =
    "debug.rustyquest.native_renderer.display_composite.source";
pub(crate) const PROP_DISPLAY_COMPOSITE_MODE: &str =
    "debug.rustyquest.native_renderer.display_composite.mode";
pub(crate) const PROP_DISPLAY_COMPOSITE_WIDTH: &str =
    "debug.rustyquest.native_renderer.display_composite.width";
pub(crate) const PROP_DISPLAY_COMPOSITE_HEIGHT: &str =
    "debug.rustyquest.native_renderer.display_composite.height";
pub(crate) const PROP_DISPLAY_COMPOSITE_MAX_IMAGES: &str =
    "debug.rustyquest.native_renderer.display_composite.max_images";
pub(crate) const PROP_DISPLAY_COMPOSITE_FPS_CAP: &str =
    "debug.rustyquest.native_renderer.display_composite.fps_cap";
pub(crate) const PROP_DISPLAY_COMPOSITE_FEEDBACK_ENABLED: &str =
    "debug.rustyquest.native_renderer.display_composite.feedback.enabled";
pub(crate) const PROP_DISPLAY_COMPOSITE_FEEDBACK_PROJECTION: &str =
    "debug.rustyquest.native_renderer.display_composite.feedback.projection";
pub(crate) const PROP_DISPLAY_COMPOSITE_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.display_composite.high_rate_json_payload";
pub(crate) const PROP_VIDEO_PROJECTION_ENABLED: &str =
    "debug.rustyquest.native_renderer.video_projection.enabled";
pub(crate) const PROP_VIDEO_PROJECTION_SOURCE: &str =
    "debug.rustyquest.native_renderer.video_projection.source";
pub(crate) const PROP_VIDEO_PROJECTION_PATH: &str =
    "debug.rustyquest.native_renderer.video_projection.path";
pub(crate) const PROP_VIDEO_PROJECTION_BROKER_HOST: &str =
    "debug.rustyquest.native_renderer.video_projection.broker.host";
pub(crate) const PROP_VIDEO_PROJECTION_BROKER_LEFT_PORT: &str =
    "debug.rustyquest.native_renderer.video_projection.broker.left_port";
pub(crate) const PROP_VIDEO_PROJECTION_BROKER_RIGHT_PORT: &str =
    "debug.rustyquest.native_renderer.video_projection.broker.right_port";
pub(crate) const PROP_VIDEO_PROJECTION_BROKER_CONNECT_TIMEOUT_MS: &str =
    "debug.rustyquest.native_renderer.video_projection.broker.connect_timeout_ms";
pub(crate) const PROP_VIDEO_PROJECTION_BROKER_MEDIA_LAYOUT: &str =
    "debug.rustyquest.native_renderer.video_projection.broker.media_layout";
pub(crate) const PROP_VIDEO_PROJECTION_STEREO_LAYOUT: &str =
    "debug.rustyquest.native_renderer.video_projection.stereo_layout";
pub(crate) const PROP_VIDEO_PROJECTION_WIDTH: &str =
    "debug.rustyquest.native_renderer.video_projection.width";
pub(crate) const PROP_VIDEO_PROJECTION_HEIGHT: &str =
    "debug.rustyquest.native_renderer.video_projection.height";
pub(crate) const PROP_VIDEO_PROJECTION_MAX_IMAGES: &str =
    "debug.rustyquest.native_renderer.video_projection.max_images";
pub(crate) const PROP_VIDEO_PROJECTION_FPS_CAP: &str =
    "debug.rustyquest.native_renderer.video_projection.fps_cap";
pub(crate) const PROP_VIDEO_PROJECTION_LOOPING: &str =
    "debug.rustyquest.native_renderer.video_projection.looping";
pub(crate) const PROP_VIDEO_PROJECTION_TARGET: &str =
    "debug.rustyquest.native_renderer.video_projection.target";
pub(crate) const PROP_VIDEO_PROJECTION_OPACITY: &str =
    "debug.rustyquest.native_renderer.video_projection.opacity";
pub(crate) const PROP_VIDEO_PROJECTION_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.video_projection.high_rate_json_payload";
pub(crate) const PROP_SWAPCHAIN_COLOR_FORMAT_MODE: &str =
    "debug.rustyquest.native_renderer.swapchain.color_format";
pub(crate) const PROP_PROJECTION_SWAPCHAIN_RESOLUTION_SCALE: &str =
    "debug.rustyquest.native_renderer.projection.swapchain.resolution_scale";
pub(crate) const PROP_FOVEATION_MODE: &str = "debug.rustyquest.native_renderer.foveation.mode";
pub(crate) const PROP_FOVEATION_LEVEL: &str = "debug.rustyquest.native_renderer.foveation.level";
pub(crate) const PROP_FOVEATION_DYNAMIC: &str =
    "debug.rustyquest.native_renderer.foveation.dynamic";
pub(crate) const PROP_FOVEATION_VERTICAL_OFFSET: &str =
    "debug.rustyquest.native_renderer.foveation.vertical_offset";
pub(crate) const PROP_FOVEATION_VULKAN_FDM: &str =
    "debug.rustyquest.native_renderer.foveation.vulkan_fdm";
pub(crate) const PROP_LSL_ENABLED: &str = "debug.rustyquest.native_renderer.lsl.enabled";
pub(crate) const PROP_LSL_OUTLET_ENABLED: &str =
    "debug.rustyquest.native_renderer.lsl.outlet.enabled";
pub(crate) const PROP_LSL_INLET_ENABLED: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.enabled";
pub(crate) const PROP_LSL_MULTICAST_LOCK_ENABLED: &str =
    "debug.rustyquest.native_renderer.lsl.multicast_lock.enabled";
pub(crate) const PROP_LSL_STREAM_PREFIX: &str =
    "debug.rustyquest.native_renderer.lsl.stream_prefix";
pub(crate) const PROP_LSL_PARTICIPANT_ID: &str =
    "debug.rustyquest.native_renderer.lsl.participant_id";
pub(crate) const PROP_LSL_SESSION_ID: &str = "debug.rustyquest.native_renderer.lsl.session_id";
pub(crate) const PROP_LSL_SOURCE_ID_PREFIX: &str =
    "debug.rustyquest.native_renderer.lsl.source_id_prefix";
pub(crate) const PROP_LSL_INLET_STREAM_NAME: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.stream_name";
pub(crate) const PROP_LSL_INLET_STREAM_TYPE: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.stream_type";
pub(crate) const PROP_LSL_INLET_SOURCE_ID: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.source_id";
pub(crate) const PROP_LSL_INLET_ROUTES: &str = "debug.rustyquest.native_renderer.lsl.inlet.routes";
pub(crate) const PROP_LSL_INLET_SAMPLE_HOLD_SECONDS: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.sample_hold_seconds";
pub(crate) const PROP_LSL_INLET_RECOVER_LOST_STREAMS: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.recover_lost_streams";
pub(crate) const PROP_LSL_INLET_TEST_SOURCE_ENABLED: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.test_source.enabled";
pub(crate) const PROP_LSL_INLET_TEST_SOURCE_VALUE01: &str =
    "debug.rustyquest.native_renderer.lsl.inlet.test_source.value01";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_ENABLED: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.enabled";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_BIND_HOST: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.bind_host";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_PORT: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.port";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_PATH: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.path";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_MAX_FRAME_BYTES: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.max_frame_bytes";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_LAN_ENABLED: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.lan_enabled";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN_REQUIRED: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.session_token_required";
pub(crate) const PROP_MANIFOLD_EMBEDDED_BROKER_SESSION_TOKEN: &str =
    "debug.rustyquest.native_renderer.manifold.embedded_broker.session_token";
pub(crate) const PROP_SDF_UPDATE_PERIOD_FRAMES: &str =
    "debug.rustyquest.native_renderer.sdf.update_period_frames";
pub(crate) const PROP_REPLAY_VISUAL_PROOF_ENABLED: &str =
    "debug.rustyquest.native_renderer.replay.visual_proof.enabled";
pub(crate) const PROP_HAND_MESH_INPUT_SOURCE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.input.source";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.enabled";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_OFFSET_UV: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.offset_uv";
pub(crate) const PROP_HAND_MESH_VISUAL_DIAGNOSTIC_ALPHA: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.diagnostic.alpha";
pub(crate) const PROP_HAND_MESH_VISUAL_MESH_SOURCE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.mesh_source";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_ALPHA: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.alpha";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_B: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.b";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_G: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.g";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_BASE_COLOR_R: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.base_color.r";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_PROFILE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.profile";
pub(crate) const PROP_HAND_MESH_VISUAL_MATERIAL_RIM_STRENGTH: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.material.rim_strength";
pub(crate) const PROP_HAND_MESH_VISUAL_WIREFRAME_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.enabled";
pub(crate) const PROP_HAND_MESH_VISUAL_WIREFRAME_WIDTH_PX: &str =
    "debug.rustyquest.native_renderer.hand_mesh.visual.wireframe.width_px";
pub(crate) const PROP_HAND_MESH_GRAFT_COPIES_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.enabled";
pub(crate) const PROP_HAND_MESH_GRAFT_COPY_SCALE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.graft_copies.scale";
pub(crate) const PROP_HAND_MESH_REAL_HANDS_VISIBLE: &str =
    "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ENABLED: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.enabled";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_PER_HAND: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.per_hand";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_RADIUS_M: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.radius_m";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_DYNAMICS: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.dynamics";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.blend_mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_COMPOSITION_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.composition_mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.transparency.depth_suppression_strength";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_MODE: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.mode";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_IMPLEMENTATION: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.implementation";
pub(crate) const PROP_HAND_ANCHOR_PARTICLES_ORDERING_INTERVAL_FRAMES: &str =
    "debug.rustyquest.native_renderer.hand_anchor_particles.ordering.interval_frames";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_AXIS_X: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.orientation_axis.x";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_AXIS_Y: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.orientation_axis.y";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_AXIS_Z: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.orientation_axis.z";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_INHALE_THRESHOLD: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.inhale_threshold";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_EXHALE_THRESHOLD: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.exhale_threshold";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_ROTATION_GUARD_DEGREES: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.rotation_guard_degrees";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_MOVING_AVERAGE_GUARD: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.moving_average_guard";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_SHORT_WINDOW_SAMPLES: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.short_window.samples";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_LONG_WINDOW_SAMPLES: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.long_window.samples";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_SHORT_WINDOW_SECONDS: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.short_window.seconds";
pub(crate) const PROP_CONTROLLER_BREATH_STATE_LONG_WINDOW_SECONDS: &str =
    "debug.rustyquest.native_renderer.controller_breath_state.long_window.seconds";
pub(crate) const PROP_PRIVATE_PARTICLES_VISUAL_SCALE: &str =
    "debug.rustyquest.native_renderer.private_particles.visual.scale";
pub(crate) const PROP_PRIVATE_PARTICLES_WORLD_ANCHOR_SCALE_M: &str =
    "debug.rustyquest.native_renderer.private_particles.world_anchor.scale_m";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER0_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver0.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER1_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver1.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER2_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver2.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER3_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver3.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER4_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver4.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER5_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver5.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER6_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver6.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_DRIVER7_VALUE01: &str =
    "debug.rustyquest.native_renderer.private_particles.driver7.value01";
pub(crate) const PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_MODE: &str =
    "debug.rustyquest.native_renderer.private_particles.breath_state_driver.mode";
pub(crate) const PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_TARGET_SLOT: &str =
    "debug.rustyquest.native_renderer.private_particles.breath_state_driver.target_slot";
pub(crate) const PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_INHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_particles.breath_state_driver.inhale.seconds.min_to_max";
pub(crate) const PROP_PRIVATE_PARTICLES_BREATH_STATE_DRIVER_EXHALE_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_particles.breath_state_driver.exhale.seconds.max_to_min";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_HOST: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.broker.host";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PATH: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.broker.path";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_BROKER_PORT: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.broker.port";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ENABLED: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.enabled";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.high_rate_json_payload";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_ROUTES: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.routes";
pub(crate) const PROP_PRIVATE_PARTICLES_MANIFOLD_DRIVER_SAMPLE_HOLD_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_particles.manifold_driver.sample_hold_seconds";
pub(crate) const PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES: &str =
    "debug.rustyquest.native_renderer.private_particles.offscreen.half_res";
pub(crate) const PROP_PRIVATE_PARTICLES_OFFSCREEN_HALF_RES_TRACERS_ONLY: &str =
    "debug.rustyquest.native_renderer.private_particles.offscreen.half_res.tracers_only";
pub(crate) const PROP_PRIVATE_PARTICLES_TRACER_DRAW_SLOTS_PER_OSCILLATOR: &str =
    "debug.rustyquest.native_renderer.private_particles.tracer.draw_slots_per_oscillator";
pub(crate) const PROP_PRIVATE_PARTICLES_TRACER_LIFETIME_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_particles.tracer.lifetime_seconds";
pub(crate) const PROP_PRIVATE_PARTICLES_TRACER_COPIES_PER_SECOND: &str =
    "debug.rustyquest.native_renderer.private_particles.tracer.copies_per_second";
pub(crate) const PROP_PRIVATE_PARTICLES_TRANSPARENCY_OPACITY: &str =
    "debug.rustyquest.native_renderer.private_particles.transparency.opacity";
pub(crate) const PROP_PRIVATE_PARTICLES_TRANSPARENCY_OUTPUT_ALPHA_SCALE: &str =
    "debug.rustyquest.native_renderer.private_particles.transparency.output_alpha_scale";
pub(crate) const PROP_PRIVATE_PARTICLES_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH: &str =
    "debug.rustyquest.native_renderer.private_particles.transparency.depth_suppression_strength";
pub(crate) const PROP_PRIVATE_PARTICLES_TRANSPARENCY_RGB_ALPHA_COUPLING: &str =
    "debug.rustyquest.native_renderer.private_particles.transparency.rgb_alpha_coupling";
pub(crate) const PROP_PRIVATE_PARTICLES_COLOR_FACING_ATTENUATION_STRENGTH: &str =
    "debug.rustyquest.native_renderer.private_particles.color.facing_attenuation_strength";
pub(crate) const PROP_ENVIRONMENT_DEPTH_MODE: &str =
    "debug.rustyquest.native_renderer.environment_depth.mode";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SOURCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.source";
pub(crate) const PROP_ENVIRONMENT_DEPTH_LAYER_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.layer_policy";
pub(crate) const PROP_ENVIRONMENT_DEPTH_DEPTH_UNITS_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.depth_units_policy";
pub(crate) const PROP_ENVIRONMENT_DEPTH_DEBUG_VIEW: &str =
    "debug.rustyquest.native_renderer.environment_depth.debug_view";
pub(crate) const PROP_ENVIRONMENT_DEPTH_REFERENCE_SPACE: &str =
    "debug.rustyquest.native_renderer.environment_depth.reference_space";
pub(crate) const PROP_ENVIRONMENT_DEPTH_HAND_REMOVAL_ENABLED: &str =
    "debug.rustyquest.native_renderer.environment_depth.hand_removal.enabled";
pub(crate) const PROP_ENVIRONMENT_DEPTH_NATIVE_PASSTHROUGH_REQUIRED: &str =
    "debug.rustyquest.native_renderer.environment_depth.native_passthrough.required";
pub(crate) const PROP_ENVIRONMENT_DEPTH_PARTICLE_CAPACITY: &str =
    "debug.rustyquest.native_renderer.environment_depth.particle_capacity";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SAMPLE_STRIDE_PIXELS: &str =
    "debug.rustyquest.native_renderer.environment_depth.sample_stride_pixels";
pub(crate) const PROP_ENVIRONMENT_DEPTH_NEAR_M: &str =
    "debug.rustyquest.native_renderer.environment_depth.near_m";
pub(crate) const PROP_ENVIRONMENT_DEPTH_FAR_M: &str =
    "debug.rustyquest.native_renderer.environment_depth.far_m";
pub(crate) const PROP_ENVIRONMENT_DEPTH_HIGH_RATE_JSON_PAYLOAD: &str =
    "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_MODEL: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_model";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_RADIUS_CELLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.radius_cells";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_NEIGHBORS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_neighbors";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_OBSERVATIONS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_observations";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_MIN_SOURCE_LAYERS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.min_source_layers";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MIN_CELLS: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.component_min_cells";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_COMPONENT_MODE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.component_mode";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_SOURCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_source";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_NORMAL_COHERENCE: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.normal_coherence";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_SMALL_COMPONENT_POLICY: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.small_component_policy";
pub(crate) const PROP_ENVIRONMENT_DEPTH_SURFACE_SUPPORT_FREE_SPACE_DECAY: &str =
    "debug.rustyquest.native_renderer.environment_depth.surface_support.free_space_decay";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_AMPLITUDE: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.amplitude";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_ENABLED: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.enabled";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_OSCILLATOR_HZ: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.oscillator_hz";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_PHASE_HZ: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.phase_hz";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_SOURCE: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.source";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_SPEED_BOOST: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.speed_boost";
pub(crate) const PROP_PASSTHROUGH_STYLE_AUDIO_REACTIVE_UPDATE_HZ: &str =
    "debug.rustyquest.native_renderer.passthrough.style.audio_reactive.update_hz";
pub(crate) const PROP_PASSTHROUGH_STYLE_BRIGHTNESS: &str =
    "debug.rustyquest.native_renderer.passthrough.style.brightness";
pub(crate) const PROP_PASSTHROUGH_STYLE_COLOR_AMPLITUDE: &str =
    "debug.rustyquest.native_renderer.passthrough.style.color.amplitude";
pub(crate) const PROP_PASSTHROUGH_STYLE_COLOR_PHASE: &str =
    "debug.rustyquest.native_renderer.passthrough.style.color.phase";
pub(crate) const PROP_PASSTHROUGH_STYLE_CONTRAST: &str =
    "debug.rustyquest.native_renderer.passthrough.style.contrast";
pub(crate) const PROP_PASSTHROUGH_STYLE_EDGE_COLOR_A: &str =
    "debug.rustyquest.native_renderer.passthrough.style.edge_color.a";
pub(crate) const PROP_PASSTHROUGH_STYLE_EDGE_COLOR_B: &str =
    "debug.rustyquest.native_renderer.passthrough.style.edge_color.b";
pub(crate) const PROP_PASSTHROUGH_STYLE_EDGE_COLOR_G: &str =
    "debug.rustyquest.native_renderer.passthrough.style.edge_color.g";
pub(crate) const PROP_PASSTHROUGH_STYLE_EDGE_COLOR_R: &str =
    "debug.rustyquest.native_renderer.passthrough.style.edge_color.r";
pub(crate) const PROP_PASSTHROUGH_STYLE_MODE: &str =
    "debug.rustyquest.native_renderer.passthrough.style.mode";
pub(crate) const PROP_PASSTHROUGH_STYLE_OPACITY: &str =
    "debug.rustyquest.native_renderer.passthrough.style.opacity";
pub(crate) const PROP_PASSTHROUGH_STYLE_SATURATION: &str =
    "debug.rustyquest.native_renderer.passthrough.style.saturation";
pub(crate) const PROP_STIMULUS_VOLUME_ENABLED: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.enabled";
pub(crate) const PROP_STIMULUS_VOLUME_PROFILE: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.profile";
pub(crate) const PROP_STIMULUS_VOLUME_COMPOSITION: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.composition";
pub(crate) const PROP_STIMULUS_VOLUME_RENDER_TARGET: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.render_target";
pub(crate) const PROP_STIMULUS_VOLUME_RAYMARCH_SAMPLES: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.raymarch_samples";
pub(crate) const PROP_STIMULUS_VOLUME_CENTRAL_FOV_FRACTION: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.central_fov_fraction";
pub(crate) const PROP_STIMULUS_VOLUME_GRADIENT_SMOOTHING: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.gradient_smoothing";
pub(crate) const PROP_STIMULUS_VOLUME_PATTERN_FAMILY: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.pattern_family";
pub(crate) const PROP_STIMULUS_VOLUME_RANDOMIZE_ENABLED: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.enabled";
pub(crate) const PROP_STIMULUS_VOLUME_RANDOMIZE_MIN_HZ: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.min_hz";
pub(crate) const PROP_STIMULUS_VOLUME_RANDOMIZE_MAX_HZ: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.randomize.max_hz";
pub(crate) const PROP_STIMULUS_VOLUME_SAFETY_ACK: &str =
    "debug.rustyquest.native_renderer.stimulus_volume.safety_ack";
pub(crate) const PROP_CONTROL_PANEL_MODE: &str =
    "debug.rustyquest.native_renderer.control_panel.mode";
pub(crate) const PROP_PROCESSING_LAYER: &str = "debug.rustyquest.native_renderer.processing.layer";
pub(crate) const PROP_PROJECTION_BORDER_POLICY: &str =
    "debug.rustyquest.native_renderer.projection.border.policy";
pub(crate) const PROP_PROJECTION_BORDER_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.border.opacity";
pub(crate) const PROP_PROJECTION_AREA_OPACITY: &str =
    "debug.rustyquest.native_renderer.projection.area.opacity";
pub(crate) const PROP_PERIPHERAL_STRETCH_CORE_SCALE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.core.scale";
pub(crate) const PROP_PERIPHERAL_STRETCH_EDGE_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.edge.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_MAX_INSET_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.max.inset.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_INNER_BLEND_UV: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.inner.blend.uv";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_CURVE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.curve";
pub(crate) const PROP_PERIPHERAL_STRETCH_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.blend.mode";
pub(crate) const PROP_PERIPHERAL_STRETCH_DEBUG: &str =
    "debug.rustyquest.native_renderer.peripheral.stretch.debug";
pub(crate) const PROP_VIDEO_BORDER_BLEND_MODE: &str =
    "debug.rustyquest.native_renderer.video_border_blend.mode";
pub(crate) const PROP_PRIVATE_LAYER_ENABLED: &str =
    "debug.rustyquest.native_renderer.private_layer.enabled";
pub(crate) const PROP_PRIVATE_LAYER_SECONDS: &str =
    "debug.rustyquest.native_renderer.private_layer.layer_seconds";
pub(crate) const PROP_PRIVATE_LAYER_OVERRIDE: &str =
    "debug.rustyquest.native_renderer.private_layer.layer_override";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT0: &str =
    "debug.rustyquest.native_renderer.private_layer.effect0";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT1: &str =
    "debug.rustyquest.native_renderer.private_layer.effect1";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT2: &str =
    "debug.rustyquest.native_renderer.private_layer.effect2";
pub(crate) const PROP_PRIVATE_LAYER_EFFECT3: &str =
    "debug.rustyquest.native_renderer.private_layer.effect3";
