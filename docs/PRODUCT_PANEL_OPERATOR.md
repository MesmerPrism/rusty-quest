# Product panel operator

The complete product panel retains its original view builders and action
owners when its views are hosted by the native mesh carrier. The existing
session, breath, Polar and LSL command tools remain the semantic operators for
those owners. `tools/Invoke-ProductPanelControl.ps1` fills the presentation,
particle field-patch and local BLE/network policy gaps through the same panel
callbacks. Simulated pointer input is an additional input test surface.

The command receiver is the existing exact-package, DUMP-protected
`BreathCompositionCommandReceiver`. The selected panel source closure must
include `ProductPanelOperator.java`. The active production Activity registers
an owner token; replacing or destroying that owner retires its token.

## Requests and readback

Begin with a UTF-8 JSON request file:

```json
{"schema":"rusty.quest.product_panel.command.v1","operation":"status"}
```

Run the CLI with the exact device transport, canonical serial, current boot ID,
package, hash-pinned ADB binary, request path and a create-new output directory.
PowerShell 7.6 and Windows ACL support are required. The output directory's
parents must already exist. The CLI verifies device identity before and after
one ordered broadcast; it does not launch the app or retry an action.

Read `app-receipt.json` in the private output directory. A current status
contains the `owner_token`, current route/page/topic/scroll, page choices,
particle readback, BLE policy and secret-free network state. Use that token
and a new 16-character lowercase hexadecimal request ID for an invocation:

```json
{"schema":"rusty.quest.product_panel.command.v1","operation":"invoke","owner_token":"<current 32-character token>","request_id":"0123456789abcdef","action":"navigate","parameters":{"surface":"experimenter","page":"prepare"}}
```

The schema and every action's parameter object are closed shapes. Available
actions are:

| Action | Parameters |
| --- | --- |
| `panel-visible` | Boolean `visible`; current mesh owner only, with original generation/revision and submitted-frame evidence in `panel_visibility` |
| `navigate` | `surface`: `experimenter` with `page`: `prepare`, `controls`, `condition`, `session`, `polar`; or `developer` with `page`: `home`, `particles`, `breath`, `polar`, `lsl`, `status` |
| `scroll` | Integer `delta_y`, between -8192 and 8192 |
| `particle-patch` | Nonempty `patch` object; the original particle owner validates its existing field names, bounds and revision |
| `ble-enabled`, `ble-open-control` | Boolean `enabled` |
| `network-enroll` | Boolean `trusted_lan`, explicit `consent: true` |
| `network-direct-start` | `route`: `hotspot` or `wifi_direct` |
| `network-relay-start` | Nonempty `session_id` and `token`, positive integer `expiry` in Unix milliseconds |
| `network-join-details`, `network-stop`, `network-revoke` | Empty object |

Existing feature selection, permissions and owner validation still apply.
A permission request is reported as requested, not as a completed connection.
Enrollment credentials and join details are private results. The CLI protects
all output files with a current-user-only ACL and prints metadata only.

`dispatched` records that the owner callback returned. Its nested result and
fresh `observation` determine the actual effect; queued particle changes still
require their original renderer-effective revision. `pending`, `unknown` and
`rejected` remain distinct outcomes. A host transport failure can leave the
effect unknown even when the command was delivered.

Query a previously submitted request without repeating its action:

```json
{"schema":"rusty.quest.product_panel.command.v1","operation":"result","owner_token":"<same token>","request_id":"0123456789abcdef"}
```

Each owner retains up to 256 request IDs, recording an ID before calling its
handler. The same exact bytes return the cached dispatch and a fresh
observation; different bytes with the same ID are rejected. Entries are never
evicted to permit replay. Unknown outcomes must be investigated through result
and status queries, not a new action ID. Owner replacement prevents replay
under the old token and does not establish whether an earlier effect occurred.

## Validation

`ProductPanelOperatorTest` exercises callback, re-entry, no-replay, owner
replacement, thread and hostile-shape behavior on the actual Java protocol.
`tools/Test-ProductPanelControlCli.ps1` exercises the actual CLI using a private
fake ADB fixture, including identity mismatch, lost acknowledgement, receipt
correlation and credential-output protection. These host checks do not prove
device input, rendering or network connectivity.
