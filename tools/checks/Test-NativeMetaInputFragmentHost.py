#!/usr/bin/env python3
"""Exercise the actual native build.rs with a byte-capturing compiler child.

Compiler-input identity only: no GLSL, Android renderer, APK or device proof.
Run with an isolated Python interpreter (python -I -S), Cargo and rustc on PATH.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tomllib
import unittest


PREFIX = "RUSTY_QUEST_NATIVE_RENDERER_"
FRAGMENT = PREFIX + "META_INPUT_FRAGMENT"
SHA = FRAGMENT + "_SHA256"
ORIGINAL = b"#version 450\nlayout(location=0) out vec4 c;\nvoid main(){c=vec4(1);}\n"
CHANGED = b"#version 450\nlayout(location=0) out vec4 c;\nvoid main(){c=vec4(0);}\n"

# This boundary double opens the path supplied by the real producer. Its sole
# output is that exact file's bytes; it performs no shader or SPIR-V validation.
COMPILER_PROBE = r'''
use std::{env, fs, path::PathBuf};
fn main() {
    let values: Vec<_> = env::args_os().skip(1).collect();
    let output_arg = values.iter().position(|v| v == "-o").expect("-o");
    let source = PathBuf::from(&values[output_arg - 1]);
    let output = PathBuf::from(&values[output_arg + 1]);
    if output.file_name().unwrap() == "display_composite_feedback.frag.spv" {
        if let Some(replacement) = env::var_os("FRAGMENT_TEST_REPLACEMENT") {
            // Deterministic edit after producer validation, before compiler read.
            fs::copy(replacement, env::var_os("FRAGMENT_TEST_EXTERNAL").unwrap()).unwrap();
        }
        fs::write(output.with_extension("input-path"), source.as_os_str().to_string_lossy().as_bytes()).unwrap();
    }
    fs::write(output, fs::read(source).expect("compiler source read")).unwrap();
}
'''


def run(command, *, cwd, env=None):
    return subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", check=False)


def checked(command, *, cwd, log):
    result = run(command, cwd=cwd)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}); see {log}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True, help="fresh, retained test output")
    parser.add_argument("--cargo", default="cargo")
    parser.add_argument("--rustc", default="rustc")
    options = parser.parse_args()
    repo = options.repo_root.resolve()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    native = repo / "apps/native-renderer-android/native"
    manifest = tomllib.loads((native / "Cargo.toml").read_text(encoding="utf-8"))
    harness = output / "producer"
    harness.mkdir()
    # Keep the production build dependencies unchanged; no renderer workspace or
    # sibling source graph is resolved. Cargo compiles the actual build.rs file.
    harness_manifest = """[package]
name = "native-meta-input-fragment-host"
version = "0.0.0"
edition = "2021"
[workspace]
[[bin]]
name = "producer"
path = """ + json.dumps(str(native / "build.rs")) + "\n[dependencies]\n"
    for name, value in manifest["build-dependencies"].items():
        if isinstance(value, str):
            harness_manifest += f"{name} = {json.dumps(value)}\n"
        else:
            fields = ", ".join(f"{key} = {json.dumps(item)}" for key, item in value.items())
            harness_manifest += f"{name} = {{ {fields} }}\n"
    (harness / "Cargo.toml").write_text(harness_manifest, encoding="utf-8")
    checked([options.cargo, "build", "--offline", "--manifest-path", str(harness / "Cargo.toml"),
             "--target-dir", str(output / "target"), "--bin", "producer"],
            cwd=repo, log=output / "producer-compile.log")
    checked([options.cargo, "test", "--offline", "--manifest-path", str(harness / "Cargo.toml"),
             "--target-dir", str(output / "target"), "--bin", "producer"],
            cwd=repo, log=output / "producer-existing-tests.log")
    suffix = ".exe" if os.name == "nt" else ""
    producer = output / "target/debug" / ("producer" + suffix)
    probe_source = output / "compiler-probe.rs"
    probe_source.write_text(COMPILER_PROBE, encoding="utf-8")
    probe = output / ("compiler-probe" + suffix)
    checked([options.rustc, "--edition=2021", str(probe_source), "-o", str(probe)],
            cwd=repo, log=output / "probe-compile.log")
    (output / "python-origin.json").write_text(json.dumps({
        "executable": sys.executable, "isolated": sys.flags.isolated,
        "no_site": sys.flags.no_site, "sys_path": sys.path,
        "tomllib": tomllib.__file__, "unittest": unittest.__file__,
        "subprocess": subprocess.__file__, "hashlib": hashlib.__file__,
    }, indent=2), encoding="utf-8")

    class FragmentIdentity(unittest.TestCase):
        def invoke(self, *, digest=hashlib.sha256(ORIGINAL).hexdigest(),
                   selected=True, fragment=True, before=False, during=False):
            case = output / self.id().rsplit(".", 1)[-1]
            case.mkdir()
            out = case / "out"
            out.mkdir()
            external = case / "external.frag.glsl"
            external.write_bytes(ORIGINAL)
            # This models the wrapper's completed hash check. A later edit must
            # be rejected by the producer even without invoking that wrapper.
            self.assertEqual(hashlib.sha256(external.read_bytes()).hexdigest(),
                             hashlib.sha256(ORIGINAL).hexdigest())
            if before:
                external.write_bytes(CHANGED)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith(("RUSTY_QUEST_", "CARGO_FEATURE_", "FRAGMENT_TEST_"))}
            environment.update({"OUT_DIR": str(out), "CARGO_CFG_TARGET_OS": "android",
                                "GLSLC": str(probe)})
            if selected:
                environment[PREFIX + "META_INPUT_DIAGNOSTIC"] = "true"
            if fragment:
                environment[FRAGMENT] = str(external)
            if digest is not None:
                environment[SHA] = digest
            if during:
                replacement = case / "replacement.frag.glsl"
                replacement.write_bytes(CHANGED)
                environment["FRAGMENT_TEST_REPLACEMENT"] = str(replacement)
                environment["FRAGMENT_TEST_EXTERNAL"] = str(external)
            result = run([str(producer)], cwd=native, env=environment)
            (case / "stdout.log").write_text(result.stdout, encoding="utf-8")
            (case / "stderr.log").write_text(result.stderr, encoding="utf-8")
            (case / "exit.txt").write_text(str(result.returncode), encoding="utf-8")
            return result, out, external

        def rejected(self, **selection):
            result, out, _ = self.invoke(**selection)
            self.assertNotEqual(result.returncode, 0, "direct producer accepted invalid fragment identity")
            self.assertIn("Meta input", result.stderr, "producer failed outside fragment selection/identity")
            self.assertFalse((out / "display_composite_feedback.frag.spv").exists(),
                             "invalid identity reached the fragment compiler")

        def test_missing_sha_is_rejected(self):
            self.rejected(digest=None)

        def test_wrong_sha_is_rejected(self):
            self.rejected(digest="0" * 64)

        def test_uppercase_sha_is_rejected(self):
            self.rejected(digest=hashlib.sha256(ORIGINAL).hexdigest().upper())

        def test_short_sha_is_rejected(self):
            self.rejected(digest="a" * 63)

        def test_nonhex_sha_is_rejected(self):
            self.rejected(digest="g" * 64)

        def test_sha_with_newline_is_rejected(self):
            self.rejected(digest=hashlib.sha256(ORIGINAL).hexdigest() + "\n")

        def test_edit_after_wrapper_check_is_rejected(self):
            self.rejected(before=True)

        def test_compiler_reads_captured_snapshot_after_external_edit(self):
            result, out, external = self.invoke(during=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(external.read_bytes(), CHANGED, "compiler-boundary edit did not occur")
            compiled = out / "display_composite_feedback.frag.spv"
            self.assertEqual(compiled.read_bytes(), ORIGINAL,
                             "compiler reopened mutable external fragment instead of verified snapshot")
            source = Path(compiled.with_suffix(".input-path").read_text(encoding="utf-8"))
            self.assertEqual(source.parent, out, "compiler input is not owned by OUT_DIR")
            self.assertEqual(source.read_bytes(), ORIGINAL)

        def test_valid_identity_keeps_original_rerun_and_tracks_sha(self):
            result, out, external = self.invoke()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((out / "display_composite_feedback.frag.spv").read_bytes(), ORIGINAL)
            self.assertIn(f"cargo:rerun-if-changed={external}", result.stdout.splitlines())
            self.assertIn(f"cargo:rerun-if-env-changed={SHA}", result.stdout.splitlines())

        def test_selected_fragment_still_requires_opt_in(self):
            self.rejected(selected=False)

        def test_selected_diagnostic_still_requires_fragment(self):
            self.rejected(fragment=False)

        def test_unselected_uses_public_shader_without_hash(self):
            result, out, _ = self.invoke(selected=False, fragment=False, digest=None)
            self.assertEqual(result.returncode, 0, result.stderr)
            expected = (native / "shaders/display_composite_feedback.frag.glsl").read_bytes()
            self.assertEqual((out / "display_composite_feedback.frag.spv").read_bytes(), expected)

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(FragmentIdentity)
    with (output / "tests.log").open("w", encoding="utf-8") as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    print((output / "tests.log").read_text(encoding="utf-8"), end="")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
