use std::env;
use std::fs;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR must be set"));
    write_recorded_hand_replay_source(&out_dir);
    let shaders = [
        (
            "shaders/surface_particles.vert.glsl",
            "surface_particles.vert.spv",
            "vertex",
        ),
        (
            "shaders/surface_particles.frag.glsl",
            "surface_particles.frag.spv",
            "fragment",
        ),
        (
            "shaders/surface_particles.comp.glsl",
            "surface_particles.comp.spv",
            "compute",
        ),
        (
            "shaders/surface_private_particles.vert.glsl",
            "surface_private_particles.vert.spv",
            "vertex",
        ),
        (
            "shaders/surface_private_particles.frag.glsl",
            "surface_private_particles.frag.spv",
            "fragment",
        ),
        (
            "shaders/replay_hands.vert.glsl",
            "replay_hands.vert.spv",
            "vertex",
        ),
        (
            "shaders/replay_hands.frag.glsl",
            "replay_hands.frag.spv",
            "fragment",
        ),
        (
            "shaders/camera_hwb_probe.vert.glsl",
            "camera_hwb_probe.vert.spv",
            "vertex",
        ),
        (
            "shaders/camera_hwb_probe.frag.glsl",
            "camera_hwb_probe.frag.spv",
            "fragment",
        ),
        (
            "shaders/camera_hwb_raw_color.frag.glsl",
            "camera_hwb_raw_color.frag.spv",
            "fragment",
        ),
        (
            "shaders/public_guide_blur.frag.glsl",
            "public_guide_blur.frag.spv",
            "fragment",
        ),
        (
            "shaders/spatial_video_projection.vert.glsl",
            "spatial_video_projection.vert.spv",
            "vertex",
        ),
        (
            "shaders/spatial_video_projection.frag.glsl",
            "spatial_video_projection.frag.spv",
            "fragment",
        ),
    ];
    let glslc = find_glslc();
    let mut public_guide_blur_shader_byte_count = 0_u64;
    for (source, output_name, stage) in shaders {
        println!("cargo:rerun-if-changed={source}");
        let output = out_dir.join(output_name);
        let byte_count = compile_shader(&glslc, Path::new(source), &output, stage);
        if output_name == "public_guide_blur.frag.spv" {
            public_guide_blur_shader_byte_count = byte_count;
        }
    }
    let opaque_guide_shader = compile_optional_guide_shader_env(
        &glslc,
        &out_dir,
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_GUIDE_SHADER",
    );
    let opaque_projection_shader = compile_optional_shader_env(
        &glslc,
        &out_dir,
        "RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_SHADER",
        "spatial_opaque_projection.frag.spv",
        "fragment",
    );
    let opaque_projection_effect =
        opaque_projection_effect_env("RUSTY_QUEST_SPATIAL_CAMERA_PANEL_OPAQUE_PROJECTION_EFFECT");
    let private_surface_particle = private_surface_particle_build_env(&glslc, &out_dir);
    write_spatial_surface_private_particle_payload_metadata(&out_dir, &private_surface_particle);
    write_spatial_multistack_build_metadata(
        &out_dir,
        public_guide_blur_shader_byte_count,
        opaque_guide_shader,
        opaque_projection_shader,
        opaque_projection_effect,
        &private_surface_particle,
    );
}

#[derive(Clone, Copy)]
struct OptionalShaderBuild {
    compiled: bool,
    byte_count: u64,
}

#[derive(Clone, Copy)]
struct OptionalGuideShaderBuild {
    compiled: bool,
    total_byte_count: u64,
    pass_byte_counts: [u64; 6],
}

#[derive(Clone)]
struct PrivateSurfaceParticleBuild {
    profile_configured: bool,
    shader_configured: bool,
    payload_dir_configured: bool,
    marker_prefix_configured: bool,
    shader_compiled: bool,
    shader_byte_count: u64,
    payload_files_present: bool,
    positions_byte_count: u64,
    normals_byte_count: u64,
    aux0_byte_count: u64,
    mask_texture_byte_count: u64,
    staged_payload_ready: bool,
    profile_counts_present: bool,
    profile_id_hash: u64,
    main_particle_count: u64,
    tracer_state_capacity: u64,
    tracer_draw_count: u64,
    retention_indicator_draw_count: u64,
    default_draw_count: u64,
    alias_policy_present: bool,
    public_runtime_packet_field_count: u64,
    active_alias_count: u64,
    activation_gated_alias_count: u64,
    future_rejection_marker_count: u64,
    forbidden_alias_payload_count: u64,
    profile_json: String,
}

#[derive(Clone, Default)]
struct PrivateSurfaceParticleProfileCounts {
    present: bool,
    profile_id_hash: u64,
    main_particle_count: u64,
    tracer_state_capacity: u64,
    tracer_draw_count: u64,
    retention_indicator_draw_count: u64,
    default_draw_count: u64,
    alias_policy_present: bool,
    public_runtime_packet_field_count: u64,
    active_alias_count: u64,
    activation_gated_alias_count: u64,
    future_rejection_marker_count: u64,
    forbidden_alias_payload_count: u64,
    profile_json: String,
}

fn compile_optional_guide_shader_env(
    glslc: &Path,
    out_dir: &Path,
    env_key: &str,
) -> OptionalGuideShaderBuild {
    println!("cargo:rerun-if-env-changed={env_key}");
    let mut pass_byte_counts = [0_u64; 6];
    let output_names = (0..pass_byte_counts.len())
        .map(|pass_index| format!("spatial_opaque_guide_pass_{pass_index}.frag.spv"))
        .collect::<Vec<_>>();
    let Some(source) = env_path(env_key) else {
        for output_name in output_names {
            fs::write(out_dir.join(output_name), []).unwrap_or_else(|error| {
                panic!("failed to write empty optional guide shader output: {error}")
            });
        }
        return OptionalGuideShaderBuild {
            compiled: false,
            total_byte_count: 0,
            pass_byte_counts,
        };
    };
    println!("cargo:rerun-if-changed={}", source.display());
    for (pass_index, output_name) in output_names.iter().enumerate() {
        let define = format!("-DPRIVATE_LAYER_GUIDE_PASS_MODE={pass_index}");
        pass_byte_counts[pass_index] = compile_shader_with_args(
            glslc,
            &source,
            &out_dir.join(output_name),
            "fragment",
            &[define],
        );
    }
    OptionalGuideShaderBuild {
        compiled: true,
        total_byte_count: pass_byte_counts.iter().copied().sum(),
        pass_byte_counts,
    }
}

fn compile_optional_shader_env(
    glslc: &Path,
    out_dir: &Path,
    env_key: &str,
    output_name: &str,
    stage: &str,
) -> OptionalShaderBuild {
    println!("cargo:rerun-if-env-changed={env_key}");
    let Some(source) = env_path(env_key) else {
        fs::write(out_dir.join(output_name), []).unwrap_or_else(|error| {
            panic!("failed to write empty optional shader output {output_name}: {error}")
        });
        return OptionalShaderBuild {
            compiled: false,
            byte_count: 0,
        };
    };
    println!("cargo:rerun-if-changed={}", source.display());
    let byte_count = compile_shader(glslc, &source, &out_dir.join(output_name), stage);
    OptionalShaderBuild {
        compiled: true,
        byte_count,
    }
}

fn opaque_projection_effect_env(env_key: &str) -> [f32; 4] {
    println!("cargo:rerun-if-env-changed={env_key}");
    let Some(value) = env::var_os(env_key) else {
        return [1.0, 1.0, 0.0, 1.0];
    };
    let value = value.to_string_lossy();
    let parts = value
        .split([',', ';', ' '])
        .filter(|part| !part.is_empty())
        .map(|part| {
            part.parse::<f32>()
                .unwrap_or_else(|error| panic!("{env_key} has invalid float {part}: {error}"))
        })
        .collect::<Vec<_>>();
    if parts.len() != 4 {
        panic!("{env_key} must contain four floats");
    }
    [parts[0], parts[1], parts[2], parts[3]]
}

#[derive(Clone, Copy)]
struct PrivateSurfaceParticlePayloadBuild {
    files_present: bool,
    positions_byte_count: u64,
    normals_byte_count: u64,
    aux0_byte_count: u64,
    mask_texture_byte_count: u64,
}

fn private_surface_particle_build_env(glslc: &Path, out_dir: &Path) -> PrivateSurfaceParticleBuild {
    for env_key in [
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR",
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX",
    ] {
        println!("cargo:rerun-if-env-changed={env_key}");
    }
    let profile_path = env_path("RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PROFILE");
    let profile_configured = profile_path.is_some();
    let shader_configured =
        env_path("RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER").is_some();
    let payload_dir = env_path("RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_PAYLOAD_DIR");
    let payload_dir_configured = payload_dir.is_some();
    let marker_prefix_configured =
        env::var_os("RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_MARKER_PREFIX")
            .map(|value| !value.is_empty())
            .unwrap_or(false);
    let shader = compile_optional_shader_env(
        glslc,
        out_dir,
        "RUSTY_QUEST_SPATIAL_SURFACE_PRIVATE_PARTICLE_SHADER",
        "spatial_surface_private_particle.comp.spv",
        "compute",
    );
    let payload = stage_private_surface_particle_payload_files(out_dir, payload_dir.as_deref());
    let profile_counts = private_surface_particle_profile_counts(profile_path.as_deref());
    let executable_inputs_configured = profile_configured
        && shader_configured
        && payload_dir_configured
        && marker_prefix_configured;
    PrivateSurfaceParticleBuild {
        profile_configured,
        shader_configured,
        payload_dir_configured,
        marker_prefix_configured,
        shader_compiled: shader.compiled,
        shader_byte_count: shader.byte_count,
        payload_files_present: payload.files_present,
        positions_byte_count: payload.positions_byte_count,
        normals_byte_count: payload.normals_byte_count,
        aux0_byte_count: payload.aux0_byte_count,
        mask_texture_byte_count: payload.mask_texture_byte_count,
        staged_payload_ready: executable_inputs_configured
            && shader.compiled
            && payload.files_present,
        profile_counts_present: profile_counts.present,
        profile_id_hash: profile_counts.profile_id_hash,
        main_particle_count: profile_counts.main_particle_count,
        tracer_state_capacity: profile_counts.tracer_state_capacity,
        tracer_draw_count: profile_counts.tracer_draw_count,
        retention_indicator_draw_count: profile_counts.retention_indicator_draw_count,
        default_draw_count: profile_counts.default_draw_count,
        alias_policy_present: profile_counts.alias_policy_present,
        public_runtime_packet_field_count: profile_counts.public_runtime_packet_field_count,
        active_alias_count: profile_counts.active_alias_count,
        activation_gated_alias_count: profile_counts.activation_gated_alias_count,
        future_rejection_marker_count: profile_counts.future_rejection_marker_count,
        forbidden_alias_payload_count: profile_counts.forbidden_alias_payload_count,
        profile_json: profile_counts.profile_json,
    }
}

fn private_surface_particle_profile_counts(
    profile_path: Option<&Path>,
) -> PrivateSurfaceParticleProfileCounts {
    let Some(profile_path) = profile_path else {
        return PrivateSurfaceParticleProfileCounts::default();
    };
    println!("cargo:rerun-if-changed={}", profile_path.display());
    let text = fs::read_to_string(profile_path).unwrap_or_else(|error| {
        panic!(
            "failed to read private surface-particle profile {}: {error}",
            profile_path.display()
        )
    });
    let json = serde_json::from_str::<serde_json::Value>(&text).unwrap_or_else(|error| {
        panic!(
            "failed to parse private surface-particle profile JSON {}: {error}",
            profile_path.display()
        )
    });
    let profile_id = json
        .get("profile_id")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("");
    let profile_json = serde_json::to_string(&json).unwrap_or_else(|error| {
        panic!(
            "failed to normalize private surface-particle profile JSON {}: {error}",
            profile_path.display()
        )
    });
    let payload = json
        .get("private_payload")
        .and_then(serde_json::Value::as_object)
        .unwrap_or_else(|| {
            panic!(
                "private surface-particle profile {} missing private_payload object",
                profile_path.display()
            )
        });
    let alias_policy = json
        .get("runtime_parameter_alias_policy")
        .and_then(serde_json::Value::as_object);
    let (
        alias_policy_present,
        public_runtime_packet_field_count,
        active_alias_count,
        activation_gated_alias_count,
        future_rejection_marker_count,
        forbidden_alias_payload_count,
    ) = if let Some(alias_policy) = alias_policy {
        (
            true,
            private_surface_particle_profile_array_len(
                alias_policy,
                "public_runtime_packet_fields",
                profile_path,
            ),
            private_surface_particle_profile_array_len(
                alias_policy,
                "active_aliases",
                profile_path,
            ),
            private_surface_particle_profile_array_len(
                alias_policy,
                "activation_gated_aliases",
                profile_path,
            ),
            private_surface_particle_profile_array_len(
                alias_policy,
                "future_profile_aware_rejection_markers",
                profile_path,
            ),
            private_surface_particle_profile_array_len(
                alias_policy,
                "forbidden_alias_payloads",
                profile_path,
            ),
        )
    } else {
        (false, 0, 0, 0, 0, 0)
    };
    PrivateSurfaceParticleProfileCounts {
        present: true,
        profile_id_hash: stable_fnv1a64(profile_id.as_bytes()),
        main_particle_count: private_surface_particle_profile_u64(
            payload,
            "main_particle_count",
            profile_path,
        ),
        tracer_state_capacity: private_surface_particle_profile_u64(
            payload,
            "tracer_state_capacity",
            profile_path,
        ),
        tracer_draw_count: private_surface_particle_profile_u64(
            payload,
            "tracer_draw_count",
            profile_path,
        ),
        retention_indicator_draw_count: private_surface_particle_profile_u64(
            payload,
            "retention_indicator_draw_count",
            profile_path,
        ),
        default_draw_count: private_surface_particle_profile_u64(
            payload,
            "default_draw_count_including_retention",
            profile_path,
        ),
        alias_policy_present,
        public_runtime_packet_field_count,
        active_alias_count,
        activation_gated_alias_count,
        future_rejection_marker_count,
        forbidden_alias_payload_count,
        profile_json,
    }
}

fn private_surface_particle_profile_array_len(
    object: &serde_json::Map<String, serde_json::Value>,
    key: &str,
    profile_path: &Path,
) -> u64 {
    object
        .get(key)
        .and_then(serde_json::Value::as_array)
        .unwrap_or_else(|| {
            panic!(
                "private surface-particle profile {} missing array field runtime_parameter_alias_policy.{key}",
                profile_path.display()
            )
        })
        .len() as u64
}

fn stable_fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325_u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn private_surface_particle_profile_u64(
    payload: &serde_json::Map<String, serde_json::Value>,
    key: &str,
    profile_path: &Path,
) -> u64 {
    payload
        .get(key)
        .and_then(serde_json::Value::as_u64)
        .unwrap_or_else(|| {
            panic!(
                "private surface-particle profile {} missing numeric private_payload.{key}",
                profile_path.display()
            )
        })
}

fn stage_private_surface_particle_payload_files(
    out_dir: &Path,
    payload_dir: Option<&Path>,
) -> PrivateSurfaceParticlePayloadBuild {
    let payload_files = [
        (
            "private_particle_positions.f32.bin",
            "spatial_private_particle_positions.f32.bin",
        ),
        (
            "private_particle_normals.f32.bin",
            "spatial_private_particle_normals.f32.bin",
        ),
        (
            "private_particle_aux0.u32.bin",
            "spatial_private_particle_aux0.u32.bin",
        ),
        (
            "private_particle_mask_texture.r8.bin",
            "spatial_private_particle_mask_texture.r8.bin",
        ),
    ];
    let Some(payload_dir) = payload_dir else {
        for (_, output_name) in payload_files {
            fs::write(out_dir.join(output_name), []).unwrap_or_else(|error| {
                panic!(
                    "failed to write empty private particle payload output {output_name}: {error}"
                )
            });
        }
        return PrivateSurfaceParticlePayloadBuild {
            files_present: false,
            positions_byte_count: 0,
            normals_byte_count: 0,
            aux0_byte_count: 0,
            mask_texture_byte_count: 0,
        };
    };
    if !payload_dir.is_dir() {
        panic!(
            "private surface-particle payload directory is not a directory: {}",
            payload_dir.display()
        );
    }

    let mut byte_counts = [0_u64; 4];
    for (index, (input_name, output_name)) in payload_files.iter().enumerate() {
        let input = payload_dir.join(input_name);
        if !input.is_file() {
            panic!(
                "private surface-particle payload file missing: {}",
                input.display()
            );
        }
        println!("cargo:rerun-if-changed={}", input.display());
        byte_counts[index] = fs::copy(&input, out_dir.join(output_name)).unwrap_or_else(|error| {
            panic!(
                "failed to stage private surface-particle payload {} -> {}: {error}",
                input.display(),
                out_dir.join(output_name).display()
            )
        });
    }
    PrivateSurfaceParticlePayloadBuild {
        files_present: true,
        positions_byte_count: byte_counts[0],
        normals_byte_count: byte_counts[1],
        aux0_byte_count: byte_counts[2],
        mask_texture_byte_count: byte_counts[3],
    }
}

fn compile_shader(glslc: &Path, source: &Path, output: &Path, stage: &str) -> u64 {
    compile_shader_with_args(glslc, source, output, stage, &[])
}

fn compile_shader_with_args(
    glslc: &Path,
    source: &Path,
    output: &Path,
    stage: &str,
    extra_args: &[String],
) -> u64 {
    let mut command = Command::new(glslc);
    command
        .arg("-O")
        .arg(format!("-fshader-stage={stage}"))
        .args(extra_args)
        .arg("-o")
        .arg(output)
        .arg(source);
    let status = command
        .status()
        .unwrap_or_else(|error| panic!("failed to run glslc at {}: {error}", glslc.display()));
    if !status.success() {
        panic!(
            "glslc failed for {} with status {status}; glslc={}",
            source.display(),
            glslc.display()
        );
    }
    fs::metadata(output)
        .unwrap_or_else(|error| {
            panic!(
                "failed to stat compiled shader {}: {error}",
                output.display()
            )
        })
        .len()
}

fn write_spatial_multistack_build_metadata(
    out_dir: &Path,
    public_guide_blur_shader_byte_count: u64,
    opaque_guide_shader: OptionalGuideShaderBuild,
    opaque_projection_shader: OptionalShaderBuild,
    opaque_projection_effect: [f32; 4],
    private_surface_particle: &PrivateSurfaceParticleBuild,
) {
    let output = out_dir.join("spatial_public_multistack_build.rs");
    let guide_pass_byte_counts = opaque_guide_shader
        .pass_byte_counts
        .iter()
        .map(u64::to_string)
        .collect::<Vec<_>>()
        .join(", ");
    fs::write(
        &output,
        format!(
            "#[allow(dead_code)]\n\
             pub(crate) const PUBLIC_GUIDE_BLUR_SHADER_COMPILED: bool = true;\n\
             #[allow(dead_code)]\n\
             pub(crate) const PUBLIC_GUIDE_BLUR_SHADER_BYTE_COUNT: usize = {public_guide_blur_shader_byte_count};\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_GUIDE_SHADER_COMPILED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_GUIDE_SHADER_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_GUIDE_SHADER_PASS_COUNT: usize = 6;\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_GUIDE_SHADER_PASS_BYTE_COUNTS: [usize; 6] = [{guide_pass_byte_counts}];\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_PROJECTION_SHADER_COMPILED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_PROJECTION_SHADER_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const OPAQUE_PROJECTION_EFFECT: [f32; 4] = [{:.8}, {:.8}, {:.8}, {:.8}];\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_CONFIGURED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_SHADER_CONFIGURED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PAYLOAD_DIR_CONFIGURED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MARKER_PREFIX_CONFIGURED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PAYLOAD_FILES_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_STAGED_PAYLOAD_READY: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_COUNTS_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_ID_HASH: u64 = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_JSON: &str = {:?};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MAIN_PARTICLE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_TRACER_STATE_CAPACITY: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_TRACER_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_RETENTION_INDICATOR_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_DEFAULT_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ALIAS_POLICY_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PUBLIC_RUNTIME_PACKET_FIELD_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ACTIVE_ALIAS_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ACTIVATION_GATED_ALIAS_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_FUTURE_REJECTION_MARKER_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_FORBIDDEN_ALIAS_PAYLOAD_COUNT: usize = {};\n",
            bool_literal(opaque_guide_shader.compiled),
            opaque_guide_shader.total_byte_count,
            bool_literal(opaque_projection_shader.compiled),
            opaque_projection_shader.byte_count,
            opaque_projection_effect[0],
            opaque_projection_effect[1],
            opaque_projection_effect[2],
            opaque_projection_effect[3],
            bool_literal(private_surface_particle.profile_configured),
            bool_literal(private_surface_particle.shader_configured),
            bool_literal(private_surface_particle.payload_dir_configured),
            bool_literal(private_surface_particle.marker_prefix_configured),
            bool_literal(private_surface_particle.payload_files_present),
            bool_literal(private_surface_particle.staged_payload_ready),
            bool_literal(private_surface_particle.profile_counts_present),
            private_surface_particle.profile_id_hash,
            private_surface_particle.profile_json,
            private_surface_particle.main_particle_count,
            private_surface_particle.tracer_state_capacity,
            private_surface_particle.tracer_draw_count,
            private_surface_particle.retention_indicator_draw_count,
            private_surface_particle.default_draw_count,
            bool_literal(private_surface_particle.alias_policy_present),
            private_surface_particle.public_runtime_packet_field_count,
            private_surface_particle.active_alias_count,
            private_surface_particle.activation_gated_alias_count,
            private_surface_particle.future_rejection_marker_count,
            private_surface_particle.forbidden_alias_payload_count,
        ),
    )
    .unwrap_or_else(|error| {
        panic!(
            "failed to write Spatial public multi-stack build metadata {}: {error}",
            output.display()
        )
    });
}

fn write_spatial_surface_private_particle_payload_metadata(
    out_dir: &Path,
    private_surface_particle: &PrivateSurfaceParticleBuild,
) {
    let output = out_dir.join("spatial_surface_private_particle_payload.rs");
    fs::write(
        &output,
        format!(
            "#[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_SHADER_COMPILED: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_SHADER_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_SHADER_SPIRV: &[u8] = include_bytes!(concat!(env!(\"OUT_DIR\"), \"/spatial_surface_private_particle.comp.spv\"));\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PAYLOAD_FILES_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_POSITIONS_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_NORMALS_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_AUX0_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_BYTE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_WIDTH: u32 = 128;\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_HEIGHT: u32 = 128;\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_LAYERS: u32 = 64;\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_MODE: &str = \"texture-array-nearest\";\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_POSITIONS_BYTES: &[u8] = include_bytes!(concat!(env!(\"OUT_DIR\"), \"/spatial_private_particle_positions.f32.bin\"));\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_NORMALS_BYTES: &[u8] = include_bytes!(concat!(env!(\"OUT_DIR\"), \"/spatial_private_particle_normals.f32.bin\"));\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_AUX0_BYTES: &[u8] = include_bytes!(concat!(env!(\"OUT_DIR\"), \"/spatial_private_particle_aux0.u32.bin\"));\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MASK_TEXTURE_BYTES: &[u8] = include_bytes!(concat!(env!(\"OUT_DIR\"), \"/spatial_private_particle_mask_texture.r8.bin\"));\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_STAGED_PAYLOAD_READY: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_COUNTS_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_ID_HASH: u64 = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PROFILE_JSON: &str = {:?};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_MAIN_PARTICLE_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_TRACER_STATE_CAPACITY: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_TRACER_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_RETENTION_INDICATOR_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_DEFAULT_DRAW_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ALIAS_POLICY_PRESENT: bool = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_PUBLIC_RUNTIME_PACKET_FIELD_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ACTIVE_ALIAS_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_ACTIVATION_GATED_ALIAS_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_FUTURE_REJECTION_MARKER_COUNT: usize = {};\n\
             #[allow(dead_code)]\n\
             pub(crate) const PRIVATE_SURFACE_PARTICLE_FORBIDDEN_ALIAS_PAYLOAD_COUNT: usize = {};\n",
            bool_literal(private_surface_particle.shader_compiled),
            private_surface_particle.shader_byte_count,
            bool_literal(private_surface_particle.payload_files_present),
            private_surface_particle.positions_byte_count,
            private_surface_particle.normals_byte_count,
            private_surface_particle.aux0_byte_count,
            private_surface_particle.mask_texture_byte_count,
            bool_literal(private_surface_particle.staged_payload_ready),
            bool_literal(private_surface_particle.profile_counts_present),
            private_surface_particle.profile_id_hash,
            private_surface_particle.profile_json,
            private_surface_particle.main_particle_count,
            private_surface_particle.tracer_state_capacity,
            private_surface_particle.tracer_draw_count,
            private_surface_particle.retention_indicator_draw_count,
            private_surface_particle.default_draw_count,
            bool_literal(private_surface_particle.alias_policy_present),
            private_surface_particle.public_runtime_packet_field_count,
            private_surface_particle.active_alias_count,
            private_surface_particle.activation_gated_alias_count,
            private_surface_particle.future_rejection_marker_count,
            private_surface_particle.forbidden_alias_payload_count,
        ),
    )
    .unwrap_or_else(|error| {
        panic!(
            "failed to write Spatial surface private particle payload metadata {}: {error}",
            output.display()
        )
    });
}

fn bool_literal(value: bool) -> &'static str {
    if value {
        "true"
    } else {
        "false"
    }
}

fn write_recorded_hand_replay_source(out_dir: &Path) {
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT");
    println!(
        "cargo:rerun-if-changed=../../../fixtures/native-renderer/recorded-hand-replay-public-shape.json"
    );

    let output = out_dir.join("recorded_hand_replay_source.json");
    if let Ok(capture_dir) = env::var("RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR") {
        let capture_dir = PathBuf::from(capture_dir);
        if capture_dir.is_dir() {
            let frame_limit = env::var("RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT")
                .ok()
                .and_then(|value| value.parse::<usize>().ok())
                .unwrap_or(24)
                .clamp(1, 120);
            let generated = generate_recorded_hand_capture_source(&capture_dir, frame_limit);
            fs::write(&output, generated).unwrap_or_else(|error| {
                panic!(
                    "failed to write generated recorded hand replay source {}: {error}",
                    output.display()
                )
            });
            return;
        }
    }

    let fallback =
        Path::new("../../../fixtures/native-renderer/recorded-hand-replay-public-shape.json");
    fs::copy(fallback, &output).unwrap_or_else(|error| {
        panic!(
            "failed to copy public recorded hand replay source {} -> {}: {error}",
            fallback.display(),
            output.display()
        )
    });
}

fn generate_recorded_hand_capture_source(capture_dir: &Path, frame_limit: usize) -> String {
    let manifest_json = read_text(&capture_dir.join("capture.manifest.json"));
    let left_rig_json = read_text(&capture_dir.join("left.rig.json"));
    let right_rig_json = read_text(&capture_dir.join("right.rig.json"));
    let left_validation_lines =
        read_first_lines(&capture_dir.join("left.validation_mesh.jsonl"), frame_limit);
    let right_validation_lines = read_first_lines(
        &capture_dir.join("right.validation_mesh.jsonl"),
        frame_limit,
    );
    let left_validation_frame_count = line_count(&capture_dir.join("left.validation_mesh.jsonl"));
    let right_validation_frame_count = line_count(&capture_dir.join("right.validation_mesh.jsonl"));

    format!(
        "{{\"schema\":\"rusty.quest.native_renderer.recorded_hand_replay_source.v1\",\"source_id\":\"local-recorded-meta-quest-hand-capture\",\"source_kind\":\"external-recorded-capture-build-env\",\"recorded_input_equivalent\":true,\"validation_input_shape\":\"validation-mesh-frames\",\"capture_manifest_json\":\"{}\",\"hands\":[{},{}]}}",
        json_escape(&manifest_json),
        generated_hand_json(
            "left",
            &left_rig_json,
            &left_validation_lines,
            left_validation_frame_count
        ),
        generated_hand_json(
            "right",
            &right_rig_json,
            &right_validation_lines,
            right_validation_frame_count
        ),
    )
}

fn generated_hand_json(
    handedness: &str,
    rig_json: &str,
    validation_mesh_lines: &[String],
    validation_frame_count: usize,
) -> String {
    let validation_mesh_json = validation_mesh_lines
        .iter()
        .map(|line| format!("\"{}\"", json_escape(line)))
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"handedness\":\"{}\",\"validation_frame_count\":{},\"rig_json\":\"{}\",\"validation_mesh_jsonl\":[{}]}}",
        handedness,
        validation_frame_count,
        json_escape(rig_json),
        validation_mesh_json
    )
}

fn read_text(path: &Path) -> String {
    fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()))
}

fn read_first_lines(path: &Path, limit: usize) -> Vec<String> {
    println!("cargo:rerun-if-changed={}", path.display());
    let file = fs::File::open(path)
        .unwrap_or_else(|error| panic!("failed to open {}: {error}", path.display()));
    BufReader::new(file)
        .lines()
        .take(limit)
        .map(|line| {
            line.unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()))
        })
        .collect()
}

fn line_count(path: &Path) -> usize {
    let file = fs::File::open(path)
        .unwrap_or_else(|error| panic!("failed to open {}: {error}", path.display()));
    BufReader::new(file).lines().count()
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\\' => escaped.push_str("\\\\"),
            '"' => escaped.push_str("\\\""),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            '\u{08}' => escaped.push_str("\\b"),
            '\u{0c}' => escaped.push_str("\\f"),
            character if character <= '\u{1f}' => {
                escaped.push_str(&format!("\\u{:04x}", character as u32));
            }
            character => escaped.push(character),
        }
    }
    escaped
}

fn find_glslc() -> PathBuf {
    if let Some(path) = env_path("GLSLC") {
        return path;
    }
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"] {
        if let Some(root) = env_path(key) {
            let candidate = root
                .join("shader-tools")
                .join("windows-x86_64")
                .join("glslc.exe");
            if candidate.is_file() {
                return candidate;
            }
            let candidate = root.join("shader-tools").join("linux-x86_64").join("glslc");
            if candidate.is_file() {
                return candidate;
            }
            let candidate = root
                .join("shader-tools")
                .join("darwin-x86_64")
                .join("glslc");
            if candidate.is_file() {
                return candidate;
            }
        }
    }
    if let Some(android_home) = env_path("ANDROID_HOME") {
        let ndk_dir = android_home.join("ndk");
        if let Ok(entries) = std::fs::read_dir(ndk_dir) {
            for entry in entries.flatten() {
                let root = entry.path();
                let candidate = root
                    .join("shader-tools")
                    .join("windows-x86_64")
                    .join("glslc.exe");
                if candidate.is_file() {
                    return candidate;
                }
            }
        }
    }
    PathBuf::from("glslc")
}

fn env_path(key: &str) -> Option<PathBuf> {
    env::var_os(key)
        .map(PathBuf::from)
        .filter(|path| !path.as_os_str().is_empty())
}
