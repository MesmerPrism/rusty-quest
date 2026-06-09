from pathlib import Path

FORBIDDEN = (
    "RUSTY_XR_",
    "rusty.xr.",
    "/rustyxr/v1",
    "rusty-xr/android-libstd-packaging",
    "MesmerPrism/Rusty-XR",
)

SCAN_SUFFIXES = {".rs", ".toml", ".json", ".ps1"}


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    failures: list[str] = []
    for path in root.rglob("*"):
        if "target" in path.parts or ".git" in path.parts:
            continue
        if path.name == "legacy-property.profile.json":
            continue
        if path.suffix not in SCAN_SUFFIXES:
            continue
        text = path.read_text(encoding="utf-8")
        for token in FORBIDDEN:
            if token in text:
                failures.append(f"{path.relative_to(root)} contains forbidden token {token}")
    if failures:
        print("\n".join(failures))
        return 1
    print("Rusty Quest boundary scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

