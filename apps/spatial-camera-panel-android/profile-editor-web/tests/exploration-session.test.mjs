import assert from "node:assert/strict";
import test from "node:test";
import { ProfileExplorationSession } from "../exploration-session.mjs";
import { createInterferenceProfile } from "../profile-contract.mjs";

test("randomization history restores exact profiles in last-in-first-out order", () => {
  const session = new ProfileExplorationSession(3);
  const profile = createInterferenceProfile("history-profile");

  profile.profile.scale = 1;
  session.rememberBeforeRandomize(profile);
  profile.profile.scale = 2;
  session.rememberBeforeRandomize(profile);
  profile.profile.scale = 3;
  session.rememberBeforeRandomize(profile);
  profile.profile.scale = 4;
  session.rememberBeforeRandomize(profile);

  assert.equal(session.restoreLast(profile.id).profile.scale, 4);
  assert.equal(session.restoreLast(profile.id).profile.scale, 3);
  assert.equal(session.restoreLast(profile.id).profile.scale, 2);
  assert.equal(session.restoreLast(profile.id), null);
});

test("session snapshots are independent clones and do not survive a new session authority", () => {
  const source = createInterferenceProfile("source-profile");
  source.title = "Interesting pattern";
  source.profile.title = source.title;
  source.profile.source_payload = "reference-payload";
  const originalScale = source.profile.scale;

  const session = new ProfileExplorationSession();
  const saved = session.storeSnapshot(source, {
    id: "session-profile",
    title: "Interesting pattern — saved 1",
    createdAt: 42,
  });

  source.profile.scale = 12;
  assert.equal(saved.profile.scale, originalScale);
  assert.equal(saved.id, "session-profile");
  assert.equal(saved.profile.id, "session-profile");
  assert.equal(saved.created_at_epoch_ms, 42);
  assert.equal("source_payload" in saved.profile, false);
  assert.equal(new ProfileExplorationSession().savedProfiles.length, 0);
});

test("manual changes can invalidate one profile without clearing another profile's history", () => {
  const first = createInterferenceProfile("first");
  const second = createInterferenceProfile("second");
  const session = new ProfileExplorationSession();
  session.rememberBeforeRandomize(first);
  session.rememberBeforeRandomize(second);
  session.clearHistory(first.id);

  assert.equal(session.restoreLast(first.id), null);
  assert.equal(session.restoreLast(second.id).id, second.id);
});
