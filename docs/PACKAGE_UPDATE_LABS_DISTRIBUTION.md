# Package Update Labs Distribution

This runbook activates the public Rusty Kiosk Labs update feed and the
co-installable Rusty Quest Package Updater Labs app. It configures distribution
state; it does not grant device installation authority or remove the wearer's
Package Installer confirmation.

## Promotion order

1. Land and activate the repository's external-validation authority. Complete
   its adversarial same-name required-check probe before exposing any signing
   or publication environment to default-branch code.
2. Enable immutable releases for `MesmerPrism/rusty-quest` through GitHub's
   `2026-03-10` repository API and read back `enabled=true` with an independent
   administration-read credential.
3. Configure `package-updater-labs-release` for only
   `package-updater-v0.1.0-alpha.*`. Keep the updater keystore values and the
   release-settings attestation HMAC key as environment secrets. Keep the
   closed updater policy values as environment variables.
4. Immediately before creating one updater tag, attest the live immutability
   readback. The authenticated message binds schema, attestor key id,
   repository, API version, `enabled=true`, `enforced_by_owner`, exact release
   tag, exact 40-hex source revision, observation time, and expiry. It is valid
   for at most two hours and must be no older than 30 minutes when the workflow
   starts. Regenerate it for every release; never reuse it for another tag or
   source revision.
5. Publish the updater tag only after the exact source is on protected `main`.
   A successful workflow requires the draft to be mutable, the promoted/live
   prerelease to report `immutable=true`, the exact four assets to retain their
   IDs and digests, and Labs to remain non-latest.
6. Bootstrap the orphan `package-update-labs-feed` branch with only a canonical
   `.nojekyll` file. Create it through the dedicated repository write deploy
   key; do not base it on source history and do not force-push it.
7. Protect exactly `refs/heads/package-update-labs-feed` with active creation,
   update, deletion, and non-fast-forward rules. GitHub represents a deploy-key
   bypass as `actor_type=DeployKey` and `actor_id=null`, so separately audit
   that the repository has exactly one enabled write deploy key. Record the
   ruleset ID in `PACKAGE_UPDATE_LABS_FEED_RULESET_ID`.
8. In `package-update-labs-publication`, allow only `main`. Store
   `PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_BASE64` and the manifest signing seed as
   secrets. Store the exact deploy-key SHA-256 fingerprint and feed ruleset ID
   as variables. The workflow derives the public key, compares the fingerprint,
   uses a pinned GitHub Ed25519 host key and SSH origin, pushes without force,
   and requires exact remote-tip readback.
9. Enable GitHub Pages with Actions as its build type. Configure the
   `github-pages` environment for only `main`. The deploy job uploads the exact
   feed commit as a Pages artifact and verifies that commit contains only
   `.nojekyll` and the Kiosk Labs feed subtree with regular-file Git modes.
10. Use `https://mesmerprism.com/rusty-quest/` as the canonical, non-redirecting
    feed origin. First release and install an updater build pinned to that
    custom-domain origin. Then explicitly dispatch the protected publisher with
    `migrate_github_pages_project_origin_to_custom_domain=true`; scheduled runs
    cannot arm the migration. The publisher permits exactly one authenticated
    refresh from the former `https://mesmerprism.github.io` tuple: the prior
    pointer and envelope must be hash-pinned and signature-authenticated;
    package, ring, signer, key, path, version, APK hash, byte count, and filename
    must remain identical. It also requires a direct, redirect-free response
    whose bytes and SHA-256 match the pinned APK. Once the pointer uses the
    custom domain, supplying the migration assertion again is rejected and
    ordinary signed refresh rules resume.

## Required configuration names

`package-updater-labs-release` secrets:

- `PACKAGE_UPDATER_KEYSTORE_BASE64`
- `PACKAGE_UPDATER_KEYSTORE_PASSWORD`
- `PACKAGE_UPDATER_KEY_ALIAS`
- `PACKAGE_UPDATER_KEY_PASSWORD`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_HMAC_BASE64`

Its attestation variables are:

- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_KEY_ID`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_RELEASE_TAG`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_SOURCE_SHA`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_OBSERVED_AT_MS`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_EXPIRES_AT_MS`
- `PACKAGE_UPDATER_RELEASE_SETTINGS_ATTESTATION_HMAC_SHA256`

`package-update-labs-publication` secrets:

- `PACKAGE_UPDATER_MANIFEST_SIGNING_SEED_BASE64URL`
- `PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_BASE64`

Its feed-authority variables are:

- `PACKAGE_UPDATE_LABS_FEED_RULESET_ID`
- `PACKAGE_UPDATE_LABS_FEED_DEPLOY_KEY_FINGERPRINT`

The remaining policy variables are named directly in the two workflows and
must remain exact closed values. Never put private keys, HMAC keys, keystores,
local paths, device identities, or access tokens in Git, release metadata, feed
receipts, or workflow logs.

## Rotation and recovery

Hold both environments before changing authority. For deploy-key rotation,
remove the old key and install the new key within one bounded maintenance
window, restore the exactly-one-write-key inventory, update the secret and
fingerprint together, and re-read the ruleset before resuming. A second write
deploy key broadens GitHub's class-level bypass and is a hold condition.

Feed history is append-only. Never rewrite an existing content-addressed APK,
generation, or pointer history. A failed transaction removes only run-owned
new files and restores the prior pointer. If remote and local feed tips differ,
stop rather than force or merge. If release immutability, validation authority,
environment policy, deploy-key inventory, release identity, or Pages source
cannot be proven, keep publication disabled and retain the evidence for repair.
