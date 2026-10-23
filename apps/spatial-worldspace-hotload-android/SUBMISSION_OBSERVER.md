# Explicit debug submission observer

The owning builder accepts `-SubmissionObserver` only with an explicit
`-SubmissionObserverIncludeDirectory` containing official OpenXR headers. It
inventories and stages the selected repository module alongside the app,
includes both inventories and the selection in the fingerprint, and checks
source drift before issuing a capsule. The default is off. Release builds omit
the observer, JNI reader, and loader manifest even when debug selection is on.

The reader finds only the exact already-loaded layer library using
`RTLD_NOLOAD`. It keeps one library reference until process exit, queries the
separate context-status ABI, and makes no XR calls. Library discovery can fail
because of loader discovery or Android namespace visibility. Counters indicate
actual boundaries observed by this layer; dispatch bypass and handles wrapped by
another layer can remain unobserved. Neither case proves SDK interception.

The original submission readback ABI is unchanged. The separate status ABI binds
both the SDK instance and its session, with library-lifetime generations. Each
Activity owner and SDK attachment has an explicit epoch. Pausing clears the
consumer binding; stale generations or changed module identity require a new
attachment. Reader state is confined to the main thread and guarded by the exact
Activity owner; delayed callbacks from old Activities cannot clear a new reader.
The observer remains usable with CPU reference geometry and adds no producer,
frame loop, extensions, permissions, or recording owner.

Each status request consumes a fresh sample. `newSincePreviousRead` means new to
this reader, including its first sample; it does not mean submitted after the
attachment. `freshAdvancingSubmissionAtThisRead` additionally requires an
eligible witness and frame/poll ages at most one second. UI polls also consume
that sequence, and the UI labels its cached text as the last poll. Repeated reads
cannot promote a cached sample. These diagnostics grant no recording authority,
GPU completion, pixel sampling, or presentation proof.

`tests/SubmissionObserverReadPolicyTest.kt` runs the actual pure Kotlin reader
policy. The module host fixture exercises the actual layer implementation,
including exact parent matching, opt-out, saturation, and genuine concurrent
forward/return boundaries. Foundation qualifications apply only to their frozen
source; changed source needs fresh checks and Android loader acceptance.
