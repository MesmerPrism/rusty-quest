"use strict";

const state = {
  authorityRevision: 0,
  admissionRevision: 0,
  leaseAuthorityRevision: 0,
  hostRevision: 0,
  playerRevision: 0,
  selectedVideoId: null,
  videosById: new Map(),
  socket: null,
  bootstrap: ["describe", "get_state", "list_videos"],
};

const pairingCard = document.querySelector("#pairing-card");
const openLanCard = document.querySelector("#open-lan-card");
const controllerCard = document.querySelector("#controller-card");
const accessWarning = document.querySelector("#access-warning");
const pairingForm = document.querySelector("#pairing-form");
const pairingCode = document.querySelector("#pairing-code");
const pairingStatus = document.querySelector("#pairing-status");
const openLanButton = document.querySelector("#open-lan-button");
const openLanStatus = document.querySelector("#open-lan-status");
const connectionState = document.querySelector("#connection-state");
const selectedVideo = document.querySelector("#selected-video");
const projectionShape = document.querySelector("#projection-shape");
const stereoLayout = document.querySelector("#stereo-layout");
const playbackState = document.querySelector("#playback-state");
const playerRevision = document.querySelector("#player-revision");
const authorityRevision = document.querySelector("#authority-revision");
const videoList = document.querySelector("#video-list");
const eventLog = document.querySelector("#event-log");
const playButton = document.querySelector("#play-button");
const pauseButton = document.querySelector("#pause-button");

function requestId() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  const token = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return `browser-${token}`;
}

function canonicalPairingBody(code) {
  return JSON.stringify({
    pairing_code: code,
    request_id: requestId(),
  });
}

function canonicalOpenLanBody() {
  return JSON.stringify({ request_id: requestId() });
}

function canonicalCommandBody(command, payload = {}) {
  return JSON.stringify({
    command,
    expected_authority_revision: state.authorityRevision,
    expected_player_revision: state.playerRevision,
    payload,
    request_id: requestId(),
  });
}

function appendEvent(event) {
  const item = document.createElement("li");
  item.textContent = JSON.stringify(event);
  eventLog.prepend(item);
  while (eventLog.children.length > 40) {
    eventLog.lastElementChild.remove();
  }
}

function updateRevisions(event) {
  if (Number.isSafeInteger(event.authority_revision)) {
    state.authorityRevision = event.authority_revision;
    authorityRevision.textContent = String(event.authority_revision);
  }
  if (Number.isSafeInteger(event.admission_revision)) {
    state.admissionRevision = event.admission_revision;
  }
  if (Number.isSafeInteger(event.lease_authority_revision)) {
    state.leaseAuthorityRevision = event.lease_authority_revision;
  }
  if (Number.isSafeInteger(event.host_revision)) {
    state.hostRevision = event.host_revision;
  }
  const nextPlayerRevision =
    event.player_revision ?? event.state?.revision ?? event.state?.player?.revision;
  if (Number.isSafeInteger(nextPlayerRevision)) {
    state.playerRevision = nextPlayerRevision;
    playerRevision.textContent = String(nextPlayerRevision);
  }
}

function updatePlayer(next) {
  if (!next) {
    return;
  }
  state.selectedVideoId = next.selected_video_id;
  selectedVideo.textContent = next.selected_video_id ?? "—";
  updatePresentation(next.selected_video_id);
  playbackState.textContent = next.playing ? "Playing" : next.playback_state;
  playButton.disabled = !state.selectedVideoId || next.playing;
  pauseButton.disabled = !next.playing;
  document.querySelectorAll("[data-video-id]").forEach((button) => {
    button.disabled = button.dataset.videoId === state.selectedVideoId;
  });
}

function updatePresentation(videoId) {
  const presentation = state.videosById.get(videoId);
  projectionShape.textContent = presentation?.projection_shape ?? "—";
  stereoLayout.textContent = presentation?.stereo_layout ?? "—";
}

function renderVideos(videos) {
  videoList.replaceChildren();
  state.videosById = new Map(videos.map((video) => [video.video_id, video]));
  for (const video of videos) {
    const row = document.createElement("div");
    row.className = "video-row";
    const meta = document.createElement("div");
    meta.className = "video-meta";
    const title = document.createElement("strong");
    title.textContent = video.title;
    const duration = document.createElement("small");
    const durationText =
      video.duration_ms > 0 ? `${Math.round(video.duration_ms / 1000)} seconds` : "duration unavailable";
    duration.textContent =
      `${video.projection_shape} · ${video.stereo_layout} · ` +
      `${video.width_px}×${video.height_px} · ${durationText} · ${video.license}`;
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Select";
    button.dataset.videoId = video.video_id;
    button.disabled = video.video_id === state.selectedVideoId;
    button.addEventListener("click", () => {
      send("select_video", { video_id: video.video_id });
    });
    meta.append(title, duration);
    row.append(meta, button);
    videoList.append(row);
  }
  updatePresentation(state.selectedVideoId);
}

function send(command, payload = {}) {
  if (!state.socket || state.socket.readyState !== WebSocket.OPEN) {
    connectionState.textContent = "Disconnected";
    return;
  }
  state.socket.send(canonicalCommandBody(command, payload));
}

function sendNextBootstrap() {
  const next = state.bootstrap.shift();
  if (next) {
    send(next);
  }
}

function handleEvent(event) {
  appendEvent(event);
  updateRevisions(event);
  if (
    event.event === "command_applied" ||
    event.event === "state_changed" ||
    event.event === "state_observed"
  ) {
    updatePlayer(event.state?.player ?? event.state);
  }
  if (event.event === "command_result") {
    if (event.command === "get_state") {
      updatePlayer(event.state?.player);
    } else if (event.command === "list_videos") {
      renderVideos(event.videos);
    }
    sendNextBootstrap();
  }
  if (event.event === "command_rejected" || event.event === "command_failed") {
    connectionState.textContent = "Command rejected";
  }
  if (event.event === "command_not_submitted") {
    connectionState.textContent = "Player busy";
  }
}

function connect() {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(`${scheme}//${location.host}/v1/events`);
  state.socket = socket;
  socket.addEventListener("open", () => {
    connectionState.textContent = "Connected";
    sendNextBootstrap();
  });
  socket.addEventListener("message", (message) => {
    try {
      handleEvent(JSON.parse(message.data));
    } catch {
      connectionState.textContent = "Protocol error";
      socket.close();
    }
  });
  socket.addEventListener("close", () => {
    connectionState.textContent = "Disconnected";
    playButton.disabled = true;
    pauseButton.disabled = true;
  });
  socket.addEventListener("error", () => {
    connectionState.textContent = "Connection error";
  });
}

pairingForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const code = pairingCode.value.trim();
  if (!/^[0-9]{6}$/.test(code)) {
    pairingStatus.textContent = "Enter the six digits shown in the headset.";
    return;
  }
  pairingStatus.textContent = "Pairing…";
  try {
    const response = await fetch("/v1/pair", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: canonicalPairingBody(code),
    });
    const body = await response.json();
    if (!response.ok || body.paired !== true) {
      pairingStatus.textContent = "Pairing was rejected. Request a new code in the headset.";
      return;
    }
    updateRevisions(body);
    pairingCard.hidden = true;
    controllerCard.hidden = false;
    connect();
  } catch {
    pairingStatus.textContent = "The headset did not answer.";
  }
});

openLanButton.addEventListener("click", async () => {
  openLanButton.disabled = true;
  openLanStatus.textContent = "Requesting the sole controller lease…";
  try {
    const response = await fetch("/v1/open-session", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: canonicalOpenLanBody(),
    });
    const body = await response.json();
    if (!response.ok || body.session_admitted !== true || body.paired !== false) {
      openLanStatus.textContent = "Control was rejected or another controller already holds the lease.";
      openLanButton.disabled = false;
      return;
    }
    updateRevisions(body);
    openLanCard.hidden = true;
    controllerCard.hidden = false;
    connect();
  } catch {
    openLanStatus.textContent = "The headset did not answer.";
    openLanButton.disabled = false;
  }
});

async function loadAccessMode() {
  try {
    const response = await fetch("/v1/access", { credentials: "same-origin" });
    const body = await response.json();
    if (!response.ok) {
      throw new Error("access mode rejected");
    }
    if (body.access_mode === "paired" && body.authentication_required === true) {
      pairingCard.hidden = false;
      openLanCard.hidden = true;
      accessWarning.textContent =
        "Trusted local network only. This connection is authenticated but not encrypted.";
      return;
    }
    if (
      body.access_mode === "open_lan_insecure" &&
      body.authentication_required === false
    ) {
      pairingCard.hidden = true;
      openLanCard.hidden = false;
      accessWarning.textContent =
        "UNSAFE OPEN LAN: no authentication and no encryption. Anyone on this network may request control.";
      return;
    }
    throw new Error("unknown access mode");
  } catch {
    pairingCard.hidden = true;
    openLanCard.hidden = true;
    accessWarning.textContent = "The headset access mode could not be verified.";
  }
}

playButton.addEventListener("click", () => send("play"));
pauseButton.addEventListener("click", () => send("pause"));

loadAccessMode();
