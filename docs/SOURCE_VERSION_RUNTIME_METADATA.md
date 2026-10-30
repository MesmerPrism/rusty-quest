# Development source-version metadata experiment

This candidate separates provenance-only version bytes from compiler source.
It is not product, Android class-loader or performance qualification. The full
composition fingerprint, source locks, installed `0.1.0+src.<64hex>` versionName,
badging assertion, APK digest and runtime admission retain their meanings.

The public producer selects `rqDeveloperSourceRuntimeStamp=true` only for
opted-in source-version stamping in non-publication DevFast debug. It passes
explicit false otherwise. Default, release and publication paths retain
ordinary literal version fields and no experiment Java resource. Other
BuildConfig policy, enrollment, product and default fields remain unchanged.

Supported AGP variant APIs carry a lazy fingerprint Provider into output
versionName. A task writes exactly one bounded Java resource,
`META-INF/rusty-quest/source-version.v1.txt`, from the same input. Ordinary APK
inspection joins its exact bytes to source metadata and actual manifest
badging. Missing, duplicate, malformed, changed or unselected entries are
rejected. It is disjoint from embedded-duplex assets and grants no authority.

Selected `BuildConfig.VERSION_NAME` remains a public String field with the full
source-version runtime value. Its fixed initializer calls the context-free
`SpatialSourceVersion` reader, allowing unchanged generated Java bytes across
metadata changes. It is deliberately **not a compile-time constant**. Constant
expressions, annotations, switch labels and old inlined clients are a separate
compatibility boundary; field/type compatibility does not preserve them.

The reader uses its supplied class loader and public Java resource APIs. It
does not find an Activity, create a process owner, change a provider or use
hidden Android APIs. It reads at most76 bytes, accepts exactly75 ASCII bytes
in the source-version shape, closes the stream and rejects absent/ambiguous
resources. It never references BuildConfig, avoiding a class-init callback.
There is no stale/base fallback: failure can fail BuildConfig initialization
and cold startup, rather than merely a later metadata query.

ZIP inspection detects duplicate entries within an APK and missing own bytes.
The reader detects competing loader resources, including a parent shadow plus
the own entry. Enumeration alone cannot authenticate a loader that hides the
own entry completely. Existing installed-APK/actor guards still bind the
artifact; this helper does not attest hostile class loaders.

## Focused proof

`tools/checks/Test-SpatialSourceVersion.ps1 -OutDir <fresh-output>` covers the
existing derivation/badging/cache seams and actual ZIP inspection, including
missing/duplicate/wrong/malformed/unselected resource controls.

`tools/checks/Test-SpatialSourceVersionGradle.py` takes explicit repository,
fresh owner target, Gradle9.4.1, JDK17, Android SDK and Gradle-home arguments.
It uses production version wiring/writer/reader with AGP8.11.1/Kotlin2.1,
builds a tiny fixture APK, observes real generated source/compiler outcomes
across metadata/code edit/revert, and compares package/resource/host field
values. It also checks constant-expression and loader failure boundaries.
Serialize its exact build/cache resources; no clean/reset is part of it.
This is not a full product APK or device test.

In the retained fixture, metadata changes preserved generated BuildConfig
bytes and left both compilers up-to-date; real code edit/revert still compiled.
AGP still caused a configuration-cache miss when the fingerprint changed.
Unchanged metadata reused configuration. This candidate does not claim to
eliminate that miss or attribute an entire JVM phase to one dependency.

Host JVM loading does not prove Android/ART loading, cold startup, stripping
or installed field/package equality. The existing debug host receipt reads
PackageInfo and guards with BuildConfig.DEBUG (exercising class initialization),
but contains no separate VERSION_NAME field witness. Do not alter its ordered
renderer facts or create a provider to manufacture that witness. Direct field
equality remains a bounded Android diagnostic integration question before
adoption; no installed claim follows here.
