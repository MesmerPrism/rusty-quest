# Native renderer LSL operator

`tools/Invoke-NativeRendererLslPanel.ps1` projects the existing panel LSL
commands through its exact-package, DUMP-protected ordered receiver. It uses
the same native config parser and runtime reducer as the original panel.
It does not launch an app, change permissions, create a test outlet, or
automatically repeat a command.

The default `-PersistenceScope runtime-only` matches the current original
panel: Apply, Disable and Reset affect the current runtime without writing or
deleting the saved next-launch config. Explicit `-PersistenceScope persistent`
retains the older receiver's save/reset behavior. Existing broadcast callers
that omit `persistence_scope` retain their persistent behavior.

Use a new private output directory for each invocation. Supply the exact
existing ADB path and SHA-256, device transport, canonical serial, boot ID,
package and request file:

```powershell
pwsh -NoProfile -File tools/Invoke-NativeRendererLslPanel.ps1 `
  -Device $transport -CanonicalSerial $serial -BootId $boot `
  -Package $package -Adb $adb -AdbSha256 $adbSha256 `
  -RequestPath $request -OutputDirectory $newReceiptDirectory
```

A status request contains only:

```json
{"schema":"rusty.quest.native_renderer.lsl.panel_command.v1","operation":"status"}
```

The private `app-receipt.json` returns the actual `app_session_id`,
`generation` and current `config`. For a mutation, retain a fresh request ID,
that exact app-session ID and a generation greater than the observed one.
`apply` additionally contains the complete config accepted by the original
panel parser; `disable` and `reset` omit `config`.

| Operation | Required fields beyond schema and operation |
| --- | --- |
| `status` | None |
| `apply` | `request_id`, `app_session_id`, `generation`, `config` |
| `disable`, `reset` | `request_id`, `app_session_id`, `generation` |

The CLI retains request digest, raw broadcast and app response in a
current-user-only directory. Standard output contains disposition and file
locations. Accepted mutations must match the original request ID, session,
generation and persistence scope. `accepted` proves reducer admission;
effective worker state additionally requires a fresh status whose
`effective.last_effective_request_id` matches the original request.

An unknown outcome never authorizes resending the command. Keep its exact
bytes and inspect fresh status without another mutation. If later activity
has replaced the matching readback, preserve the unknown result. Backend,
network and actual inlet/outlet availability remain the original runtime's
responsibility.
