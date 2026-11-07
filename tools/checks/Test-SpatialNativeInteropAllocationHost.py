"""Actual native JNI/query/mask/marker bodies with fake OpenXR PFNs and allocation leaf.

Standalone rustc only. Stub OpenXR ABI layouts validate host wiring, not the real
OpenXR/JNI/Android ABI, Vulkan loader, driver, device lifetime or graphics behavior.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess


PREFIX = "Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_SpatialCameraPanelActivity_"


def block(text, marker):
    if marker not in text:
        raise ValueError("Required production body missing: " + marker)
    start = text.index(marker)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def pin(path):
    raw = path.read_bytes()
    return dict(path=str(path), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def pe_exports(path):
    """Read the emitted host DLL's real PE export names, without external tooling."""
    data = path.read_bytes()
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Host DLL must be a PE image")
    sections, optional_size = struct.unpack_from("<H12xH", data, pe + 6)
    optional = pe + 24
    magic = struct.unpack_from("<H", data, optional)[0]
    if magic not in (0x10B, 0x20B):
        raise ValueError("Unknown PE optional header")
    export_rva = struct.unpack_from("<I", data, optional + (112 if magic == 0x20B else 96))[0]
    table = optional + optional_size
    def offset(rva):
        for index in range(sections):
            size, start, raw_size, raw = struct.unpack_from("<IIII", data, table + 40 * index + 8)
            if start <= rva < start + max(size, raw_size):
                return raw + rva - start
        raise ValueError("Unmapped PE RVA")
    export = offset(export_rva)
    count, names_rva = struct.unpack_from("<I4xI", data, export + 24)
    names = offset(names_rva)
    result = []
    for index in range(count):
        start = offset(struct.unpack_from("<I", data, names + 4 * index)[0])
        result.append(data[start:data.index(b"\0", start)].decode("ascii"))
    return sorted(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rustc", type=Path, default=Path(shutil.which("rustc") or "rustc"))
    parser.add_argument("--negative-controls", action="store_true", help="Reject mutated extracted allocation and mask gates")
    args = parser.parse_args()
    root, out, rustc = args.root.resolve(), args.output.resolve(), args.rustc.resolve(strict=True)
    out.relative_to(root / "target")
    out.mkdir(parents=True, exist_ok=False)
    source = root / "apps/spatial-camera-panel-android/native-receipt/src/lib.rs"
    fixture = root / "tools/checks/fixtures/spatial_native_interop_allocation_host.rs"
    inputs = [source, fixture, Path(__file__).resolve(), rustc]
    before = [pin(path) for path in inputs]
    text = source.read_text(encoding="utf-8-sig")
    signatures = [
        "fn record_no_render_interop_receipt(",
        "struct OpenXrNativeCapabilityProbe {", "impl OpenXrNativeCapabilityProbe {",
        "struct OpenXrFunctionResolution {", "fn probe_openxr_native_capabilities(",
        "fn resolve_openxr_function(", "fn xr_result_token(", "fn version_token(",
        "fn runtime_name_from_openxr(", "pub(crate) fn marker_token(",
        "fn log_receipt(", "fn log_vulkan_object_probe(", "pub(crate) fn bool_token(",
    ]
    symbols = [PREFIX + name for name in ("nativeRecordNoRenderInteropReceipt", "nativeRecordSelectedNoRenderInteropReceipt")]
    selected = {signature: block(text, signature) for signature in signatures}
    for symbol in symbols:
        signature = 'pub extern "system" fn ' + symbol + "("
        position = text.index(signature)
        if "#[no_mangle]" not in text[max(0, position - 70):position]:
            raise ValueError("Production JNI export lacks no_mangle: " + symbol)
        selected[symbol] = "#[no_mangle]\n#[allow(non_snake_case)]\n" + block(text, signature)
    constants = "\n".join(re.findall(r"(?m)^const RECEIPT_[^\n]+", text))
    production = constants + "\n" + "\n".join(selected.values())
    generated = out / "native-interop.rs"
    template = fixture.read_text(encoding="utf-8-sig")
    generated.write_text(template.replace("// PRODUCTION_BODIES", production), encoding="utf-8")
    commands = [
        ("compile-tests", [str(rustc), "--edition=2021", "--test", str(generated), "-o", str(out / "controls.exe")]),
        ("controls", [str(out / "controls.exe"), "--test-threads=1", "--nocapture"]),
        ("compile-host-exports", [str(rustc), "--edition=2021", "--crate-type=cdylib", str(generated), "-o", str(out / "interop.dll")]),
    ]
    checks = []
    for label, argv in commands:
        child = subprocess.run(argv, capture_output=True, timeout=60)
        (out / (label + ".stdout")).write_bytes(child.stdout)
        (out / (label + ".stderr")).write_bytes(child.stderr)
        checks.append(dict(name=label, argv=argv, exit_code=child.returncode))
        if child.returncode:
            break
    exports = pe_exports(out / "interop.dll") if len(checks) == 3 and checks[-1]["exit_code"] == 0 else []
    negatives = []
    if args.negative_controls and len(checks) == 3 and all(row["exit_code"] == 0 for row in checks):
        mutations = {
            "allocation-guard-removed": re.sub(
                r"if allow_vulkan_object_construction\s*&& probe\.vulkan_requirements2_succeeded",
                "if probe.vulkan_requirements2_succeeded", production, count=1),
            "destroyed-bit-fabricated": production.replace("if openxr_probe.vulkan_objects_destroyed {", "if true {", 1),
        }
        for label, mutated in mutations.items():
            if mutated == production:
                raise ValueError("Mutation target absent: " + label)
            path = out / (label + ".rs")
            path.write_text(template.replace("// PRODUCTION_BODIES", mutated), encoding="utf-8")
            binary = out / (label + ".exe")
            compile = subprocess.run([str(rustc), "--edition=2021", "--test", str(path), "-o", str(binary)],
                                     capture_output=True, timeout=60)
            (out / (label + ".compile.stderr")).write_bytes(compile.stderr)
            control = None
            if compile.returncode == 0:
                control = subprocess.run([str(binary), "--exact",
                    "tests::borrowed_only_retains_queries_resolves_constructors_but_invokes_none", "--nocapture"],
                    capture_output=True, timeout=60)
                (out / (label + ".stdout")).write_bytes(control.stdout)
                (out / (label + ".stderr")).write_bytes(control.stderr)
            negatives.append(dict(name=label, compiled=compile.returncode == 0,
                                  exit_code=control.returncode if control else None,
                                  rejected=control is not None and control.returncode != 0))
    unchanged = before == [pin(path) for path in inputs]
    result = dict(passed=unchanged and len(checks) == 3 and all(row["exit_code"] == 0 for row in checks)
                  and set(symbols).issubset(exports) and all(row["rejected"] for row in negatives)
                  and (not args.negative_controls or len(negatives) == 2),
                  source_unchanged=unchanged, checks=checks, negative_controls=negatives,
                  source_pins=before, generated=pin(generated), host_dll_exports=exports,
                  extracted_body_sha256={name: hashlib.sha256(body.encode()).hexdigest() for name, body in selected.items()},
                  limits=__doc__, device_calls=0, apk_build=False, full_native_crate_build=False)
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(passed=result["passed"], source_unchanged=unchanged, checks=checks,
                          host_dll_exports=exports, negative_controls=negatives)))
    if not result["passed"]:
        for name in ("compile-tests.stderr", "controls.stdout", "controls.stderr", "compile-host-exports.stderr"):
            path = out / name
            if path.exists() and path.stat().st_size:
                print(path.read_text(encoding="utf-8", errors="replace"))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
