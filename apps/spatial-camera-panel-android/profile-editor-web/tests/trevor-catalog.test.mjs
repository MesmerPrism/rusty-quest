import assert from "node:assert/strict";
import test from "node:test";
import { makeBundle, validateBundle } from "../profile-contract.mjs";
import {
  TREVOR_PORTAL_CARDS,
  TREVOR_SOURCE_COMMIT,
  createTrevorOriginalProfiles,
  getTrevorOriginalProfile,
} from "../trevor-catalog.mjs";

const ORIGINAL_TITLES = [
  "Simulated 7 Hz Closed Eye Lucia",
  "Simulated 40 Hz Open Eye Roxiva",
  "Simulated red",
  "Simulated blorbs",
  "Simulated shlorgs",
  "Real 7 Hz Black & White Strobe",
  "Real 14 Hz Noistrobe",
  "Real 20 Hz Black & White Strobe",
  "Real 12 Hz Red Strobe",
];

test("Trevor's pinned portal catalog is complete and ordered", () => {
  assert.equal(TREVOR_SOURCE_COMMIT, "52c71cc069f4102bc4148e05c5fd3fc4d5466479");
  assert.deepEqual(TREVOR_PORTAL_CARDS.map(card => card.title), ORIGINAL_TITLES);
  assert.equal(TREVOR_PORTAL_CARDS.filter(card => card.category === "Simulation").length, 5);
  assert.equal(TREVOR_PORTAL_CARDS.filter(card => card.category === "Strobe").length, 4);
});

test("all nine source profiles validate as one Quest bundle", () => {
  const originals = createTrevorOriginalProfiles();
  const bundle = validateBundle(makeBundle(originals));
  assert.equal(bundle.profile_count, 9);
  assert.deepEqual(bundle.profiles.map(profile => profile.title), ORIGINAL_TITLES);
  assert.ok(bundle.profiles.slice(0, 5).every(profile => profile.profile.source_payload));
});

test("source profiles are cloned and Quest-sanitized without changing the catalog", () => {
  const first = getTrevorOriginalProfile("source-sim-40hz-pale");
  const second = getTrevorOriginalProfile("source-sim-40hz-pale");
  const ray = first.profile.patterns.find(pattern => pattern.kind === "ray");
  assert.equal(ray.period, 50);
  first.title = "changed locally";
  assert.equal(second.title, "Simulated 40 Hz Open Eye Roxiva");
});
