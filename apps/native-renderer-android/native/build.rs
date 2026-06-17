use std::{
    env, fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::Command,
};

fn main() {
    println!("cargo:rerun-if-changed=shaders/camera_projection.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/camera_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_downsample.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_blur_5tap.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/guide_projection.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_hand_skinning.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_tile_bins.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_field.comp.glsl");
    println!("cargo:rerun-if-changed=shaders/gpu_sdf_overlay.frag.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_mesh_visual.vert.glsl");
    println!("cargo:rerun-if-changed=shaders/hand_mesh_visual.frag.glsl");
    println!(
        "cargo:rerun-if-changed=../../../fixtures/native-renderer/recorded-hand-replay-public-shape.json"
    );
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_CAPTURE_DIR");
    println!("cargo:rerun-if-env-changed=RUSTY_QUEST_NATIVE_RECORDED_HAND_FRAME_LIMIT");

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR is set by Cargo"));
    write_recorded_hand_replay_source(&out_dir);

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
}

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
    let status = Command::new(glslc)
        .arg("--target-env=vulkan1.1")
        .arg(format!("-fshader-stage={stage}"))
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
