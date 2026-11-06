#!/usr/bin/env python3
"""Compile the real Android locator/JNI bodies on a Unix host with an inert SDK.

Only cfg selection and the platform logger are adapted. The constructor, Drop,
owner mutex, JNI entry points and presentation lookup are production source.
This is a source/API ownership control, not a headset/OpenXR qualification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline", action="store_true")
    parser.add_argument("--android-check", action="store_true",
                        help="typecheck unchanged source for aarch64-linux-android instead of host tests")
    parser.add_argument("--dependency-source-root", type=Path,
                        help="optional read-only Cargo registry source directory for ash/OpenXR")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / "apps/spatial-camera-panel-android/native-receipt/src/camera_latency_diagnostics.rs"
    fixture = root / "tools/checks/fixtures/openxr_locator_lifecycle.rs"
    output = args.output.resolve()
    if output.exists() or not output.is_relative_to(root / "target"):
        raise ValueError("a fresh repository-local target directory is required")
    output.mkdir(parents=True)
    raw = source.read_bytes()
    text = raw.decode().replace('#[cfg(not(target_os = "android"))]', '#[cfg(any())]')
    text = text.replace('#[cfg(target_os = "android")]', '')
    prefix = "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_native"
    signature = text.split(prefix + "ConfigureCameraLatencyOpenXrHandles(", 1)[1].split(") ->", 1)[0]
    owner_arg = "owner, " if "owner_id:" in signature else ""
    adapters = f'''\n#[cfg(test)] fn test_configure(owner: i64, instance: i64, session: i64) -> i64 {{
        {prefix}ConfigureCameraLatencyOpenXrHandles(std::ptr::null_mut(), std::ptr::null_mut(), {owner_arg}instance, session, ownership_controls::fake_get_proc as *const () as usize as i64)
    }}\n'''
    if prefix + "ReleaseCameraLatencyOpenXrHandles(" in text:
        adapters += f'''#[cfg(test)] fn test_release(owner: i64, retiring: bool) -> i64 {{
            {prefix}ReleaseCameraLatencyOpenXrHandles(std::ptr::null_mut(), std::ptr::null_mut(), owner, retiring as u8)
        }}\n'''
    else:
        adapters += "#[cfg(test)] fn test_release(_owner: i64, _retiring: bool) -> i64 { 0 }\n"
    generated = raw if args.android_check else (text + adapters + fixture.read_text()).encode()
    (output / "diagnostics.rs").write_bytes(generated)
    (output / "lib.rs").write_text('''#![allow(dead_code)]
fn bool_token(v: bool) -> &'static str { if v { "true" } else { "false" } }
mod camera_hwb_marker { pub fn log_camera_hwb_marker(_: impl AsRef<str>) {} }
#[path="diagnostics.rs"] mod diagnostics;
''')
    # Use the caller's ordinary Cargo home. No repository dependency graph is
    # synthesized: this isolated harness compiles one actual source owner.
    manifest = '''[package]
name = "quest-openxr-locator-source-control"
version = "0.0.0"
edition = "2021"
[lib]
path = "lib.rs"
[workspace]
[dependencies]
ash = { version = "=0.38.0", default-features = false, features = ["std", "debug"] }
openxr-sys = "=0.13.1"
libc = "=0.2.189"
jni = { version = "=0.21.1", default-features = false }
'''
    if args.dependency_source_root:
        manifest += "\n[patch.crates-io]\n"
        for name, pattern in [("ash", "ash-0.38.*"), ("openxr-sys", "openxr-sys-0.13.1")]:
            matches = list(args.dependency_source_root.glob(pattern))
            if len(matches) != 1:
                raise ValueError(f"expected one {pattern} source directory")
            manifest += f'{name} = {{ path = {json.dumps(str(matches[0]))} }}\n'
    (output / "Cargo.toml").write_text(manifest)
    command = ["cargo", "check" if args.android_check else "test", "--offline",
               "--manifest-path", str(output / "Cargo.toml")]
    if args.android_check:
        command += ["--target", "aarch64-linux-android"]
    elif args.baseline:
        command += ["retirement_precedes_sdk_invalidation"]
    if not args.android_check:
        command += ["--", "--test-threads=1"]
    result = subprocess.run(command, capture_output=True, text=True)
    (output / "stdout.txt").write_text(result.stdout)
    (output / "stderr.txt").write_text(result.stderr)
    if source.read_bytes() != raw:
        raise ValueError("source changed during the control")
    report = {"source": str(source), "source_sha256": sha(raw),
              "fixture_sha256": sha(fixture.read_bytes()), "runner_sha256": sha(Path(__file__).read_bytes()),
              "generated_sha256": sha(generated), "command": command, "exit_code": result.returncode,
              "rustc": subprocess.check_output(["rustc", "-Vv"], text=True),
              "cargo": subprocess.check_output(["cargo", "-V"], text=True).strip(),
              "manifest_sha256": sha((output / "Cargo.toml").read_bytes()),
              "lock_sha256": sha((output / "Cargo.lock").read_bytes()),
              "scope": ("unchanged diagnostic owner source; actual aarch64-linux-android API typecheck"
                        if args.android_check else
                        "actual Android locator/JNI/lookup source; modeled OpenXR SDK dispatch; Unix host")}
    (output / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(result.stdout)
    print(result.stderr)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
