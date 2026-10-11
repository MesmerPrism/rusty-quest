"""Bounded read-only RQEC1 BLE diagnostic. No pairing or command writes."""
import argparse
import asyncio
import datetime
import hashlib
import importlib.metadata
import json
import math
import pathlib
import sys
import time

SERVICE = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020001"
STATUS = "9a7b2001-7d6a-4b7f-9d4a-6f7c0a020002"
SAFE_INTEGER = 9007199254740991


def positive_int64(value):
    if not isinstance(value, str) or not value.isascii() or not value.isdecimal():
        raise ValueError("observation identifier must be canonical decimal string")
    number = int(value)
    if str(number) != value or not 0 < number <= 9223372036854775807:
        raise ValueError("invalid observation identifier")
    return number


def integer(value, minimum=0):
    if type(value) is not int or not minimum <= value <= SAFE_INTEGER:
        raise ValueError("invalid safe integer")
    return value


def decode_status(raw):
    if len(raw) > 480:
        raise ValueError("status exceeds protocol 480-byte limit")
    text = raw.decode("utf-8", errors="strict")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON member")
            result[key] = value
        return result

    value = json.loads(text, object_pairs_hook=unique,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
    if not isinstance(value, dict):
        raise ValueError("status must be an object")
    return text, value


class Observation:
    """High waters survive invalidation; one instance belongs to one connection."""

    def __init__(self, maximum_age_ms=3000):
        self.epoch = self.read = self.advances = 0
        self.state = (0, 0)
        self.available = False
        self.age = 0
        self.delivered = None
        self.maximum_age_ms = maximum_age_ms

    def inspect(self, status, now=None):
        now = time.monotonic() if now is None else now
        try:
            if integer(status.get("v"), 1) != 1:
                raise ValueError("invalid status version")
            state = (integer(status.get("g")), integer(status.get("r")))
            observed = status.get("o")
            if observed is None:
                self.available = False
                return {"available": False, "reason": "legacy native observation absent"}
            if not isinstance(observed, dict) or integer(observed.get("v"), 1) != 1:
                raise ValueError("invalid observation version")
            if observed.get("s") == "unknown":
                if set(observed) != {"v", "s"}:
                    raise ValueError("unknown observation must be closed")
                self.available = False
                return {"available": False, "reason": "native observation unknown"}
            if observed.get("s") != "observed" or set(observed) != {"v", "s", "e", "i", "g", "r", "a"}:
                raise ValueError("invalid observed member shape")
            epoch, read = positive_int64(observed["e"]), positive_int64(observed["i"])
            if (integer(observed["g"]), integer(observed["r"])) != state:
                raise ValueError("native and outer state do not join")
            age = integer(observed["a"])
            if epoch < self.epoch or epoch == self.epoch and read < self.read:
                raise ValueError("native observation regressed")
            if epoch == self.epoch and state < self.state:
                raise ValueError("native state regressed within epoch")
            advanced = epoch > self.epoch or read > self.read
            if not advanced:
                if not self.available:
                    raise ValueError("replayed read cannot restore cleared observation")
                if state != self.state:
                    raise ValueError("same native read changed its joined state")
                elapsed = now - self.delivered
                if elapsed < 0:
                    raise ValueError("host monotonic clock regressed")
                age = max(age, self.age + int(elapsed * 1000))
            if advanced and self.epoch:
                self.advances += 1
            self.epoch, self.read, self.state = epoch, read, state
            self.age, self.delivered = age, now
            if age > self.maximum_age_ms:
                raise ValueError("native age lower bound exceeds selected diagnostic limit")
            self.available = True
            return {"available": True, "epoch": epoch, "read": read, "advanced": advanced,
                    "age_ms_lower_bound": age, "delivery_delay_unknown": True}
        except (ValueError, TypeError, KeyError, AttributeError):
            self.available = False
            raise


def select_device(discovered, address, name=None):
    # Windows can retain the system advertisement last, omitting the app UUID.
    # Require fresh exact address/name here and actual service membership later.
    matches = [(device, adv) for device, adv in discovered.values()
               if device.address.casefold() == address.casefold()
               and (name is None or name in (adv.local_name, device.name))]
    if len(matches) != 1:
        raise ValueError("exactly one freshly discovered address/name match required")
    return matches[0]


def status_characteristic(client, service_uuid, status_uuid):
    service = client.services.get_service(service_uuid)
    characteristic = client.services.get_characteristic(status_uuid)
    if service is None or characteristic is None or "read" not in characteristic.properties or not any(
            item.uuid.casefold() == characteristic.uuid.casefold() for item in service.characteristics):
        raise ValueError("status characteristic is not a readable member of the selected service")
    return characteristic


async def run(args, scanner, client_factory, bleak_version):
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    source = pathlib.Path(__file__).read_bytes()
    (output / "probe-source.py").write_bytes(source)
    configuration = {key: value for key, value in vars(args).items()}
    config_bytes = (json.dumps(configuration, sort_keys=True, indent=2) + "\n").encode()
    (output / "configuration.json").write_bytes(config_bytes)
    started = time.monotonic()
    events = output / "events.jsonl"

    def record(kind, **fields):
        item = {"kind": kind, "elapsed_s": time.monotonic() - started,
                "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), **fields}
        with events.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(item, separators=(",", ":")) + "\n")

    result = {"schema": "rusty.quest.experiment_ble_status_diagnostic.v1",
              "bleak_version": bleak_version, "python_version": sys.version,
              "source_sha256": hashlib.sha256(source).hexdigest(),
              "configuration_sha256": hashlib.sha256(config_bytes).hexdigest(),
              "read_only": True, "cycles": [], "success": False,
              "browser_or_command_qualification": False, "physical_readiness_qualification": False,
              "observation_scope": "per connection; reconnect starts fresh high waters",
              "peer_identity_join": "explicit address requires independent peer evidence"}
    try:
        for cycle in range(args.cycles):
            row = {"cycle": cycle + 1, "samples": 0, "errors": [], "disconnect_confirmed": False}
            result["cycles"].append(row)
            discovered = await asyncio.wait_for(
                scanner.discover(timeout=args.scan_seconds, return_adv=True), args.scan_seconds + 5)
            device, advertisement = select_device(discovered, args.address, args.name)
            record("fresh_discovery", cycle=cycle + 1, address=device.address,
                   name=advertisement.local_name or device.name, service_uuids=advertisement.service_uuids)
            if args.scan_only:
                row["scan_only"] = True
                break
            options = {"timeout": args.connect_timeout}
            if sys.platform == "win32":
                options["winrt"] = {"use_cached_services": False}
            client = client_factory(device, **options)
            observation = Observation(args.maximum_age_ms)
            try:
                await asyncio.wait_for(client.connect(), args.connect_timeout)
                if not bool(client.is_connected):
                    raise ValueError("connect returned without connected transport")
                record("connected", cycle=cycle + 1, connected=bool(client.is_connected))
                characteristic = status_characteristic(client, args.service, args.status)
                record("gatt_membership", cycle=cycle + 1, service=args.service, status=characteristic.uuid)
                deadline = time.monotonic() + args.seconds
                while time.monotonic() < deadline:
                    if not bool(client.is_connected):
                        raise ValueError("transport disconnected during status polling")
                    raw = bytes(await asyncio.wait_for(client.read_gatt_char(characteristic, use_cached=False), args.read_timeout))
                    if len(raw) > 480:
                        raise ValueError("status exceeds protocol 480-byte limit")
                    text = raw.decode("utf-8", errors="strict")
                    raw_path = output / f"cycle-{cycle + 1}-sample-{row['samples'] + 1}.bin"
                    # Only strictly valid UTF-8 <=480-byte status is retained as raw status.
                    raw_path.write_bytes(raw)
                    record("raw_status", cycle=cycle + 1, raw_path=raw_path.name, bytes=len(raw),
                           sha256=hashlib.sha256(raw).hexdigest())
                    if not bool(client.is_connected):
                        raise ValueError("transport disconnected before status readback admission")
                    _, status = decode_status(raw)
                    try:
                        checked = observation.inspect(status)
                        if not checked["available"]:
                            row["errors"].append(checked["reason"])
                    except (ValueError, TypeError, KeyError, AttributeError) as error:
                        checked = {"available": False, "reason": str(error)}
                        row["errors"].append(str(error))
                    record("status", cycle=cycle + 1, raw_path=raw_path.name, bytes=len(raw), raw_utf8=text,
                           sha256=hashlib.sha256(raw).hexdigest(), status=status, observation=checked)
                    row["samples"] += 1
                    await asyncio.sleep(args.interval)
                row["advances"] = observation.advances
                if observation.advances < args.minimum_advances:
                    row["errors"].append("insufficient advancing native read completions")
            except Exception as error:
                observation.available = False
                row["errors"].append(type(error).__name__ + ": " + str(error))
                record("cycle_error", cycle=cycle + 1, error=row["errors"][-1])
            finally:
                try:
                    await asyncio.wait_for(client.disconnect(), args.disconnect_timeout)
                    row["disconnect_confirmed"] = not bool(client.is_connected)
                    record("disconnect_return", cycle=cycle + 1, connected=bool(client.is_connected))
                except Exception as error:
                    row["errors"].append("disconnect: " + repr(error))
                    record("disconnect_error", cycle=cycle + 1, error=repr(error))
            if row["errors"] or not row["disconnect_confirmed"]:
                break
            if cycle + 1 < args.cycles:
                await asyncio.sleep(args.cycle_gap)
        if args.scan_only:
            result["success"] = bool(result["cycles"] and result["cycles"][0].get("scan_only"))
        else:
            result["success"] = len(result["cycles"]) == args.cycles and all(
                not row["errors"] and row["disconnect_confirmed"] for row in result["cycles"])
    except Exception as error:
        record("error", error=type(error).__name__ + ": " + str(error))
        result["error"] = type(error).__name__ + ": " + str(error)
    finally:
        if pathlib.Path(__file__).read_bytes() != source:
            result["success"] = False
            result["error"] = "diagnostic source changed during execution"
        result["elapsed_s"] = time.monotonic() - started
        result["discovery_only"] = args.scan_only
        result["transport_status_read_observed"] = any(row["samples"] for row in result["cycles"])
        result["native_observation_passed"] = result["success"] and not args.scan_only
        result["events_sha256"] = hashlib.sha256(events.read_bytes()).hexdigest() if events.exists() else None
        (output / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="create-new private output directory")
    parser.add_argument("--address", required=True, help="exact independently joined peer address")
    parser.add_argument("--name", help="optional exact freshly observed chooser name")
    parser.add_argument("--service", default=SERVICE)
    parser.add_argument("--status", default=STATUS)
    parser.add_argument("--scan-only", action="store_true")
    for name, default in (("scan-seconds", 10), ("seconds", 30), ("interval", 1),
                          ("connect-timeout", 20), ("read-timeout", 5),
                          ("disconnect-timeout", 135), ("cycle-gap", 2)):
        parser.add_argument("--" + name, type=float, default=default)
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--maximum-age-ms", type=int, default=3000)
    parser.add_argument("--minimum-advances", type=int, default=2)
    args = parser.parse_args(argv)
    limits = {"scan_seconds": (1, 30), "seconds": (1, 120), "interval": (0.5, 5),
              "connect_timeout": (1, 30), "read_timeout": (1, 15),
              "disconnect_timeout": (1, 135), "cycle_gap": (0, 10),
              "cycles": (1, 5), "maximum_age_ms": (1, 30000), "minimum_advances": (1, 1000)}
    for name, (minimum, maximum) in limits.items():
        value = getattr(args, name)
        if not math.isfinite(value) or not minimum <= value <= maximum:
            parser.error(f"{name} must be finite and in {minimum}..{maximum}")
    if not args.address.strip() or len(args.address) > 128:
        parser.error("bounded nonempty exact address required")
    import uuid
    try:
        args.service, args.status = str(uuid.UUID(args.service)), str(uuid.UUID(args.status))
    except ValueError:
        parser.error("service/status must be full UUIDs")
    return args


if __name__ == "__main__":
    configuration = arguments()
    try:
        from bleak import BleakClient, BleakScanner
    except ImportError:
        raise SystemExit("Live diagnostics require the optional pinned Bleak dependency; see README.md")
    outcome = asyncio.run(run(configuration, BleakScanner, BleakClient, importlib.metadata.version("bleak")))
    print(json.dumps(outcome))
    raise SystemExit(0 if outcome["success"] else 1)
