# Inert experiment status observation producer

`ExperimentSessionStatusObservation` projects the coordinator's atomic
`NativeStatusSnapshot` into the existing closed
`experiment.status.observation.v1` browser contract. It does not start a socket,
authenticate a controller, issue a grant or dispatch a command. A future adapter
must provide a separately authenticated channel and runtime epoch. A different
runtime epoch requires a new subscription/producer.

The source age is the floored lower bound since app-owned native status-read
completion. It does not measure physical media or display freshness, and omits
unknown network transit time. The wire value `fresh` means the source witness
is available; consumers must still apply their stale threshold. Foreground is
`unknown`, and completion is copied from the owner projection with
`NOT_REACHED` mapped explicitly to `NONE`. No ACK or UI poll becomes a read
witness or session completion.

A newer accepted native read advances the bounded sequence. Repeated polls
retain that sequence while age grows, so a browser cannot freshen retained data
by receiving repeated packets. Unavailable status emits a newer null projection
once, preserves the read/session high-watermarks, and cannot restore the same
read. Generation and revision regressions reject; a newer session may reset its
revision. Sequence exhaustion reserves the final safe value for unavailable
and stays closed. The public projection is bounded to 1024 UTF-8 bytes.

The producer is declared in the existing optional breath-panel source closure
but has no runtime caller yet. BLE UUIDs, HMAC, commands and existing snapshot
bytes are unchanged. A private exact source allowlist needs an explicit update
before a future build selects this additional Java source. The existing embedded
WebSocket remains loopback/read-only; this component does not widen that policy.

Run the focused host producer/coordinator tests with:

```powershell
pwsh -NoProfile -File tools/checks/Test-ExperimentSessionStatusObservation.ps1 -OutputRoot <new-output>
```

For cross-owner compatibility, additionally pass `-BrowserRepo` with the site
candidate and `-LegacyParser` with the exact prior browser parser. The harness
feeds actual Java producer output to both parsers and tests retained polling and
unavailable/replay behavior. Native receipts are modeled; these tests provide
no socket, credentials, headset or physical runtime qualification.
