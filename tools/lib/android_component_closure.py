"""Selected Android source/compiler/class/DEX closure; constant values stay private.

DEX layout: https://source.android.com/docs/core/runtime/dex-format
This product assertion grants no signing, lifecycle or runtime authority.
"""
import sys
sys.dont_write_bytecode = True
import argparse
import hashlib
import json
import pathlib
import re
import struct
import zipfile

SCHEMA = "rusty.quest.android_component_contract.v1"
DEVELOPMENT_FEEDBACK_SOURCES = ('main/java/org/rusty/quest/feedback/api/FeedbackModule.java', 'main/java/org/rusty/quest/feedback/ArtifactStore.java', 'main/java/org/rusty/quest/feedback/DexDefinitions.java', 'main/java/org/rusty/quest/feedback/GenerationLoader.java', 'main/java/org/rusty/quest/feedback/ModuleHost.java', 'main/java/org/rusty/quest/feedback/ConnectionObservationController.java', 'android/java/org/rusty/quest/feedback/AndroidDexLoader.java')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def json_sha(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    with pathlib.Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def need(predicate, code):
    if not predicate:
        raise ValueError(code)


def load(path):
    def unique(pairs):
        out = {}
        for key, value in pairs:
            need(key not in out, "duplicate-json-field")
            out[key] = value
        return out
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=unique)


def ordinary(path):
    path = pathlib.Path(path).absolute()
    for candidate in [path, *path.parents]:
        need(not candidate.is_symlink() and not getattr(candidate, "is_junction", lambda: False)(), "source-link")
    return path.resolve(strict=True)


def owned(root, relative):
    need(isinstance(relative, str) and "\\" not in relative and ":" not in relative, "source-path")
    parts = pathlib.PurePosixPath(relative)
    need(not parts.is_absolute() and relative == parts.as_posix() and
         all(part not in ("", ".", "..") for part in parts.parts), "source-path")
    path = ordinary(root / relative)
    need(path.is_relative_to(root) and path.is_file(), "source-path")
    return path


def inventory_sources(roots, variant):
    rows = []
    for key, value in sorted(roots.items()):
        root = ordinary(value)
        need(root.is_dir(), "source-root")
        for path in sorted(root.rglob("*")):
            if path.suffix not in (".kt", ".java"):
                continue
            relative = path.relative_to(root).as_posix()
            if key == "development_feedback" and relative not in DEVELOPMENT_FEEDBACK_SOURCES:
                continue
            if key == "application" and relative.split("/")[0] not in ("main", variant):
                continue
            path = owned(root, relative)
            rows.append(dict(root=key, path=relative, bytes=path.stat().st_size, sha256=file_sha(path)))
    return rows


def prepare(contract_path, feature_lock_path, expected_feature_lock_sha256, roots, variant):
    need(variant in ("debug", "release"), "variant")
    contract_path = ordinary(contract_path)
    feature_lock_path = ordinary(feature_lock_path)
    need(file_sha(feature_lock_path) == expected_feature_lock_sha256, "feature-lock-changed")
    contract = load(contract_path)
    lock = load(feature_lock_path)
    need(set(contract) == {"schema", "components"} and contract["schema"] == SCHEMA, "component-contract")
    selected = lock["selected_features"]
    need(isinstance(selected, list) and selected and all(isinstance(value, str) and value for value in selected) and
         len(selected) == len(set(selected)), "feature-selection")
    features = [row for row in lock["features"] if row["feature_id"] in selected]
    feature_ids = [row["feature_id"] for row in features]
    need(len(features) == len(selected) and len(feature_ids) == len(set(feature_ids)) and
         set(feature_ids) == set(selected) and all(row["selected"] is True for row in features), "feature-selection")
    selectors = [(row["module_id"], row["owner_lane"]) for row in features]
    need(len(selectors) == len(set(selectors)), "feature-selection")
    roots = {key: str(ordinary(value)) for key, value in roots.items() if value is not None}
    definitions = []
    components = []
    seen_ids = set()
    seen_selectors = set()
    for component in contract["components"]:
        need(set(component) == {"component_id", "module_id", "owner_lane", "definitions"}, "component-fields")
        identifier = component["component_id"]
        selector = (component["module_id"], component["owner_lane"])
        need(identifier not in seen_ids and selector not in seen_selectors, "component-duplicate")
        seen_ids.add(identifier)
        seen_selectors.add(selector)
        if selector not in selectors:
            continue
        need(component["definitions"], "component-definitions")
        components.append(identifier)
        for definition in component["definitions"]:
            need(set(definition) == {"root", "source", "class", "methods"}, "definition-fields")
            need(definition["root"] in roots and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+", definition["class"]), "definition-name")
            try:
                source = owned(pathlib.Path(roots[definition["root"]]), definition["source"])
            except (OSError, RuntimeError) as error:
                raise ValueError("definition-source-missing") from error
            need(source.suffix in (".kt", ".java"), "definition-source")
            seen_methods = set()
            for method in definition["methods"]:
                need(set(method) == {"name", "descriptor", "public", "static"} and
                     type(method["public"]) is bool and type(method["static"]) is bool and
                     re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", method["name"]) and
                     re.fullmatch(r"\([^)]*\).+", method["descriptor"]), "method-contract")
                signature = method["name"] + method["descriptor"]
                need(signature not in seen_methods, "method-duplicate")
                seen_methods.add(signature)
            definitions.append(dict(definition, component_id=identifier))
    need(set(selectors).issubset(seen_selectors), "component-coverage")
    sources = inventory_sources(roots, variant)
    expected = {(row["root"], row["path"]) for row in sources}
    need(all((row["root"], row["source"]) in expected for row in definitions), "definition-source-selection")
    value = dict(schema="rusty.quest.android_component_inventory.v1", contract_sha256=file_sha(contract_path),
        feature_lock_sha256=expected_feature_lock_sha256, variant=variant, roots=roots,
        selected_components=sorted(components), definitions=definitions, sources=sources)
    value["fingerprint"] = json_sha(value)
    return value


def verify_current_sources(inventory):
    need(inventory["schema"] == "rusty.quest.android_component_inventory.v1" and
         inventory["fingerprint"] == json_sha({key: value for key, value in inventory.items() if key != "fingerprint"}), "inventory-fingerprint")
    need(inventory_sources(inventory["roots"], inventory["variant"]) == inventory["sources"], "source-drift")


def verify_inputs(inventory, actual):
    need(actual["schema"] == "rusty.quest.android_component_gradle_inputs.v1" and
         actual["inventory_sha256"] == json_sha(inventory), "compiler-input-inventory")
    key = lambda row: (row["root"], row["path"])
    need(sorted(actual["sources"], key=key) == sorted(inventory["sources"], key=key), "compiler-input-source-closure")


class Reader:
    def __init__(self, data, endian):
        self.data = data
        self.endian = endian
        self.offset = 0

    def take(self, size):
        need(size >= 0 and self.offset + size <= len(self.data), "binary-bounds")
        out = self.data[self.offset:self.offset + size]
        self.offset += size
        return out

    def number(self, size):
        return int.from_bytes(self.take(size), self.endian)


def mutf8(data):
    return data.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")


def inspect_class(data):
    r = Reader(data, "big")
    need(r.number(4) == 0xCAFEBABE, "class-header")
    r.take(4)
    count = r.number(2)
    pool = [None] * count
    index = 1
    while index < count:
        tag = r.number(1)
        if tag == 1:
            pool[index] = mutf8(r.take(r.number(2)))
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = r.number(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            r.take(4)
        elif tag in (5, 6):
            r.take(8)
            index += 1
        elif tag == 15:
            r.take(3)
        else:
            raise ValueError("class-constant-tag")
        index += 1
    flags = r.number(2)
    name = pool[pool[r.number(2)]].replace("/", ".")
    r.take(2)
    r.take(2 * r.number(2))

    def attributes():
        out = {}
        for _ in range(r.number(2)):
            key = pool[r.number(2)]
            size = r.number(4)
            out[key] = r.take(size)
        return out

    for _ in range(r.number(2)):
        r.take(6)
        attributes()
    methods = {}
    for _ in range(r.number(2)):
        access = r.number(2)
        method = pool[r.number(2)] + pool[r.number(2)]
        attributes()
        need(method not in methods, "class-method-duplicate")
        methods[method] = access
    attrs = attributes()
    need(r.offset == len(data), "class-trailing")
    source = pool[int.from_bytes(attrs["SourceFile"], "big")] if "SourceFile" in attrs else None
    return dict(name=name, source=source, methods=methods, flags=flags)


def inspect_dex(data):
    need(data[:4] == b"dex\n" and data[4:7] in (b"035", b"037", b"038", b"039", b"040") and
         len(data) >= 112 and int.from_bytes(data[32:36], "little") == len(data) and
         int.from_bytes(data[36:40], "little") == 112 and data[40:44] == b"\x78\x56\x34\x12", "dex-header")

    def u32(offset):
        need(0 <= offset <= len(data) - 4, "dex-bounds")
        return struct.unpack_from("<I", data, offset)[0]

    def u16(offset):
        need(0 <= offset <= len(data) - 2, "dex-bounds")
        return struct.unpack_from("<H", data, offset)[0]

    def table(offset, stride):
        count, start = u32(offset), u32(offset + 4)
        need(start + count * stride <= len(data), "dex-table-bounds")
        return [start + i * stride for i in range(count)]

    def leb(offset):
        value = 0
        for shift in range(0, 35, 7):
            need(offset < len(data), "dex-leb-bounds")
            byte = data[offset]
            offset += 1
            need(shift < 28 or byte < 16, "dex-leb-overflow")
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("dex-leb")

    strings = []
    for offset in table(56, 4):
        _, start = leb(u32(offset))
        end = data.find(b"\x00", start)
        need(end >= start, "dex-string")
        strings.append(mutf8(data[start:end]))
    types = [strings[u32(offset)] for offset in table(64, 4)]
    protos = []
    for offset in table(72, 12):
        args = u32(offset + 8)
        parameters = []
        if args:
            length = u32(args)
            need(args + 4 + length * 2 <= len(data), "dex-proto")
            parameters = [types[u16(args + 4 + i * 2)] for i in range(length)]
        protos.append("(" + "".join(parameters) + ")" + types[u32(offset + 4)])
    methods = [(types[u16(offset)], strings[u32(offset + 4)] + protos[u16(offset + 2)]) for offset in table(88, 8)]
    classes = {}
    for offset in table(96, 32):
        descriptor = types[u32(offset)]
        name = descriptor[1:-1].replace("/", ".")
        need(descriptor.startswith("L") and descriptor.endswith(";") and name not in classes, "dex-class")
        defined = {}
        cursor = u32(offset + 24)
        if cursor:
            counts = []
            for _ in range(4):
                value, cursor = leb(cursor)
                counts.append(value)
            for _ in range(counts[0] + counts[1]):
                _, cursor = leb(cursor)
                _, cursor = leb(cursor)
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, cursor = leb(cursor)
                    access, cursor = leb(cursor)
                    _, cursor = leb(cursor)
                    index += delta
                    owner, method = methods[index]
                    need(owner == descriptor and method not in defined, "dex-method-owner")
                    defined[method] = access
        classes[name] = dict(methods=defined)
    return classes


def inspect_apk(apk):
    classes = {}
    entries = []
    with zipfile.ZipFile(apk) as archive:
        names = [name for name in archive.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
        need(names and len(names) == len(set(names)), "apk-dex-inventory")
        for name in sorted(names):
            data = archive.read(name)
            found = inspect_dex(data)
            need(not classes.keys() & found.keys(), "apk-duplicate-class")
            classes.update(found)
            entries.append(dict(path=name, sha256=hashlib.sha256(data).hexdigest()))
    return classes, entries


def required_methods(definition, observed):
    for method in definition["methods"]:
        signature = method["name"] + method["descriptor"]
        need(signature in observed["methods"], "required-method-absent")
        flags = observed["methods"][signature]
        need(bool(flags & 1) == method["public"] and bool(flags & 8) == method["static"], "required-method-access")


def verify_dex(inventory, apk):
    classes, entries = inspect_apk(apk)
    for definition in inventory["definitions"]:
        need(definition["class"] in classes, "required-dex-class-absent")
        required_methods(definition, classes[definition["class"]])
    return entries


def verify_compiled(inventory, actual, expected_inventory_sha256):
    need(json_sha(inventory) == expected_inventory_sha256, "prepared-inventory-changed")
    verify_current_sources(inventory)
    verify_inputs(inventory, actual)
    roots = [ordinary(root) for root in actual["class_roots"] if pathlib.Path(root).exists()]
    class_rows = []
    for definition in inventory["definitions"]:
        relative = definition["class"].replace(".", "/") + ".class"
        files = [root / relative for root in roots if (root / relative).is_file()]
        need(len(files) == 1, "required-class-output-absent-or-duplicate")
        path = ordinary(files[0])
        observed = inspect_class(path.read_bytes())
        need(observed["name"] == definition["class"] and observed["source"] == pathlib.PurePosixPath(definition["source"]).name, "class-source-binding")
        required_methods(definition, observed)
        class_rows.append(dict(name=definition["class"], sha256=file_sha(path), source=definition["source"]))
    verify_current_sources(inventory)
    return dict(schema="rusty.quest.android_component_jvm_compilation.v1", status="passed",
        contract_sha256=inventory["contract_sha256"], feature_lock_sha256=inventory["feature_lock_sha256"],
        inventory_sha256=json_sha(inventory), source_fingerprint=inventory["fingerprint"],
        gradle_inputs_sha256=json_sha(actual), class_outputs=class_rows,
        runtime_authority=False)


def verify(inventory, actual, apk, expected_inventory_sha256):
    compiled = verify_compiled(inventory, actual, expected_inventory_sha256)
    dex = verify_dex(inventory, apk)
    verify_current_sources(inventory)
    return dict(schema="rusty.quest.android_component_closure.v1", status="passed",
        contract_sha256=compiled["contract_sha256"], feature_lock_sha256=compiled["feature_lock_sha256"],
        inventory_sha256=compiled["inventory_sha256"], source_fingerprint=compiled["source_fingerprint"],
        gradle_inputs_sha256=compiled["gradle_inputs_sha256"], class_outputs=compiled["class_outputs"],
        dex=dex, apk_sha256=file_sha(apk), runtime_authority=False)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("prepare")
    for name in ("contract", "feature-lock", "expected-feature-lock-sha256", "private-root", "variant", "output"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--application-root")
    p.add_argument("--development-feedback-root")
    p = commands.add_parser("verify-sources")
    for name in ("inventory", "expected-inventory-sha256", "output"):
        p.add_argument("--" + name, required=True)
    for command in ("verify", "verify-compiled"):
        p = commands.add_parser(command)
        for name in ("inventory", "expected-inventory-sha256", "gradle-receipt", "output"):
            p.add_argument("--" + name, required=True)
        if command == "verify":
            p.add_argument("--apk", required=True)
    p = commands.add_parser("check-dex")
    for name in ("inventory", "apk", "output"):
        p.add_argument("--" + name, required=True)
    args = parser.parse_args()
    output = None if args.output == "-" else pathlib.Path(args.output)
    need(output is None or not output.exists(), "fresh-output-required")
    if args.command == "prepare":
        value = prepare(args.contract, args.feature_lock, args.expected_feature_lock_sha256,
            {"application": args.application_root, "private": args.private_root,
             "development_feedback": args.development_feedback_root}, args.variant)
    elif args.command == "verify-sources":
        need(file_sha(args.inventory) == args.expected_inventory_sha256, "prepared-inventory-changed")
        inventory = load(args.inventory)
        need(json_sha(inventory) == args.expected_inventory_sha256, "prepared-inventory-changed")
        verify_current_sources(inventory)
        value = dict(status="passed", scope="selected-source-currentness-only",
            inventory_sha256=args.expected_inventory_sha256, runtime_authority=False)
    elif args.command in ("verify", "verify-compiled"):
        need(file_sha(args.inventory) == args.expected_inventory_sha256, "prepared-inventory-changed")
        inventory, actual = load(args.inventory), load(args.gradle_receipt)
        value = (verify(inventory, actual, args.apk, args.expected_inventory_sha256) if args.command == "verify"
                 else verify_compiled(inventory, actual, args.expected_inventory_sha256))
    else:
        inventory = load(args.inventory)
        dex = verify_dex(inventory, args.apk)
        value = dict(schema="rusty.quest.android_component_dex_assertion.v1", status="passed",
            scope="DEX definitions only; source/compiler/runtime acceptance unverified",
            contract_sha256=inventory["contract_sha256"], inventory_sha256=json_sha(inventory), dex=dex, apk_sha256=file_sha(args.apk))
    if output is None:
        print(canonical(value).decode("ascii"))
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(canonical(value))
        print("Android component " + args.command + " passed")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, TypeError, OSError, struct.error, UnicodeError, zipfile.BadZipFile) as error:
        code = str(error) if isinstance(error, ValueError) and re.fullmatch(r"[a-z-]+", str(error)) else "component-observation-unavailable"
        raise SystemExit("Android component assertion failed: " + code)
