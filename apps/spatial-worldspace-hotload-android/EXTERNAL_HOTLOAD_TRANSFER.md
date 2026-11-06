# External diagnostic candidate transfer

The debug operator and in-app diagnostic panel share five fixed commands:

| Command | Argument | Result |
| --- | --- | --- |
| `compute-info` | none | Lazily hash the current base APK and return the app session, live producer generation, active kernel generation/digest, and next request identity. |
| `compute-stage` | none | Read the fixed app-owned inbox, validate its manifest against current context, and copy bounded bytes into native staging. |
| `compute-activate` | full candidate SHA-256 | Request activation of that exact staged candidate. |
| `compute-discard` | full candidate SHA-256 | Request disposal of that exact staged candidate. |
| `compute-rollback` | none | Request the retained native previous completed pipeline. |

`compute-revert` retains its original meaning: select packaged A. Staging alone
does not prepare or activate a pipeline. Native receipts and fence completion
remain separate from SDK sampling and visible output.

The provider is debug-only and requires both Android DUMP permission and shell
UID. It accepts no byte payloads, caller paths, or extra bundles. The fixed
inbox is `files/native-hotload/inbox/`, containing `kernel.spv` (at most 64 KiB)
and `candidate.json` (at most 8 KiB). Files must be regular; symbolic links and
files with multiple hard links are rejected. Canonical paths must remain inside
the app's files directory.
Manifest parsing rejects duplicate/unknown fields and wrong JSON types.

Obtain `compute-info` explicitly through the admitted operator session and
save its JSON object verbatim to a local context file. Supply its exact fields
to preparation and pin that file's SHA-256 for transfer. The manifest uses canonical
positive decimal strings for producer generation, expected kernel generation,
and request identity; these fit signed 64-bit Kotlin/JNI integers. `codeBytes`
is a JSON integer. App identity is the full lowercase base-APK SHA-256.

```text
python Prepare-ExternalHotloadKernel.py --source app/src/debug/fixtures/hotload_external.comp --glslc <compiler> --spirv-val <validator> --spirv-dis <disassembler> --output <new-prepared-directory> --app-build <appBuild> --session <session> --producer-generation <producerGeneration> --expected-generation <expectedGeneration> --expected-active-digest <expectedActiveDigest> --request-id <nextRequestId>

python Stage-ExternalHotloadCandidate.py --adb <adb-executable> --serial <serial> --package <application-id> --candidate-dir <prepared-directory> --expected-apk-sha <base-apk-sha256> --context-json <context-file> --context-sha <context-file-sha256> --host-receipt-sha <host-receipt-sha256> --output <new-receipt-file>
```

The transfer command validates the local bytes, pinned context, and a pinned
`host-receipt.json` from preparation (at most 256 KiB). It checks that receipt's
schema, exact code hash/size, candidate manifest hash, ABI hash, and successful
Vulkan 1.1 `spirv-val` marker. Transfer evidence retains the receipt, source,
disassembly, and compiler/validator/disassembler hashes. The receipt is evidence,
not authentication or a claim of shader semantic safety on the device.

The same stage-only transfer command admits two deliberately invalid fixtures
produced by `Prepare-ExternalHotloadNegative.py`: `malformed-instruction` and
`wrong-binding`. These use the distinct
`rusty.quest.hotload.external_negative_receipt.v1` schema. Its validation marker
must say `not_run_intentional_rejection`; a passed-validator claim is rejected.
The receipt must explicitly say intentional invalid candidate, stage only, and
expected native rejection. No generic validation-bypass option exists.

For these two fixtures, transfer verifies the pinned original valid host receipt,
original code/manifest/disassembly, retained source snapshot, and exact declared
single-word mutation. The malformed case clears the word count of the first
instruction. The binding case changes the unique Binding decoration's literal
from zero to one. Transfer evidence labels the candidate as an intentional
negative and records expected native rejection separately from the actual
provider response. It never activates, including if staging unexpectedly succeeds.

```text
python Prepare-ExternalHotloadNegative.py --candidate-dir <valid-prepared-directory> --host-receipt-sha <valid-host-receipt-sha256> --preset <malformed-instruction-or-wrong-binding> --output <new-negative-directory> --app-build <appBuild> --session <session> --producer-generation <producerGeneration> --expected-generation <expectedGeneration> --expected-active-digest <expectedActiveDigest> --request-id <nextRequestId>
```

Obtain fresh context for each stage operation. The negative preparer preserves
the validated parent evidence, applies exactly one named mutation, and emits
a newly bound manifest. Its source snapshot describes the valid parent; the
negative receipt records the intentional binary mutation separately.

The command then verifies the
installed base APK hash, writes each fixed inbox file with app-UID `run-as` and
stdin, then verifies exact length and SHA-256 through app-UID readback. Only
after both readbacks does it issue one `compute-stage` provider call. It never
installs, launches, activates, or retries. The surrounding operator remains
responsible for device/session admission and exclusive-resource coordination.

The output receipt is exclusively created; an existing receipt blocks replay.
Before dispatch it records an unknown result, preserving that state if transport
fails or times out. A received provider response may itself report rejection or
an unknown result. Inspect status and request a fresh context before any new
operator decision. An unknown dispatch must never trigger automatic replay.
Every candidate mutation reaching the inbox adapter consumes one request
identity, including local validation rejection; `compute-info` observes the
next identity without consuming it. Readiness/argument guards may block before
that adapter is called.

The status reports context preparation, the last successfully staged manifest,
and local rejection separately. Native status reports actual active/staged/
rollback identity and its own last rejection. The saved manifest is historical
context, so activation or a producer restart can make it stale. A confirmed
native discard clears the saved candidate.

Run `python Test-ExternalHotloadTransfer.py` for pure mocked transfer tests.
These tests invoke no ADB process and provide no Android compilation or device
execution evidence.
The Android manifest parser and filesystem adapter still require Android/JVM
runtime tests; the mocked transfer suite does not exercise those classes.
