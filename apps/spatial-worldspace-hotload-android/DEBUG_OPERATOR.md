# Debug workload operator

Debug provider authority `${applicationId}.probe-control`, DUMP permission and shell UID2000. Schema `rusty.quest.spatial_worldspace_hotload.debug_operator.v1`. Closed verbs and argument limits are documented in README. No arbitrary paths, commands, intents, properties, secret/environment reads or high-rate payloads are exposed.

`admitted` means the request passed the provider gate. `observed` means the main-looper handler returned within its bounded1000ms wait. An admitted asynchronous GPU stop is not completed until native JSON says `teardownCompleted:true`. If queued work times out before starting it is cancelled; if it already started the result is explicitly unknown. `sdkSampledProof` and `controllerInputProof` remain false.

Release excludes the provider and HotReloadFeature; its feature facade is inert. A debug receipt proves no physical input or visible SDK frame. Actual headset operations require separate current device custody and typed effect guards.
