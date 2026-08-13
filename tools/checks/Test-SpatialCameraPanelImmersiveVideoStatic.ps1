param(
    [string]$RepoRoot
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
}
$repoRootPath = (Resolve-Path $RepoRoot).Path

function Read-RequiredText {
    param([Parameter(Mandatory=$true)][string]$RelativePath)
    $path = Join-Path $repoRootPath $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing immersive-video integration file: $RelativePath"
    }
    return Get-Content -Raw -LiteralPath $path
}

function Assert-Contains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Label is missing required token: $Needle"
    }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory=$true)][string]$Label,
        [Parameter(Mandatory=$true)][string]$Text,
        [Parameter(Mandatory=$true)][string]$Needle
    )
    if ($Text.Contains($Needle)) {
        throw "$Label contains forbidden token: $Needle"
    }
}

$catalog = Read-RequiredText "apps\spatial-camera-panel-android\gradle\libs.versions.toml"
$appGradle = Read-RequiredText "apps\spatial-camera-panel-android\app\build.gradle.kts"
$manifest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\AndroidManifest.xml"
$ids = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\res\values\ids.xml"
$route = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoRouteModule.kt"
$coordinator = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoPanelCoordinator.kt"
$autoRecenterGate = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoAutoRecenterGate.kt"
$session = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoSession.kt"
$background = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialBackgroundMode.kt"
$controlPanel = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\PrivateLayerControlPanel.kt"
$profiles = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelStoredProfiles.kt"
$passthroughLut = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialPassthroughLutModule.kt"
$geometry = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionGeometryCoordinator.kt"
$panelCarrier = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraHwbProjectionPanelCarrierCoordinator.kt"
$validationWorkflow = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialValidationWorkflowCoordinator.kt"
$uiActionTool = Read-RequiredText "tools\Invoke-SpatialCameraPanelAndroidUiAction.ps1"
$offlinePack = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\OfflineImmersiveMediaPack.kt"
$sharedOfflineLibrary = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SharedOfflineImmersiveMediaLibrary.kt"
$sharedPlainLibrary = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SharedPlainImmersiveMediaLibrary.kt"
$stereoPlayback = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialStereoVideoPlayback.java"
$videoRuntime = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialVideoProjectionRuntimeCoordinator.kt"
$nativeVideoProjection = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection.rs"
$nativeVideoSettings = Read-RequiredText "apps\spatial-camera-panel-android\native-receipt\src\spatial_video_projection_settings.rs"
$activity = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialCameraPanelActivity.kt"
$routeTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoRouteModuleTest.kt"
$sessionTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialImmersiveVideoSessionPolicyTest.kt"
$backgroundTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialBackgroundModeTest.kt"
$plainLibraryTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SharedPlainImmersiveMediaLibraryPolicyTest.kt"
$decoderLifecycleTest = Read-RequiredText "apps\spatial-camera-panel-android\app\src\test\java\io\github\mesmerprism\rustyquest\spatial_camera_panel\SpatialVideoDecoderLifecycleTest.kt"
$stageTool = Read-RequiredText "tools\Stage-SpatialCameraPanelImmersiveVideo.ps1"
$packTool = Read-RequiredText "tools\New-SpatialCameraPanelOfflineMediaPack.ps1"
$installPackTool = Read-RequiredText "tools\Install-SpatialCameraPanelOfflineMediaPack.ps1"
$buildTool = Read-RequiredText "tools\Build-SpatialCameraPanelAndroid.ps1"
$styles = Read-RequiredText "apps\spatial-camera-panel-android\app\src\main\res\values\styles.xml"

Assert-Contains "Spatial SDK version catalog" $catalog 'spatialsdk = "0.13.2"'
Assert-Contains "Media3 version catalog" $catalog 'media3 = "1.4.1"'
Assert-Contains "App dependencies" $appGradle "libs.androidx.media3.exoplayer"
Assert-Contains "App dependencies" $appGradle "libs.androidx.media3.datasource"
Assert-Contains "App embedded-key boundary" $appGradle "RUSTY_QUEST_OFFLINE_MEDIA_KEY_HEX"
Assert-Contains "App embedded-key boundary" $appGradle "OFFLINE_MEDIA_KEY_HEX"
Assert-Contains "App packaged-media boundary" $appGradle "RUSTY_QUEST_OFFLINE_MEDIA_PACK_ASSET_DIR"
Assert-Contains "App packaged-media boundary" $appGradle "OFFLINE_MEDIA_PACKAGED_ASSETS"
Assert-Contains "App packaged-media asset-root validation" $appGradle 'resolve("offline-media-packs")'
Assert-Contains "App packaged-media asset-root validation" $appGradle "must point to an asset root"
Assert-Contains "App release build" $appGradle 'getByName("release")'
Assert-Contains "App release build" $appGradle "signingConfig = signingConfigs.getByName(`"debug`")"
Assert-Contains "Panel ids" $ids "spatial_immersive_video_panel"
Assert-NotContains "Android manifest" $manifest 'android:debuggable="true"'
Assert-NotContains "Android manifest" $manifest "android.permission.READ_MEDIA_VIDEO"
Assert-NotContains "Android manifest" $manifest "android.permission.READ_EXTERNAL_STORAGE"

Assert-Contains "Immersive route" $route 'Equirect180("equirect-180")'
Assert-Contains "Immersive route" $route 'Equirect360("equirect-360")'
Assert-Contains "Immersive route" $route 'SideBySideLeftRight("side-by-side-left-right")'
Assert-Contains "Immersive route" $route 'TopBottom("top-bottom")'
Assert-Contains "Immersive route" $route "path-outside-app-scoped-media-root"
Assert-Contains "Immersive route" $route "content-uri-not-media-video"
Assert-Contains "Immersive route" $route "content-uri-not-readable"
Assert-Contains "Immersive route" $route "projection-shape-unknown"
Assert-Contains "Immersive route" $route "failClosed=true"
Assert-Contains "Immersive route" $route "directToSurface=true"
Assert-Contains "Immersive route" $route "resolveOfflinePack"
Assert-Contains "Immersive route" $route "resolvePlainMedia"
Assert-Contains "Immersive route" $route "resolveAvailablePlainMedia"
Assert-Contains "Immersive route" $route "playbackInitiallyEnabled"
Assert-Contains "Immersive route" $route "sharedPlainVideo="
Assert-Contains "Immersive route" $route "offlineEncryptedPack="
Assert-Contains "Immersive route" $route "plaintextFileWritten=false"

Assert-Contains "Immersive coordinator" $coordinator "VideoSurfacePanelRegistration("
Assert-Contains "Immersive coordinator" $coordinator "Equirect180ShapeOptions"
Assert-Contains "Immersive coordinator" $coordinator "Equirect360ShapeOptions"
Assert-Contains "Immersive coordinator" $coordinator "StereoMode.None"
Assert-Contains "Immersive coordinator" $coordinator "panelRegistrationIds: Map<SpatialImmersiveVideoPresentationMode, List<Int>>"
Assert-Contains "Immersive coordinator" $coordinator "blackBackdropRegistrationIds:"
Assert-Contains "Immersive coordinator" $coordinator "settingsCreator = { mediaPanelSettings(config, registeredMode) }"
Assert-Contains "Immersive coordinator" $coordinator "width = presentation.displayWidthPx"
Assert-Contains "Immersive coordinator" $coordinator "height = presentation.displayHeightPx"
Assert-Contains "Immersive coordinator" $coordinator "stereoMode = presentation.stereoMode"
Assert-Contains "Immersive coordinator" $coordinator "width = quadGeometry.widthMeters"
Assert-Contains "Immersive coordinator" $coordinator "height = quadGeometry.heightMeters"
Assert-Contains "Immersive coordinator" $coordinator "status=first-frame-rendered"
Assert-Contains "Immersive coordinator" $coordinator "status=playback-progress"
Assert-Contains "Immersive coordinator" $coordinator "advancing=`${currentPositionMs > firstFramePositionMs}"
Assert-Contains "Immersive coordinator" $coordinator "status=decoded-video-size"
Assert-Contains "Immersive coordinator" $coordinator "autoRecenterGate.arm(config.path, source)"
Assert-Contains "Immersive coordinator" $coordinator 'readyRoute = "direct-spatial-first-frame"'
Assert-Contains "Immersive coordinator" $coordinator 'readyRoute = "custom-projection-decoder-ready"'
Assert-Contains "Immersive coordinator" $coordinator 'val inputSource = "new-video-load"'
Assert-Contains "Immersive coordinator" $coordinator "sameViewerPoseAuthority=true exactlyOncePerChangedLoad=true"
Assert-Contains "Video-load recenter gate" $autoRecenterGate "loadedPath != pendingPath"
Assert-Contains "Video-load recenter gate" $autoRecenterGate 'pendingPath = null'
Assert-Contains "Video-load recenter tests" $sessionTest "changedVideoRecentersExactlyOnceWhenFirstSuccessfulRouteBecomesReady"
Assert-Contains "Video-load recenter tests" $sessionTest "staleOrFailedVideoRouteCannotConsumePendingRecenter"
Assert-Contains "Video-load recenter activity route" $activity "::recenterImmersiveVideo"
Assert-Contains "Custom projection recenter active-route gate" $activity "spatialVideoProjectionRuntimeCoordinator.started"
Assert-Contains "Custom projection recenter placement update" $activity '"video-recenter-${activityMarkerToken(inputSource)}"'
Assert-Contains "Custom projection recenter success marker" $activity "activeVideoRoute=custom-projection viewerPoseAuthority=Scene.getViewerPose"
Assert-Contains "Video-load recenter activity route" $activity "notifyCustomProjectionVideoLoaded"
Assert-Contains "Immersive coordinator" $coordinator "clearVideoSurface()"
Assert-Contains "Immersive coordinator" $coordinator "context.getExternalFilesDir(null)"
Assert-Contains "Immersive coordinator" $coordinator '"immersive-video/" + requestedPath.removePrefix(matchingAlias)'
Assert-Contains "Immersive coordinator" $coordinator "context.contentResolver"
Assert-Contains "Immersive coordinator" $coordinator "granted-media-content-uri"
Assert-NotContains "Immersive coordinator" $coordinator "ReadableVideoSurfacePanelRegistration"
Assert-Contains "Immersive coordinator" $coordinator "IMMERSIVE_VIDEO_OFFLINE_PACK_ID"
Assert-Contains "Immersive coordinator" $coordinator "EncryptedOfflineImmersiveMediaDataSource.Factory"
Assert-Contains "Immersive coordinator" $coordinator "DefaultMediaSourceFactory"
Assert-Contains "Immersive coordinator" $coordinator "BuildConfig.OFFLINE_MEDIA_KEY_HEX"
Assert-Contains "Immersive coordinator" $coordinator "ComposeViewPanelRegistration("
Assert-Contains "Immersive coordinator" $coordinator "SpatialImmersiveVideoBlackBackingPolicy"
Assert-Contains "Immersive coordinator" $coordinator "DIRECT_VIDEO_BLACK_BACKING_Z_INDEX = -41"
Assert-Contains "Immersive coordinator" $coordinator "uncoveredVideoPixelsRevealPassthrough=false"
Assert-Contains "Immersive coordinator" $coordinator "PanelAppThemeOpaqueVideoBlack"
Assert-Contains "Immersive coordinator" $coordinator "blackBackdropEntity?.setComponent(Transform(recenteredPose))"
Assert-Contains "Immersive styles" $styles 'name="PanelAppThemeOpaqueVideoBlack"'
Assert-Contains "Immersive styles" $styles '<item name="android:windowBackground">@android:color/black</item>'

Assert-Contains "Offline immersive pack" $offlinePack "rusty.quest.offline_immersive_media_pack.v1"
Assert-Contains "Offline immersive pack" $offlinePack 'Cipher.getInstance("AES/GCM/NoPadding")'
Assert-Contains "Offline immersive pack" $offlinePack "GCMParameterSpec(128, chunk.nonce)"
Assert-Contains "Offline immersive pack" $offlinePack "updateAAD(pack.aad(chunk))"
Assert-Contains "Offline immersive pack" $offlinePack "ciphertext-sha256-mismatch"
Assert-Contains "Offline immersive pack" $offlinePack "chunk-authentication-failed"
Assert-Contains "Offline immersive pack" $offlinePack "plaintextFileWritten=false"
Assert-Contains "Offline immersive pack" $offlinePack "BaseDataSource(false)"
Assert-Contains "Offline immersive pack" $offlinePack "PackagedOfflineImmersiveMediaPackImporter"
Assert-Contains "Offline immersive pack" $offlinePack "fun installedPackIds(context: Context)"
Assert-Contains "Offline immersive pack" $offlinePack "MAX_DISCOVERED_PACKS = 32"
Assert-Contains "Offline immersive pack" $offlinePack "OfflineImmersiveMediaExtractorDataSource"
Assert-Contains "Offline immersive pack" $offlinePack "MediaDataSource()"
Assert-Contains "Offline immersive pack" $offlinePack '"offline-media-packs"'
Assert-NotContains "Offline immersive pack" $offlinePack "FileOutputStream"
Assert-Contains "Shared offline library" $sharedOfflineLibrary "takePersistableUriPermission"
Assert-Contains "Shared offline library" $sharedOfflineLibrary "ensurePlainVideoTaxonomy()"
Assert-Contains "Shared offline library" $sharedOfflineLibrary "DocumentsContract.createDocument("
Assert-Contains "Shared offline library" $sharedOfflineLibrary "plainVideoTaxonomyReady"
Assert-Contains "Shared offline library" $sharedOfflineLibrary "the app never writes or copies video bytes"
Assert-Contains "Shared offline library" $sharedOfflineLibrary 'PACKS_DIRECTORY_NAME = "offline-media-packs"'
Assert-Contains "Shared offline library" $sharedOfflineLibrary "OfflineImmersiveMediaPackLoader.resolve("
Assert-Contains "Shared offline library" $sharedOfflineLibrary "resolver.openInputStream(document.uri)"
Assert-NotContains "Shared offline library" $sharedOfflineLibrary "context.filesDir"
Assert-Contains "Shared plain library" $sharedPlainLibrary 'ROOT_DIRECTORY_NAME = "plain-videos"'
Assert-Contains "Shared plain library" $sharedPlainLibrary "canonicalDirectoryChains()"
Assert-Contains "Shared plain library" $sharedPlainLibrary 'listOf("flat", "equirect-180", "equirect-360")'
Assert-Contains "Shared plain library" $sharedPlainLibrary 'listOf("mono", "side-by-side-left-right", "top-bottom")'
Assert-Contains "Shared plain library" $sharedPlainLibrary "MediaMetadataRetriever"
Assert-Contains "Shared plain library" $sharedPlainLibrary "getScaledFrameAtTime("
Assert-Contains "Shared plain library" $sharedPlainLibrary "plain-video-container-sample-geometry-mismatch"
Assert-Contains "Shared plain library" $sharedPlainLibrary "plain-video-declared-shape-geometry-mismatch"
Assert-Contains "Shared plain library" $sharedPlainLibrary "MAX_ACCEPTED_ITEMS = 32"
Assert-Contains "Shared plain library" $sharedPlainLibrary "contentUri = candidate.uri.toString()"
Assert-NotContains "Shared plain library" $sharedPlainLibrary "FileOutputStream"
Assert-NotContains "Shared plain library" $sharedPlainLibrary "writeBytes("

Assert-Contains "Activity" $activity "immersiveVideoPanelCoordinator.requested"
Assert-Contains "Activity" $activity "directImmersiveVideoPanelRequested()"
Assert-Contains "Activity" $activity "status=layered-carriers-adopted"
Assert-Contains "Activity" $activity "customProjectionCarrierShape=planar-quad"
Assert-Contains "Activity" $activity "immersiveVideoPanelCoordinator::updateFromViewer"
Assert-NotContains "Activity" $activity "usesImmersiveVideoAsCustomProjectionSource()"
Assert-Contains "Activity" $activity "spatialVideoProjectionRuntimeCoordinator.replaceMediaSource("
Assert-NotContains "Activity" $activity "projectionPanelVisibilityCoordinator.restartWith("
Assert-NotContains "Activity" $activity "return listOfNotNull(immersiveVideoPanelCoordinator.panelRegistrationOrNull())"
Assert-Contains "Activity" $activity 'immersiveVideoPanelCoordinator.destroy("activity-destroy")'
Assert-Contains "Immersive coordinator" $coordinator "DIRECT_VIDEO_BACKGROUND_Z_INDEX = -40"
Assert-Contains "Immersive coordinator" $coordinator "HEAD_FIXED_VIDEO_DISTANCE_METERS"
Assert-Contains "Immersive coordinator" $coordinator "spatialPanelRebuilt="
Assert-Contains "Immersive coordinator" $coordinator "status=direct-layer-fade-out-started"
Assert-Contains "Immersive coordinator" $coordinator "status=direct-layer-source-swap"
Assert-Contains "Immersive coordinator" $coordinator "status=direct-layer-fade-in-started"
Assert-Contains "Immersive coordinator" $coordinator "reason=transition-in-progress"
Assert-Contains "Immersive coordinator" $coordinator "fun recenterAtViewer("
Assert-Contains "Immersive coordinator" $coordinator "customProjectionCarrierRetained=true"
Assert-Contains "Immersive coordinator" $coordinator "retainedPackDiscovery=true"
Assert-Contains "Immersive coordinator" $coordinator "SharedPlainImmersiveMediaLibrary.discover(context)"
Assert-Contains "Immersive coordinator" $coordinator "plainFolderTaxonomy=plain-videos-shape-stereo"
Assert-Contains "Immersive coordinator" $coordinator "passivePlainMediaSeed"
Assert-Contains "Immersive coordinator" $coordinator "!BuildConfig.IMMERSIVE_VIDEO_DEFAULT_ENABLED"
Assert-Contains "Immersive coordinator" $coordinator 'config.isSharedPlainVideo -> "shared-plain-video"'
Assert-Contains "Immersive coordinator" $coordinator 'releasePlayer("selection-hidden-source-swap")'
Assert-Contains "Immersive coordinator" $coordinator "decoderOverlap=false"
Assert-Contains "Immersive coordinator" $coordinator "fun setBackgroundMode("
Assert-Contains "Immersive coordinator" $coordinator "playback-disabled-background-retained"
Assert-Contains "Immersive coordinator" $coordinator "backgroundMode != SpatialBackgroundMode.Black"

Assert-Contains "Immersive session" $session "compatibleWithSession("
Assert-Contains "Immersive session" $session 'CUSTOM_PROJECTION_SOURCE = "encrypted-offline-pack"'
Assert-Contains "Immersive session" $session 'PLAIN_CUSTOM_PROJECTION_SOURCE = "shared-plain-video"'
Assert-Contains "Immersive session" $session "MAX_CUSTOM_PROJECTION_DIMENSION_PX = 4096"
Assert-Contains "Immersive session" $session 'SpatialImmersiveVideoStereoLayout.TopBottom ->'
Assert-Contains "Immersive session" $session 'WorldAnchored("world-anchored")'
Assert-Contains "Immersive session" $session 'HeadFixedBorder("head-fixed-border")'
Assert-Contains "Immersive session" $session "EQUIRECT_360_OUTPUT_WIDTH_PX = 4096"
Assert-Contains "Immersive session" $session "fun directPanelPresentation("
Assert-Contains "Immersive session" $session "SpatialImmersiveVideoStereoLayout.TopBottom -> StereoMode.UpDown"
Assert-Contains "Immersive session" $session "floatArrayOf(0.0f, 0.0f, 1.0f, 0.5f)"
Assert-Contains "Immersive session" $session "floatArrayOf(0.0f, 0.5f, 1.0f, 0.5f)"
Assert-Contains "Immersive session" $session "directVideoRegistrationImmutable=true"
Assert-Contains "Immersive session" $session "HEAD_FIXED_VIDEO_DISTANCE_METERS = 2.05f"
Assert-Contains "Immersive session" $session "HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE = 1.20f"
Assert-Contains "Immersive session" $session 'AspectPreservingCover("aspect-preserving-cover")'
Assert-Contains "Immersive session" $session "PARTICLE_LAYER_WIDTH_METERS * distanceScale * HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE"
Assert-Contains "Immersive session" $session "PARTICLE_LAYER_HEIGHT_METERS * distanceScale * HEAD_FIXED_VIDEO_COVER_OVERSCAN_SCALE"
Assert-Contains "Immersive session" $session "directVideoCarrierGeometryOwner=head-fixed-outer-video-panel"
Assert-Contains "Immersive session" $session "customProjectionGeometryUnchanged=true"
Assert-Contains "Immersive session tests" $sessionTest "headFixedTopBottomDirectPanelOverscansOuterVideoAndKeepsEyeCrops"
Assert-Contains "Immersive session tests" $sessionTest "assertEquals(5.40f, PARTICLE_LAYER_WIDTH_METERS"
Assert-Contains "Immersive session tests" $sessionTest "assertEquals(4.00f, PARTICLE_LAYER_HEIGHT_METERS"
Assert-Contains "Immersive session tests" $sessionTest "worldAnchoredFlatVideoKeepsItsSourceAspectSizeInsteadOfUsingHeadFixedCover"
Assert-Contains "Immersive session tests" $sessionTest "directVideoCoverageTargetMeters=6.6420x4.9200"
Assert-Contains "Immersive session tests" $sessionTest "directVideoCoverageOverscanScale=1.2000"
Assert-Contains "Immersive session tests" $sessionTest "StereoMode.UpDown.view2OffsetY"
Assert-Contains "Immersive session tests" $sessionTest "directPanelStereoContractIsIndependentOfWorldOrHeadFixedRegistration"
Assert-Contains "Immersive session tests" $sessionTest "validatedSharedPlainVideoFeedsBothDirectAndCustomProjectionDecoders"
Assert-Contains "Shared plain tests" $plainLibraryTest "canonicalFolderTaxonomyDeclaresShapeAndStereoWithoutFilenameRules"
Assert-Contains "Shared plain tests" $plainLibraryTest "folderBootstrapPlanIsFixedCompleteAndContainsNoCallerPaths"
Assert-Contains "Shared plain tests" $plainLibraryTest "metadataAndDecodedSampleMustAgree"
Assert-Contains "Activity" $activity "Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
Assert-Contains "Activity" $activity "DEFAULT_SHARED_MEDIA_INITIAL_URI"
Assert-Contains "Activity" $activity "primary%3ADocuments"
Assert-Contains "Activity" $activity "chooseSharedMediaFolderFromValidation"
Assert-Contains "Activity" $activity "intent.action = null"
Assert-Contains "Activity" $activity "plainVideoTaxonomyReady=`${snapshot.plainVideoTaxonomyReady}"
Assert-Contains "Decoder lifecycle" $stereoPlayback "DECODER_STOP_JOIN_TIMEOUT_MS = 1_500L"
Assert-Contains "Decoder lifecycle" $stereoPlayback "EVENT_HANDOFF_BLOCKED"
Assert-Contains "Decoder lifecycle" $stereoPlayback "if (!stopLocked())"
Assert-Contains "Decoder lifecycle" $stereoPlayback "replacementAllowedAfterStop"
Assert-Contains "Decoder lifecycle" $stereoPlayback "Keep the codec output surface valid until the MediaCodec owner has exited."
Assert-Contains "Decoder lifecycle" $stereoPlayback "private static volatile Thread playbackThread"
Assert-Contains "Decoder lifecycle" $stereoPlayback "private static void releasePlaybackOwnershipWithoutLock(Surface surface)"
Assert-Contains "Decoder lifecycle" $stereoPlayback "stopLocked joins while holding LOCK"
Assert-Contains "Decoder lifecycle" $stereoPlayback "extractor.setDataSource("
Assert-Contains "Decoder cadence" $stereoPlayback "shouldRenderSurfaceOutput("
Assert-Contains "Decoder cadence" $stereoPlayback "codec.releaseOutputBuffer(outputIndex, render)"
Assert-Contains "Decoder cadence" $stereoPlayback "cadenceBoundary=mediacodec-output-before-surface"
Assert-Contains "Decoder cadence" $stereoPlayback "compressedReferenceFramesPreserved=true"
Assert-Contains "Decoder cadence" $stereoPlayback "nativeCadenceFallbackRetained=true"
$stopBodyStart = $stereoPlayback.IndexOf("private static boolean stopLocked()")
$stopBodyEnd = $stereoPlayback.IndexOf("static boolean replacementAllowedAfterStop", $stopBodyStart)
if ($stopBodyStart -lt 0 -or $stopBodyEnd -lt 0) {
    throw "Decoder lifecycle stop body could not be isolated."
}
$stopBody = $stereoPlayback.Substring($stopBodyStart, $stopBodyEnd - $stopBodyStart)
$decoderJoinIndex = $stopBody.IndexOf("thread.join(DECODER_STOP_JOIN_TIMEOUT_MS)")
$nativeSurfaceStopIndex = $stopBody.IndexOf("nativeStopStereoVideoStream()")
if ($decoderJoinIndex -lt 0 -or $nativeSurfaceStopIndex -lt $decoderJoinIndex) {
    throw "Decoder lifecycle must join the MediaCodec owner before destroying its native output surface."
}
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "decoderHandoffComplete=false"
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "oldDecoderStoppedBeforeNew=true"
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "decoderOverlap=false"
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "readableVideoConsumerRequired=false"
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "zeroContributionDecodeWorkSkipped=true"
Assert-Contains "Decoder lifecycle runtime" $videoRuntime "fun updateReadableVideoConsumer(required: Boolean, reason: String)"
Assert-Contains "Decoder lifecycle direct route" $coordinator "fun setDirectVideoConsumerRequired(required: Boolean, source: String)"
Assert-Contains "Decoder lifecycle direct route" $coordinator 'releasePlayer("direct-zero-contribution")'
Assert-Contains "Decoder lifecycle direct route" $coordinator "hiddenClockAdvanced=true"
Assert-Contains "Decoder lifecycle ownership" $activity "fun updateVideoDecoderOwnership("
Assert-Contains "Decoder lifecycle ownership" $activity "fun startCustomVideoProjectionWithDecoderOwnership("
Assert-Contains "Decoder lifecycle ownership" $activity "stopBeforeStart=true"
Assert-Contains "Decoder lifecycle tests" $decoderLifecycleTest "failedOldDecoderStopBlocksReplacementInsteadOfOverlapping"
Assert-Contains "Decoder lifecycle tests" $decoderLifecycleTest "transparentUnderlayNeverStartsTheZeroContributionCustomDecoder"
Assert-Contains "Decoder lifecycle tests" $decoderLifecycleTest "losingTheReadableConsumerStopsBeforeLaterSourceChanges"
Assert-Contains "Decoder cadence tests" $decoderLifecycleTest "codecOutputCadenceKeepsMicrosecondQuantizedThirtyFpsFrames"
Assert-Contains "Decoder cadence tests" $decoderLifecycleTest "codecOutputCadenceSkipsIntermediateSixtyFpsSurfaceFrames"
Assert-Contains "Decoder cadence tests" $decoderLifecycleTest "codecOutputCadenceRestartsSafelyForANonMonotonicTimeline"
Assert-Contains "Native video cache" $nativeVideoProjection "spatial_video_projection_import_cache_limit(frame.max_images)"
Assert-Contains "Native video cache" $nativeVideoProjection "videoProjectionImportCacheEntries={}"
Assert-Contains "Native video cache" $nativeVideoSettings "SPATIAL_VIDEO_PROJECTION_IMPORT_CACHE_LIMIT: usize = 8"
Assert-Contains "Native video cache" $nativeVideoSettings "import_cache_preserves_the_proven_eight_entry_rotating_bound"
Assert-Contains "Native video receipt sampling" $nativeVideoSettings "should_log_spatial_video_projection_frame"
Assert-Contains "Native video receipt sampling" $nativeVideoSettings "should_log_spatial_video_projection_import"
Assert-Contains "Native video receipt sampling" $nativeVideoProjection "receiptSampling=first-eviction-and-every-60-misses"
Assert-Contains "Immersive geometry" $geometry "Equirect180ShapeOptions"
Assert-Contains "Immersive geometry" $geometry "Equirect360ShapeOptions"
Assert-Contains "Immersive panel carrier" $panelCarrier "world-anchored-transform-retained"
Assert-Contains "Immersive panel carrier" $panelCarrier "fun rebuild("
Assert-Contains "Immersive validation workflow" $validationWorkflow '"video-world-anchored"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"video-head-fixed-border"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"video-recenter"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"video-playback-off"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"video-playback-on"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"background-black"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"background-passthrough"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"background-lut-passthrough"'
Assert-Contains "Immersive validation workflow" $validationWorkflow '"choose-shared-media-folder"'
Assert-Contains "Immersive validation workflow" $validationWorkflow 'bindings.chooseSharedMediaFolder()'
Assert-Contains "Immersive UI action tool" $uiActionTool '"video-world-anchored"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"video-head-fixed-border"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"video-recenter"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"video-playback-off"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"video-playback-on"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"background-black"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"background-passthrough"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"background-lut-passthrough"'
Assert-Contains "Immersive UI action tool" $uiActionTool '"choose-shared-media-folder"'
Assert-Contains "Immersive coordinator" $coordinator "fun selectPrevious("
Assert-Contains "Immersive coordinator" $coordinator "fun selectNext("
Assert-Contains "Immersive coordinator" $coordinator "status=catalog-ready"
Assert-Contains "Immersive coordinator" $coordinator "projectionClassLocked=false"

Assert-Contains "Immersive route tests" $routeTest "routesEquirect180SideBySideStereo"
Assert-Contains "Immersive route tests" $routeTest "routesEquirect360MonoEvenWhenSourceMetadataWouldClaimStereo"
Assert-Contains "Immersive route tests" $routeTest "routesAReadableGrantedMediaContentUriWithoutBroadStorageAccess"
Assert-Contains "Immersive route tests" $routeTest "sharedPlainMediaCanSeedTheCatalogWithoutEnablingPlaybackAtLaunch"
Assert-Contains "Immersive route tests" $routeTest "rejectsUnknownProjectionInsteadOfGuessingFromFilenameOrMetadata"

Assert-Contains "Background policy" $background 'Black("black")'
Assert-Contains "Background policy" $background 'Passthrough("passthrough")'
Assert-Contains "Background policy" $background 'LutPassthrough("lut-passthrough")'
Assert-Contains "Background policy" $background "diagnosticLutRequested"
Assert-Contains "Background controls" $controlPanel 'Media("Media library"'
Assert-Contains "Background controls" $controlPanel 'Section("Background")'
Assert-Contains "Background controls" $controlPanel 'label = "LUT passthrough"'
Assert-Contains "Background profiles" $profiles "backgroundMode: String? = null"
Assert-Contains "Background profiles" $profiles "backgroundMode?.let { SpatialBackgroundMode.fromToken(it).token }"
Assert-Contains "Background profiles" $profiles "SpatialBackgroundMode.fromToken(backgroundMode)"
Assert-Contains "Background profiles" $profiles "legacyBlackBackground"
Assert-Contains "Background activity" $activity "fun setSpatialBackgroundMode("
Assert-Contains "Background activity" $activity "diagnosticPassthroughLutRequested"
Assert-Contains "Background LUT" $passthroughLut "Scene.setPassthroughLUT"
Assert-Contains "Background tests" $backgroundTest "eachBackgroundModeResolvesOneExplicitCompositionPolicy"
Assert-Contains "Background tests" $backgroundTest "retainedDiagnosticLutComposesWithTheSelectedBackground"

Assert-Contains "Immersive staging tool" $stageTool 'throw "-Serial or RUSTY_QUEST_SERIAL is required'
Assert-Contains "Immersive staging tool" $stageTool '@("-s", $script:Serial)'
Assert-Contains "Immersive staging tool" $stageTool "/sdcard/Movies/RustyQuestImmersiveVideo"
Assert-Contains "Immersive staging tool" $stageTool "content://media/external/video/media/`$mediaStoreId"
Assert-Contains "Immersive staging tool" $stageTool '"-f", "0x1"'
Assert-Contains "Immersive staging tool" $stageTool "single_uri_read_grant = `$true"
Assert-Contains "Immersive staging tool" $stageTool "broad_shared_storage_permission_required = `$false"
Assert-Contains "Immersive staging tool" $stageTool "videos_bundled_in_apk = `$false"
Assert-Contains "Immersive staging tool" $stageTool "metadata_autodetection_authority = `$false"
Assert-Contains "Immersive staging tool" $stageTool "transfer_verified"
Assert-Contains "Immersive staging tool" $stageTool '$markerOutput.Contains("advancing=true")'
Assert-Contains "Immersive staging tool" $stageTool '"--pid=$targetPid", "*:E"'
Assert-Contains "Immersive staging tool" $stageTool "target_process_alive_after_observation"
Assert-Contains "Immersive staging tool" $stageTool "target_fatal_detected"
Assert-Contains "Immersive staging tool" $stageTool "bounded_runtime_validation_passed"
Assert-Contains "Immersive staging tool" $stageTool '"RQSpatialCameraPanel:I"'
Assert-NotContains "Immersive staging tool" $stageTool "kill-server"
Assert-NotContains "Immersive staging tool" $stageTool "start-server"

Assert-Contains "Offline pack tool" $packTool "[Security.Cryptography.AesGcm]::new"
Assert-Contains "Offline pack tool" $packTool '$aes.Encrypt('
Assert-Contains "Offline pack tool" $packTool '$aes.Decrypt('
Assert-Contains "Offline pack tool" $packTool "embedded_key_written_to_pack = `$false"
Assert-Contains "Offline pack tool" $packTool "plaintext_files_written = `$false"
Assert-NotContains "Offline pack tool" $packTool "Write-Output `$KeyHex"

Assert-Contains "Offline pack installer" $installPackTool '@("-s", $script:Serial)'
Assert-Contains "Offline pack installer" $installPackTool '"/sdcard/Documents/RustySpatialMedia"'
Assert-Contains "Offline pack installer" $installPackTool 'storage_access_framework_selection_required'
Assert-NotContains "Offline pack installer" $installPackTool "/sdcard/Android/obb/`$PackageName"
Assert-Contains "Offline pack installer" $installPackTool "IMMERSIVE_VIDEO_OFFLINE_PACK_ID"
Assert-Contains "Offline pack installer" $installPackTool "key_transferred_separately = `$false"
Assert-Contains "Offline pack installer" $installPackTool "plaintext_files_staged = 0"
Assert-Contains "Offline pack installer" $installPackTool "advancing=true"
Assert-Contains "Offline pack installer" $installPackTool "PackagedInApk"
Assert-Contains "Offline pack installer" $installPackTool "assets/offline-media-packs/`$packId/`$name"
Assert-NotContains "Offline pack installer" $installPackTool "kill-server"
Assert-NotContains "Offline pack installer" $installPackTool "start-server"

Assert-Contains "Spatial Camera Panel build tool" $buildTool '[ValidateSet("Debug", "Release")]'
Assert-Contains "Spatial Camera Panel build tool" $buildTool '":app:assemble$BuildType"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'spatial_sdk_version = "0.13.2"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'media3_version = "1.4.1"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'immersive_video_media_packaged = $offlineMediaPackagedAssets'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'offline_immersive_media_pack_supported = $true'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'offline_media_key_embedded_prototype = $offlineMediaEmbeddedKeyEnabled'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'offline_media_key_value_recorded = $false'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'offline_immersive_media_packaged_assets = $offlineMediaPackagedAssets'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'offline_immersive_media_plaintext_file_written = $false'
Assert-Contains "Spatial Camera Panel build tool" $buildTool 'camera_hwb_projection_right_secondary_behavior = "direct-video-recenter-existing-entity"'
Assert-Contains "Spatial Camera Panel build tool" $buildTool '"video-recenter"'

Write-Host "Spatial Camera Panel immersive-video static checks passed"
