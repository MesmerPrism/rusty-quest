const clone = value => structuredClone(value);

export class ProfileExplorationSession {
  constructor(historyLimit = 128) {
    if (!Number.isInteger(historyLimit) || historyLimit < 1) {
      throw new Error("historyLimit must be a positive integer");
    }
    this.historyLimit = historyLimit;
    this.histories = new Map();
    this.savedProfiles = [];
  }

  rememberBeforeRandomize(profile) {
    const history = this.histories.get(profile.id) ?? [];
    history.push(clone(profile));
    if (history.length > this.historyLimit) history.splice(0, history.length - this.historyLimit);
    this.histories.set(profile.id, history);
  }

  restoreLast(profileId) {
    const history = this.histories.get(profileId);
    if (!history?.length) return null;
    const restored = history.pop();
    if (history.length === 0) this.histories.delete(profileId);
    return clone(restored);
  }

  clearHistory(profileId) {
    this.histories.delete(profileId);
  }

  clearAllHistory() {
    this.histories.clear();
  }

  storeSnapshot(profile, { id, title, createdAt = Date.now() }) {
    const saved = clone(profile);
    saved.id = id;
    saved.title = title;
    saved.created_at_epoch_ms = createdAt;
    saved.profile.id = id;
    saved.profile.title = title;
    saved.profile.source_label = `Session save of ${profile.profile.source_label ?? profile.title}`;
    delete saved.profile.source_payload;
    this.savedProfiles.push(saved);
    return saved;
  }

  findSaved(profileId) {
    return this.savedProfiles.find(profile => profile.id === profileId) ?? null;
  }

  replaceSaved(profileId, replacement) {
    const index = this.savedProfiles.findIndex(profile => profile.id === profileId);
    if (index < 0) return false;
    this.savedProfiles[index] = replacement;
    return true;
  }

  removeSaved(profileId) {
    const index = this.savedProfiles.findIndex(profile => profile.id === profileId);
    if (index < 0) return false;
    this.savedProfiles.splice(index, 1);
    this.clearHistory(profileId);
    return true;
  }

  clearSaved() {
    for (const profile of this.savedProfiles) this.clearHistory(profile.id);
    this.savedProfiles.length = 0;
  }
}
