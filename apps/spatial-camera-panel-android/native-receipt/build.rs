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
    write_spatial_multistack_build_metadata(
        &out_dir,
        public_guide_blur_shader_byte_count,
        opaque_guide_shader,
        opaque_projection_shader,
        opaque_projection_effect,
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
             pub(crate) const OPAQUE_PROJECTION_EFFECT: [f32; 4] = [{:.8}, {:.8}, {:.8}, {:.8}];\n",
            bool_literal(opaque_guide_shader.compiled),
            opaque_guide_shader.total_byte_count,
            bool_literal(opaque_projection_shader.compiled),
            opaque_projection_shader.byte_count,
            opaque_projection_effect[0],
            opaque_projection_effect[1],
            opaque_projection_effect[2],
            opaque_projection_effect[3],
        ),
    )
    .unwrap_or_else(|error| {
        panic!(
            "failed to write Spatial public multi-stack build metadata {}: {error}",
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
