"""Bounded Bleak client for the fixed Quest shell-capability BLE protocol."""

import argparse
import asyncio
from datetime import datetime, timezone
import hmac
import json
from pathlib import Path
import socket
import time

from bleak import BleakClient, BleakScanner

SERVICE = "b11c0001-7a2b-4c3d-9e0f-112233445566"
RX = "b11c0002-7a2b-4c3d-9e0f-112233445566"
TX = "b11c0003-7a2b-4c3d-9e0f-112233445566"
FRAME = 20


def endpoint(value):
    host, separator, raw_port = value.rpartition(":")
    if not separator or not host:
        raise argparse.ArgumentTypeError("endpoint must be host:port")
    try:
        port = int(raw_port)
    except ValueError as error:
        raise argparse.ArgumentTypeError("endpoint port must be an integer") from error
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("endpoint port is out of range")
    return host, port


async def exercise(token_hex, secret_hex, output, target):
    token, secret = bytes.fromhex(token_hex), bytes.fromhex(secret_hex)
    if len(token) != 16 or len(secret) != 32:
        raise ValueError("token_or_secret")
    host, port = target
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    events = []
    authenticated = False
    mutation_started = False
    off_started = None
    off_observations = []

    def persist():
        (output / "ble-events.json").write_text(
            json.dumps(events, indent=2) + "\n", encoding="utf-8"
        )

    def record(kind, **fields):
        event = {
            "kind": kind,
            "utc": datetime.now(timezone.utc).isoformat(),
            "host_monotonic": time.monotonic(),
            **fields,
        }
        events.append(event)
        persist()

    def request(operation, sequence):
        header = bytes([1, operation]) + sequence.to_bytes(2, "big")
        return header + hmac.digest(secret, b"C" + token + header, "sha256")[:16]

    def parse_reply(value):
        if len(value) != FRAME or value[0] != 1:
            raise RuntimeError("reply_shape_or_version")
        expected = hmac.digest(secret, b"R" + token + value[:4], "sha256")[:16]
        if not hmac.compare_digest(value[4:], expected):
            raise RuntimeError("reply_mac")
        return value[1], int.from_bytes(value[2:4], "big")

    def tcp_probe():
        started = time.monotonic()
        try:
            with socket.create_connection((host, port), timeout=1.0):
                return {"connected": True, "elapsed_seconds": time.monotonic() - started}
        except OSError as error:
            return {
                "connected": False,
                "elapsed_seconds": time.monotonic() - started,
                "error_type": type(error).__name__,
                "errno": error.errno,
            }

    found = await BleakScanner.discover(
        timeout=6.0, return_adv=True, service_uuids=[SERVICE]
    )
    if not isinstance(found, dict):
        raise RuntimeError("bleak_discovery_shape")
    candidates = [
        (device, advertisement)
        for device, advertisement in found.values()
        if SERVICE in [item.lower() for item in advertisement.service_uuids]
    ]
    record("discovery", candidate_count=len(candidates))
    if not candidates or len(candidates) > 4:
        raise RuntimeError("bounded_candidate_set")

    # At most one reconnect per candidate is allowed before authentication.
    for device, _advertisement in [candidate for candidate in candidates for _ in range(2)]:
        phase = "connect_and_discover_services"
        try:
            async with BleakClient(
                device,
                services=[SERVICE],
                timeout=10.0,
                winrt={"use_cached_services": False},
            ) as client:
                phase = "read_ready"
                ready = bytes(
                    await asyncio.wait_for(client.read_gatt_char(TX, use_cached=False), 8)
                )
                phase = "authenticate_ready"
                outcome, sequence = parse_reply(ready)
                if (outcome, sequence) != (0x10, 0):
                    raise RuntimeError("fresh_ready")
                authenticated = True
                phase = "authenticated_exchange"
                record("target_authenticated")

                async def exchange(label, frame, expected_outcome, expected_sequence):
                    record(label + "_sent", ble_connected=bool(client.is_connected))
                    await asyncio.wait_for(
                        client.write_gatt_char(RX, frame, response=True), 10
                    )
                    for _ in range(30):
                        value = bytes(
                            await asyncio.wait_for(
                                client.read_gatt_char(TX, use_cached=False), 5
                            )
                        )
                        actual_outcome, actual_sequence = parse_reply(value)
                        if (actual_outcome, actual_sequence) == (
                            expected_outcome,
                            expected_sequence,
                        ):
                            record(
                                label + "_verified",
                                outcome=actual_outcome,
                                sequence=actual_sequence,
                                ble_connected=bool(client.is_connected),
                            )
                            return
                        await asyncio.sleep(0.1)
                    raise RuntimeError(label + "_reply_timeout")

                wrong = bytearray(request(1, 1))
                wrong[-1] ^= 1
                await exchange("wrong_mac", bytes(wrong), 0xE1, 1)
                await exchange("noop", request(1, 1), 1, 1)
                await exchange("replay", request(1, 1), 0xE2, 1)
                mutation_started = True
                await exchange("wifi_off", request(2, 2), 2, 2)
                off_started = time.monotonic()
                until = off_started + 10.0
                while time.monotonic() < until:
                    observation = await asyncio.to_thread(tcp_probe)
                    observation["offset_seconds"] = time.monotonic() - off_started
                    observation["ble_connected"] = bool(client.is_connected)
                    off_observations.append(observation)
                    record("off_tcp_probe", **observation)
                    if not observation["ble_connected"]:
                        raise RuntimeError("ble_lost_while_off")
                    await asyncio.sleep(max(0.0, min(1.0, until - time.monotonic())))
                if not off_observations or any(item["connected"] for item in off_observations):
                    raise RuntimeError("tcp_remained_reachable_while_off")
                await exchange("wifi_resume", request(3, 3), 3, 3)
                result = {
                    "schema": "rusty.quest.shell_caps_ble_client.v1",
                    "authenticated": True,
                    "off_confirmed": True,
                    "resume_confirmed": True,
                    "off_interval_seconds": time.monotonic() - off_started,
                    "off_tcp_observations": off_observations,
                    "events": events,
                }
                (output / "result.json").write_text(
                    json.dumps(result, indent=2) + "\n", encoding="utf-8"
                )
                return result
        except Exception as error:
            record(
                "candidate_error",
                authenticated=authenticated,
                mutation_started=mutation_started,
                phase=phase,
                error_type=type(error).__name__,
                error=str(error)[:500],
            )
            if authenticated:
                raise
    raise RuntimeError("no_authenticated_candidate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token", required=True, choices=None)
    parser.add_argument("--secret", required=True, choices=None)
    parser.add_argument("--endpoint", required=True, type=endpoint)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not __import__("re").fullmatch(r"[0-9a-f]{32}", args.token):
        parser.error("token must be 32 lowercase hex characters")
    if not __import__("re").fullmatch(r"[0-9a-f]{64}", args.secret):
        parser.error("secret must be 64 lowercase hex characters")
    asyncio.run(exercise(args.token, args.secret, args.output, args.endpoint))


if __name__ == "__main__":
    main()
