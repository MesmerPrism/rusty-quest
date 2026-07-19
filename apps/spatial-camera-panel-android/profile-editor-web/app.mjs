import {
  INTERFERENCE_FIELDS,
  PATTERN_FIELDS,
  TEMPORAL_FIELDS,
  createInterferenceProfile,
  createPattern,
  createTemporalProfile,
  downloadBundle,
  makeBundle,
  randomizeQuestSafe,
  validateBundle,
} from "./profile-contract.mjs?v=20260719-1";
import {
  TREVOR_PORTAL_CARDS,
  createTrevorOriginalProfiles,
  getTrevorOriginalProfile,
  isTrevorOriginalId,
} from "./trevor-catalog.mjs?v=20260719-1";
import { ProfileExplorationSession } from "./exploration-session.mjs?v=20260719-1";
import { FlatProfileRenderer } from "./renderer.mjs?v=20260719-1";

const STORAGE_KEY = "rusty-quest-vr-strobe-profile-bundle-v1";
const ENTRY_ACK_KEY = "rusty-quest-vr-strobe-warning-ack-v1";
const byId = id => document.getElementById(id);
const elements = {
  entryWarning: byId("entry-warning-view"),
  portal: byId("portal-view"), portalGrid: byId("portal-grid"), customGrid: byId("custom-profile-grid"),
  designer: byId("designer-view"), status: byId("status"), count: byId("profile-count"),
  profileSelect: byId("profile-select"), importFile: byId("import-file"),
  simulationPanel: byId("simulation-panel"), simulationEditor: byId("simulation-editor"),
  simulationMetadata: byId("simulation-metadata"), strobePanel: byId("strobe-panel"),
  strobeBody: byId("strobe-panel-body"), strobeEditor: byId("strobe-editor"),
  strobeMetadata: byId("strobe-metadata"),
  previewToggle: byId("preview-toggle"), summary: byId("selected-summary"),
};

function migrateWithOriginalCatalog(savedBundle) {
  const originals = createTrevorOriginalProfiles();
  const savedById = new Map(savedBundle.profiles.map(profile => [profile.id, profile]));
  const orderedOriginals = originals.map(original => savedById.get(original.id) ?? original);
  const extraProfiles = savedBundle.profiles.filter(profile => !isTrevorOriginalId(profile.id));
  const withoutOldStarter = extraProfiles.filter(profile => !(
    profile.id === "browser-starter-interference" &&
    profile.title === "New interference profile" &&
    profile.profile?.source_label === "Rusty Quest browser editor"
  ));
  return makeBundle([...orderedOriginals, ...withoutOldStarter]);
}

function initialBundle() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) return migrateWithOriginalCatalog(validateBundle(JSON.parse(saved)));
  } catch (error) {
    console.warn("Ignoring invalid local profile bundle", error);
  }
  return makeBundle(createTrevorOriginalProfiles());
}

let bundle = initialBundle();
const exploration = new ProfileExplorationSession();
let selectedId = null;
let previewEnabled = false;
let entryAcknowledged = false;
let strobeExpanded = false;
let statusTimer = null;
let renderer = null;

try {
  entryAcknowledged = sessionStorage.getItem(ENTRY_ACK_KEY) === "accepted";
} catch (error) {
  console.warn("Session acknowledgement storage is unavailable", error);
}

try {
  renderer = new FlatProfileRenderer(byId("preview-canvas"));
} catch (error) {
  showStatus(`Preview unavailable: ${error.message}`, true, 0);
  elements.previewToggle.disabled = true;
}

function allProfiles() {
  return [...bundle.profiles, ...exploration.savedProfiles];
}

function selectedProfile() {
  return bundle.profiles.find(profile => profile.id === selectedId)
    ?? exploration.findSaved(selectedId)
    ?? null;
}

function replaceProfile(profileId, replacement) {
  const persistentIndex = bundle.profiles.findIndex(profile => profile.id === profileId);
  if (persistentIndex >= 0) {
    bundle.profiles[persistentIndex] = replacement;
    return true;
  }
  return exploration.replaceSaved(profileId, replacement);
}

function exportableBundle() {
  return makeBundle(allProfiles());
}

function showStatus(message, error = false, hideAfter = 3500) {
  if (statusTimer) window.clearTimeout(statusTimer);
  elements.status.textContent = message;
  elements.status.className = `status visible${error ? " error" : ""}`;
  if (hideAfter) statusTimer = window.setTimeout(() => {
    if (elements.status.textContent === message) elements.status.className = "status";
  }, hideAfter);
}

function persist(message = "Profile saved locally") {
  bundle.profile_count = bundle.profiles.length;
  bundle = validateBundle(bundle);
  if (exploration.savedProfiles.length) makeBundle(exploration.savedProfiles);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(bundle));
  updatePreview();
  renderProfileSelect();
  elements.count.textContent = `${allProfiles().length} profiles ready to export`;
  if (message) showStatus(message);
}

function updatePreview() {
  const selected = selectedProfile();
  if (renderer) renderer.setProfile(previewEnabled ? selected : null);
  elements.summary.textContent = selected
    ? `${selected.title} · ${selected.kind} · ${selected.distance_meters.toFixed(2)} m · Quest flat/curved metadata retained`
    : "No profile selected";
}

function uniqueId(prefix) {
  const stem = `${prefix}-${Date.now().toString(36)}`;
  let candidate = stem, suffix = 1;
  while (allProfiles().some(profile => profile.id === candidate)) candidate = `${stem}-${suffix++}`;
  return candidate;
}

function makeCard({ title, category, className = "", onClick }) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = `stimulus-card${className ? ` ${className}` : ""}`;
  const heading = document.createElement("h2"); heading.textContent = title;
  const label = document.createElement("span"); label.textContent = category;
  button.append(heading, label);
  button.addEventListener("click", onClick);
  return button;
}

function ensureOriginalProfile(id) {
  let profile = bundle.profiles.find(candidate => candidate.id === id);
  if (!profile) {
    profile = getTrevorOriginalProfile(id);
    if (!profile) throw new Error(`Unknown source profile ${id}`);
    bundle.profiles.push(profile);
    persist(`${profile.title} restored to the Quest profile set`);
  }
  return profile;
}

function renderPortal() {
  elements.portalGrid.replaceChildren();
  for (const card of TREVOR_PORTAL_CARDS) {
    elements.portalGrid.append(makeCard({
      title: card.title,
      category: card.category,
      onClick: () => openDesigner(ensureOriginalProfile(card.id).id),
    }));
  }
  elements.portalGrid.append(
    makeCard({
      title: "Simulated Strobe Design", category: "design page", className: "design-card",
      onClick: () => addProfile("interference"),
    }),
    makeCard({
      title: "Real Strobe Design", category: "design page", className: "design-card",
      onClick: () => addProfile("temporal"),
    }),
  );

  elements.customGrid.replaceChildren();
  const customProfiles = allProfiles().filter(profile => !isTrevorOriginalId(profile.id));
  if (customProfiles.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-library";
    empty.textContent = "No additional profiles yet. Use either design page above to create one.";
    elements.customGrid.append(empty);
  } else {
    for (const profile of customProfiles) {
      elements.customGrid.append(makeCard({
        title: profile.title,
        category: exploration.findSaved(profile.id)
          ? "Session save"
          : profile.kind === "interference" ? "Simulation profile" : "Strobe profile",
        onClick: () => openDesigner(profile.id),
      }));
    }
  }
  elements.count.textContent = `${allProfiles().length} profiles ready to export`;
}

function renderProfileSelect() {
  elements.profileSelect.replaceChildren();
  for (const profile of allProfiles()) {
    const option = document.createElement("option");
    option.value = profile.id;
    const category = exploration.findSaved(profile.id)
      ? "Session save"
      : profile.kind === "interference" ? "Simulation" : "Strobe";
    option.textContent = `${profile.title} — ${category}`;
    elements.profileSelect.append(option);
  }
  elements.profileSelect.value = selectedId ?? "";
}

function addProfile(kind) {
  const id = uniqueId(`browser-${kind}`);
  const profile = kind === "interference" ? createInterferenceProfile(id) : createTemporalProfile(id);
  bundle.profiles.push(profile);
  persist(`${profile.title} added`);
  openDesigner(id);
}

function openDesigner(id) {
  if (!entryAcknowledged) return showEntryWarning();
  const previousKind = selectedProfile()?.kind;
  selectedId = id;
  const selected = selectedProfile();
  if (!selected) return;
  previewEnabled = Boolean(renderer);
  elements.previewToggle.textContent = previewEnabled ? "Pause preview" : "Enable preview";
  if (selected.kind === "temporal" && previousKind !== "temporal") setStrobeExpanded(false);
  elements.entryWarning.classList.add("hidden");
  elements.portal.classList.add("hidden");
  elements.designer.classList.remove("hidden");
  renderDesigner();
}

function showPortal() {
  if (!entryAcknowledged) return showEntryWarning();
  previewEnabled = false;
  if (renderer) renderer.setProfile(null);
  elements.entryWarning.classList.add("hidden");
  elements.designer.classList.add("hidden");
  elements.portal.classList.remove("hidden");
  renderPortal();
}

function showEntryWarning() {
  previewEnabled = false;
  if (renderer) renderer.setProfile(null);
  elements.designer.classList.add("hidden");
  elements.portal.classList.add("hidden");
  elements.entryWarning.classList.remove("hidden");
  byId("warning-accept").focus();
}

function acknowledgeEntryWarning() {
  entryAcknowledged = true;
  try { sessionStorage.setItem(ENTRY_ACK_KEY, "accepted"); }
  catch (error) { console.warn("Could not retain session acknowledgement", error); }
  showPortal();
}

function normalizedValue(descriptor, raw) {
  const [, , min, max, , type] = descriptor;
  if (type !== "number") return raw;
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return null;
  return Math.min(max, Math.max(min, numeric));
}

function commitField(target, descriptor, raw, message = "") {
  const [name] = descriptor;
  const normalized = normalizedValue(descriptor, raw);
  if (normalized === null) return;
  target[name] = normalized;
  if (selectedId) exploration.clearHistory(selectedId);
  try { persist(message); }
  catch (error) { showStatus(error.message, true); }
}

function createControl(target, descriptor, { slider = false } = {}) {
  const [name, , min, max, step, type] = descriptor;
  if (type === "boolean") {
    const input = document.createElement("input");
    input.type = "checkbox"; input.checked = Boolean(target[name]);
    input.addEventListener("change", () => commitField(target, descriptor, input.checked));
    return input;
  }
  if (type === "noise") {
    const select = document.createElement("select");
    for (const [optionValue, optionLabel] of [["white", "WHITE PIXEL"], ["perlin", "PERLIN"]]) {
      const option = document.createElement("option");
      option.value = optionValue; option.textContent = optionLabel; select.append(option);
    }
    select.value = target[name];
    select.addEventListener("change", () => commitField(target, descriptor, select.value));
    return select;
  }
  if (type === "color") {
    const input = document.createElement("input");
    input.type = "color"; input.value = target[name];
    input.addEventListener("input", () => commitField(target, descriptor, input.value));
    return input;
  }
  const number = document.createElement("input");
  number.type = "number"; number.value = target[name]; number.min = min; number.max = max; number.step = step;
  if (!slider) {
    number.addEventListener("change", () => {
      const normalized = normalizedValue(descriptor, number.value);
      if (normalized !== null) number.value = normalized;
      commitField(target, descriptor, number.value);
    });
    return number;
  }
  const paired = document.createElement("div"); paired.className = "paired-input";
  const range = document.createElement("input");
  range.type = "range"; range.min = min; range.max = max; range.step = step; range.value = target[name];
  range.addEventListener("input", () => { number.value = range.value; commitField(target, descriptor, range.value); });
  number.addEventListener("change", () => {
    const normalized = normalizedValue(descriptor, number.value);
    if (normalized === null) return;
    number.value = normalized; range.value = normalized; commitField(target, descriptor, normalized);
  });
  paired.append(range, number);
  return paired;
}

function appendSimulationField(grid, target, descriptor) {
  const label = document.createElement("label"); label.textContent = descriptor[1];
  grid.append(label, createControl(target, descriptor, { slider: descriptor[5] === "number" }));
}

function simulationSection(title, open = true) {
  const details = document.createElement("details"); details.open = open;
  const summary = document.createElement("summary"); summary.textContent = title;
  const grid = document.createElement("div"); grid.className = "field-grid";
  details.append(summary, grid);
  return { details, grid };
}

function renderPatternGroup(stored, kind, title) {
  const patterns = stored.profile.patterns.filter(pattern => pattern.kind === kind);
  const details = document.createElement("details");
  const summary = document.createElement("summary"); summary.textContent = `${title} (${patterns.length}/8)`;
  const content = document.createElement("div"); content.className = "pattern-content";
  const toolbar = document.createElement("div"); toolbar.className = "pattern-toolbar";
  const add = document.createElement("button"); add.type = "button"; add.textContent = "Add +";
  add.addEventListener("click", () => {
    if (patterns.length >= 8) return showStatus(`Quest supports at most 8 ${kind} patterns`, true);
    stored.profile.patterns.push(createPattern(kind)); exploration.clearHistory(stored.id); persist("Pattern added"); renderDesigner();
  });
  toolbar.append(add); content.append(toolbar);
  patterns.forEach((pattern, groupIndex) => {
    const actualIndex = stored.profile.patterns.indexOf(pattern);
    const block = document.createElement("div"); block.className = "pattern-block";
    const heading = document.createElement("div"); heading.className = "pattern-heading";
    const label = document.createElement("strong"); label.textContent = `${title} ${groupIndex + 1}`;
    const remove = document.createElement("button"); remove.type = "button"; remove.textContent = "Remove -";
    remove.addEventListener("click", () => {
      stored.profile.patterns.splice(actualIndex, 1); exploration.clearHistory(stored.id); persist("Pattern removed"); renderDesigner();
    });
    heading.append(label, remove);
    const grid = document.createElement("div"); grid.className = "field-grid";
    PATTERN_FIELDS.forEach(descriptor => appendSimulationField(grid, pattern, descriptor));
    block.append(heading, grid); content.append(block);
  });
  details.append(summary, content);
  return details;
}

function renderSimulationEditor(stored) {
  elements.simulationEditor.replaceChildren();
  const groups = [...new Set(INTERFERENCE_FIELDS.map(field => field[6]))];
  for (const group of groups) {
    const { details, grid } = simulationSection(group, true);
    INTERFERENCE_FIELDS.filter(field => field[6] === group).forEach(field => appendSimulationField(grid, stored.profile, field));
    elements.simulationEditor.append(details);
  }
  for (const [kind, title] of [
    ["perlin", "Perlin Fields (Noise)"], ["stripe", "Stripes"], ["ripple", "Ripples"], ["ray", "Rays"],
  ]) elements.simulationEditor.append(renderPatternGroup(stored, kind, title));
}

function strobeField(target, descriptor) {
  const field = document.createElement("div");
  field.className = `strobe-field${descriptor[5] === "boolean" ? " checkbox-field" : ""}`;
  const label = document.createElement("label"); label.textContent = descriptor[1];
  const control = createControl(target, descriptor);
  if (descriptor[5] === "boolean") field.append(control, label);
  else field.append(label, control);
  return field;
}

function renderStrobeEditor(stored) {
  elements.strobeEditor.replaceChildren();
  const groups = [...new Set(TEMPORAL_FIELDS.map(field => field[6]))];
  for (const group of groups) {
    const block = document.createElement("section"); block.className = "strobe-control-group";
    if (group === "Noise Parameters") {
      const label = document.createElement("span"); label.className = "group-label"; label.textContent = group; block.append(label);
    }
    const fields = document.createElement("div"); fields.className = "strobe-fields";
    TEMPORAL_FIELDS.filter(field => field[6] === group).forEach(field => fields.append(strobeField(stored.profile, field)));
    block.append(fields); elements.strobeEditor.append(block);
  }
  const control = document.createElement("section"); control.className = "strobe-control-group";
  const label = document.createElement("span"); label.className = "group-label"; label.textContent = "CONTROL";
  const preview = document.createElement("button"); preview.type = "button";
  preview.textContent = previewEnabled ? "PAUSE" : "PLAY";
  preview.addEventListener("click", togglePreview);
  control.append(label, preview); elements.strobeEditor.append(control);
}

function renderMetadata(container, stored) {
  container.replaceChildren();
  const grid = document.createElement("div"); grid.className = "metadata-grid";
  const titleLabel = document.createElement("label"); titleLabel.textContent = "Profile name";
  const title = document.createElement("input"); title.type = "text"; title.value = stored.title;
  title.addEventListener("change", () => {
    const next = title.value.trim();
    if (!next) { title.value = stored.title; return showStatus("Profile name cannot be empty", true); }
    stored.title = next; stored.profile.title = next; exploration.clearHistory(stored.id);
    persist("Profile renamed"); renderProfileSelect(); renderPortal(); updatePreview();
  });
  grid.append(titleLabel, title);

  const distance = ["distance_meters", "Quest distance (m)", 1.05, 4, 0.05, "number"];
  const curved = ["curved_mode", "Quest curved mode", null, null, null, "boolean"];
  const concavity = ["concavity", "Quest concavity", 0, 1, 0.01, "number"];
  for (const [descriptor, target] of [[distance, stored], [curved, stored.carrier], [concavity, stored.carrier]]) {
    const label = document.createElement("label"); label.textContent = descriptor[1];
    grid.append(label, createControl(target, descriptor, { slider: descriptor[5] === "number" && stored.kind === "interference" }));
  }
  const sourceLabel = document.createElement("label"); sourceLabel.textContent = "Source";
  const source = document.createElement("span"); source.className = "readback";
  source.textContent = stored.profile.source_label ?? "Rusty Quest browser editor";
  grid.append(sourceLabel, source);
  container.append(grid);
}

function renderDesigner() {
  const stored = selectedProfile();
  if (!stored) return showPortal();
  renderProfileSelect();
  const interference = stored.kind === "interference";
  elements.designer.classList.toggle("simulation-mode", interference);
  elements.designer.classList.toggle("strobe-mode", !interference);
  elements.simulationPanel.classList.toggle("hidden", !interference);
  elements.strobePanel.classList.toggle("hidden", interference);
  if (interference) {
    renderSimulationEditor(stored);
    renderMetadata(elements.simulationMetadata, stored);
  } else {
    renderStrobeEditor(stored);
    renderMetadata(elements.strobeMetadata, stored);
  }
  byId("duplicate-button").disabled = false;
  byId("delete-button").disabled = false;
  updatePreview();
}

function setStrobeExpanded(expanded) {
  strobeExpanded = expanded;
  elements.strobePanel.classList.toggle("collapsed", !expanded);
  elements.strobeBody.classList.toggle("hidden", !expanded);
  byId("strobe-header").setAttribute("aria-expanded", String(expanded));
  byId("strobe-panel-title").textContent = expanded ? "CONTROLS" : "STROBE";
  byId("strobe-toggle-label").textContent = expanded ? "COLLAPSE" : "EXPAND";
}

function togglePreview() {
  if (!renderer) return showStatus("Preview is unavailable in this browser", true);
  if (previewEnabled) {
    previewEnabled = false;
    elements.previewToggle.textContent = "Enable preview";
  } else {
    previewEnabled = true;
    elements.previewToggle.textContent = "Pause preview";
  }
  renderDesigner();
}

function resetSelectedProfile() {
  const current = selectedProfile();
  if (!current) return;
  exploration.clearHistory(current.id);
  let reset = getTrevorOriginalProfile(current.id);
  if (!reset) {
    reset = current.kind === "interference" ? createInterferenceProfile(current.id) : createTemporalProfile(current.id);
    reset.title = current.title; reset.profile.title = current.title;
    reset.created_at_epoch_ms = current.created_at_epoch_ms;
    reset.distance_meters = current.distance_meters;
    reset.carrier = structuredClone(current.carrier);
  }
  replaceProfile(current.id, reset);
  persist(isTrevorOriginalId(current.id) ? "Original Trevor profile restored" : "Profile parameters reset");
  renderDesigner();
}

function downloadCurrentBundle() {
  const downloadable = exportableBundle();
  downloadBundle(downloadable);
  showStatus(`${downloadable.profiles.length} profile(s) exported for Quest`);
}

function selectDesignerProfile(id, { preservePreview = true } = {}) {
  const previousKind = selectedProfile()?.kind;
  selectedId = id;
  const selected = selectedProfile();
  if (!selected) return;
  if (!preservePreview) previewEnabled = false;
  if (selected.kind === "temporal" && previousKind !== "temporal") setStrobeExpanded(false);
  elements.previewToggle.textContent = previewEnabled ? "Pause preview" : "Enable preview";
  renderDesigner();
}

function cycleProfiles(direction) {
  const profiles = allProfiles();
  if (!profiles.length) return;
  const currentIndex = profiles.findIndex(profile => profile.id === selectedId);
  const start = currentIndex < 0 ? 0 : currentIndex;
  const nextIndex = (start + direction + profiles.length) % profiles.length;
  selectDesignerProfile(profiles[nextIndex].id);
  showStatus(`${profiles[nextIndex].title} · ${nextIndex + 1} of ${profiles.length}`, false, 1800);
}

function randomizeSelectedProfile() {
  const current = selectedProfile();
  if (!current) return;
  exploration.rememberBeforeRandomize(current);
  try {
    replaceProfile(current.id, randomizeQuestSafe(current));
    persist("Quest-safe randomization applied");
    renderDesigner();
  } catch (error) {
    exploration.restoreLast(current.id);
    showStatus(`Randomization failed: ${error.message}`, true, 0);
  }
}

function restoreLastRandomizedProfile() {
  const current = selectedProfile();
  if (!current) return;
  const restored = exploration.restoreLast(current.id);
  if (!restored) return showStatus("No earlier randomization for this profile", false, 2200);
  replaceProfile(current.id, restored);
  persist("Previous randomized profile restored");
  renderDesigner();
}

function storeCurrentInSession() {
  const current = selectedProfile();
  if (!current) return;
  let saveNumber = 1;
  let title = `${current.title} — saved ${saveNumber}`;
  while (allProfiles().some(profile => profile.title === title)) {
    title = `${current.title} — saved ${++saveNumber}`;
  }
  const id = uniqueId(`session-${current.kind}`);
  const saved = exploration.storeSnapshot(current, { id, title, createdAt: Date.now() });
  try {
    makeBundle([saved]);
    renderProfileSelect();
    renderPortal();
    updatePreview();
    showStatus(`${title} stored for this browser session`);
  } catch (error) {
    exploration.removeSaved(id);
    showStatus(`Session save failed: ${error.message}`, true, 0);
  }
}

function editableShortcutTarget(target) {
  return target instanceof Element && Boolean(target.closest("input, textarea, select, [contenteditable='true']"));
}

function handleDesignerShortcut(event) {
  if (elements.designer.classList.contains("hidden") || editableShortcutTarget(event.target)) return;
  if (event.ctrlKey || event.metaKey || event.altKey) return;
  const action = {
    ArrowLeft: () => cycleProfiles(-1),
    ArrowRight: () => cycleProfiles(1),
    ArrowUp: randomizeSelectedProfile,
    ArrowDown: restoreLastRandomizedProfile,
    " ": togglePreview,
    Spacebar: togglePreview,
    s: storeCurrentInSession,
    S: storeCurrentInSession,
  }[event.key];
  if (!action) return;
  event.preventDefault();
  if (event.repeat) return;
  action();
}

byId("back-button").addEventListener("click", showPortal);
byId("warning-accept").addEventListener("click", acknowledgeEntryWarning);
elements.previewToggle.addEventListener("click", togglePreview);
byId("strobe-header").addEventListener("click", () => setStrobeExpanded(!strobeExpanded));
elements.profileSelect.addEventListener("change", () => {
  selectDesignerProfile(elements.profileSelect.value);
});
byId("randomize-button").addEventListener("click", randomizeSelectedProfile);
byId("reset-profile-button").addEventListener("click", resetSelectedProfile);
byId("strobe-reset-button").addEventListener("click", resetSelectedProfile);
byId("duplicate-button").addEventListener("click", () => {
  const source = selectedProfile(); if (!source) return;
  const copy = structuredClone(source), id = uniqueId(`${source.id}-copy`);
  copy.id = id; copy.title = `${source.title} copy`; copy.profile.id = id; copy.profile.title = copy.title;
  copy.profile.source_label = `Browser copy of ${source.profile.source_label ?? source.title}`;
  delete copy.profile.source_payload;
  copy.created_at_epoch_ms = Date.now();
  bundle.profiles.push(copy); selectedId = id;
  persist("Profile duplicated"); renderDesigner();
});
byId("delete-button").addEventListener("click", () => {
  const profile = selectedProfile();
  const sessionOnly = Boolean(profile && exploration.findSaved(profile.id));
  const location = sessionOnly ? "this browser session" : "the Quest profile set";
  if (!profile || !window.confirm(`Delete “${profile.title}” from ${location}?`)) return;
  exploration.clearHistory(profile.id);
  if (sessionOnly) exploration.removeSaved(profile.id);
  else {
    const index = bundle.profiles.indexOf(profile);
    if (index >= 0) bundle.profiles.splice(index, 1);
  }
  persist(sessionOnly ? "Session save deleted" : "Profile deleted"); showPortal();
});
byId("restore-originals-button").addEventListener("click", () => {
  if (!window.confirm("Restore Trevor's nine original profiles? Additional profiles will be kept.")) return;
  const extras = bundle.profiles.filter(profile => !isTrevorOriginalId(profile.id));
  bundle = makeBundle([...createTrevorOriginalProfiles(), ...extras]);
  for (const profile of createTrevorOriginalProfiles()) exploration.clearHistory(profile.id);
  persist("Trevor's original nine-profile set restored"); renderPortal();
});
byId("add-interference").addEventListener("click", () => addProfile("interference"));
byId("add-temporal").addEventListener("click", () => addProfile("temporal"));
byId("download-button").addEventListener("click", downloadCurrentBundle);
byId("designer-download-button").addEventListener("click", downloadCurrentBundle);
document.addEventListener("keydown", handleDesignerShortcut);
elements.importFile.addEventListener("change", async () => {
  const file = elements.importFile.files?.[0]; if (!file) return;
  try {
    bundle = validateBundle(JSON.parse(await file.text()));
    exploration.clearSaved();
    exploration.clearAllHistory();
    selectedId = bundle.profiles[0]?.id ?? null;
    persist(`${bundle.profiles.length} profile(s) imported`);
    if (elements.designer.classList.contains("hidden") || !selectedProfile()) showPortal();
    else renderDesigner();
  } catch (error) { showStatus(`Import rejected: ${error.message}`, true, 0); }
  finally { elements.importFile.value = ""; }
});

setStrobeExpanded(false);
persist("");
renderPortal();
if (entryAcknowledged) showPortal();
else showEntryWarning();
