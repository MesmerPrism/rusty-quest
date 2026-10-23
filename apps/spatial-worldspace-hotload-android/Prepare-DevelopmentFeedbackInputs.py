"""Create a new public CPU scene packet. No private assets, rig, backend or SDK build."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)


def mask():
    rows = bytearray()
    for y in range(1024):
        rows.append(0)
        for x in range(1024):
            value = 255 if (x % 128 - 63.5) ** 2 + (y % 128 - 63.5) ** 2 <= 56 ** 2 else 0
            rows.extend((value, value, value, 255))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1024, 1024, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    output = args.output.absolute()
    if output.exists():
        parser.error("Output must be create-new; existing packets are immutable")
    if output.parent.resolve() != output.parent:
        parser.error("Output parent must be a physical absolute path")
    data = mask()
    asset = {"path": "workload/mask.png", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    manifest = {
        "schema": "rusty.quest.spatial_worldspace_hotload.development_scene.v1",
        "source": "public-procedural", "backend": "none", "handSource": "none",
        "atlasPacketVersion": 4, "handRecordLayout": 1, "maskAsset": asset["path"],
        "mainCapacity": 512, "tracerCapacity": 0, "retainedParticleCapacity": 0,
        "particlesOffset": 0, "particlesCount": 512, "auxiliaryOffset": 512, "auxiliaryCount": 512,
        "handsOffset": 1024, "handsCount": 0, "graftsOffset": 1024, "graftsCount": 0,
        "totalRecords": 1028, "initialVisibleCount": 64, "assets": [asset],
        "provenance": {"producer": Path(__file__).name,
                       "producerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       "mask": "64 repeated public circular grayscale tiles; integer bounds; opaque alpha",
                       "privateInputs": False},
    }
    destination = output / "assets/workload"
    destination.mkdir(parents=True, exist_ok=False)
    (destination / "mask.png").write_bytes(data)
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "prepared-public-scene", "output": str(output),
                      "manifestSha256": hashlib.sha256((destination / "manifest.json").read_bytes()).hexdigest(),
                      "backend": "none", "privateInputs": False, "python": sys.version.split()[0]}))


if __name__ == "__main__":
    main()
