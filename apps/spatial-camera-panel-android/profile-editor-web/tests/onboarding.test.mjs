import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../", import.meta.url);
const read = path => readFile(new URL(path, root), "utf8");

test("portal visibly links to the onboarding workflow", async () => {
  const portal = await read("index.html");
  assert.match(portal, /href="onboarding\/"/);
  assert.match(portal, /Setup &amp; Quest workflow/);
});

test("onboarding separates APK installation from profile interchange", async () => {
  const guide = await read("onboarding/index.html");
  assert.match(guide, /Meta Quest File Manager/);
  assert.match(guide, /Invoke-SpatialVrStrobeProfileTransfer\.ps1/);
  assert.match(guide, /rusty\.quest\.spatial_vr_strobe\.profile_bundle\.v1/);
  assert.match(guide, /they do not live-sync/i);
  assert.match(guide, /Right A/);
  assert.match(guide, /Left X/);
  assert.match(guide, /Right trigger/);
  assert.match(guide, /trevorhewitt\/vr_strobe/);
});
