# Cached effective-input observation

The debug-only embedded duplex operator has one additive read:
`cached_effective_inputs`. It exposes the already published ProcessHost
effective-input cache without initializing a host, selecting/freezing inputs,
reading a profile/store, invoking Native, allocating keys, enrolling, changing
visual controls or submitting an owner command. Existing endpoint/default
behavior is unchanged. DUMP permission and the existing shell-UID check apply.

This read is absent from earlier packages whose only external projection was
the `effective_input_owner` field of a visual hotload receipt. An in-process
cache getter alone is not an installed operator capability. Package this
additive route with the next independently useful APK; do not start a lifecycle
or build solely to obtain a missing historical observation.

## Closed request and result

The method's argument is a fresh 32-character lowercase hexadecimal challenge.
Its extras are exactly:

| Extra | Android content type | Meaning |
|---|---|---|
| `run_id` | `s` | Current actual selected run, 32 lowercase hexadecimal characters |
| `expected_pid`, `expected_uid` | `i` | Actual current app process and UID |
| `expected_start_ticks` | `s` | Positive decimal process birth ticks |
| `expected_boot_id` | `s` | Actual lowercase boot UUID |

No Original, path, caller JSON, capture/refresh flag or optional mutation fields
are accepted. The early provider branch precedes `withRunRequest` and host
construction. Physical PID/UID/birth/boot are checked against the current app
process. A selected-run mismatch refuses; an existing immutable observation
must match that exact cached run context, including its selection generation.
Concurrent cache/run replacement refuses rather than joining different owners.

The normal v3 transport envelope returns `status=observed`, the challenge/run,
and `receipt_base64` (UTF-8, standard Base64 without wrapping). The decoded
`rusty.quest.embedded_duplex.cached_effective_inputs.v1` result has:

- `observation_scope=cached_only`, actual physical `process`, requested and
  selected run ids, and cached run selection generation;
- `phase=uninitialized` and null selection/native configuration when the host
  or its matching input cache is absent; no first capture or disk reconstruction
  follows. With no host, selected run is null and selection generation is zero;
- otherwise cached `frozen` or `native_configured` phase, selected process
  generation/installed role, source `packaged` or `control_profile`, base
  manifest/route SHA-256, endpoint-source profile SHA-256 (nullable), effective
  route SHA-256 and the two effective peers' media/control protocol/IPv4/port;
- only when cached native configuration exists: actual previously published
  runtime-config SHA-256, native executor generation and app-record SHA-256;
- `socket_liveness_observed=false` in every phase.

The formatter whitelists these fields and enforces a 64 KiB input/output
limit. It never forwards raw route documents, private keys/tokens, journals,
device identifiers, filesystem paths or unknown cached fields. Process identity
and endpoint addresses remain caller-owned diagnostic evidence, not public
publication inputs.

Profile identity is the frozen endpoint selection's raw SHA only. The cache
does not retain profile id/revision, so this read does not invent them or reopen
the active file. `packaged` means the endpoint selection is packaged; it does
not assert that no visual-only profile exists. Code/source Git identity and
installed APK identity remain the current actor/caller's separately held
inputs. Frozen selection, Native configuration and live sockets are distinct
claims. A returned historical sample makes no new authority or socket proof.

## Reusable caller

`tools/embedded_duplex_cached_inputs.py` owns one fixed request and strict
decoder. Load/hold its exact source with the existing host input custody. It
starts no process itself and accepts no generic method/URI/command. Supply the
already owned reader with current care/actor checks and actual capture/exit/
EOF/reap handling; keep that existing reader's actual pre/post guards:

```python
observation = cached_inputs.observe(
    selected_package, actual_run_id,
    {"pid": actual_pid, "uid": actual_uid,
     "start_ticks": str(actual_start_ticks), "boot_id": actual_boot_id},
    fresh_challenge,
    lambda argv: entry.runtime.read(selected_peer, fresh_read_output, argv),
)
```

The callback receives only fixed serial-relative `shell content call` arguments
for this method. The selected Entry supplies its already validated transport.
The caller validates nonce/process/run, closed output shape, hashes/endpoints,
size and duplicate-key absence. It calls the reader once and never retries,
initializes, captures, falls back to visual publication or changes enrollment
after uninitialized/unavailable/rejected output. A prior package that rejects
the method remains unsupported; do not probe it by a mutating call.

The decoder requires one complete `Result: Bundle[{...}]` frame with only
schema/status/challenge/run_id/receipt_base64 fields, each exactly once. Outer
schema must be v3 and status observed, with the exact request challenge/run.
It accepts only the transport's optional single LF/CRLF ending and 8192-character
scalar bound; prefix/trailing data, duplicate/extra fields and header failures
are rejected before the independently checked inner DTO.

## Focused offline fixture and next useful qualifier

Run `tools/checks/Test-CachedEffectiveInputsHost.py` with explicit `--output`
(fresh below target), `--java-home`, `--stdlib`, `--json-jar` and
`--compiler-classpath` suppliers. It reuses the existing guarded parser fixture,
compiles the whole new projector plus exact cache/Observation/service/provider
method bodies, and exercises actual Java output through the Python caller.
Android process/container/Bundle/Base64 leaves are modeled. These fixtures do
not establish Binder/Android/Native/device execution or package adoption.

At the next independently useful enrollment/bootstrap with this route packaged:
bind the current APK/process/run and observe `uninitialized` before an ordinary
owner capture when available; let the existing owner perform its needed capture;
then read cached frozen/profile/base/effective route and native-configuration
joins. Observe actual endpoint resources separately at their existing owner.
Read-only polling must not create the missing snapshot. Earn endpoint B and
exact saved-A rollback during otherwise-needed eligible generations, preserving
the full visual/profile library; deleting a file or peer Stop does not roll
back a frozen endpoint route. No separate lifecycle is introduced by this read.
