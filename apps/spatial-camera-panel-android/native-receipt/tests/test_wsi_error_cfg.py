"""Compile production WSI error bodies under SDK and legacy cfgs.

Model inputs only: this proves String conversion and prerequisite refusal,
not Vulkan calls, ownership, cleanup, ABI, presentation or device behavior.
Run inside the selected owning build environment with an explicit rustc.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


CASES = (
    (r"\.create_android_surface\(&surface_info, None\)",
     "create-android-surface-ModeledVulkanFailure", False),
    (r"select_camera_surface_device\(&instance, &surface_loader, surface, &physical_devices\)",
     "no-camera-hwb-vulkan-device", True),
    (r"\.get_physical_device_surface_formats\(physical_device, surface\)",
     "surface-formats-ModeledVulkanFailure", False),
    (r"\.get_physical_device_surface_capabilities\(physical_device, surface\)",
     "surface-capabilities-ModeledVulkanFailure", False),
    (r"\.create_swapchain\(&swapchain_info, None\)",
     "create-swapchain-ModeledVulkanFailure", False),
    (r"\.get_swapchain_images\(swapchain\)",
     "swapchain-images-ModeledVulkanFailure", False),
)


def production_bodies(source):
    start = source.index("    unsafe fn create_with_startup(")
    end = source.index("        let image_views = create_image_views", start)
    region = source[start:end]
    closures = []
    for anchor, expected, option in CASES:
        method = "ok_or_else" if option else "map_err"
        argument = r"\|\|" if option else r"\|error\|"
        pattern = (anchor + r"\s*\." + method + r"\("
                   + r"(?P<body>" + argument + r"\s*\{.*?\n[ \t]*\})\)\?")
        matches = list(re.finditer(pattern, region, re.DOTALL))
        if len(matches) != 1:
            raise ValueError("production error-closure shape changed: " + anchor)
        closures.append((matches[0].group("body"), expected, option))
    blocks = list(re.finditer(
        r"(?P<body>if !extension_status\.external_hwb_extension_ready\n"
        r".*?^        \})", region, re.DOTALL | re.MULTILINE))
    if len(blocks) != 1:
        raise ValueError("production prerequisite-block shape changed")
    return closures, blocks[0].group("body")


def rust_tests(closures, prerequisite):
    parts = ["#[derive(Debug)] struct ModeledVulkanFailure;\n"]
    for index, (closure, _expected, option) in enumerate(closures):
        parts.append(f"fn error_case_{index}(failed: bool) -> Result<(), String> {{\n")
        if option:
            parts.append("let value = if failed { None } else { Some(()) };\n")
            parts.append("value.ok_or_else(" + closure + ")?;\n")
        else:
            parts.append("let value = if failed { Err(ModeledVulkanFailure) } else { Ok(()) };\n")
            parts.append("value.map_err(" + closure + ")?;\n")
        parts.append("Ok(())\n}\n")
    parts.append("#[test] fn production_closures_keep_string_reasons_and_success() {\n")
    for index, (_closure, expected, _option) in enumerate(closures):
        parts.append(f"assert_eq!(error_case_{index}(true).unwrap_err(), {json.dumps(expected)});\n")
        parts.append(f"assert!(error_case_{index}(false).is_ok());\n")
    parts.append("}\n")
    parts.append("""
struct ModelExtensionStatus {
    external_hwb_extension_ready: bool,
    sampler_ycbcr_extension_ready: bool,
    sampler_ycbcr_feature_ready: bool,
}
fn production_prerequisite(a: bool, b: bool, c: bool) -> Result<(), String> {
    let extension_status = ModelExtensionStatus {
        external_hwb_extension_ready: a,
        sampler_ycbcr_extension_ready: b,
        sampler_ycbcr_feature_ready: c,
    };
""" + prerequisite + "\nOk(())\n}\n")
    parts.append("""
#[test] fn production_prerequisite_refuses_each_missing_capability() {
    for a in [false, true] {
        for b in [false, true] {
            for c in [false, true] {
                let result = production_prerequisite(a, b, c);
                if a && b && c {
                    assert!(result.is_ok());
                } else {
                    assert_eq!(result.unwrap_err(), format!(
                        "vulkan-ahb-prereq-missing-externalHwb-{a}-samplerYcbcrExt-{b}-samplerYcbcrFeature-{c}"));
                }
            }
        }
    }
}
""")
    return "".join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rustc", required=True, type=Path)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--source", type=Path,
                        default=Path(__file__).resolve().parents[1] / "src/camera_hwb_probe.rs")
    args = parser.parse_args()
    if not args.rustc.is_absolute() or not args.rustc.is_file():
        parser.error("--rustc must select an existing absolute compiler path")
    if not args.output_root.is_absolute() or args.output_root.exists():
        parser.error("--output-root must be an absolute fresh private directory")
    args.source = args.source.resolve(strict=True)
    args.rustc = args.rustc.resolve(strict=True)
    args.output_root = args.output_root.resolve()
    source_repo = args.source.parents[4]
    if args.output_root == source_repo or source_repo in args.output_root.parents:
        parser.error("--output-root must be outside the selected source repository")
    if not args.output_root.parent.is_dir():
        parser.error("--output-root parent must already exist")
    raw = args.source.read_bytes()
    # Universal newlines permit the exact source checkout's CRLF without
    # changing cfg attributes or any production statements in extracted bodies.
    source = raw.decode("utf-8-sig").replace("\r\n", "\n")
    closures, prerequisite = production_bodies(source)
    generated = rust_tests(closures, prerequisite)
    args.output_root.mkdir()
    unit = args.output_root / "production_wsi_error_cfg.rs"
    unit.write_text(generated, encoding="utf-8", newline="\n")
    print(json.dumps({"source_sha256": hashlib.sha256(raw).hexdigest(),
                      "source_bytes": len(raw), "closure_count": len(closures),
                      "generated_sha256": hashlib.sha256(unit.read_bytes()).hexdigest(),
                      "model_scope": "error type and prerequisite branch only"}), flush=True)
    failures = 0
    for mode, cfg in (("legacy", []), ("sdk", ["--cfg", "rq_environment_depth_spatial_sdk_api_layer"])):
        binary = args.output_root / ("wsi_" + mode + (".exe" if sys.platform == "win32" else ""))
        print("Compile production bodies: " + mode, flush=True)
        compiled = subprocess.run([str(args.rustc), "--edition=2021", "--test", str(unit),
                                   "-o", str(binary), *cfg], check=False)
        if compiled.returncode != 0:
            failures += 1
            continue
        tested = subprocess.run([str(binary), "--nocapture"], check=False)
        failures += tested.returncode != 0
    return int(failures != 0)


if __name__ == "__main__":
    raise SystemExit(main())
