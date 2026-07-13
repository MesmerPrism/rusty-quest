# Closed Feature Activation

Rusty Quest keeps reusable adapter code separate from application selection.
The application owns which feature is selected; a shared adapter only acts
after the application supplies an exact, closed feature lock and matching
runtime input.

## Ownership

- `rusty-quest-feature-activation` owns parsing the complete
  `rusty.morphospace.workflow.feature_lock.v1` shape, rejecting unknown fields,
  hashing the exact lock bytes, comparing the runtime project/feature/revision/
  digest/profile tuple, and producing the common applied-or-rejected decision.
- `rusty-quest-particle-adapter` and `rusty-quest-hand-adapter` are thin policy
  facades. Each facade owns only its `requested_by` selector, receipt schema,
  effective-marker namespace, and a nominal decision type whose private inner
  value can only be minted by that facade's resolver.
- Each application owns its project ID, feature ID, default lock, conformance
  lock, accepted runtime profile, package/client identity, and any effects that
  follow an applied decision.

The generic crate must not contain application package names, project IDs,
feature IDs, runtime profiles, permissions, routes, assets, Android properties,
or app-specific marker namespaces. Adding another consumer should require a
small policy facade, not another JSON parser or copied decision state machine.

## Activation rule

Optional features are inert by default. Activation proceeds only when all of
the following are true:

1. The full lock parses with the exact supported schema, revision is nonzero,
   `default_activation` is `disabled`, its size/counts are bounded, feature and
   module identities are independently unique, module-ID dependencies are
   present/enabled/acyclic, and no selected feature-ID conflict is active.
2. Exactly one selected feature matches the application-owned feature ID and
   its module ID matches the separate application-owned module expectation.
3. The selected entry is enabled and matches the adapter facade's selector,
   receipt schema, and effective marker.
4. The exact lock bytes match the application-owned accepted SHA-256 embedded
   beside the compile-time lock. Runtime properties cannot redefine this hash.
5. The runtime input is explicitly enabled and matches the accepted profile,
   project, feature, lock revision, and SHA-256 of the exact supplied bytes.

Any mismatch produces a typed rejection before marker, input, scene, media,
permission, route, or rendering effects. A conformance lock is test evidence;
it does not change an application's inert default lock.

Hand and particle decisions are intentionally different Rust types. A hand
decision cannot cross a particle effect gate (or vice versa), and neither type
has a public constructor or public inner fields. Compile-fail doctests preserve
both constraints.

## Validation

Run the shared engine and both current facades together:

```powershell
cargo test -p rusty-quest-feature-activation -p rusty-quest-particle-adapter -p rusty-quest-hand-adapter
& .\tools\checks\Test-QuestParticleAdapterStatic.ps1
& .\tools\checks\Test-QuestHandAdapterStatic.ps1
```

Application promotion additionally requires its workspace lock checks and,
when runtime behavior is claimed, explicit serial-scoped device evidence for
both applied and damaged inputs with cleanup and bounded fatal scanning.
