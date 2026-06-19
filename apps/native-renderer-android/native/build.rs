use std::{
    env, fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::Command,
};

fn main() {
    println!("cargo:rerun-if-changed=shaders/camera_projection.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/camera_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/camera_luma_diagnostic.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_downsample.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_5tap.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_projection.frag.glsl");
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
