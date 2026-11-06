#!/usr/bin/env python3
"""Run actual Android owner protocol tests with explicit frozen DTO suppliers.

This isolated package manifest loads the changed library source in place and
the selected nested supplier's real DTO dependency graph. It is not a claim
that a flat checkout is an Android application Cargo composition. No APK or
device is accessed; all outputs stay under a fresh target directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    runner_pin = digest(Path(__file__))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--supplier-quest-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--filter", default="owner_dispatch::tests")
    args = parser.parse_args()
    root, supplier, output = args.root.resolve(), args.supplier_quest_root.resolve(), args.output.resolve()
    permitted=args.scratch_root.resolve() if args.scratch_root else root/"target"
    if output==permitted or output.exists() or not output.is_relative_to(permitted):
        raise ValueError("fresh repository-local target required")
    output.mkdir(parents=True)
    source = root / "crates/rusty-quest-media-stream-android/src/lib.rs"
    dependency = supplier / "crates/rusty-quest-media-stream"
    if not (dependency / "Cargo.toml").is_file():
        raise ValueError("explicit real media DTO supplier unavailable")
    manifest = f'''[package]
name = "rusty-quest-media-stream-android"
version = "0.0.0"
edition = "2021"
[workspace]
[lib]
path = {json.dumps(str(source))}
[features]
default = []
test-support = []
[dependencies]
ed25519-dalek = {{ version = "=2.1.1", default-features = false, features = ["std"] }}
rusty-quest-media-stream = {{ path = {json.dumps(str(dependency))} }}
serde = {{ version = "1", features = ["derive"] }}
serde_json = "1"
sha2 = "0.10"
'''
    (output / "Cargo.toml").write_text(manifest)
    prefix = ["cargo", "--offline", "--manifest-path", str(output / "Cargo.toml")]
    host = subprocess.check_output(["rustc", "-Vv"], text=True).split("host: ", 1)[1].splitlines()[0]
    metadata_command = [prefix[0], "metadata", *prefix[1:], "--format-version", "1",
                        "--filter-platform", host]
    metadata = subprocess.run(metadata_command, capture_output=True, text=True)
    (output / "metadata.stderr").write_text(metadata.stderr)
    if metadata.returncode:
        raise RuntimeError(metadata.stderr)
    (output / "metadata.json").write_text(metadata.stdout)
    packages = json.loads(metadata.stdout)["packages"]
    files = set()
    for package in packages:
        if package["source"] is not None:
            continue
        package_manifest = Path(package["manifest_path"])
        files.add(package_manifest)
        directory = source.parent.parent if package["name"] == "rusty-quest-media-stream-android" else package_manifest.parent
        for path in directory.rglob("*"):
            if path.is_file() and "target" not in path.relative_to(directory).parts and (
                    path.suffix in {".rs", ".json", ".toml"} or path.name == "build.rs"):
                files.add(path)
    pins = [{"path": str(path), "bytes": path.stat().st_size, "sha256": digest(path)}
            for path in sorted(files)]
    command = [prefix[0], "test", *prefix[1:], "--locked", args.filter, "--", "--test-threads=1"]
    result = subprocess.run(command, capture_output=True, text=True)
    (output / "stdout.txt").write_text(result.stdout)
    (output / "stderr.txt").write_text(result.stderr)
    for row in pins:
        path = Path(row["path"])
        if digest(path) != row["sha256"]:
            raise ValueError("input changed during protocol control: " + str(path))
    if digest(Path(__file__)) != runner_pin:
        raise ValueError("protocol runner changed during control")
    report = {"command": command, "exit_code": result.returncode,
              "source_pins": pins, "runner_sha256": runner_pin,
              "lock_sha256": digest(output / "Cargo.lock"),
              "rustc": subprocess.check_output(["rustc", "-Vv"], text=True),
              "scope": "actual owner protocol/crypto and real explicitly selected DTO suppliers; isolated host package",
              "limits": "No application graph, Android SDK/JNI, APK, device or publication qualification"}
    (output / "RESULT.json").write_text(json.dumps(report, indent=2) + "\n")
    print(result.stdout)
    if result.returncode:
        print(result.stderr)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
