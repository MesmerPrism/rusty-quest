from pathlib import Path


def join_token(*parts: str) -> str:
    return "".join(parts)


FORBIDDEN = (
    "RUSTY_XR_",
    "rusty.xr.",
    "/rustyxr/v1",
    "rusty-xr/android-libstd-packaging",
    "MesmerPrism/Rusty-XR",
    "S:\\Work\\tmp\\",
    "S:/Work/tmp/",
    join_token("Rusty", "-Symmetric", "-Morpho", "vision"),
    join_token("Morpho", "vision"),
    join_token("RUSTY_", "KURA", "MOTO"),
    join_token("Kura", "moto"),
    join_token("kura", "moto"),
    join_token("KURA", "MOTO"),
    join_token("private", "_anchor", "_payload"),
    join_token("PRIVATE", "_ANCHOR", "_PAYLOAD"),
    join_token("private", "Anchor", "Payload"),
    join_token("Private", "Anchor", "Payload"),
    join_token("private", "_anchor", "_particles"),
    join_token("PRIVATE", "_KURA", "MOTO"),
    join_token("private", "_kura", "moto"),
    join_token("private", "Kura", "moto"),
    join_token("movement", "_coupling"),
    join_token("movement", "_base", "_frequency", "_hz"),
    join_token("movement", "Base", "Frequency", "Hz"),
    join_token("movement", "Coupling"),
    join_token("native", "-kura", "moto"),
    join_token("low", "-energy"),
    join_token("high", "-energy"),
    join_token("movement", "-only"),
)

SCAN_SUFFIXES = {".java", ".json", ".kt", ".kts", ".glsl", ".md", ".ps1", ".rs", ".toml", ".xml"}
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "local-artifacts", "target"}


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    failures: list[str] = []
    for path in root.rglob("*"):
        if any(part in SKIP_DIRS for part in path.parts):
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
