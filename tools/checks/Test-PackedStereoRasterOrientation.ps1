param([string]$RepoRoot=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),[string]$EvidenceRoot='')
$QuestRoot=$RepoRoot
if(-not$EvidenceRoot){$EvidenceRoot=Join-Path $RepoRoot (".local/packed-raster-check-"+[guid]::NewGuid().ToString('N'))}
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
New-Item -ItemType Directory $EvidenceRoot -ErrorAction Stop|Out-Null
$media=Join-Path $QuestRoot 'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media'
$native=Join-Path $QuestRoot 'apps/spatial-camera-panel-android/native-receipt'
$gl=[IO.File]::ReadAllText((Join-Path $media 'PackedStereoGlCompositor.java'))
$encoder=[IO.File]::ReadAllText((Join-Path $media 'PackedStereoEncoderWorker.java'))
$shader=[IO.File]::ReadAllText((Join-Path $native 'shaders/packed_sbs_normalize.frag.glsl'))
$normalizer=[IO.File]::ReadAllText((Join-Path $native 'src/packed_sbs_normalizer.rs'))
$import=[IO.File]::ReadAllText((Join-Path $native 'src/spatial_stereo_source_import.rs'))
if(-not$gl.Contains('input.surfaceTexture.getTransformMatrix(transform)')-or-not$gl.Contains('vUv=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy')){throw 'SurfaceTexture matrix application absent'}
if(-not$gl.Contains('-1f, -1f, 0f, 0f')-or-not$gl.Contains('-1f,  1f, 0f, 1f')-or-not$gl.Contains('leftTexture, identity()')-or-not$gl.Contains('rightTexture, identity()')){throw 'Actual GL snapshot/pack orientation differs'}
if(-not$encoder.Contains('-1,-1,0,0')-or-not$encoder.Contains('v=uv;gl_Position=vec4(position,0.,1.)')){throw 'Actual GL encoder orientation differs'}
if(-not$normalizer.Contains('height: self.extent.height as f32')){throw 'Actual Vulkan viewport differs'}
$fixed=$shader.Contains('pc.sourceBottomUp != 0u ? 1.0 - localUv.y : localUv.y')
if(-not$fixed){throw 'Own GL bottom-up source raster is not normalized before common processing'}
if(-not$fixed-and-not$shader.Contains('vec2 packedUv = vec2(halfOrigin + localUv.x * 0.5, localUv.y)')){throw 'Actual normalize mapping differs'}
if($fixed-and(-not$import.Contains('normalizer.set_source_bottom_up(matches!(&frame.lease,StereoSourceLease::Own(_)))'))){throw 'Own/Peer origin selection absent'}
if(-not$import.Contains('let previous=self.sources[origin].take()')-or-not$import.Contains('previous.normalizer.destroy(device)')-or-not$import.Contains('self.sources[origin]=Some(ImportedSource{normalizer,image,frame})')){throw 'Per-origin normalizer lifetime is not freshly bound'}
$cases=@()
# Source cells encode eye, X and canonical top-down Y. Android SurfaceTexture
# matrix is represented by a supported flip-Y fixture. Applying it renders a
# canonical GL image: top at GL t=1. Identity packing preserves that GL raster.
# Positive Vulkan viewport/uv samples y=0 at output top. These are coordinate
# semantics, not a captured headset matrix or GPU/device acceptance claim.
foreach($eye in 0,1){foreach($x in 0,1){foreach($y in 0,1){
 $expected="eye$eye-x$x-y$y"
 $glPackedRows=@("eye$eye-x$x-y1","eye$eye-x$x-y0")
 $ownSampleY=if($fixed){1-$y}else{$y}
 $own=$glPackedRows[$ownSampleY]
 $peerDecodedRows=@("eye$eye-x$x-y0","eye$eye-x$x-y1")
 $peer=$peerDecodedRows[$y]
 if(($own-ceq$expected)-ne$fixed){throw 'Own mapping regression did not match actual shader'}
 if($peer-cne$expected){throw 'Peer decoder raster regressed'}
 $cases+=@{eye=$eye;x=$x;top_down_y=$y;expected=$expected;own_observed=$own;peer_observed=$peer;own_correct=$own-ceq$expected;peer_correct=$true}
}}}
# A non-symmetric interior point demonstrates Y reflection without X reflection
# or eye exchange. Eye-local clamping/half-origin remains the actual shader.
if(-not$shader.Contains('halfOrigin + localUv.x * 0.5')-or-not$shader.Contains('halfOrigin + 0.5 - packedInset.x')){throw 'Eye boundary mapping changed'}
@{passed=$true;baseline_vertical_reflection_reproduced=-not$fixed;candidate_own_correct=$fixed;peer_unchanged=$true;source_derived_coordinate_fixture=$true;device_matrix_captured=$false;physical_orientation_qualified=$false;cases=$cases;shader_sha256=(Get-FileHash (Join-Path $native 'shaders/packed_sbs_normalize.frag.glsl')).Hash.ToLowerInvariant()}|ConvertTo-Json -Depth 12|Set-Content (Join-Path $EvidenceRoot 'result.json')
Write-Host 'Source-derived GL packing -> Vulkan normalization -> common-raster mapping PASS'
