import argparse
import asyncio
import json
import pathlib
import tempfile
import types
import unittest
from unittest.mock import patch

import probe


def status(epoch="1", read="10", generation=1, revision=0, age=10):
    return {"v": 1, "g": generation, "r": revision,
            "o": {"v": 1, "s": "observed", "e": epoch, "i": read,
                  "g": generation, "r": revision, "a": age}}


class ObservationTests(unittest.TestCase):
    def test_canonical_identifiers_and_integer_version(self):
        for value in ("0", "-1", "01", "9223372036854775808", "١", 1, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                probe.positive_int64(value)
        self.assertEqual(probe.positive_int64("9223372036854775807"), 9223372036854775807)
        for location in ("v", "o"):
            item = status()
            if location == "v":
                item["v"] = True
            else:
                item["o"]["v"] = True
            with self.assertRaises(ValueError):
                probe.Observation().inspect(item, 0)

    def test_unknown_and_malformed_require_a_strictly_new_read(self):
        for invalid in ({"v": 1, "g": 1, "r": 0, "o": {"v": 1, "s": "unknown"}},
                        {"v": 1, "g": 1, "r": 0, "o": {}},
                        status(read="9")):
            with self.subTest(invalid=invalid):
                observation = probe.Observation()
                observation.inspect(status(), 0)
                try:
                    observation.inspect(invalid, 0.1)
                except ValueError:
                    pass
                self.assertFalse(observation.available)
                self.assertEqual((observation.epoch, observation.read), (1, 10))
                with self.assertRaisesRegex(ValueError, "replayed"):
                    observation.inspect(status(), 0.2)
                self.assertTrue(observation.inspect(status(read="11"), 0.3)["available"])

    def test_repeated_read_cannot_reset_age_or_state(self):
        observation = probe.Observation(1000)
        observation.inspect(status(age=100), 0)
        self.assertEqual(observation.inspect(status(age=0), 0.5)["age_ms_lower_bound"], 600)
        with self.assertRaisesRegex(ValueError, "age"):
            observation.inspect(status(age=0), 1.5)
        with self.assertRaisesRegex(ValueError, "replayed"):
            observation.inspect(status(age=0), 1.6)
        self.assertEqual(observation.inspect(status(read="11", age=5), 1.7)["age_ms_lower_bound"], 5)
        with self.assertRaisesRegex(ValueError, "changed"):
            observation.inspect(status(read="11", revision=1), 1.8)

    def test_epoch_state_and_join_failures_preserve_highwater(self):
        observation = probe.Observation()
        observation.inspect(status(epoch="2", generation=2, revision=3), 0)
        bad_join = status(epoch="2", read="11", generation=2, revision=3)
        bad_join["o"]["r"] = 4
        for bad in (bad_join, status(epoch="1"), status(epoch="2", read="11", generation=1),
                    status(epoch="2", read="11", generation=2, revision=True),
                    status(epoch="2", read="11", generation=probe.SAFE_INTEGER + 1)):
            with self.assertRaises(ValueError):
                observation.inspect(bad, 0.1)
            self.assertEqual((observation.epoch, observation.read), (2, 10))
        self.assertTrue(observation.inspect(status(epoch="3", read="1", generation=0), 1)["available"])
        self.assertTrue(probe.Observation().inspect(status(read="1"), 0)["available"])

    def test_strict_json_and_raw_bounds(self):
        for raw in (b'{"v":1,"v":1}', b'{"a":NaN}', b'[]', b'\xff', b' ' * 481):
            with self.subTest(raw=raw), self.assertRaises((ValueError, UnicodeError)):
                probe.decode_status(raw)
        _, parsed = probe.decode_status(json.dumps(status()).encode())
        self.assertEqual(parsed, status())


def advertisement(name=None, service_uuids=None):
    return types.SimpleNamespace(local_name=name, service_uuids=service_uuids or [])


class SelectionTests(unittest.TestCase):
    def test_windows_last_system_advertisement_keeps_exact_address_selection(self):
        device = types.SimpleNamespace(address="PEER-A", name="Example Headset")
        inventory = {"peer": (device, advertisement(service_uuids=["system-uuid"]))}
        self.assertIs(probe.select_device(inventory, "peer-a", "Example Headset")[0], device)
        with self.assertRaises(ValueError):
            probe.select_device(inventory, "PEER-B")
        with self.assertRaises(ValueError):
            probe.select_device(inventory, "PEER-A", "Other Headset")
        with self.assertRaises(ValueError):
            probe.select_device({**inventory, "duplicate": inventory["peer"]}, "PEER-A")

    def test_gatt_membership_and_read_property_are_mandatory(self):
        member = types.SimpleNamespace(uuid=probe.STATUS, properties=["read"])
        service = types.SimpleNamespace(characteristics=[member])
        collection = types.SimpleNamespace(get_service=lambda _: service,
                                           get_characteristic=lambda _: member)
        client = types.SimpleNamespace(services=collection)
        self.assertIs(probe.status_characteristic(client, probe.SERVICE, probe.STATUS), member)
        service.characteristics = []
        with self.assertRaises(ValueError):
            probe.status_characteristic(client, probe.SERVICE, probe.STATUS)
        service.characteristics = [member]
        member.properties = ["write"]
        with self.assertRaises(ValueError):
            probe.status_characteristic(client, probe.SERVICE, probe.STATUS)

    def test_actual_windows_boolean_proxy_needs_conversion(self):
        try:
            from bleak.backends.winrt.client import BleakClientWinRT
        except ImportError:
            self.skipTest("optional Windows Bleak backend absent")
        proxy = BleakClientWinRT._DeprecatedIsConnectedReturn(True)
        with self.assertRaises(TypeError):
            json.dumps({"connected": proxy})
        self.assertEqual(json.loads(json.dumps({"connected": bool(proxy)})), {"connected": True})


class RuntimeTests(unittest.IsolatedAsyncioTestCase):
    async def exercise(self, *, cycles=1, disconnect_timeout=False, invalid_raw=None, disconnect_during_read=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        output = pathlib.Path(temporary.name) / "run"
        args = probe.arguments(["--output", str(output), "--address", "PEER-A", "--cycles", str(cycles)])
        # Host-only timing seam; no scanner, client, address or radio operation is real.
        args.seconds, args.interval, args.cycle_gap, args.disconnect_timeout = 0.02, 0.001, 0, 0.001
        args.minimum_advances = 1
        device = types.SimpleNamespace(address="PEER-A", name="Example Headset")

        class Scanner:
            scans = 0

            @classmethod
            async def discover(cls, **_):
                cls.scans += 1
                return {"peer": (device, advertisement())}

        class Client:
            def __init__(self, *_args, **_kwargs):
                self.is_connected = False
                self.read = 0
                member = types.SimpleNamespace(uuid=probe.STATUS, properties=["read"])
                service = types.SimpleNamespace(characteristics=[member])
                self.services = types.SimpleNamespace(get_service=lambda _: service,
                                                     get_characteristic=lambda _: member)

            async def connect(self):
                self.is_connected = True

            async def read_gatt_char(self, _, **options):
                if options != {"use_cached": False}:
                    raise AssertionError("Actual status read must select uncached observation")
                self.read += 1
                if disconnect_during_read:
                    self.is_connected = False
                return invalid_raw if invalid_raw is not None else json.dumps(status(read=str(self.read))).encode()

            async def disconnect(self):
                if disconnect_timeout:
                    await asyncio.sleep(60)
                self.is_connected = False

            # Intentionally no write, notification, pairing or command method exists.

        result = await probe.run(args, Scanner, Client, "host-surrogate")
        return result, output, Scanner.scans

    async def test_each_reconnect_requires_fresh_discovery_and_snapshots_inputs(self):
        result, output, scans = await self.exercise(cycles=2)
        self.assertTrue(result["success"], result)
        self.assertEqual(scans, 2)
        self.assertEqual((output / "probe-source.py").read_bytes(), pathlib.Path(probe.__file__).read_bytes())
        self.assertTrue((output / "configuration.json").exists())
        self.assertFalse(result["browser_or_command_qualification"])
        self.assertFalse(result["physical_readiness_qualification"])
        self.assertTrue(result["native_observation_passed"])

    async def test_disconnect_timeout_retains_failure_and_prevents_reconnect(self):
        result, output, scans = await self.exercise(cycles=2, disconnect_timeout=True)
        self.assertFalse(result["success"])
        self.assertEqual(scans, 1)
        self.assertFalse(result["cycles"][0]["disconnect_confirmed"])
        self.assertTrue(any("TimeoutError" in item for item in result["cycles"][0]["errors"]))
        self.assertTrue((output / "RESULT.json").exists())
        self.assertIn('"kind":"disconnect_error"', (output / "events.jsonl").read_text())

    async def test_malformed_json_keeps_bounded_utf8_raw_but_invalid_utf8_does_not(self):
        result, output, _ = await self.exercise(invalid_raw=b"{malformed}")
        self.assertFalse(result["success"])
        self.assertEqual((output / "cycle-1-sample-1.bin").read_bytes(), b"{malformed}")
        result, output, _ = await self.exercise(invalid_raw=b"\xff")
        self.assertFalse(result["success"])
        self.assertEqual(list(output.glob("*.bin")), [])

    async def test_lost_transport_cannot_admit_completed_read(self):
        result, output, scans = await self.exercise(cycles=2, disconnect_during_read=True)
        self.assertFalse(result["success"])
        self.assertFalse(result["native_observation_passed"])
        self.assertEqual(scans, 1)
        self.assertEqual(result["cycles"][0]["samples"], 0)
        self.assertTrue((output / "cycle-1-sample-1.bin").exists())
        self.assertTrue(any("readback admission" in item for item in result["cycles"][0]["errors"]))


class ArgumentTests(unittest.TestCase):
    def test_nonfinite_and_unbounded_timing_are_rejected_before_dependency_or_radio(self):
        for option, value in (("--seconds", "nan"), ("--disconnect-timeout", "136"),
                              ("--cycles", "0"), ("--scan-seconds", "inf")):
            with self.subTest(option=option), patch("sys.stderr"), self.assertRaises(SystemExit):
                probe.arguments(["--output", "private-output", "--address", "PEER-A", option, value])


if __name__ == "__main__":
    unittest.main()
