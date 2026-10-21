"""Type-check actual bridge methods against an existing real JNI Android rlib.

Metadata only: no Cargo, linking, JVM, JNI call, Android runtime or device action.
The capability bound has no implementation and grants no authority.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def between(source, start, following):
    if source.count(start) != 1 or source.count(following) != 1:
        raise ValueError("production method boundary changed")
    first = source.index(start)
    last = source.index(following, first + len(start))
    method = source[first:last].rstrip()
    if not method.endswith("}"):
        raise ValueError("production method end changed")
    return method


def compile_unit(source, incoming_path):
    bind = between(source,
        "    pub(crate) fn bind_incoming_fresh_setup_phase(",
        "    pub(crate) fn assert_incoming_fresh_setup_initial_allowed(")
    checked = between(source, "    fn checked_call<'local, T>(",
                      "    fn attached(&self)")
    attached = between(source, "    fn attached(&self)",
                       "    fn execute_and_verify_java(")
    free_checked = between(source, "\nfn checked_call<'local, T>(",
                           "\nfn valid_key_id(")
    # JNI types and methods remain real. Only the unrelated native capability
    # is abstracted by its actual interface; there is no implementation or run.
    prefix = """
extern crate jni;
#[path = """ + json.dumps(incoming_path.as_posix(), ensure_ascii=False) + """ ]
mod incoming_fresh_phase;
mod bridge {
    use std::sync::Arc;
    use jni::objects::{GlobalRef, JObject, JValue};
    use jni::{JNIEnv, JavaVM};
    trait CapabilityRead {
        fn require_live(&self) -> Result<(), String>;
    }
    struct JavaOwnerCallbacks<C: CapabilityRead> {
        capability: C,
        vm: Arc<JavaVM>,
        callback: GlobalRef,
    }
    impl<C: CapabilityRead> JavaOwnerCallbacks<C> {
"""
    unit = prefix + "\n".join((bind, checked, attached)) + "\n}\n"
    unit += free_checked + "\n}\n"
    return unit, {name: hashlib.sha256(text.encode()).hexdigest()
                  for name, text in (("bind", bind), ("checked_member", checked),
                                     ("attached", attached), ("checked_free", free_checked))}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def host_dependencies(args, parser):
    selection_path = args.host_dependency_selection
    if not selection_path.is_absolute() or not selection_path.is_file():
        parser.error("explicit absolute existing host dependency selection required")
    raw = selection_path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != args.expected_host_selection_sha256:
        parser.error("host dependency selection bytes changed")
    selection = json.loads(raw)
    if (set(selection) != {"schema", "directory", "files"}
            or selection["schema"] != "task.jni-host-dependency-selection.v1"):
        parser.error("unsupported host dependency selection")
    # Cargo places host proc-macros beside the cross-target directory, not in it.
    conventional = args.jni_rlib.parent.parents[2] / "release" / "deps"
    selected = Path(selection["directory"])
    if (not selected.is_absolute() or not selected.is_dir()
            or selected.resolve(strict=True) != conventional.resolve(strict=True)):
        parser.error("host dependencies must be the existing sibling Cargo release directory")
    files = selection["files"]
    if not isinstance(files, list) or not files:
        parser.error("closed host proc-macro DLL selection required")
    paths = []
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            parser.error("invalid host dependency raw reference")
        path = Path(item["path"])
        if (not path.is_absolute() or path.parent.resolve(strict=True) != conventional
                or path.suffix.lower() != ".dll" or not path.is_file()):
            parser.error("host dependency must be an immediate selected-directory DLL")
        if path.stat().st_size != item["bytes"] or digest(path) != item["sha256"]:
            parser.error("selected host dependency bytes changed")
        paths.append(path.resolve(strict=True))
    if (len(paths) != len(set(paths))
            or set(paths) != {p.resolve(strict=True) for p in conventional.glob("*.dll")}):
        parser.error("host proc-macro DLL membership changed")
    return conventional, selection


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rustc", required=True, type=Path)
    parser.add_argument("--jni-rlib", required=True, type=Path)
    parser.add_argument("--expected-jni-sha256", required=True)
    parser.add_argument("--host-dependency-selection", required=True, type=Path)
    parser.add_argument("--expected-host-selection-sha256", required=True)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--source", type=Path,
                        default=Path(__file__).resolve().parents[1]
                        / "src/embedded_duplex/java_bridge.rs")
    args = parser.parse_args()
    for selected in (args.rustc, args.jni_rlib):
        if not selected.is_absolute() or not selected.is_file():
            parser.error("explicit absolute existing compiler/JNI rlib required")
    if not args.output_root.is_absolute() or args.output_root.exists():
        parser.error("absolute fresh private output directory required")
    args.rustc = args.rustc.resolve(strict=True)
    args.jni_rlib = args.jni_rlib.resolve(strict=True)
    args.source = args.source.resolve(strict=True)
    args.output_root = args.output_root.resolve()
    if args.jni_rlib.suffix != ".rlib" or not args.jni_rlib.name.startswith("libjni-"):
        parser.error("select the actual compiled JNI rlib")
    if digest(args.jni_rlib) != args.expected_jni_sha256:
        parser.error("selected JNI artifact bytes changed")
    source_repo = args.source.parents[5]
    if source_repo == args.output_root or source_repo in args.output_root.parents:
        parser.error("output must be outside the source repository")
    # Never write into the selected compiler artifact/cache tree.
    if (args.jni_rlib.parent.name != "deps"
            or args.jni_rlib.parent.parent.name != "release"
            or args.jni_rlib.parent.parent.parent.name != "aarch64-linux-android"):
        parser.error("select the existing release Android dependency directory")
    cache_root = args.jni_rlib.parent.parents[4]
    if cache_root == args.output_root or cache_root in args.output_root.parents:
        parser.error("output must be outside the selected cache root")
    host_deps, host_selection = host_dependencies(args, parser)
    if not args.output_root.parent.is_dir():
        parser.error("existing output parent required")
    incoming = args.source.parent / "incoming_fresh_phase.rs"
    raw = args.source.read_bytes()
    source = raw.decode("utf-8-sig").replace("\r\n", "\n")
    unit, bodies = compile_unit(source, incoming)
    args.output_root.mkdir()
    code = args.output_root / "production_jni_lifetime.rs"
    code.write_text(unit, encoding="utf-8", newline="\n")
    print(json.dumps({"bridge_sha256": hashlib.sha256(raw).hexdigest(),
                      "incoming_sha256": digest(incoming),
                      "jni_rlib_sha256": digest(args.jni_rlib),
                      "host_dependency_selection_sha256": args.expected_host_selection_sha256,
                      "host_dependencies": host_selection,
                      "extracted_methods": bodies,
                      "generated_sha256": digest(code),
                      "scope": "actual JNI types and production-method lifetime check only"}),
          flush=True)
    result = subprocess.run([
        str(args.rustc), "--edition=2021", "--crate-type", "lib",
        "--target", "aarch64-linux-android", "--emit=metadata",
        "--extern", "jni=" + str(args.jni_rlib),
        "-L", "dependency=" + str(args.jni_rlib.parent),
        "-L", "dependency=" + str(host_deps),
        str(code), "-o", str(args.output_root / "production_jni_lifetime.rmeta")
    ], check=False)
    # Retain the real compiler outcome and refuse a concurrently changed selection.
    host_dependencies(args, parser)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
