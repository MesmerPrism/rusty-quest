# Full experiment Wi-Fi status

The authenticated gateway's existing v1 `status` request returns an additive
`wifi_snapshot: 1` payload. The request/reply envelope, HMAC domains, command
schemas, original command IDs and receipt journal remain unchanged. The full
UTF-8 status payload is bounded to 16 KiB. Overflow is unavailable; required
fields and grants are never silently removed. Breath history keeps its separate
`breath-monitor` read route. The BLE serializer and its 480-byte budget remain
independent.

Every full payload contains these fields:

| Fields | Meaning |
| --- | --- |
| `v: 1`, `m: "gated"`, `wifi_snapshot: 1` | Existing version/mode and full Wi-Fi marker |
| `control_mode` | Explicit `choose`, `direct` or `remote` native operator mode |
| `o` | Exact v1 native observation described below |
| `p`, `g`, `r` | Parsed session phase, generation and revision |
| `c`, `rec`, `co`, `ms`, `st` | Condition, recording boolean, completion, active milliseconds and storage status |
| `pending` | App-owned original operation still being observed; boolean |
| `h` | Current app presentation, or `unknown` when not established |
| `pi` | Exact booleans `{ "r": required, "f": fresh, "h": pause_latched }` |

`pi` is mandatory even while idle or when Polar input is not required. Missing
or malformed native input booleans make full status unavailable. Other existing
fields, such as counts, audio/guidance readiness, armed preparation time and
bias, may remain in full form. Supplementary app presentation/readiness does not
replace session truth.

The exact observed `o` schema is
`{ "v": 1, "s": "observed", "e": "<runtime epoch>", "i": "<read completion nanos>", "g": generation, "r": revision, "a": age_ms }`.
Epoch and read ID are positive canonical int64 decimal strings. Generation and
revision are nonnegative JavaScript-safe integers; age is nonnegative elapsed
monotonic milliseconds. The full route refuses unavailable observations, so it
does not emit the compact `{ "v": 1, "s": "unknown" }` form with the marker.

One synchronous native status response supplies the existing production JSON
parser and read-only coordinator. Its completion witness is captured immediately
after that native call. `p/g/r/ms/rec/co/c/st`, `o` and `pi` come from that same
response. Age advances from the original witness through final serialization;
HTTP delivery never refreshes a cached body's witness. Regressed reads, invalid
clocks, observations older than 5000 ms, changed carrier/native lifetimes or mode
revisions refuse the full projection. The producer is reset when its native
carrier scope changes. A failed full projection clears its gateway grants.

Optional `pd:{v:1,native,java}` carries the bounded acquisition diagnostic
projection described in [Polar recovery](POLAR_SENSOR_RECOVERY.md#diagnostic-observations-over-wi-fi).
It includes the current native reason and separately retained original pause,
measured native/Java ages and counters, plus observed link/stream state. Unknown
fields and cached native ages are null. The original read/delivery witness still
ages the observation. Raw Polar owner objects, sensor identities and private
capture details never enter this projection. Older owners can omit `pd`; this
observation grants no command or stream capability.

Optional `q` session and `z` Polar grants retain their existing meanings, full
native fences and independent admission. Their native tuple comes from the same
read as the returned session state. Optional idle `y` retains the Polar setup
owner/revision schema. No compact BLE trimming is applied to any of them. A
status request can reconcile an original operation receipt; it does not dispatch
an experiment or acquisition command. Transport loss does not pause a native
experiment. Polar acquisition hold and explicit manual Resume retain their own
native policy.

Run `python tools/checks/test_experiment_wifi_snapshot.py --json-jar <host-json-jar>
--jdk-bin <jdk-bin>` for the production producer, serializer, parser/coordinator
and scan receipt controls. Platform I/O is inert. The mode and gateway suites
also retain authentication, original receipt, guest, Direct/Remote and dedupe
checks. Host checks do not prove physical LAN or sensor behavior.
