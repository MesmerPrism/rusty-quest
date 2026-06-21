use std::{
    env, fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::Command,
};

fn main() {
    println!("cargo:rerun-if-changed=shaders/camera_projection.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/camera_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/display_composite_feedback.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/display_composite_feedback.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/display_composite_recursive_feedback.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/video_projection.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/video_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/camera_luma_diagnostic.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_downsample.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_5tap.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_video_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_hand_skinning.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_tile_bins.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_field.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_overlay.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_mesh_visual.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_mesh_visual.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_anchor_particles.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_anchor_particles.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_anchor_particles_sort.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/environment_depth_particles_synthetic.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/environment_depth_particles_meta.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/environment_depth_particles.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/environment_depth_particles.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/private_particles_placeholder.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/private_particles_sort.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/private_particles.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/private_particles.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/stimulus_volume_raymarch.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/stimulus_volume_projection.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/stimulus_volume_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/private_kuramoto_particles_placeholder.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/private_layer_placeholder.frag.glsl");
    println!(
        "cargo:rerun-if-changed=../../../fixtures/native-renderer/recorded-hand-replay-public-shape.json"
    );
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_DATA_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_SHADER");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_SHADER_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DATA_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_KIND");
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_R8"
    );
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS");
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_MODE"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_OPACITY"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_ORDERING_MODE"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_MAX_COUNT"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_VISUAL_SCALE"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_PREFIX"
    );
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_FIELDS"
    );
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_SHADER_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_GUIDE_SHADER");
    println!(
        "cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_PROJECTION_SHADER"
    );

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR is set by Cargo"));
    write_recorded_hand_replay_source(&out_dir);
    let private_layer_sources = private_layer_shader_sources();
    write_private_layer_payload_config(&out_dir, private_layer_sources.is_some());
    let private_kuramoto_payload = private_kuramoto_payload_sources();
    write_private_kuramoto_payload_config(&out_dir, private_kuramoto_payload.as_ref());
    write_private_kuramoto_payload_files(&out_dir, private_kuramoto_payload.as_ref());
    let private_particle_payload = private_particle_payload_sources();
    write_private_particle_payload_config(&out_dir, private_particle_payload.as_ref());
    write_private_particle_payload_files(&out_dir, private_particle_payload.as_ref());

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }

    let glslc = find_glslc().unwrap_or_else(|| {
        panic!(
            "Android shader build needs glslc. Put glslc on PATH, set GLSLC, or set ANDROID_NDK_HOME."
        )
    });
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/camera_projection.vert.glsl"),
        &out_dir.join("camera_projection.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/camera_projection.frag.glsl"),
        &out_dir.join("camera_projection.frag.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/display_composite_feedback.vert.glsl"),
        &out_dir.join("display_composite_feedback.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/display_composite_feedback.frag.glsl"),
        &out_dir.join("display_composite_feedback.frag.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/display_composite_recursive_feedback.frag.glsl"),
        &out_dir.join("display_composite_recursive_feedback.frag.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/video_projection.vert.glsl"),
        &out_dir.join("video_projection.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/video_projection.frag.glsl"),
        &out_dir.join("video_projection.frag.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/camera_luma_diagnostic.comp.glsl"),
        &out_dir.join("camera_luma_diagnostic.comp.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/guide_blur_downsample.frag.glsl"),
        &out_dir.join("guide_blur_downsample.frag.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/guide_blur_5tap.frag.glsl"),
        &out_dir.join("guide_blur_5tap.frag.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/guide_projection.frag.glsl"),
        &out_dir.join("guide_projection.frag.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/guide_video_projection.frag.glsl"),
        &out_dir.join("guide_video_projection.frag.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/gpu_hand_skinning.comp.glsl"),
        &out_dir.join("gpu_hand_skinning.comp.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/gpu_sdf_tile_bins.comp.glsl"),
        &out_dir.join("gpu_sdf_tile_bins.comp.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/gpu_sdf_field.comp.glsl"),
        &out_dir.join("gpu_sdf_field.comp.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/gpu_sdf_overlay.frag.glsl"),
        &out_dir.join("gpu_sdf_overlay.frag.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/hand_mesh_visual.vert.glsl"),
        &out_dir.join("hand_mesh_visual.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/hand_mesh_visual.frag.glsl"),
        &out_dir.join("hand_mesh_visual.frag.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/hand_anchor_particles.vert.glsl"),
        &out_dir.join("hand_anchor_particles.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/hand_anchor_particles.frag.glsl"),
        &out_dir.join("hand_anchor_particles.frag.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/hand_anchor_particles_sort.comp.glsl"),
        &out_dir.join("hand_anchor_particles_sort.comp.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/environment_depth_particles_synthetic.comp.glsl"),
        &out_dir.join("environment_depth_particles_synthetic.comp.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/environment_depth_particles_meta.comp.glsl"),
        &out_dir.join("environment_depth_particles_meta.comp.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/environment_depth_particles.vert.glsl"),
        &out_dir.join("environment_depth_particles.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/environment_depth_particles.frag.glsl"),
        &out_dir.join("environment_depth_particles.frag.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/private_particles.vert.glsl"),
        &out_dir.join("private_particles.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/private_particles.frag.glsl"),
        &out_dir.join("private_particles.frag.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/private_particles_sort.comp.glsl"),
        &out_dir.join("private_particles_sort.comp.spv"),
    );
    compile_shader(
        &glslc,
        "compute",
        Path::new("shaders/stimulus_volume_raymarch.comp.glsl"),
        &out_dir.join("stimulus_volume_raymarch.comp.spv"),
    );
    compile_shader(
        &glslc,
        "vertex",
        Path::new("shaders/stimulus_volume_projection.vert.glsl"),
        &out_dir.join("stimulus_volume_projection.vert.spv"),
    );
    compile_shader(
        &glslc,
        "fragment",
        Path::new("shaders/stimulus_volume_projection.frag.glsl"),
        &out_dir.join("stimulus_volume_projection.frag.spv"),
    );
    if let Some(payload) = private_kuramoto_payload.as_ref() {
        println!("cargo:rerun-if-changed={}", payload.shader.display());
        compile_shader(
            &glslc,
            "compute",
            &payload.shader,
            &out_dir.join("private_kuramoto_particles.comp.spv"),
        );
    } else {
        compile_shader(
            &glslc,
            "compute",
            Path::new("shaders/private_kuramoto_particles_placeholder.comp.glsl"),
            &out_dir.join("private_kuramoto_particles.comp.spv"),
        );
    }
    if let Some(payload) = private_particle_payload.as_ref() {
        println!("cargo:rerun-if-changed={}", payload.shader.display());
        compile_shader(
            &glslc,
            "compute",
            &payload.shader,
            &out_dir.join("private_particles.comp.spv"),
        );
    } else {
        compile_shader(
            &glslc,
            "compute",
            Path::new("shaders/private_particles_placeholder.comp.glsl"),
            &out_dir.join("private_particles.comp.spv"),
        );
    }
    compile_private_layer_payload(&glslc, private_layer_sources.as_ref(), &out_dir);
}

struct PrivateLayerShaderSources {
    guide: PathBuf,
    projection: PathBuf,
}

struct PrivateKuramotoPayloadSources {
    data_dir: PathBuf,
    shader: PathBuf,
}

struct PrivateParticlePayloadSources {
    data_dir: PathBuf,
    shader: PathBuf,
    kind: String,
    marker_prefix: String,
    marker_fields: String,
    particle_count: usize,
    aux0_rows: usize,
    mask_texture: Option<PrivateParticleMaskTextureSource>,
}

struct PrivateParticleMaskTextureSource {
    path: PathBuf,
    width: usize,
    height: usize,
    layers: usize,
    bytes: usize,
    mode_code: u32,
    mode_marker: &'static str,
}

const PRIVATE_LAYER_GUIDE_OUTPUTS: [(&str, &str); 6] = [
    ("0", "private_layer_guide_analysis0.frag.spv"),
    ("1", "private_layer_guide_scratch_horizontal.frag.spv"),
    ("2", "private_layer_guide_analysis1.frag.spv"),
    ("3", "private_layer_guide_control0.frag.spv"),
    ("4", "private_layer_guide_scratch_strength.frag.spv"),
    ("5", "private_layer_guide_control1.frag.spv"),
];

fn write_recorded_hand_replay_source(out_dir: &Path) {
    let output = out_dir.join("recorded_hand_replay_source.json");
    if let Ok(capture_dir) = env::var("RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR") {
        let capture_dir = PathBuf::from(capture_dir);
        if capture_dir.is_dir() {
            let frame_limit = env::var("RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT")
                .ok()
                .and_then(|value| value.parse::<usize>().ok())
                .unwrap_or(12)
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

fn write_private_layer_payload_config(out_dir: &Path, payload_linked: bool) {
    let output = out_dir.join("private_layer_payload_config.rs");
    let implementation_path = if payload_linked {
        "external-private-shader-dir"
    } else {
        "none"
    };
    let source = format!(
        "pub(crate) const PRIVATE_LAYER_PAYLOAD_LINKED: bool = {payload_linked};\npub(crate) const PRIVATE_LAYER_IMPLEMENTATION_PATH: &str = \"{implementation_path}\";\n"
    );
    fs::write(&output, source).unwrap_or_else(|error| {
        panic!(
            "failed to write generated private layer payload config {}: {error}",
            output.display()
        )
    });
}

fn write_private_kuramoto_payload_config(
    out_dir: &Path,
    payload: Option<&PrivateKuramotoPayloadSources>,
) {
    let output = out_dir.join("private_kuramoto_payload_config.rs");
    let payload_linked = payload.is_some();
    let (data_path, shader_path) = payload
        .map(|payload| {
            (
                payload.data_dir.display().to_string(),
                payload.shader.display().to_string(),
            )
        })
        .unwrap_or_else(|| ("none".to_string(), "none".to_string()));
    let source = format!(
        "pub(crate) const PRIVATE_KURAMOTO_PAYLOAD_LINKED: bool = {payload_linked};\npub(crate) const PRIVATE_KURAMOTO_IMPLEMENTATION_PATH: &str = \"{}\";\npub(crate) const PRIVATE_KURAMOTO_DATA_PATH: &str = \"{}\";\npub(crate) const PRIVATE_KURAMOTO_SAMPLE_COUNT: usize = 1024;\n",
        rust_string_literal(&shader_path),
        rust_string_literal(&data_path),
    );
    fs::write(&output, source).unwrap_or_else(|error| {
        panic!(
            "failed to write generated private Kuramoto payload config {}: {error}",
            output.display()
        )
    });
}

fn private_layer_shader_sources() -> Option<PrivateLayerShaderSources> {
    let explicit_guide = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_GUIDE_SHADER")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_file());
    let explicit_projection =
        env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_PROJECTION_SHADER")
            .ok()
            .map(PathBuf::from)
            .filter(|path| path.is_file());
    if let (Some(guide), Some(projection)) = (explicit_guide, explicit_projection) {
        return Some(PrivateLayerShaderSources { guide, projection });
    }

    let shader_dir = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_LAYER_SHADER_DIR")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_dir())?;
    let guide = shader_dir.join("private_layer_guide_pass.frag.glsl");
    let projection = shader_dir.join("private_layer_projection.frag.glsl");
    if guide.is_file() && projection.is_file() {
        Some(PrivateLayerShaderSources { guide, projection })
    } else {
        None
    }
}

fn private_kuramoto_payload_sources() -> Option<PrivateKuramotoPayloadSources> {
    let data_dir = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_DATA_DIR")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_dir())?;
    let explicit_shader = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_SHADER")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_file());
    let shader = explicit_shader.or_else(|| {
        env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_KURAMOTO_SHADER_DIR")
            .ok()
            .map(PathBuf::from)
            .map(|path| path.join("kuramoto_particles.comp.glsl"))
            .filter(|path| path.is_file())
    })?;
    Some(PrivateKuramotoPayloadSources { data_dir, shader })
}

fn private_particle_payload_sources() -> Option<PrivateParticlePayloadSources> {
    let data_dir = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_DATA_DIR")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_dir())?;
    let explicit_shader = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_file());
    let shader = explicit_shader.or_else(|| {
        env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_SHADER_DIR")
            .ok()
            .map(PathBuf::from)
            .map(|path| path.join("private_particles.comp.glsl"))
            .filter(|path| path.is_file())
    })?;
    let positions = data_dir.join("private_particle_positions.f32.bin");
    let normals = data_dir.join("private_particle_normals.f32.bin");
    if !positions.is_file() || !normals.is_file() {
        return None;
    }
    let position_bytes = file_len(&positions)?;
    let normal_bytes = file_len(&normals)?;
    if position_bytes == 0 || position_bytes != normal_bytes || position_bytes % 16 != 0 {
        panic!(
            "generic private particle payload requires matching non-empty vec4<f32> position/normal files, got positions={} bytes normals={} bytes",
            position_bytes, normal_bytes
        );
    }
    let particle_count = (position_bytes / 16) as usize;
    let aux0 = data_dir.join("private_particle_aux0.u32.bin");
    let aux0_rows = if aux0.is_file() {
        let aux0_bytes = file_len(&aux0)?;
        if aux0_bytes == 0 || aux0_bytes % 16 != 0 {
            panic!(
                "generic private particle aux0 payload must be a non-empty uvec4<u32> file, got {} bytes",
                aux0_bytes
            );
        }
        (aux0_bytes / 16) as usize
    } else {
        particle_count * 2
    };
    let kind = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_KIND")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "generic-private-particles".to_string());
    let marker_prefix = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_PREFIX")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLES".to_string());
    let marker_fields = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MARKER_FIELDS")
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| "privateParticleMarkerFields=generic".to_string());
    let mask_texture = private_particle_mask_texture_source(&data_dir);
    Some(PrivateParticlePayloadSources {
        data_dir,
        shader,
        kind,
        marker_prefix,
        marker_fields,
        particle_count,
        aux0_rows,
        mask_texture,
    })
}

fn private_particle_mask_texture_source(
    data_dir: &Path,
) -> Option<PrivateParticleMaskTextureSource> {
    let explicit = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_R8")
        .ok()
        .map(PathBuf::from)
        .filter(|path| path.is_file());
    let path = explicit.or_else(|| {
        let default_path = data_dir.join("private_particle_mask_texture.r8.bin");
        default_path.is_file().then_some(default_path)
    })?;
    let bytes = file_len(&path)? as usize;
    if bytes == 0 {
        panic!(
            "generic private particle mask texture must be a non-empty R8 file: {}",
            path.display()
        );
    }
    let width =
        required_env_usize("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH");
    let height =
        required_env_usize("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT");
    let layers =
        required_env_usize("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS");
    let expected_bytes = width
        .checked_mul(height)
        .and_then(|value| value.checked_mul(layers))
        .expect("generic private particle mask texture dimensions overflow usize");
    if expected_bytes != bytes {
        panic!(
            "generic private particle mask texture byte count mismatch for {}: got {}, expected {} from {}x{}x{} R8",
            path.display(),
            bytes,
            expected_bytes,
            width,
            height,
            layers
        );
    }
    let (mode_code, mode_marker) = private_particle_mask_texture_mode();
    println!("cargo:rerun-if-changed={}", path.display());
    Some(PrivateParticleMaskTextureSource {
        path,
        width,
        height,
        layers,
        bytes,
        mode_code,
        mode_marker,
    })
}

fn required_env_usize(name: &str) -> usize {
    let raw = env::var(name).unwrap_or_else(|_| {
        panic!("{name} is required when a generic private particle mask texture is provided")
    });
    let value = raw
        .parse::<usize>()
        .unwrap_or_else(|error| panic!("{name} must be a positive integer, got {raw:?}: {error}"));
    if value == 0 {
        panic!("{name} must be positive");
    }
    value
}

fn private_particle_mask_texture_mode() -> (u32, &'static str) {
    let raw = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_MASK_TEXTURE_MODE")
        .unwrap_or_else(|_| "texture-array-blend".to_string());
    match raw.trim().to_ascii_lowercase().as_str() {
        "procedural" | "procedural-fallback" | "debug-procedural" => (0, "procedural-fallback"),
        "texture-array" | "texture-array-nearest" | "nearest" => (1, "texture-array-nearest"),
        "texture-array-blend" | "blend" | "two-layer-blend" => (2, "texture-array-blend"),
        other => panic!("unsupported generic private particle mask texture mode: {other}"),
    }
}

fn optional_env_f32(name: &str, default: f32, min: f32, max: f32) -> f32 {
    let Some(raw) = env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
    else {
        return default;
    };
    let value = raw
        .parse::<f32>()
        .unwrap_or_else(|error| panic!("{name} must be a finite float, got {raw:?}: {error}"));
    if !value.is_finite() || value < min || value > max {
        panic!("{name} must be finite and in range [{min}, {max}], got {value}");
    }
    value
}

fn optional_env_usize(name: &str, default: usize, min: usize, max: usize) -> usize {
    let Some(raw) = env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
    else {
        return default;
    };
    let value = raw
        .parse::<usize>()
        .unwrap_or_else(|error| panic!("{name} must be an integer, got {raw:?}: {error}"));
    if value < min || value > max {
        panic!("{name} must be in range [{min}, {max}], got {value}");
    }
    value
}

fn private_particle_transparency_blend_mode() -> &'static str {
    let raw = env::var("RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE")
        .unwrap_or_else(|_| "src-one-one-minus-src-alpha".to_string());
    match raw.trim().to_ascii_lowercase().as_str() {
        "alpha-over" | "premultiplied-alpha-over" | "src-one-one-minus-src-alpha" => {
            "src-one-one-minus-src-alpha"
        }
        "additive"
        | "unity-additive"
        | "shuriken-additive"
        | "src-alpha-one"
        | "src-alpha-one-additive" => "src-alpha-one-additive",
        other => panic!("unsupported generic private particle transparency blend mode: {other}"),
    }
}

fn private_particle_transparency_config() -> (f32, f32, f32, f32, &'static str, &'static str) {
    let blend_mode_name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE";
    let opacity_name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_OPACITY";
    let alpha_scale_name =
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE";
    let depth_name =
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH";
    let coupling_name =
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING";
    let source = if [
        blend_mode_name,
        opacity_name,
        alpha_scale_name,
        depth_name,
        coupling_name,
    ]
    .iter()
    .any(|name| {
        env::var(name)
            .ok()
            .is_some_and(|value| !value.trim().is_empty())
    }) {
        "particle-payload-build-env"
    } else {
        "default-generated-config"
    };
    (
        optional_env_f32(opacity_name, 1.0, 0.0, 4.0),
        optional_env_f32(alpha_scale_name, 1.0, 0.0, 4.0),
        optional_env_f32(depth_name, 0.0, 0.0, 8.0),
        optional_env_f32(coupling_name, 1.0, 0.0, 1.0),
        private_particle_transparency_blend_mode(),
        source,
    )
}

fn private_particle_ordering_config() -> (u32, &'static str, &'static str) {
    let name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_ORDERING_MODE";
    let source = if env::var(name)
        .ok()
        .is_some_and(|value| !value.trim().is_empty())
    {
        "particle-payload-build-env"
    } else {
        "default-generated-config"
    };
    let raw = env::var(name).unwrap_or_else(|_| "back-to-front".to_string());
    match raw.trim().to_ascii_lowercase().as_str() {
        "back-to-front" | "depth-sort" | "gpu-depth-sort" => (0, "back-to-front", source),
        "source-order" | "legacy-source-order" | "unsorted" | "no-depth-sort" => {
            (1, "source-order", source)
        }
        other => panic!("unsupported generic private particle ordering mode: {other}"),
    }
}

fn private_particle_color_config() -> (f32, &'static str) {
    let name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH";
    let source = if env::var(name)
        .ok()
        .is_some_and(|value| !value.trim().is_empty())
    {
        "particle-payload-build-env"
    } else {
        "default-generated-config"
    };
    (optional_env_f32(name, 0.0, 0.0, 1.0), source)
}

fn private_particle_visual_config() -> (f32, &'static str) {
    let visual_scale_name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_VISUAL_SCALE";
    let source = if env::var(visual_scale_name)
        .ok()
        .is_some_and(|value| !value.trim().is_empty())
    {
        "particle-payload-build-env"
    } else {
        "default-generated-config"
    };
    (optional_env_f32(visual_scale_name, 1.0, 0.05, 1.0), source)
}

fn private_particle_tracer_config(particle_count: usize) -> (usize, usize, f32, f32, &'static str) {
    let max_count_name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_MAX_COUNT";
    let draw_slots_name =
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR";
    let lifetime_name = "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS";
    let copies_per_second_name =
        "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND";
    let source = if [
        max_count_name,
        draw_slots_name,
        lifetime_name,
        copies_per_second_name,
    ]
    .iter()
    .any(|name| {
        env::var(name)
            .ok()
            .is_some_and(|value| !value.trim().is_empty())
    }) {
        "particle-payload-build-env"
    } else {
        "default-generated-config"
    };
    let max_count = optional_env_usize(max_count_name, 0, 0, 1_000_000);
    let state_slots_per_oscillator = if particle_count == 0 {
        0
    } else {
        max_count / particle_count
    };
    (
        max_count,
        optional_env_usize(
            draw_slots_name,
            state_slots_per_oscillator,
            0,
            state_slots_per_oscillator.max(1024),
        )
        .min(state_slots_per_oscillator),
        optional_env_f32(lifetime_name, 1.25, 0.001, 60.0),
        optional_env_f32(copies_per_second_name, 14.0, 0.0, 240.0),
        source,
    )
}

fn write_private_particle_payload_config(
    out_dir: &Path,
    payload: Option<&PrivateParticlePayloadSources>,
) {
    let output = out_dir.join("private_particle_payload_config.rs");
    let payload_linked = payload.is_some();
    let (
        transparency_opacity,
        transparency_output_alpha_scale,
        transparency_depth_suppression_strength,
        transparency_rgb_alpha_coupling,
        transparency_blend_mode,
        transparency_parameter_source,
    ) = private_particle_transparency_config();
    let (ordering_mode_code, ordering_mode, ordering_parameter_source) =
        private_particle_ordering_config();
    let (color_facing_attenuation_strength, color_parameter_source) =
        private_particle_color_config();
    let (
        data_path,
        shader_path,
        kind,
        marker_prefix,
        marker_fields,
        particle_count,
        aux0_rows,
        mask_linked,
        mask_path,
        mask_width,
        mask_height,
        mask_layers,
        mask_bytes,
        mask_mode_code,
        mask_mode_marker,
    ) = payload
        .map(|payload| {
            let (
                mask_linked,
                mask_path,
                mask_width,
                mask_height,
                mask_layers,
                mask_bytes,
                mask_mode_code,
                mask_mode_marker,
            ) = payload
                .mask_texture
                .as_ref()
                .map(|mask| {
                    (
                        true,
                        mask.path.display().to_string(),
                        mask.width,
                        mask.height,
                        mask.layers,
                        mask.bytes,
                        mask.mode_code,
                        mask.mode_marker,
                    )
                })
                .unwrap_or((
                    false,
                    "none".to_string(),
                    1,
                    1,
                    1,
                    1,
                    0,
                    "procedural-fallback",
                ));
            (
                payload.data_dir.display().to_string(),
                payload.shader.display().to_string(),
                payload.kind.clone(),
                payload.marker_prefix.clone(),
                payload.marker_fields.clone(),
                payload.particle_count,
                payload.aux0_rows,
                mask_linked,
                mask_path,
                mask_width,
                mask_height,
                mask_layers,
                mask_bytes,
                mask_mode_code,
                mask_mode_marker,
            )
        })
        .unwrap_or_else(|| {
            (
                "none".to_string(),
                "none".to_string(),
                "none".to_string(),
                "RUSTY_QUEST_NATIVE_RENDERER_PRIVATE_PARTICLES".to_string(),
                "privateParticleMarkerFields=none".to_string(),
                1,
                2,
                false,
                "none".to_string(),
                1,
                1,
                1,
                1,
                0,
                "procedural-fallback",
            )
        });
    let (particle_visual_scale, particle_visual_parameter_source) =
        private_particle_visual_config();
    let (
        tracer_max_count,
        tracer_draw_slots_per_oscillator,
        tracer_lifetime_seconds,
        tracer_copies_per_second,
        tracer_parameter_source,
    ) = private_particle_tracer_config(particle_count);
    let source = format!(
        "pub(crate) const PRIVATE_PARTICLE_PAYLOAD_LINKED: bool = {payload_linked};\npub(crate) const PRIVATE_PARTICLE_IMPLEMENTATION_PATH: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_DATA_PATH: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_KIND: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_MARKER_PREFIX: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_MARKER_FIELDS: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_COUNT: usize = {particle_count};\npub(crate) const PRIVATE_PARTICLE_VISUAL_SCALE: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_VISUAL_PARAMETER_SOURCE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_TRACER_MAX_COUNT: usize = {tracer_max_count};\npub(crate) const PRIVATE_PARTICLE_TRACER_DRAW_SLOTS_PER_OSCILLATOR: usize = {tracer_draw_slots_per_oscillator};\npub(crate) const PRIVATE_PARTICLE_TRACER_LIFETIME_SECONDS: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRACER_COPIES_PER_SECOND: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRACER_PARAMETER_SOURCE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_AUX0_VEC4_ROWS: usize = {aux0_rows};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_LINKED: bool = {mask_linked};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_PATH: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_WIDTH: u32 = {mask_width};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_HEIGHT: u32 = {mask_height};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_LAYERS: u32 = {mask_layers};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_BYTES: usize = {mask_bytes};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_MODE_CODE: u32 = {mask_mode_code};\npub(crate) const PRIVATE_PARTICLE_MASK_TEXTURE_MODE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_OPACITY: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_OUTPUT_ALPHA_SCALE: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_DEPTH_SUPPRESSION_STRENGTH: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_RGB_ALPHA_COUPLING: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_BLEND_MODE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_TRANSPARENCY_PARAMETER_SOURCE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_ORDERING_MODE_CODE: u32 = {ordering_mode_code};\npub(crate) const PRIVATE_PARTICLE_ORDERING_MODE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_ORDERING_PARAMETER_SOURCE: &str = \"{}\";\npub(crate) const PRIVATE_PARTICLE_COLOR_FACING_ATTENUATION_STRENGTH: f32 = {:.8};\npub(crate) const PRIVATE_PARTICLE_COLOR_PARAMETER_SOURCE: &str = \"{}\";\n",
        rust_string_literal(&shader_path),
        rust_string_literal(&data_path),
        rust_string_literal(&kind),
        rust_string_literal(&marker_prefix),
        rust_string_literal(&marker_fields),
        particle_visual_scale,
        rust_string_literal(particle_visual_parameter_source),
        tracer_lifetime_seconds,
        tracer_copies_per_second,
        rust_string_literal(tracer_parameter_source),
        rust_string_literal(&mask_path),
        rust_string_literal(mask_mode_marker),
        transparency_opacity,
        transparency_output_alpha_scale,
        transparency_depth_suppression_strength,
        transparency_rgb_alpha_coupling,
        rust_string_literal(transparency_blend_mode),
        rust_string_literal(transparency_parameter_source),
        rust_string_literal(ordering_mode),
        rust_string_literal(ordering_parameter_source),
        color_facing_attenuation_strength,
        rust_string_literal(color_parameter_source),
    );
    fs::write(&output, source).unwrap_or_else(|error| {
        panic!(
            "failed to write generated private particle payload config {}: {error}",
            output.display()
        )
    });
}

fn write_private_particle_payload_files(
    out_dir: &Path,
    payload: Option<&PrivateParticlePayloadSources>,
) {
    let files = [
        (
            "private_particle_positions.f32.bin",
            "private_particle_positions.f32.bin",
        ),
        (
            "private_particle_normals.f32.bin",
            "private_particle_normals.f32.bin",
        ),
    ];

    for (source_name, output_name) in files {
        let output = out_dir.join(output_name);
        if let Some(payload) = payload {
            let source = payload.data_dir.join(source_name);
            println!("cargo:rerun-if-changed={}", source.display());
            fs::copy(&source, &output).unwrap_or_else(|error| {
                panic!(
                    "failed to copy generic private particle payload {} -> {}: {error}",
                    source.display(),
                    output.display()
                )
            });
            continue;
        }
        fs::write(&output, [0_u8; 16]).unwrap_or_else(|error| {
            panic!(
                "failed to write placeholder generic private particle payload {}: {error}",
                output.display()
            )
        });
    }

    let aux0_output = out_dir.join("private_particle_aux0.u32.bin");
    if let Some(payload) = payload {
        let source = payload.data_dir.join("private_particle_aux0.u32.bin");
        if source.is_file() {
            println!("cargo:rerun-if-changed={}", source.display());
            fs::copy(&source, &aux0_output).unwrap_or_else(|error| {
                panic!(
                    "failed to copy generic private particle aux0 payload {} -> {}: {error}",
                    source.display(),
                    aux0_output.display()
                )
            });
        } else {
            let zero_bytes = payload.aux0_rows * 16;
            fs::write(&aux0_output, vec![0_u8; zero_bytes]).unwrap_or_else(|error| {
                panic!(
                    "failed to write zero generic private particle aux0 payload {}: {error}",
                    aux0_output.display()
                )
            });
        }
    } else {
        fs::write(&aux0_output, vec![0_u8; 32]).unwrap_or_else(|error| {
            panic!(
                "failed to write placeholder generic private particle aux0 payload {}: {error}",
                aux0_output.display()
            )
        });
    }

    let mask_output = out_dir.join("private_particle_mask_texture.r8.bin");
    if let Some(payload) = payload.and_then(|payload| payload.mask_texture.as_ref()) {
        println!("cargo:rerun-if-changed={}", payload.path.display());
        fs::copy(&payload.path, &mask_output).unwrap_or_else(|error| {
            panic!(
                "failed to copy generic private particle mask texture {} -> {}: {error}",
                payload.path.display(),
                mask_output.display()
            )
        });
    } else {
        fs::write(&mask_output, [255_u8]).unwrap_or_else(|error| {
            panic!(
                "failed to write placeholder generic private particle mask texture {}: {error}",
                mask_output.display()
            )
        });
    }
}

fn write_private_kuramoto_payload_files(
    out_dir: &Path,
    payload: Option<&PrivateKuramotoPayloadSources>,
) {
    let files = [
        (
            "recorded-meta-quest-hand-samples-1024-coordinate-triangles.u32.bin",
            "private_kuramoto_left_coordinate_triangles.u32.bin",
        ),
        (
            "recorded-meta-quest-hand-samples-1024-coordinate-barycentric.f32.bin",
            "private_kuramoto_left_coordinate_barycentric.f32.bin",
        ),
        (
            "recorded-meta-quest-hand-samples-1024-surface-distance-edges.u32.bin",
            "private_kuramoto_left_surface_distance_edges.u32.bin",
        ),
        (
            "recorded-meta-quest-hand-samples-1024-surface-distance-meters.f32.bin",
            "private_kuramoto_left_surface_distance_meters.f32.bin",
        ),
        (
            "recorded-meta-quest-hand-samples-1024-small-world-edges.u32.bin",
            "private_kuramoto_left_small_world_edges.u32.bin",
        ),
        (
            "recorded-meta-quest-right-hand-samples-1024-coordinate-triangles.u32.bin",
            "private_kuramoto_right_coordinate_triangles.u32.bin",
        ),
        (
            "recorded-meta-quest-right-hand-samples-1024-coordinate-barycentric.f32.bin",
            "private_kuramoto_right_coordinate_barycentric.f32.bin",
        ),
        (
            "recorded-meta-quest-right-hand-samples-1024-surface-distance-edges.u32.bin",
            "private_kuramoto_right_surface_distance_edges.u32.bin",
        ),
        (
            "recorded-meta-quest-right-hand-samples-1024-surface-distance-meters.f32.bin",
            "private_kuramoto_right_surface_distance_meters.f32.bin",
        ),
        (
            "recorded-meta-quest-right-hand-samples-1024-small-world-edges.u32.bin",
            "private_kuramoto_right_small_world_edges.u32.bin",
        ),
    ];

    for (source_name, output_name) in files {
        let output = out_dir.join(output_name);
        if let Some(payload) = payload {
            let source = payload.data_dir.join(source_name);
            println!("cargo:rerun-if-changed={}", source.display());
            if source.is_file() {
                fs::copy(&source, &output).unwrap_or_else(|error| {
                    panic!(
                        "failed to copy private Kuramoto payload {} -> {}: {error}",
                        source.display(),
                        output.display()
                    )
                });
                continue;
            }
        }
        fs::write(&output, [0_u8; 4]).unwrap_or_else(|error| {
            panic!(
                "failed to write placeholder private Kuramoto payload {}: {error}",
                output.display()
            )
        });
    }
}

fn file_len(path: &Path) -> Option<u64> {
    fs::metadata(path).ok().map(|metadata| metadata.len())
}

fn compile_private_layer_payload(
    glslc: &Path,
    sources: Option<&PrivateLayerShaderSources>,
    out_dir: &Path,
) {
    if let Some(sources) = sources {
        println!("cargo:rerun-if-changed={}", sources.guide.display());
        println!("cargo:rerun-if-changed={}", sources.projection.display());
        for (mode, output_name) in PRIVATE_LAYER_GUIDE_OUTPUTS {
            compile_shader_with_defines(
                glslc,
                "fragment",
                &sources.guide,
                &out_dir.join(output_name),
                &[("PRIVATE_LAYER_GUIDE_PASS_MODE", mode)],
            );
        }
        compile_shader(
            glslc,
            "fragment",
            &sources.projection,
            &out_dir.join("private_layer_projection.frag.spv"),
        );
        return;
    }

    let placeholder = Path::new("shaders/private_layer_placeholder.frag.glsl");
    for (_, output_name) in PRIVATE_LAYER_GUIDE_OUTPUTS {
        compile_shader(glslc, "fragment", placeholder, &out_dir.join(output_name));
    }
    compile_shader(
        glslc,
        "fragment",
        placeholder,
        &out_dir.join("private_layer_projection.frag.spv"),
    );
}

fn generate_recorded_hand_capture_source(capture_dir: &Path, frame_limit: usize) -> String {
    let manifest_json = read_text(&capture_dir.join("capture.manifest.json"));
    let left_rig_json = read_text(&capture_dir.join("left.rig.json"));
    let right_rig_json = read_text(&capture_dir.join("right.rig.json"));
    let left_clip_lines = read_first_lines(&capture_dir.join("left.clip.jsonl"), frame_limit);
    let right_clip_lines = read_first_lines(&capture_dir.join("right.clip.jsonl"), frame_limit);
    let left_validation_mesh_lines =
        read_first_lines(&capture_dir.join("left.validation_mesh.jsonl"), frame_limit);
    let right_validation_mesh_lines = read_first_lines(
        &capture_dir.join("right.validation_mesh.jsonl"),
        frame_limit,
    );
    let left_clip_frame_count = line_count(&capture_dir.join("left.clip.jsonl"));
    let right_clip_frame_count = line_count(&capture_dir.join("right.clip.jsonl"));
    let left_validation_frame_count = line_count(&capture_dir.join("left.validation_mesh.jsonl"));
    let right_validation_frame_count = line_count(&capture_dir.join("right.validation_mesh.jsonl"));

    format!(
        "{{\"schema\":\"rusty.quest.native_renderer.recorded_hand_replay_source.v1\",\"source_id\":\"local-recorded-meta-quest-hand-capture\",\"source_kind\":\"external-recorded-capture-build-env\",\"recorded_input_equivalent\":true,\"validation_input_shape\":\"bind-mesh-plus-compact-joint-frame\",\"capture_manifest_json\":\"{}\",\"hands\":[{},{}]}}",
        json_escape(&manifest_json),
        generated_hand_json(
            "left",
            &left_rig_json,
            &left_clip_lines,
            &left_validation_mesh_lines,
            left_clip_frame_count,
            left_validation_frame_count
        ),
        generated_hand_json(
            "right",
            &right_rig_json,
            &right_clip_lines,
            &right_validation_mesh_lines,
            right_clip_frame_count,
            right_validation_frame_count
        ),
    )
}

fn generated_hand_json(
    handedness: &str,
    rig_json: &str,
    clip_lines: &[String],
    validation_mesh_lines: &[String],
    clip_frame_count: usize,
    validation_frame_count: usize,
) -> String {
    let clip_json = clip_lines
        .iter()
        .map(|line| format!("\"{}\"", json_escape(line)))
        .collect::<Vec<_>>()
        .join(",");
    let validation_mesh_json = validation_mesh_lines
        .iter()
        .map(|line| format!("\"{}\"", json_escape(line)))
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"handedness\":\"{}\",\"clip_frame_count\":{},\"validation_frame_count\":{},\"rig_json\":\"{}\",\"clip_jsonl\":[{}],\"validation_mesh_jsonl\":[{}]}}",
        handedness,
        clip_frame_count,
        validation_frame_count,
        json_escape(rig_json),
        clip_json,
        validation_mesh_json
    )
}

fn read_text(path: &Path) -> String {
    fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()))
}

fn read_first_lines(path: &Path, limit: usize) -> Vec<String> {
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

fn rust_string_literal(value: &str) -> String {
    value.replace('\\', "\\\\").replace('"', "\\\"")
}

fn find_glslc() -> Option<PathBuf> {
    if let Ok(path) = env::var("GLSLC") {
        let path = PathBuf::from(path);
        if path.is_file() {
            return Some(path);
        }
    }

    for file_name in ["glslc.exe", "glslc"] {
        if let Some(path) = find_on_path(file_name) {
            return Some(path);
        }
    }

    for env_name in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Ok(root) = env::var(env_name) {
            let candidate = PathBuf::from(root).join("shader-tools/windows-x86_64/glslc.exe");
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

fn find_on_path(file_name: &str) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    for entry in env::split_paths(&path) {
        let candidate = entry.join(file_name);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn compile_shader(glslc: &Path, stage: &str, source: &Path, output: &Path) {
    compile_shader_with_defines(glslc, stage, source, output, &[]);
}

fn compile_shader_with_defines(
    glslc: &Path,
    stage: &str,
    source: &Path,
    output: &Path,
    defines: &[(&str, &str)],
) {
    let status = Command::new(glslc)
        .arg("--target-env=vulkan1.1")
        .arg(format!("-fshader-stage={stage}"))
        .args(
            defines
                .iter()
                .map(|(name, value)| format!("-D{name}={value}")),
        )
        .arg(source)
        .arg("-o")
        .arg(output)
        .status()
        .unwrap_or_else(|error| panic!("failed to run glslc at {}: {error}", glslc.display()));

    if !status.success() {
        panic!(
            "glslc failed for {} with status {}",
            source.display(),
            status
        );
    }
}
