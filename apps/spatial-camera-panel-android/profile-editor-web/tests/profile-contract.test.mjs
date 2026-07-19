import assert from "node:assert/strict";
import test from "node:test";
import {
  PROFILE_BUNDLE_SCHEMA,
  QUEST_RANDOMIZATION_ENVELOPE,
  createInterferenceProfile,
  createTemporalProfile,
  makeBundle,
  randomizeQuestSafe,
  validateBundle,
} from "../profile-contract.mjs";

test("browser bundle round-trips the shared Quest schema", () => {
  const bundle = makeBundle([
    createInterferenceProfile("test-interference"),
    createTemporalProfile("test-temporal"),
  ]);
  const decoded = validateBundle(JSON.parse(JSON.stringify(bundle)));
  assert.equal(decoded.schema, PROFILE_BUNDLE_SCHEMA);
  assert.equal(decoded.profile_count, 2);
  assert.equal(decoded.profiles[0].distance_meters, 4);
  assert.equal(decoded.profiles[0].carrier.concavity, 1);
});

test("browser rejects values Quest would clamp", () => {
  const bundle = makeBundle([createInterferenceProfile("test-bounds")]);
  bundle.profiles[0].distance_meters = 4.01;
  assert.throws(() => validateBundle(bundle), /distance_meters out of bounds/);
});

test("Quest randomization preserves the v3 fine-detail envelope", () => {
  assert.equal(QUEST_RANDOMIZATION_ENVELOPE.id, "quest-reliable-v3");
  assert.deepEqual(QUEST_RANDOMIZATION_ENVELOPE.nonRayPeriod, [1.5, 50]);
  assert.deepEqual(QUEST_RANDOMIZATION_ENVELOPE.rayPeriod, [3, 50]);
  assert.equal(QUEST_RANDOMIZATION_ENVELOPE.fineDetailProbability, 0.60);
  for (let run = 0; run < 200; run += 1) {
    const stored = randomizeQuestSafe(createInterferenceProfile(`random-${run}`));
    validateBundle(makeBundle([stored]));
    assert.ok(stored.profile.patterns.filter(pattern => pattern.active).length <= 3);
    assert.ok(stored.profile.scale >= 0.75 && stored.profile.scale <= 16);
  }
});

test("duplicate ids are rejected before Quest transfer", () => {
  const profile = createTemporalProfile("duplicate");
  const bundle = makeBundle([profile]);
  bundle.profiles.push(structuredClone(profile));
  bundle.profile_count = 2;
  assert.throws(() => validateBundle(bundle), /Duplicate profile id/);
});
