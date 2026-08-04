# Rusty Spatial Video Player Labs onboarding

Rusty Spatial Video Player is a media-free Meta Spatial SDK application. It
plays local videos directly from a wearer-authorized document folder and offers
bounded controls through the separately installed Rusty Connection Hub.

## Install

1. Install the current Rusty Connection Hub Labs APK.
2. Install the matching Rusty Spatial Video Player Labs APK. Both artifacts must
   retain the release manifest signer recorded beside their downloads.
3. Launch Connection Hub, choose paired control or explicitly opt into
   **Unsafe trusted-LAN plaintext**, and connect the browser shown by the Hub.
   The plaintext option has no confidentiality and is not production eligible.
4. Leave Connection Hub running and launch Rusty Spatial Video Player.

The player surface appears in the already-connected Hub browser. Connection Hub
continues running when the player closes or another surface provider launches.

## Prepare the folder

Create `Documents/RustySpatialMedia` on the headset. Launch the player, choose
**Choose folder**, open that exact folder, and confirm **Use this folder**. If
the provider supplies write permission, the app creates the complete fixed
taxonomy without writing any video bytes.

Put each `.mp4` in exactly one directory:

```text
RustySpatialMedia/plain-videos/<shape>/<stereo>/video.mp4
```

Shapes are `flat`, `equirect-180`, and `equirect-360`. Stereo layouts are
`mono`, `side-by-side-left-right`, and `top-bottom`. For top/bottom the top image
is the left eye; for side-by-side the left image is the left eye.

Press **Reload** after adding or removing files. Reload reconstructs this
player's bounded immutable Spatial panel registrations; it does not stop or
reconfigure Connection Hub. Up to eleven validated user videos appear beside
one bundled fallback clip.

## Validation and rejection

Folder placement declares intended geometry, not truth. Before listing a file,
the player checks that it is a readable video, has positive duration, uses zero
container rotation, has bounded dimensions, yields a decoded sample matching
container geometry, has an even packed axis for stereo, and has a per-eye aspect
consistent with the declared projection. Filenames, embedded guesses, browser
arguments, URLs, and arbitrary paths never select projection or stereo.

The on-headset panel reports accepted, rejected, and probed counts. A rejected
file remains untouched. Move it to the correct taxonomy directory or transcode
it, then reload.

## CLI workflow

`tools/Invoke-SpatialVideoPlayerQuest.ps1` supplies pinned, typed actions for
APK inspection/install/launch/observation, fixed-taxonomy file staging, and
bounded app/Hub marker capture. It uses QuestIonAble File Manager for APK and
file mutations. The only direct ADB route is the read-only bounded log capture.

Example for a 4096×4096, 60 fps, equirectangular 360° top/bottom MP4:

```powershell
$common = @{
  Serial = '<exact-quest-serial>'
  FileManagerCli = '<pinned-questionable-file-manager.exe>'
  FileManagerSha256 = '<exact-sha256>'
}
pwsh -File .\tools\Invoke-SpatialVideoPlayerQuest.ps1 -Action PrepareFolder @common `
  -ConfirmAdbDirectoryFallback
pwsh -File .\tools\Invoke-SpatialVideoPlayerQuest.ps1 -Action StageVideo @common `
  -Video '<video.mp4>' -Shape equirect-360 -Stereo top-bottom
pwsh -File .\tools\Invoke-SpatialVideoPlayerQuest.ps1 -Action Install @common `
  -Apk '<rusty-spatial-video-player.apk>'
pwsh -File .\tools\Invoke-SpatialVideoPlayerQuest.ps1 -Action Launch @common `
  -Apk '<rusty-spatial-video-player.apk>'
```

The source video is never bundled into the APK or release.
`PrepareFolder` is the one named fallback: current QFM has bounded file push but
no fixed-taxonomy directory-create operation, so the CLI requires an explicit
switch and dispatches only the ten hard-coded `mkdir -p` targets shown above.

## Security and lifecycle boundaries

- Connection Hub owns pairing, controller sessions, leases, replay protection,
  expiry, revoke, listener lifecycle, and WebSocket continuity.
- The player owns its persisted folder grant, catalog validation, Media3 decode,
  Spatial SDK presentation, and observed effect state.
- The Hub protocol carries only low-rate surface state and four empty-argument
  commands. It carries no video bytes, paths, URLs, content URIs, uploads,
  intents, shell commands, or arbitrary JSON execution.
- The app-local legacy listener is stopped by default. Connection Hub's
  plaintext trusted-LAN option remains explicit, visibly unsafe, and revocable.
