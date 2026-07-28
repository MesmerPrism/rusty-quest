# Spatial product isolation

The private effect project and Spatial VR Strobe are separate projects and
separate Android application modules. They share only a neutral Spatial SDK
support library and one Gradle root for dependency-cache efficiency.

| Identity | Private effect adapter | Spatial VR Strobe |
| --- | --- | --- |
| Gradle application module | `:app` | `:strobe-app` |
| Source root | `apps/spatial-camera-panel-android/app` | `apps/spatial-vr-strobe-android/app` |
| Shared dependency | `:spatial-sdk-shared` | `:spatial-sdk-shared` |
| Workspace | private repo `morphospace/` | `apps/spatial-vr-strobe-android/morphospace/` |
| Package | `io.github.mesmerprism.rustyquest.spatial_camera_panel` | `io.github.mesmerprism.rustyquest.spatial_vr_strobe` |
| Client | `client.quest.spatial-camera-panel` | `client.quest.spatial-vr-strobe` |
| Lock | `lock.spatial-camera-panel.v1` | `lock.spatial-vr-strobe.v1` |
| Marker namespace | `RUSTY_QUEST_SPATIAL_CAMERA_PANEL` | `RUSTY_QUEST_SPATIAL_VR_STROBE` |
| Property namespace | `debug.rustyquest.spatial_camera_panel` | `debug.rustyquest.spatial_vr_strobe` |

`Build-SpatialCameraPanelAndroid.ps1` builds only `:app` and rejects the Strobe
identity. `Build-SpatialVrStrobeAndroid.ps1` builds only `:strobe-app` and never
delegates through the Camera build. Each application receives a separate
Gradle project cache, app build directory, and output directory. Both use the
normal shared Gradle dependency cache, whose locking is concurrency-safe; this
avoids downloading and transforming the same Android dependencies twice.

The legacy `apps/spatial-camera-panel-android/morphospace/` workspace preserves
mixed integration history. MOD-013 closes its in-flight projection; no new
consumer-project work belongs there.

The two current protocol-v2 workspaces pass the portable workflow-contract
validator. The legacy v1 ledger is checked by its compatibility static gate:
the current portable validator reports historical vocabulary and scope
diagnostics against old accepted units, which must not be rewritten merely to
fit a newer schema.
