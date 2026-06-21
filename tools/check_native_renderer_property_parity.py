"""Validate native renderer runtime-profile property parity.

This is a low-rate settings-authority guard. It compares native renderer
runtime-profile fixtures against the native renderer runtime parser constants
and the manifest-consuming profile validators, with additional literal checks
for specialized cross-field validation families.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


NATIVE_RENDERER_PREFIX = "debug.rustyquest.native_renderer."
PROFILE_SCHEMA = "rusty.quest.runtime_profile.v1"
MANIFEST_SCHEMA = "rusty.quest.native_renderer_property_manifest.v2"
REPORT_SCHEMA = "rusty.quest.native_renderer_property_parity.v2"
PROFILE_GLOB = "quest-native-renderer*.profile.json"
CANONICAL_BREATHING_ROOM_PROFILE = "quest-native-renderer-breathing-room-pmb-scale.profile.json"
PROPERTY_MANIFEST_PATH = Path("fixtures/native-renderer/native-renderer-property-manifest.json")
PROPERTY_RE = re.compile(r'"(debug\.rustyquest\.native_renderer\.[^"]+)"')
VALID_MANIFEST_VALUE_KINDS = {"bool", "f32", "f32_pair", "string", "token", "u16", "u32", "u64"}
VALID_MANIFEST_LIFECYCLES = {"startup-effective"}
VALID_MANIFEST_CLEAR_BEHAVIORS = {"profile-owned-explicit-set"}
VALID_MANIFEST_DEFAULT_BEHAVIORS = {"runtime-owner-default-when-unset"}
REQUIRED_MANIFEST_VALIDATORS = (
    "runtime-parser",
    "profile-matrix",
    "rusty-quest-profile",
    "Apply-RuntimeProfile.ps1",
)

RUNTIME_PROPERTY_SOURCES = (
    Path("apps/native-renderer-android/native/src/native_renderer_camera_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_display_composite_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_properties.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_property_values.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_environment_depth_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_hand_anchor_particle_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_passthrough_style_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_projection_border_stretch_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_stimulus_volume_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_video_projection_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_visual_options.rs"),
    Path("apps/native-renderer-android/native/src/native_renderer_options.rs"),
    Path("apps/native-renderer-android/native/src/projection_target_state.rs"),
)

PROFILE_VALIDATOR_SOURCES = (
    Path("crates/rusty-quest-profile/src/lib.rs"),
)

MANIFEST_CONSUMER_TOKENS = {
    Path("tools/Apply-RuntimeProfile.ps1"): (
        "NativeRendererPropertyManifestRelativePath",
        "Import-NativeRendererPropertyManifest",
        "Assert-NativeRendererManifestProperty",
        "Assert-NativeRendererManifestRange",
    ),
    Path("crates/rusty-quest-profile/src/lib.rs"): (
        "NATIVE_RENDERER_PROPERTY_MANIFEST_JSON",
        "validate_native_renderer_profile_against_manifest",
        "validate_native_renderer_manifest_value",
        "validate_native_renderer_manifest_range",
    ),
}

SPECIALIZED_APPLY_PREFIXES = (
    "debug.rustyquest.native_renderer.environment_depth.",
    "debug.rustyquest.native_renderer.stimulus_volume.",
    "debug.rustyquest.native_renderer.projection.target.",
    "debug.rustyquest.native_renderer.manifold.",
)

BREATHING_ROOM_EXPECTED_VALUES = {
    "debug.rustyquest.native_renderer.camera.output": "guide-public",
    "debug.rustyquest.native_renderer.guide.blur.enabled": "false",
    "debug.rustyquest.native_renderer.guide.resolution": "camera-native",
    "debug.rustyquest.native_renderer.camera.ycbcr.mode": "forced-bt601-narrow",
    "debug.rustyquest.native_renderer.camera.resolution": "1280x1280",
    "debug.rustyquest.native_renderer.camera.reader_max_images": "4",
    "debug.rustyquest.native_renderer.camera.quality_profile": "direct-baseline",
    "debug.rustyquest.native_renderer.camera.sync_mode": "early-delete-ahb-retained",
    "debug.rustyquest.native_renderer.camera.luma_diagnostic.enabled": "false",
    "debug.rustyquest.native_renderer.camera.stereo_pairing": "latest-latest",
    "debug.rustyquest.native_renderer.swapchain.color_format": "unorm",
    "debug.rustyquest.native_renderer.hand_mesh.input.source": "disabled",
    "debug.rustyquest.native_renderer.hand_mesh.real_hands.visible": "false",
    "debug.rustyquest.native_renderer.hand_anchor_particles.enabled": "false",
    "debug.rustyquest.native_renderer.environment_depth.mode": "disabled",
    "debug.rustyquest.native_renderer.environment_depth.high_rate_json_payload": "false",
    "debug.rustyquest.native_renderer.stimulus_volume.enabled": "false",
    "debug.rustyquest.native_renderer.private_layer.enabled": "false",
}


def repo_path(repo_root: Path, relative: Path) -> Path:
    return repo_root / relative


def load_property_manifest(repo_root: Path) -> tuple[dict[str, Any], list[str]]:
    issues: list[str] = []
    path = repo_path(repo_root, PROPERTY_MANIFEST_PATH)
    if not path.exists():
        return {}, [f"native renderer property manifest is missing: {PROPERTY_MANIFEST_PATH}"]
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != MANIFEST_SCHEMA:
        issues.append(f"{PROPERTY_MANIFEST_PATH}: expected schema {MANIFEST_SCHEMA}")
    if manifest.get("prefix") != NATIVE_RENDERER_PREFIX:
        issues.append(f"{PROPERTY_MANIFEST_PATH}: expected prefix {NATIVE_RENDERER_PREFIX}")
    properties = manifest.get("properties")
    if not isinstance(properties, list):
        return {}, issues + [f"{PROPERTY_MANIFEST_PATH}: properties must be a list"]
    expected_count = manifest.get("property_count")
    if expected_count != len(properties):
        issues.append(
            f"{PROPERTY_MANIFEST_PATH}: property_count={expected_count} does not match {len(properties)} entries"
        )
    by_name: dict[str, Any] = {}
    names: list[str] = []
    for index, entry in enumerate(properties):
        if not isinstance(entry, dict):
            issues.append(f"{PROPERTY_MANIFEST_PATH}: property entry {index} is not an object")
            continue
        name = str(entry.get("name", ""))
        names.append(name)
        if not name.startswith(NATIVE_RENDERER_PREFIX):
            issues.append(f"{PROPERTY_MANIFEST_PATH}: property does not use native prefix: {name}")
        if name in by_name:
            issues.append(f"{PROPERTY_MANIFEST_PATH}: duplicate property entry: {name}")
        by_name[name] = entry
        for field in (
            "family",
            "runtime_owner",
            "lifecycle",
            "clear_behavior",
            "default_behavior",
            "validators",
            "value_kind",
        ):
            if field not in entry:
                issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} missing field {field}")
        lifecycle = entry.get("lifecycle")
        if lifecycle not in VALID_MANIFEST_LIFECYCLES:
            issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} has invalid lifecycle {lifecycle!r}")
        clear_behavior = entry.get("clear_behavior")
        if clear_behavior not in VALID_MANIFEST_CLEAR_BEHAVIORS:
            issues.append(
                f"{PROPERTY_MANIFEST_PATH}: {name} has invalid clear_behavior {clear_behavior!r}"
            )
        default_behavior = entry.get("default_behavior")
        if default_behavior not in VALID_MANIFEST_DEFAULT_BEHAVIORS:
            issues.append(
                f"{PROPERTY_MANIFEST_PATH}: {name} has invalid default_behavior {default_behavior!r}"
            )
        value_kind = entry.get("value_kind")
        if value_kind not in VALID_MANIFEST_VALUE_KINDS:
            issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} has invalid value_kind {value_kind!r}")
        if value_kind == "token":
            allowed = entry.get("allowed_values")
            if not isinstance(allowed, list) or not allowed:
                issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} token requires non-empty allowed_values")
        if value_kind in {"f32", "f32_pair", "u16", "u32", "u64"} and "range" in entry:
            range_spec = entry["range"]
            if not isinstance(range_spec, dict) or "min" not in range_spec or "max" not in range_spec:
                issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} range must include min and max")
            elif range_spec["min"] > range_spec["max"]:
                issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} range min is greater than max")
        validators = entry.get("validators")
        if not isinstance(validators, list) or not validators:
            issues.append(f"{PROPERTY_MANIFEST_PATH}: {name} validators must be a non-empty list")
        else:
            validator_names = [str(validator) for validator in validators]
            duplicate_validators = sorted(
                {
                    validator
                    for validator in validator_names
                    if validator_names.count(validator) > 1
                }
            )
            if duplicate_validators:
                issues.append(
                    f"{PROPERTY_MANIFEST_PATH}: {name} has duplicate validators: "
                    + ", ".join(duplicate_validators)
                )
            missing_validators = [
                validator
                for validator in REQUIRED_MANIFEST_VALIDATORS
                if validator not in validator_names
            ]
            if missing_validators:
                issues.append(
                    f"{PROPERTY_MANIFEST_PATH}: {name} missing required validators: "
                    + ", ".join(missing_validators)
                )
    if names != sorted(names):
        issues.append(f"{PROPERTY_MANIFEST_PATH}: properties must be sorted by name")
    return by_name, issues


def check_manifest_consumer_wiring(repo_root: Path) -> list[str]:
    issues: list[str] = []
    for relative, tokens in MANIFEST_CONSUMER_TOKENS.items():
        text = repo_path(repo_root, relative).read_text(encoding="utf-8")
        for token in tokens:
            if token not in text:
                issues.append(f"{relative}: manifest consumer token is missing: {token}")
    return issues


def extract_runtime_properties(repo_root: Path) -> set[str]:
    properties: set[str] = set()
    for relative in RUNTIME_PROPERTY_SOURCES:
        text = repo_path(repo_root, relative).read_text(encoding="utf-8")
        text_before_tests = text.split("#[cfg(test)]", 1)[0]
        properties.update(PROPERTY_RE.findall(text_before_tests))
    return properties


def extract_apply_properties(repo_root: Path) -> set[str]:
    text = repo_path(repo_root, Path("tools/Apply-RuntimeProfile.ps1")).read_text(encoding="utf-8")
    return set(PROPERTY_RE.findall(text))


def extract_profile_validator_properties(repo_root: Path) -> set[str]:
    properties: set[str] = set()
    for relative in PROFILE_VALIDATOR_SOURCES:
        text = repo_path(repo_root, relative).read_text(encoding="utf-8")
        text_before_tests = text.split("#[cfg(test)]", 1)[0]
        properties.update(PROPERTY_RE.findall(text_before_tests))
    return properties


def profile_property_records(profile: dict[str, Any]) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    for item in profile.get("set_properties", []):
        records.append(
            {
                "name": str(item.get("name", "")),
                "value": str(item.get("value", "")),
            }
        )
    return records


def check_profile(path: Path) -> tuple[set[str], list[str]]:
    issues: list[str] = []
    profile = json.loads(path.read_text(encoding="utf-8"))
    if profile.get("schema") != PROFILE_SCHEMA:
        issues.append(f"{path.name}: expected schema {PROFILE_SCHEMA}")
    owned = [str(name) for name in profile.get("owned_android_properties", [])]
    records = profile_property_records(profile)
    owned_set = set(owned)
    set_names = [record["name"] for record in records]
    set_name_set = set(set_names)
    duplicate_owned = sorted({name for name in owned if owned.count(name) > 1})
    duplicate_set = sorted({name for name in set_names if set_names.count(name) > 1})
    if duplicate_owned:
        issues.append(f"{path.name}: duplicate owned properties: {', '.join(duplicate_owned)}")
    if duplicate_set:
        issues.append(f"{path.name}: duplicate set_properties: {', '.join(duplicate_set)}")
    missing_set = sorted(owned_set - set_name_set)
    extra_set = sorted(set_name_set - owned_set)
    if missing_set:
        issues.append(f"{path.name}: owned properties are not set: {', '.join(missing_set)}")
    if extra_set:
        issues.append(f"{path.name}: set_properties includes unowned keys: {', '.join(extra_set)}")
    non_native = sorted(name for name in owned_set | set_name_set if not name.startswith(NATIVE_RENDERER_PREFIX))
    if non_native:
        issues.append(f"{path.name}: non-native-renderer properties: {', '.join(non_native)}")
    high_rate_true = sorted(
        record["name"]
        for record in records
        if record["name"].endswith(".high_rate_json_payload") and record["value"].lower() != "false"
    )
    if high_rate_true:
        issues.append(f"{path.name}: high-rate JSON payload properties must be false: {', '.join(high_rate_true)}")
    return owned_set | set_name_set, issues


def parse_finite_float(value: str) -> float | None:
    try:
        parsed = float(value.strip())
    except ValueError:
        return None
    if parsed != parsed or parsed in (float("inf"), float("-inf")):
        return None
    return parsed


def validate_numeric_range(
    path_name: str, property_name: str, value: float, manifest_entry: dict[str, Any]
) -> list[str]:
    range_spec = manifest_entry.get("range")
    if not isinstance(range_spec, dict):
        return []
    min_value = range_spec.get("min")
    max_value = range_spec.get("max")
    issues: list[str] = []
    if min_value is not None and value < float(min_value):
        issues.append(
            f"{path_name}: {property_name}={value:g} is below manifest minimum {float(min_value):g}"
        )
    if max_value is not None and value > float(max_value):
        issues.append(
            f"{path_name}: {property_name}={value:g} is above manifest maximum {float(max_value):g}"
        )
    return issues


def validate_profile_values_against_manifest(
    path: Path, manifest_by_name: dict[str, Any]
) -> list[str]:
    issues: list[str] = []
    profile = json.loads(path.read_text(encoding="utf-8"))
    for record in profile_property_records(profile):
        name = record["name"]
        value = record["value"]
        manifest_entry = manifest_by_name.get(name)
        if manifest_entry is None:
            issues.append(f"{path.name}: {name} is missing from native renderer property manifest")
            continue
        value_kind = manifest_entry.get("value_kind")
        if value_kind == "bool":
            if value.strip().lower() not in {"true", "false"}:
                issues.append(f"{path.name}: {name}={value!r} must be a manifest bool true/false")
        elif value_kind == "token":
            allowed_values = {str(item) for item in manifest_entry.get("allowed_values", [])}
            if value not in allowed_values:
                issues.append(
                    f"{path.name}: {name}={value!r} is not in manifest allowed_values"
                )
        elif value_kind in {"u16", "u32", "u64"}:
            try:
                parsed_int = int(value.strip())
            except ValueError:
                issues.append(f"{path.name}: {name}={value!r} must be an integer")
                continue
            if str(parsed_int) != value.strip():
                issues.append(f"{path.name}: {name}={value!r} must be a base-10 integer")
            if parsed_int < 0:
                issues.append(f"{path.name}: {name}={value!r} must be unsigned")
            if value_kind == "u16" and parsed_int > 65_535:
                issues.append(f"{path.name}: {name}={value!r} exceeds u16 maximum")
            issues.extend(validate_numeric_range(path.name, name, float(parsed_int), manifest_entry))
        elif value_kind == "f32":
            parsed_float = parse_finite_float(value)
            if parsed_float is None:
                issues.append(f"{path.name}: {name}={value!r} must be a finite float")
                continue
            issues.extend(validate_numeric_range(path.name, name, parsed_float, manifest_entry))
        elif value_kind == "f32_pair":
            parts = [part.strip() for part in value.split(",")]
            if len(parts) != 2:
                issues.append(f"{path.name}: {name}={value!r} must be two comma-separated floats")
                continue
            for part in parts:
                parsed_float = parse_finite_float(part)
                if parsed_float is None:
                    issues.append(f"{path.name}: {name}={value!r} contains a non-finite float")
                    continue
                issues.extend(validate_numeric_range(path.name, name, parsed_float, manifest_entry))
        elif value_kind == "string":
            if manifest_entry.get("non_empty") and not value.strip():
                issues.append(f"{path.name}: {name} must be a non-empty string")
    return issues


def check_breathing_room_profile(path: Path) -> list[str]:
    issues: list[str] = []
    if not path.exists():
        return [f"{CANONICAL_BREATHING_ROOM_PROFILE}: missing canonical Breathing Room profile"]
    profile = json.loads(path.read_text(encoding="utf-8"))
    values = {record["name"]: record["value"] for record in profile_property_records(profile)}
    if len(values) < 55:
        issues.append(
            f"{CANONICAL_BREATHING_ROOM_PROFILE}: expected at least 55 explicit properties, found {len(values)}"
        )
    for name, expected in BREATHING_ROOM_EXPECTED_VALUES.items():
        actual = values.get(name)
        if actual is None:
            issues.append(f"{CANONICAL_BREATHING_ROOM_PROFILE}: missing required property {name}")
        elif actual != expected:
            issues.append(
                f"{CANONICAL_BREATHING_ROOM_PROFILE}: expected {name}={expected}, found {actual}"
            )
    return issues


def count_manifest_field(manifest_by_name: dict[str, Any], field: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for entry in manifest_by_name.values():
        value = str(entry.get(field, ""))
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def build_report(repo_root: Path) -> dict[str, Any]:
    profile_paths = sorted((repo_root / "fixtures/runtime-profiles").glob(PROFILE_GLOB))
    manifest_by_name, manifest_issues = load_property_manifest(repo_root)
    manifest_properties = set(manifest_by_name)
    runtime_properties = extract_runtime_properties(repo_root)
    apply_properties = extract_apply_properties(repo_root)
    profile_validator_properties = extract_profile_validator_properties(repo_root)
    profile_properties: set[str] = set()
    issues: list[str] = list(manifest_issues)
    issues.extend(check_manifest_consumer_wiring(repo_root))
    per_profile_property_counts: dict[str, int] = {}
    for path in profile_paths:
        properties, profile_issues = check_profile(path)
        profile_properties.update(properties)
        per_profile_property_counts[path.name] = len(properties)
        issues.extend(profile_issues)
        issues.extend(validate_profile_values_against_manifest(path, manifest_by_name))
    runtime_not_manifest = sorted(runtime_properties - manifest_properties)
    if runtime_not_manifest:
        issues.append(
            "runtime parser properties are missing from native renderer property manifest: "
            + ", ".join(runtime_not_manifest)
        )
    manifest_not_runtime = sorted(manifest_properties - runtime_properties)
    if manifest_not_runtime:
        issues.append(
            "native renderer property manifest entries are missing from runtime parser: "
            + ", ".join(manifest_not_runtime)
        )
    profile_not_manifest = sorted(profile_properties - manifest_properties)
    if profile_not_manifest:
        issues.append(
            "native renderer profiles declare properties missing from property manifest: "
            + ", ".join(profile_not_manifest)
        )
    profile_not_runtime = sorted(profile_properties - runtime_properties)
    if profile_not_runtime:
        issues.append(
            "native renderer profiles declare properties missing from runtime parser: "
            + ", ".join(profile_not_runtime)
        )
    specialized_profile_properties = {
        name
        for name in profile_properties
        if any(name.startswith(prefix) for prefix in SPECIALIZED_APPLY_PREFIXES)
    }
    specialized_profile_validator_properties = {
        name
        for name in profile_validator_properties
        if any(name.startswith(prefix) for prefix in SPECIALIZED_APPLY_PREFIXES)
    }
    specialized_apply_properties = {
        name
        for name in apply_properties
        if any(name.startswith(prefix) for prefix in SPECIALIZED_APPLY_PREFIXES)
    }
    specialized_manifest_properties = {
        name
        for name in manifest_properties
        if any(name.startswith(prefix) for prefix in SPECIALIZED_APPLY_PREFIXES)
    }
    manifest_missing_required_validators = {
        name: [
            validator
            for validator in REQUIRED_MANIFEST_VALIDATORS
            if validator not in manifest_by_name[name].get("validators", [])
        ]
        for name in manifest_properties
    }
    manifest_missing_required_validators = {
        name: missing
        for name, missing in manifest_missing_required_validators.items()
        if missing
    }
    manifest_missing_profile_validator = sorted(
        specialized_manifest_properties - specialized_profile_validator_properties
    )
    if manifest_missing_profile_validator:
        issues.append(
            "manifest specialized properties are missing from rusty-quest-profile validator: "
            + ", ".join(manifest_missing_profile_validator)
        )
    manifest_missing_apply = sorted(specialized_manifest_properties - specialized_apply_properties)
    if manifest_missing_apply:
        issues.append(
            "manifest specialized properties are missing from Apply-RuntimeProfile.ps1: "
            + ", ".join(manifest_missing_apply)
        )
    specialized_missing_profile_validator = sorted(
        specialized_profile_properties - specialized_profile_validator_properties
    )
    if specialized_missing_profile_validator:
        issues.append(
            "specialized profile properties are missing from rusty-quest-profile validator: "
            + ", ".join(specialized_missing_profile_validator)
        )
    specialized_missing_apply = sorted(specialized_profile_properties - apply_properties)
    if specialized_missing_apply:
        issues.append(
            "specialized profile properties are missing from Apply-RuntimeProfile.ps1: "
            + ", ".join(specialized_missing_apply)
        )
    validator_missing_apply = sorted(
        specialized_profile_validator_properties - specialized_apply_properties
    )
    if validator_missing_apply:
        issues.append(
            "rusty-quest-profile specialized validator properties are missing from Apply-RuntimeProfile.ps1: "
            + ", ".join(validator_missing_apply)
        )
    apply_missing_validator = sorted(
        specialized_apply_properties - specialized_profile_validator_properties
    )
    if apply_missing_validator:
        issues.append(
            "Apply-RuntimeProfile.ps1 specialized properties are missing from rusty-quest-profile validator: "
            + ", ".join(apply_missing_validator)
        )
    issues.extend(
        check_breathing_room_profile(
            repo_root / "fixtures/runtime-profiles" / CANONICAL_BREATHING_ROOM_PROFILE
        )
    )
    report = {
        "schema": REPORT_SCHEMA,
        "profile_glob": PROFILE_GLOB,
        "property_manifest": str(PROPERTY_MANIFEST_PATH),
        "required_manifest_validators": list(REQUIRED_MANIFEST_VALIDATORS),
        "manifest_property_count": len(manifest_properties),
        "manifest_low_rate_validator_property_count": len(
            manifest_properties - set(manifest_missing_required_validators)
        ),
        "manifest_lifecycle_counts": count_manifest_field(manifest_by_name, "lifecycle"),
        "manifest_clear_behavior_counts": count_manifest_field(manifest_by_name, "clear_behavior"),
        "manifest_default_behavior_counts": count_manifest_field(
            manifest_by_name, "default_behavior"
        ),
        "profile_count": len(profile_paths),
        "profile_property_count": len(profile_properties),
        "runtime_property_count": len(runtime_properties),
        "apply_specialized_property_count": len(apply_properties & specialized_profile_properties),
        "profile_validator_specialized_property_count": len(
            profile_validator_properties & specialized_profile_properties
        ),
        "runtime_only_properties": sorted(runtime_properties - profile_properties),
        "runtime_missing_manifest_properties": runtime_not_manifest,
        "manifest_missing_runtime_properties": manifest_not_runtime,
        "profile_missing_manifest_properties": profile_not_manifest,
        "manifest_missing_required_validators": manifest_missing_required_validators,
        "manifest_specialized_missing_profile_validator_properties": manifest_missing_profile_validator,
        "manifest_specialized_missing_apply_properties": manifest_missing_apply,
        "profile_only_properties": profile_not_runtime,
        "profile_validator_missing_properties": specialized_missing_profile_validator,
        "specialized_apply_missing_properties": specialized_missing_apply,
        "specialized_validator_missing_apply_properties": validator_missing_apply,
        "specialized_apply_missing_validator_properties": apply_missing_validator,
        "per_profile_property_counts": per_profile_property_counts,
        "ok": not issues,
        "issues": issues,
    }
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    repo_root = Path(args.repo_root).resolve()
    report = build_report(repo_root)
    if args.out:
        out = Path(args.out)
        if not out.is_absolute():
            out = repo_root / out
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        "native renderer property parity: "
        f"manifest_properties={report['manifest_property_count']} "
        f"low_rate_validator_properties={report['manifest_low_rate_validator_property_count']} "
        f"profiles={report['profile_count']} "
        f"profile_properties={report['profile_property_count']} "
        f"runtime_properties={report['runtime_property_count']} "
        f"profile_validator_properties={report['profile_validator_specialized_property_count']} "
        f"ok={str(report['ok']).lower()}"
    )
    if report["issues"]:
        for issue in report["issues"]:
            print(f"[FAIL] {issue}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
