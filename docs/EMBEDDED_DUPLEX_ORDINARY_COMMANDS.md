# Ordinary embedded duplex Start and Peer Stop

The panel's **Start** action and the explicit ordinary debug call use the same
resumed Activity handler and `RuntimeService.ordinaryStart`. It prepares the
current signed-session Start preflight and then invokes the existing Start
owner. Bootstrap, Pair, renderer ARM and `prepare_peer_start` remain separate
operations; Start does not compose them automatically.

Ordinary Start requires the selected source-bound-v2 prepared owner, the
current resumed Activity and its actual display attachment, CAMERA and
HEADSET_CAMERA permissions, a current ARM bound to the same process/configuration,
the actual ready SDK preparation, and a current signed pair session. USE_SCENE
is additionally required only when the selected environment-depth owner and
current layer controls enable a depth consumer. An unselected or disabled depth
consumer adds no scene-permission requirement. The Activity supplies that actual
selected-feature readiness; the Host rechecks it, the attachment, camera
permissions and ARM before the existing one-use intent dispatch and Native effect. A pause/resume, changed attachment, expired or replaced session,
changed ARM, Stop request, or missing prerequisite refuses the action. Neither
a successful preflight nor a completed outer Java Future proves moving frames.

## Fixed debug wire call

The debug-only provider keeps its package-scoped authority, DUMP permission,
shell-UID admission and closed typed parser. The new form is:

| Field | Required value |
| --- | --- |
| Method | `start` |
| Argument | The current ARM challenge, lowercase 32-hex |
| Extra `original_id` | A lowercase 32-hex Original ID |
| Extra `prepare_preflight` | Boolean `true` |

Those are the exact two extras. Missing ID, false/string/integer flags and
additional fields are rejected. The flag on another method is rejected.
No arbitrary JSON, native command, actor or configuration input is accepted.

For example, substitute the actual selected package, serial, current ARM
challenge and new Original ID:

```text
adb -s <serial> shell content call --uri content://<package>.embedded-duplex-operator --method start --arg <current-arm-challenge> --extra original_id:s:<original-id> --extra prepare_preflight:b:true
```

The provider keeps the canonical Original method/operation `start`; the typed
Boolean extra distinguishes ordinary mode in the request digest. It retains
`beginOriginal`, carries that actual Original through the current resumed
Activity gate, and returns its observation envelope. The Activity calls the
same runtime facade as the panel, with the supplied challenge as an expected
current ARM. The panel resolves the actual held ARM through that facade.

The existing no-ID `start`, explicit-ID `start` without this flag, diagnostic
preflight and qualification routes remain distinct. They do not acquire the
ordinary mode's automatic preflight step.

## Observation and ACK

Use the existing [Original query/ACK protocol](EMBEDDED_DUPLEX_ORIGINAL_CUSTODY.md).
`operation_query` takes only the Original ID extra and a valid query challenge.
`operation_ack` takes the Original ID plus `request_sha256`, `outcome_sha256`
and `consumer_receipt_sha256`, each a lowercase 64-hex digest. ACK must use the
actual retained request/outcome and consumer receipt; a timeout or a pending
observation supplies no successful settlement.

An exact repeated Original request observes the same invocation. Changing the
flag, operation or other input under that ID is refused. Query and ACK do not
repeat Start. The same actor, actual Future/callback, command, persistence and
applicable exchange joins remain required; no new ACK shortcut is added.
The panel displays Start result status without claiming stereo presentation,
frame freshness, physical cleanup or reciprocal media delivery.

## Peer Stop and Whole

The panel exposes **Peer Stop** separately. It remains available while Start is
pending and reaches the existing peer Stop owner with the actual held ARM.
Its desired Stop is signaled independently of the pending Start command lane;
it does not require Start permissions, another Pair, a preflight or a diagnostic
run. Peer Stop is not Whole and does not certify whole-product/display/fence
retirement. Existing Whole and app-close paths keep their own cleanup barriers.

## Focused source check

Run [Test-EmbeddedDuplexOrdinaryStartSource.ps1](../tools/Test-EmbeddedDuplexOrdinaryStartSource.ps1)
with an installed JDK17, Android API34 SDK, Gradle launcher, pinned org.json20240303
jar and the retained public owning-factory policy-vector file. Paths are selected
with parameters or the documented environment variables; no machine path is
stored in source. Coordinate the build resource before executing it.

```powershell
pwsh -NoProfile -File tools/Test-EmbeddedDuplexOrdinaryStartSource.ps1 `
  -JavaHome $env:JAVA_HOME -AndroidHome $env:ANDROID_HOME `
  -GradlePath $env:RUSTY_QUEST_GRADLE_PATH `
  -HostJsonJar $env:RUSTY_QUEST_HOST_JSON_JAR `
  -PolicyVectorPath $env:RUSTY_QUEST_ORDINARY_START_POLICY_VECTOR
```

`-RepoRoot`, `-PythonPath` and `-OutputRoot` are optional. Outputs must be new and
beneath the repository's ignored `target` directory. The runner restores every
environment variable it changes. It compiles Java/Kotlin and runs the affected
parser, selected depth-consumer policy, preflight, attachment and Activity source-contract JVM tests under
finite/none and ongoing-v2/source-bound-v2 selections. It then runs the focused
ordinary-Start/Original/intent-slot host controls with current compiled helper
classes, followed by boundary and diff checks. Source, tool, compiled helper,
test report and command pins remain in the output directory; source drift
refuses qualification.

The public vector is the exact retained source-factory fixture, not Product
enrollment or adoption. These checks use declared Android, SDK and Native model
leaves. They perform no APK packaging, Native/Cargo build, headset operation,
physical-cleanup or performance acceptance.

The selected installed `50f5d5a2166974865b0534fd21d0b59080cef1e2` family already
accepts lifecycle-v4; this work does not identify an installed v4 bug or a device
hold. The new wire flag and UI handler require the next APK containing this
source. Source checks do not change the currently installed package.
