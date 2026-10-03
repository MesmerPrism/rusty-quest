# Current stereo Hub surface

This neutral app surface uses the existing Connection Hub Binder admission,
provider grant, session reducer and empty-parameter command ABI. It does not
create a listener, enroll a controller, start or renew streaming, change a route,
reset an app, or close native media. The current private feature explicitly
selects the closed profile; the ordinary locked-playlist profile remains default.

The provider and surface IDs are `provider.quest.concurrent-stereo` and
`surface.concurrent_stereo.controls`. The only commands are
`command.concurrent_stereo.own` and `command.concurrent_stereo.peer`, with matching
`capability.concurrent_stereo.own` and `.peer` controller capabilities. The exact
v1 descriptor canonical bytes and SHA are retained in
`apps/manifold-broker-android/contracts/spatial-camera-panel-concurrent-stereo-surface.v1.json`.
No new Broker schema or caller-provided parameter bundle is accepted. The
required actual provider/package/signer and controller grants remain separate
owner prerequisites; source code does not establish those grants.

An app-owned serialized observation requires a live process/display attachment,
the held native qualification arm/configuration, an active native route and a
current signed common session with at least five seconds remaining. A command
retains the existing installed-body hash check, then rechecks attachment,
cancellation, native generations and signed-pair state immediately before the
policy update. The brightness-mask profile is unavailable here, rather than
silently changing its configuration.

`provider_applied` requires the exact requested control revision and retired GPU
policy, demanded-bank current process/source generations, a bounded one-second
native sample age and current app context. A retired pair sequence may precede
a newer acquired frame; it cannot be from a future sequence or another process.
The monitor labels stale or absent pixel evidence separately. Opaque arm and
native process IDs are decimal strings to preserve all 64 bits in browsers;
revision, counters and time fields remain bounded exact scalar integers.

The app target invalidates on scene loss, process/arm/config/route expiry or
shutdown. A negative-only cancellation token prevents queued commands after
scene shutdown or current Hub session, registration or Binder retirement. The
exact owner lane checks it before mutation; retired binding callbacks cannot
reactivate a token. Late completion cannot clear a successor command's gate.
Ambiguous dispatched effects report outcome unknown, never an
application success or an automatic retry. Two headset commands have independent
receipts; applying complementary policies is not an atomic paired transaction,
a cross-head camera-origin proof, or byte-identical encoded imagery.

Host compile and modeled retirement/lifecycle regressions are separate from
actual Hub grants, wearer-started listener, headset adoption and streaming
qualification. Web Bluetooth rendezvous diagnostics alone cannot grant this
surface or transport Hub control.
