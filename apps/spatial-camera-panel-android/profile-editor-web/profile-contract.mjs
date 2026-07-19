// AGPL-3.0-or-later. Profile vocabulary derived with permission from
// Trevor Hewitt's vr_strobe, commit 52c71cc069f4102bc4148e05c5fd3fc4d5466479.

export const PROFILE_BUNDLE_SCHEMA = "rusty.quest.spatial_vr_strobe.profile_bundle.v1";
export const PROFILE_BUNDLE_VERSION = 1;
export const MAX_PROFILES = 512;
export const MAX_PATTERNS_PER_KIND = 8;
export const QUEST_RANDOMIZATION_ENVELOPE = Object.freeze({
  id: "quest-reliable-v3",
  fineDetailProbability: 0.60,
  scale: [0.75, 16],
  nonRayPeriod: [1.5, 50],
  rayPeriod: [3, 50],
  perlinScale: [0.5, 40],
  brightness: [-0.12, 0.12],
  contrast: [0.9, 1.55],
  maxActivePatterns: 3,
});

export const INTERFERENCE_FIELDS = [
  ["color_count", "colorCount", 2, 3, 1, "number", "Colors"],
  ["color_1", "col1", null, null, null, "color", "Colors"],
  ["color_2", "col2", null, null, null, "color", "Colors"],
  ["color_3", "col3", null, null, null, "color", "Colors"],
  ["oscillator_active", "oscActive", null, null, null, "boolean", "Color Animation"],
  ["oscillator_frequency_hz", "oscFreq", 0, 40, 0.05, "number", "Color Animation"],
  ["oscillator_shape", "oscShape", 0.1, 10, 0.05, "number", "Color Animation"],
  ["scale", "scale", 0.1, 100, 0.05, "number", "Global Transforms"],
  ["shear_x", "shearX", -2, 2, 0.01, "number", "Global Transforms"],
  ["shear_y", "shearY", -2, 2, 0.01, "number", "Global Transforms"],
  ["offset_x", "offsetX", -1, 1, 0.01, "number", "Global Transforms"],
  ["offset_y", "offsetY", -1, 1, 0.01, "number", "Global Transforms"],
  ["shake_amplitude", "shakeAmp", 0, 0.1, 0.001, "number", "Global Transforms"],
  ["shake_frequency_hz", "shakeFreq", 0, 40, 0.05, "number", "Global Transforms"],
  ["rotation_speed", "rotSpeed", -5, 5, 0.01, "number", "Global Transforms"],
  ["step_factor", "stepFactor", 0, 1, 0.01, "number", "Global Transforms"],
  ["trail_amount", "trailAmount", 0, 0.99, 0.01, "number", "Post Processing"],
  ["blur_radius", "blurRadius", 0, 15, 0.1, "number", "Post Processing"],
  ["glow_strength", "glowStrength", 0, 3, 0.01, "number", "Post Processing"],
  ["brightness", "brightness", -1, 1, 0.01, "number", "Post Processing"],
  ["contrast", "contrast", 0, 3, 0.01, "number", "Post Processing"],
  ["noise_frequency", "noiseFreq", 0.1, 5, 0.01, "number", "In-Shader Effects"],
  ["noise_strength", "noiseStrength", 0, 1, 0.01, "number", "In-Shader Effects"],
  ["noise_bias", "noiseBias", 0, 1, 0.01, "number", "In-Shader Effects"],
  ["vignette_center", "vigCenter", 0, 5, 0.01, "number", "In-Shader Effects"],
  ["vignette_edge", "vigEdge", 0, 5, 0.01, "number", "In-Shader Effects"],
  ["vignette_bias", "vigBias", 0, 1, 0.01, "number", "In-Shader Effects"],
];

export const TEMPORAL_FIELDS = [
  ["color_1", "COL 1", null, null, null, "color", "Colors"],
  ["color_2", "COL 2", null, null, null, "color", "Colors"],
  ["frequency_hz", "FREQ (HZ)", 0.1, 120, 0.1, "number", "Frequency"],
  ["duty_percent", "DUTY (%)", 1, 99, 1, "number", "Frequency"],
  ["noise_type", "TYPE", null, null, null, "noise", "Noise Parameters"],
  ["noise_resolution", "RES (PX)", 1, 50, 1, "number", "Noise Parameters"],
  ["noise_phase_1", "NOISE 1", null, null, null, "boolean", "Noise Parameters"],
  ["noise_amplitude_1", "AMP 1", 0, 1, 0.01, "number", "Noise Parameters"],
  ["noise_phase_2", "NOISE 2", null, null, null, "boolean", "Noise Parameters"],
  ["noise_amplitude_2", "AMP 2", 0, 1, 0.01, "number", "Noise Parameters"],
  ["fixation_enabled", "FIXATION", null, null, null, "boolean", "Fixation"],
  ["fixation_color", "COLOR", null, null, null, "color", "Fixation"],
  ["fixation_size", "SIZE", 2, 100, 1, "number", "Fixation"],
];

export const PATTERN_FIELDS = [
  ["active", "Active", null, null, null, "boolean"],
  ["strength", "Strength", -2, 2, 0.01, "number"],
  ["period", "Spatial frequency", 0.1, 50, 0.1, "number"],
  ["speed", "Speed", -10, 10, 0.01, "number"],
  ["pivot_x", "Pivot X", -2, 2, 0.01, "number"],
  ["pivot_y", "Pivot Y", -2, 2, 0.01, "number"],
  ["distort_freq", "Distortion frequency", 0, 20, 0.01, "number"],
  ["distort_amp", "Distortion amplitude", 0, 5, 0.01, "number"],
  ["distort_speed", "Distortion speed", -10, 10, 0.01, "number"],
  ["dist_mult_parallel", "Distortion parallel", 0, 5, 0.01, "number"],
  ["dist_mult_orthogonal", "Distortion orthogonal", 0, 5, 0.01, "number"],
  ["wave_freq", "Wave frequency", 0, 20, 0.01, "number"],
  ["wave_amp", "Wave amplitude", 0, 5, 0.01, "number"],
  ["wave_shape", "Wave shape", 0, 1, 0.01, "number"],
  ["angle", "Angle", 0, 6.28, 0.01, "number"],
  ["rotation_pivot_x", "Rotation pivot X", -2, 2, 0.01, "number"],
  ["rotation_pivot_y", "Rotation pivot Y", -2, 2, 0.01, "number"],
  ["rotation_speed", "Rotation speed", -2, 2, 0.01, "number"],
  ["extent", "Extent", 0, 20, 0.1, "number"],
  ["noise_move", "Pivot movement", 0, 2, 0.01, "number"],
  ["perlin_scale", "Perlin scale", 0.1, 50, 0.1, "number"],
  ["perlin_z_speed", "Perlin Z speed", -10, 10, 0.01, "number"],
  ["perlin_z_offset", "Perlin Z offset", -100, 100, 0.1, "number"],
];

const colorPattern = /^#[0-9a-f]{6}$/i;
const clone = value => structuredClone(value);
const between = (min, max) => min + Math.random() * (max - min);
const round = value => Math.round(value * 1000) / 1000;
const chance = probability => Math.random() < probability;
const signed = (min, max) => round(between(min, max)) * (chance(0.5) ? 1 : -1);
const fine = (coarseMin, coarseMax, fineMin, fineMax) =>
  round(chance(QUEST_RANDOMIZATION_ENVELOPE.fineDetailProbability)
    ? between(fineMin, fineMax) : between(coarseMin, coarseMax));
const hexChannels = color => [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16));
const colorDistanceSquared = (left, right) => {
  const a = hexChannels(left), b = hexChannels(right);
  return a.reduce((sum, channel, index) => sum + ((channel - b[index]) / 255) ** 2, 0);
};
const jitteredAnchor = anchor => `#${hexChannels(anchor)
  .map(channel => Math.max(100, Math.min(254, channel + Math.floor(between(-10, 11)))))
  .map(channel => channel.toString(16).padStart(2, "0")).join("")}`;

export function createPattern(kind = "stripe") {
  return {
    kind, active: true, strength: 1, period: 10, speed: 2, pivot_x: 0, pivot_y: 0,
    distort_freq: 1, distort_amp: 0, distort_speed: 1,
    dist_mult_parallel: 1, dist_mult_orthogonal: 1,
    wave_freq: 2, wave_amp: 0, wave_shape: 0, angle: 0,
    rotation_pivot_x: 0, rotation_pivot_y: 0, rotation_speed: 0,
    extent: 0, noise_move: 0, perlin_scale: 5, perlin_z_speed: 1,
    perlin_z_offset: 0,
  };
}

function envelope(id, title, kind, profile) {
  return {
    id, title, created_at_epoch_ms: Date.now(), distance_meters: 4,
    carrier: { curved_mode: true, concavity: 1 }, kind, profile,
  };
}

export function createInterferenceProfile(id = `browser-interference-${Date.now()}`) {
  const title = "New interference profile";
  return envelope(id, title, "interference", {
    id, title, source_label: "Rusty Quest browser editor", duration_seconds: 15,
    color_count: 3, color_1: "#f46868", color_2: "#68f468", color_3: "#6868f4",
    oscillator_active: false, oscillator_frequency_hz: 0.5, oscillator_shape: 1,
    scale: 8, shear_x: 0, shear_y: 0, offset_x: 0, offset_y: 0,
    shake_amplitude: 0, shake_frequency_hz: 5, rotation_speed: 0, step_factor: 0.65,
    trail_amount: 0, blur_radius: 0, glow_strength: 0, brightness: 0, contrast: 1.1,
    noise_frequency: 1, noise_strength: 0, noise_bias: 0.5,
    vignette_center: 0, vignette_edge: 0, vignette_bias: 0.5,
    patterns: [createPattern("stripe"), createPattern("ripple")],
  });
}

export function createTemporalProfile(id = `browser-temporal-${Date.now()}`) {
  const title = "New temporal profile";
  return envelope(id, title, "temporal", {
    id, title, source_label: "Rusty Quest browser editor", duration_seconds: 15,
    color_1: "#000000", color_2: "#ffffff", frequency_hz: 7, duty_percent: 50,
    noise_type: "white", noise_resolution: 8, noise_phase_1: false,
    noise_amplitude_1: 0.2, noise_phase_2: false, noise_amplitude_2: 0.2,
    fixation_enabled: false, fixation_color: "#ff0000", fixation_size: 15,
  });
}

export function makeBundle(profiles = []) {
  const bundle = {
    schema: PROFILE_BUNDLE_SCHEMA,
    format_version: PROFILE_BUNDLE_VERSION,
    profile_count: profiles.length,
    profiles: clone(profiles),
  };
  return validateBundle(bundle);
}

function finiteIn(value, min, max, field) {
  if (!Number.isFinite(value) || value < min || value > max) throw new Error(`${field} out of bounds`);
}

function validateFields(profile, fields) {
  for (const [name, , min, max, , type] of fields) {
    if (!(name in profile)) throw new Error(`Missing ${name}`);
    if (type === "number") finiteIn(Number(profile[name]), min, max, name);
    if (type === "color" && !colorPattern.test(profile[name])) throw new Error(`Invalid ${name}`);
    if (type === "boolean" && typeof profile[name] !== "boolean") throw new Error(`Invalid ${name}`);
    if (type === "noise" && !["white", "perlin"].includes(profile[name])) throw new Error(`Invalid ${name}`);
  }
}

function validateStoredProfile(stored) {
  if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(stored.id)) throw new Error("Invalid profile id");
  if (!stored.title?.trim() || stored.title.length > 160) throw new Error("Invalid title");
  finiteIn(Number(stored.created_at_epoch_ms), 0, Number.MAX_SAFE_INTEGER, "created_at_epoch_ms");
  finiteIn(Number(stored.distance_meters), 1.05, 4, "distance_meters");
  finiteIn(Number(stored.carrier?.concavity), 0, 1, "concavity");
  if (typeof stored.carrier?.curved_mode !== "boolean") throw new Error("Invalid carrier");
  if (stored.profile?.id !== stored.id || stored.profile?.title !== stored.title) throw new Error("Envelope mismatch");
  finiteIn(Number(stored.profile.duration_seconds), 1, 300, "duration_seconds");
  if (stored.kind === "interference") {
    validateFields(stored.profile, INTERFERENCE_FIELDS);
    const counts = new Map();
    for (const pattern of stored.profile.patterns ?? []) {
      if (!["stripe", "ripple", "ray", "perlin"].includes(pattern.kind)) throw new Error("Invalid pattern kind");
      validateFields(pattern, PATTERN_FIELDS);
      const count = (counts.get(pattern.kind) ?? 0) + 1;
      if (count > MAX_PATTERNS_PER_KIND) throw new Error(`Too many ${pattern.kind} patterns`);
      counts.set(pattern.kind, count);
      if (pattern.kind === "ray") finiteIn(Number(pattern.period), 1, 50, "period");
    }
  } else if (stored.kind === "temporal") {
    validateFields(stored.profile, TEMPORAL_FIELDS);
  } else throw new Error("Unsupported profile kind");
  return stored;
}

export function validateBundle(input) {
  const bundle = clone(input);
  if (bundle.schema !== PROFILE_BUNDLE_SCHEMA || bundle.format_version !== PROFILE_BUNDLE_VERSION) {
    throw new Error("Unsupported profile bundle");
  }
  if (!Array.isArray(bundle.profiles) || bundle.profiles.length > MAX_PROFILES ||
      bundle.profile_count !== bundle.profiles.length) throw new Error("Invalid profile count");
  bundle.profiles.forEach(validateStoredProfile);
  if (new Set(bundle.profiles.map(profile => profile.id)).size !== bundle.profiles.length) {
    throw new Error("Duplicate profile id");
  }
  return bundle;
}

export function renameProfile(stored, title) {
  stored.title = title.trim();
  stored.profile.title = stored.title;
  return validateStoredProfile(stored);
}

export function randomizeQuestSafe(stored) {
  const next = clone(stored);
  if (next.kind === "temporal") {
    if (colorDistanceSquared(next.profile.color_1, next.profile.color_2) < 0.18) {
      const channels = hexChannels(next.profile.color_1);
      next.profile.color_2 = channels.reduce((sum, channel) => sum + channel / 255, 0) > 1.5
        ? "#000000" : "#ffffff";
    }
    next.profile.frequency_hz = round(between(1, 30));
    next.profile.duty_percent = round(between(20, 80));
    next.profile.noise_resolution = Math.floor(between(4, 25));
    next.profile.noise_type = chance(0.5) ? "white" : "perlin";
    next.profile.noise_phase_1 = chance(0.2);
    next.profile.noise_phase_2 = chance(0.2);
    next.profile.noise_amplitude_1 = next.profile.noise_phase_1 ? round(between(0.05, 0.35)) : 0;
    next.profile.noise_amplitude_2 = next.profile.noise_phase_2 ? round(between(0.05, 0.35)) : 0;
    next.profile.fixation_enabled = chance(0.5);
    next.profile.fixation_size = Math.floor(between(8, 49));
    return validateStoredProfile(next);
  }
  const anchors = ["#f46868", "#68f468", "#6868f4", "#f4f468", "#f468f4", "#68f4f4"]
    .sort(() => Math.random() - 0.5);
  [next.profile.color_1, next.profile.color_2, next.profile.color_3] = anchors.slice(0, 3).map(jitteredAnchor);
  next.profile.oscillator_active = chance(0.10);
  next.profile.oscillator_frequency_hz = round(between(0.5, 12));
  next.profile.oscillator_shape = round(between(0.75, 2.5));
  next.profile.scale = fine(0.75, 6, 6.5, 16);
  next.profile.shear_x = chance(0.08) ? round(between(-0.35, 0.35)) : 0;
  next.profile.shear_y = chance(0.08) ? round(between(-0.35, 0.35)) : 0;
  next.profile.offset_x = round(between(-0.5, 0.5));
  next.profile.offset_y = round(between(-0.5, 0.5));
  next.profile.shake_amplitude = chance(0.08) ? round(between(0.002, 0.025)) : 0;
  next.profile.shake_frequency_hz = round(between(0.5, 10));
  next.profile.rotation_speed = signed(0.05, 1.25);
  next.profile.step_factor = round(between(0.05, 0.75));
  next.profile.trail_amount = chance(0.08) ? round(between(0.05, 0.35)) : 0;
  next.profile.blur_radius = chance(0.08) ? round(between(0.5, 3)) : 0;
  next.profile.glow_strength = chance(0.08) ? round(between(0.1, 0.65)) : 0;
  next.profile.brightness = round(between(-0.12, 0.12));
  next.profile.contrast = round(between(0.9, 1.55));
  next.profile.noise_frequency = round(between(0.25, 3));
  next.profile.noise_strength = chance(0.15) ? round(between(0.05, 0.25)) : 0;
  next.profile.noise_bias = round(between(0.25, 0.75));
  const vignetteEnabled = chance(0.12);
  next.profile.vignette_center = vignetteEnabled ? round(between(0.55, 0.85)) : 0;
  next.profile.vignette_edge = vignetteEnabled
    ? round(between(next.profile.vignette_center + 0.25, next.profile.vignette_center + 0.5)) : 0;
  next.profile.vignette_bias = round(between(0.2, 0.8));

  if (next.profile.patterns.length === 0) next.profile.patterns.push(createPattern("stripe"));
  if (!next.profile.patterns.some(pattern => pattern.active)) next.profile.patterns[0].active = true;
  let activeBudget = 3, distortionBudget = 1, waveBudget = 1;
  for (const pattern of next.profile.patterns) {
    pattern.active = pattern.active && activeBudget-- > 0;
    if (!pattern.active) continue;
    pattern.strength = signed(0.45, 1.35);
    pattern.pivot_x = round(between(-1, 1));
    pattern.pivot_y = round(between(-1, 1));
    if (pattern.kind === "perlin") {
      pattern.perlin_scale = fine(0.5, 18, 18.5, 40);
      pattern.perlin_z_speed = signed(0.25, 3);
      pattern.perlin_z_offset = round(between(-20, 20));
      pattern.distort_amp = 0;
      pattern.wave_amp = 0;
    } else if (pattern.kind === "ray") {
      pattern.speed = signed(0.35, 4);
      pattern.period = chance(0.6) ? Math.floor(between(32, 51)) : Math.floor(between(3, 32));
    } else {
      pattern.speed = signed(0.35, 4);
      pattern.period = fine(1.5, 28, 28.5, 50);
    }
    if (pattern.kind !== "perlin") {
      pattern.distort_freq = round(between(0.5, 6));
      pattern.distort_amp = distortionBudget > 0 && chance(0.12) ? (distortionBudget--, round(between(0.05, 0.45))) : 0;
      pattern.distort_speed = signed(0.25, 3);
      pattern.dist_mult_parallel = round(between(0.6, 1.8));
      pattern.dist_mult_orthogonal = round(between(0.6, 1.8));
      pattern.wave_freq = round(between(0.5, 8));
      pattern.wave_amp = waveBudget > 0 && chance(0.12) ? (waveBudget--, round(between(0.05, 0.5))) : 0;
      pattern.wave_shape = round(between(0.2, 0.8));
      pattern.rotation_speed = signed(0.05, 0.65);
      if (pattern.kind === "stripe") {
        pattern.angle = round(between(0, 6.28));
        pattern.extent = chance(0.08) ? Math.floor(between(2, 9)) : 0;
      } else {
        pattern.rotation_pivot_x = round(between(-0.75, 0.75));
        pattern.rotation_pivot_y = round(between(-0.75, 0.75));
        pattern.noise_move = round(between(0, 0.6));
      }
    }
  }
  return validateStoredProfile(next);
}

export function downloadBundle(bundle, filename = "rusty-vr-strobe-profiles.json") {
  const blob = new Blob([`${JSON.stringify(validateBundle(bundle), null, 2)}\n`], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = Object.assign(document.createElement("a"), { href: url, download: filename });
  link.click();
  URL.revokeObjectURL(url);
}
