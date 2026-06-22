const curveChoices = [
  "Linear",
  "AKD hump",
  "Smoothstep",
  "Reverse linear",
  "Hold low",
  "Hold high",
];

const driverModes = [
  "Oscillator",
  "Manual",
  "Input slot 0: deformation",
  "Input slot 1: coupling",
  "Input slot 2: particle size",
  "Input slot 3: depth wave",
  "Input slot 4: spin speed",
  "Input slot 5: orbit radius",
  "Input slot 6: orbit angle",
  "Input slot 7: animation",
];

const parameterGroups = [
  {
    title: "Color And Alpha",
    open: false,
    parameters: [
      param("color_driver", "Color Driver", "Gradient weight", 0, 1, 0, 1, 2, "Linear"),
      param("transparency", "Transparency", "Transparency limits", 1, 1, 0, 1, 1, "Linear"),
      param("saturation", "Saturation", "Saturation limits", 0.3, 1, 0, 1, 1, "Linear"),
      param("brightness", "Brightness", "Brightness limits", 0.3, 1, 0, 1, 1, "Linear"),
    ],
  },
  {
    title: "Size And Depth Wave",
    open: false,
    parameters: [
      param("particle_size", "Particle Size", "Particle size envelope limits", 0.04, 0.115, 0, 0.2, 1, "AKD hump", "Use percent size", true),
      param("depth_wave", "Depth Wave", "Depth wave percent limits", 0, 0.1, 0, 0.5, 0, "AKD hump"),
    ],
  },
  {
    title: "Spin And Orbit",
    open: false,
    parameters: [
      param("spin_speed", "Spin Speed", "Spin speed limits", 0.1, 0.5, 0, 1, 0, "AKD hump", "Dual spin animation", true),
      param("orbit_radius", "Orbit Radius", "Orbit radius multiplier limits", 0.2, 1.5, 0, 2, 1, "AKD hump"),
      param("orbit_angle", "Orbit Angle", "Orbit angle limits", 0, 6.283185, 0, 6.283185, 1, "Linear"),
      param("animation_phase", "Animation Phase", "Animation phase limits", 0, 1, 0, 1, 1, "AKD hump"),
    ],
  },
];

function param(id, title, rangeLabel, min, max, controlMin, controlMax, cycle, curve, optionLabel = "", optionDefault = false) {
  return {
    id,
    title,
    rangeLabel,
    min,
    max,
    controlMin,
    controlMax,
    cycle,
    curve,
    optionLabel,
    optionDefault,
    driverMode: "Oscillator",
  };
}

function buildVisualsPage() {
  const page = document.querySelector("#page-visuals");
  for (const group of parameterGroups) {
    const details = document.createElement("details");
    details.open = group.open;
    const summary = document.createElement("summary");
    summary.textContent = group.title;
    details.append(summary);
    const body = document.createElement("div");
    body.className = "section-body";
    for (const spec of group.parameters) {
      body.append(buildParameter(spec));
    }
    details.append(body);
    page.append(details);
  }
}

function buildParameter(spec) {
  const block = document.createElement("details");
  block.className = "parameter";
  block.dataset.parameterId = spec.id;
  const summary = document.createElement("summary");
  summary.textContent = spec.title;
  block.append(summary);
  const body = document.createElement("div");
  body.className = "parameter-body";
  block.append(body);

  if (spec.optionLabel) {
    const row = rowShell(spec.optionLabel);
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = spec.optionDefault;
    input.dataset.field = "option";
    row.append(input);
    body.append(row);
  }

  const rangeRow = rowShell(spec.rangeLabel);
  const rangeFields = document.createElement("div");
  rangeFields.className = "inline-fields";
  rangeFields.innerHTML = `
    <span>X</span>
    <input data-field="min" type="number" min="${spec.controlMin}" max="${spec.controlMax}" step="0.001" value="${format(spec.min)}">
    <span>Y</span>
    <input data-field="max" type="number" min="${spec.controlMin}" max="${spec.controlMax}" step="0.001" value="${format(spec.max)}">
  `;
  rangeRow.append(rangeFields);
  body.append(rangeRow);

  const curveRow = rowShell(`${spec.title.replace(" Driver", "")} Envelope Curve`);
  const curveWrap = document.createElement("div");
  curveWrap.className = "curve-row";
  const curveSelect = select(curveChoices, spec.curve, "curve");
  const canvas = document.createElement("canvas");
  canvas.width = 240;
  canvas.height = 28;
  curveWrap.append(curveSelect, canvas);
  curveRow.append(curveWrap);
  body.append(curveRow);

  const driverRow = rowShell("Driver Mode");
  const driverSelect = select(driverModes, spec.driverMode, "driver_mode");
  driverRow.append(driverSelect);
  body.append(driverRow);

  const cycleRow = rowShell(`${spec.title.replace(" Driver", "")} Cycle Multiplier`);
  const cycleWrap = document.createElement("div");
  cycleWrap.className = "cycle-row";
  cycleWrap.innerHTML = `
    <input data-field="cycle_range" type="range" min="0" max="10" step="1" value="${spec.cycle}">
    <input data-field="cycle" type="number" min="0" max="10" step="1" value="${spec.cycle}">
  `;
  cycleRow.append(cycleWrap);
  body.append(cycleRow);

  const liveRow = rowShell("Driver Value");
  const liveWrap = document.createElement("div");
  liveWrap.className = "cycle-row";
  liveWrap.innerHTML = `
    <input data-field="live_range" type="range" min="0" max="1" step="0.001" value="0.000">
    <input data-field="live" type="number" min="0" max="1" step="0.001" value="0.000">
  `;
  liveRow.append(liveWrap);
  body.append(liveRow);

  wirePair(block, "cycle_range", "cycle");
  wirePair(block, "live_range", "live");
  const refreshDriverValueState = () => {
    const editable = driverSelect.value === "Manual";
    block.querySelector('[data-field="live_range"]').disabled = !editable;
    block.querySelector('[data-field="live"]').disabled = !editable;
    liveRow.classList.toggle("disabled", !editable);
  };
  driverSelect.addEventListener("change", refreshDriverValueState);
  curveSelect.addEventListener("change", () => drawCurve(canvas, curveSelect.value));
  drawCurve(canvas, curveSelect.value);
  refreshDriverValueState();
  return block;
}

function rowShell(labelText) {
  const row = document.createElement("div");
  row.className = "row";
  const label = document.createElement("label");
  label.textContent = labelText;
  row.append(label);
  return row;
}

function select(values, selected, field) {
  const element = document.createElement("select");
  element.dataset.field = field;
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = value;
    option.selected = value === selected;
    element.append(option);
  }
  return element;
}

function wirePair(root, rangeField, numberField) {
  const range = root.querySelector(`[data-field="${rangeField}"]`);
  const number = root.querySelector(`[data-field="${numberField}"]`);
  range.addEventListener("input", () => {
    number.value = range.value;
  });
  number.addEventListener("input", () => {
    range.value = number.value;
  });
}

function drawCurve(canvas, name) {
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = "#555860";
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, h - 1);
  ctx.lineTo(w, h - 1);
  ctx.stroke();
  ctx.strokeStyle = "#11d61d";
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let x = 0; x < w; x += 1) {
    const t = x / (w - 1);
    const y01 = curveValue(name, t);
    const y = h - 2 - y01 * (h - 4);
    if (x === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();
}

function curveValue(name, t) {
  if (name === "AKD hump") return Math.sin(Math.PI * t);
  if (name === "Smoothstep") return t * t * (3 - 2 * t);
  if (name === "Reverse linear") return 1 - t;
  if (name === "Hold low") return 0;
  if (name === "Hold high") return 1;
  return t;
}

function exportJson() {
  const parameters = [...document.querySelectorAll(".parameter")].map((block) => {
    const value = (field) => block.querySelector(`[data-field="${field}"]`);
    const object = {
      id: block.dataset.parameterId,
      title: block.querySelector("summary").textContent,
      min: numberValue(value("min")),
      max: numberValue(value("max")),
      curve: value("curve").value,
      curve_code: curveCode(value("curve").value),
      driver_mode: value("driver_mode").value,
      driver_mode_code: driverModeCode(value("driver_mode").value),
      cycle_multiplier: numberValue(value("cycle")),
      driver_value: numberValue(value("live")),
      driver_value_editable: value("driver_mode").value === "Manual",
      live_driver_value: numberValue(value("live")),
    };
    const sourceSlot = driverSourceSlot(value("driver_mode").value);
    if (sourceSlot !== null) object.driver_source_slot = sourceSlot;
    const option = value("option");
    if (option) object.option_enabled = option.checked;
    return object;
  });
  return {
    schema: "rusty.quest.native_renderer.private_particle_akd_config_panel.v1",
    parameter_defaults_source: "akd-pe-oscillator-config",
    driver_mode_default: "Oscillator",
    curve_choices: curveChoices,
    driver_mode_choices: driverModes,
    driver_controls: driverControls(parameters),
    parameters,
  };
}

function driverControls(parameters) {
  const targetSlots = new Map([
    ["particle_size", 2],
    ["depth_wave", 3],
    ["spin_speed", 4],
    ["orbit_radius", 5],
    ["orbit_angle", 6],
    ["animation_phase", 7],
  ]);
  const controls = [
    directControl(0),
    directControl(1),
  ];
  for (const parameter of parameters) {
    if (!targetSlots.has(parameter.id)) continue;
    const targetSlot = targetSlots.get(parameter.id);
    const sourceSlot = parameter.driver_source_slot ?? targetSlot;
    controls.push({
      target_slot: targetSlot,
      mode: driverModeLabel(parameter.driver_mode),
      mode_code: parameter.driver_mode_code,
      source_slot: sourceSlot,
      curve: curveLabel(parameter.curve),
      curve_code: parameter.curve_code,
      range_min: parameter.min,
      range_max: parameter.max,
      cycle_multiplier: parameter.cycle_multiplier,
      value01: parameter.driver_value,
    });
  }
  return controls;
}

function directControl(targetSlot) {
  return {
    target_slot: targetSlot,
    mode: "direct",
    mode_code: 3,
    source_slot: targetSlot,
    curve: "linear",
    curve_code: 0,
    range_min: 0,
    range_max: 1,
    cycle_multiplier: 0,
    value01: 0,
  };
}

function numberValue(input) {
  return Number.parseFloat(input.value || "0");
}

function driverSourceSlot(mode) {
  const match = /^Input slot (\d+):/.exec(mode);
  return match ? Number.parseInt(match[1], 10) : null;
}

function driverModeCode(mode) {
  if (mode === "Manual") return 1;
  if (driverSourceSlot(mode) !== null) return 2;
  return 0;
}

function driverModeLabel(mode) {
  const code = driverModeCode(mode);
  if (code === 1) return "manual";
  if (code === 2) return "input-slot";
  return "oscillator";
}

function curveCode(curve) {
  if (curve === "AKD hump") return 1;
  if (curve === "Smoothstep") return 2;
  if (curve === "Reverse linear") return 3;
  if (curve === "Hold low") return 4;
  if (curve === "Hold high") return 5;
  return 0;
}

function curveLabel(curve) {
  if (curve === "AKD hump") return "akd-hump";
  if (curve === "Reverse linear") return "reverse-linear";
  if (curve === "Hold low") return "hold-low";
  if (curve === "Hold high") return "hold-high";
  return curve.toLowerCase();
}

function format(value) {
  return Number(value).toFixed(3);
}

document.querySelectorAll(".page-tabs button").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll(".page-tabs button").forEach((item) => item.classList.remove("selected"));
    document.querySelectorAll(".page").forEach((item) => item.classList.remove("selected"));
    button.classList.add("selected");
    document.querySelector(`#page-${button.dataset.page}`).classList.add("selected");
  });
});

document.querySelector("#export-json").addEventListener("click", async () => {
  const output = document.querySelector("#json-output");
  const text = JSON.stringify(exportJson(), null, 2);
  output.textContent = text;
  output.classList.add("visible");
  try {
    await navigator.clipboard.writeText(text);
  } catch (_) {
  }
});

buildVisualsPage();
