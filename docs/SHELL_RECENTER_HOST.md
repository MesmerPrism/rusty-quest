# Shell recenter host provider

`tools/shell_recenter/quest_shell_recenter.py` is the fixed local transport
adapter for [the shared Android shell module](SHELL_RECENTER.md). Hostess and
the Kiosk developer utility consume this same provider; they do not implement
AppFunction calls. The provider supports exactly:

```text
python -I tools/shell_recenter/quest_shell_recenter.py readiness --request <private-request.json>
python -I tools/shell_recenter/quest_shell_recenter.py recenter --request <private-request.json>
python -I tools/shell_recenter/quest_shell_recenter.py reconcile --request <private-request.json>
```

Readiness starts a finite shell helper to inspect the real identity,
permission, API and enabled function without recentering. Recenter performs
one deliberate operation. Reconcile only reads the saved original's local
receipt and never contacts a device. Use a new request and output directory
for readiness and a different new request for recentering. An existing output
directory suppresses dispatch, including after completion or a crashed observer.
In addition, a durable per-user original journal at
`~/.rusty-quest/shell-recenter/originals` retains each request ID across output
directories and Quest checkouts. It binds the nonce, complete request hash,
output directory and mode before any helper can start. Changing the output
directory, nonce or profile cannot replay that original. Keep this journal
after completion; deleting evidence leaves the original held, never eligible
for another dispatch. This is local original ownership, not cross-host or
remote accepted-command authority. Another host requires its actual owner
coordination and fresh deliberate operation identity.

The private request has exactly these fields:

| Field | Value |
| --- | --- |
| `schema` | `rusty.quest.shell_recenter.request.v1` |
| `request_id`, `nonce` | Separate 32-character lowercase hexadecimal identities |
| `adb` | Explicit absolute executable path; its bytes are recorded and checked again before launch |
| `canonical_serial`, `selector` | Exact physical identity and selected current transport; no discovery or implicit target |
| `boot_id`, `operator_id` | Current canonical boot UUID and bounded operator identity |
| `artifact` | Exact `{path, sha256, bytes}` for the built dex JAR |
| `source` | Exact clean Quest `{revision, tree, source_id}`; source ID comes from the module build manifest |
| `firmware` | Exact `{fingerprint, vrshell_sha256, framework_sha256}` for the supported current target |
| `output_dir` | Fresh absolute canonical host directory beneath an existing parent |

The source inventory is checked against the current clean provider/module Git
revision and tree. Platform hashes are read only from the fixed VrShell APK and
framework JAR paths. The module independently checks its compiled source ID,
boot, firmware, genuine shell Application/context/attribution, normal permission
and exact API. Profiles, hashes and discovery do not grant custody or permission
to operate a headset. Coordinate calibrated scenes and the wearer beforehand.
This is an explicit developer/operator shell path, with no Store or ordinary APK
privilege claim.

`REQUEST.json`, `ACTIVE.json` and `LAUNCH_INTENT.json` preserve the original
before helper launch. The helper separately syncs its execute intent before its
single platform dispatch. The host keeps the original process and drains both
streams until genuine final, EOF and reap. Observation deadlines, cancellation
return and Ctrl+C cannot authorize retry or process killing. A callback or
observer failure remains held. A later original callback can complete normally.

The `rusty.quest.shell_recenter.result.v1` result binds `request_sha256` to the
SHA-256 of UTF-8 sorted-key JSON with compact separators plus one newline. It
retains request/nonce/boot/source/artifact identities and separate typed
`availability`, `dispatch`, `callback`, `effect`, `original` and `cleanup`
sections. `effect.state` remains `unverified`: callback success does not prove
pose or visible reference-frame change. Retain a separate app-owned pose or
wearer witness. Original errors and their codes remain in the final evidence.

`STATUS.json` is a complete atomic projection while the original is owned.
`RESULT.json` records final observation, including held/unknown failures. Only
confirmed process absence, both EOF, reap and exact owned-file/directory removal
permit retirement and removal of `ACTIVE.json`. Cleanup never recursively
deletes a namespace or removes another helper's files. A staged prelaunch
failure can remove its one owned artifact and fresh empty stage. Any unresolved
cleanup stays held for explicit original review; reconcile never hides it.

The fixed app_process staging/identity/lifecycle transport fills the provider
gap that ordinary install/launch APIs do not expose. It accepts no executable
arguments, Binder calls, intents, function names, device paths or shell text from
the request. No ADB daemon, transport recovery, APK install, menu, Guardian or
Home-button operation is implemented.

Run the production provider lifecycle suite with:

```text
python tools/shell_recenter/test_quest_shell_recenter.py
```

Synthetic transport and real child-process tests prove host contract behavior,
including late original callbacks, errors, replay suppression, source/firmware
identity and cleanup failure. They are not an on-headset result.
