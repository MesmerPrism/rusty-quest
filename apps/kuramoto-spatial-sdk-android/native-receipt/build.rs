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
    ];
    let glslc = find_glslc();
    for (source, output_name, stage) in shaders {
        println!("cargo:rerun-if-changed={source}");
        let output = out_dir.join(output_name);
        let status = Command::new(&glslc)
            .arg("-O")
            .arg(format!("-fshader-stage={stage}"))
            .arg("-o")
            .arg(&output)
            .arg(source)
            .status()
            .unwrap_or_else(|error| panic!("failed to run glslc at {}: {error}", glslc.display()));
        if !status.success() {
            panic!(
                "glslc failed for {source} with status {status}; glslc={}",
                glslc.display()
            );
        }
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
