(() => {
  "use strict";
  const protocol = globalThis.RustyConnectionHubProtocol;
  const canonicalJson = globalThis.RustyConnectionHubCanonicalJson;
  if (!protocol) throw new Error("Connection Hub protocol projection missing");
  if (!canonicalJson) throw new Error("Connection Hub canonical JSON encoder missing");
  const surfaces = new Map();
  const root = document.querySelector("#surfaces");
  const template = document.querySelector("#surface-template");
  const pairStatus = document.querySelector("#pair-status");
  const pairButton = document.querySelector("#pair-button");
  const pairingCode = document.querySelector("#pairing-code");
  const disconnectButton = document.querySelector("#disconnect-button");
  let session = sessionStorage.getItem("rustyHubSession") || "";
  let socket = null;
  let nextSequence = null;
  let requestInFlight = false;
  let keepaliveTimer = null;
  let reconnectTimer = null;
  let authenticatedInPage = false;
  let unconfirmedReconnects = 0;
  const LOCKED_PLAYLIST_SURFACE_ID = "surface.spatial_camera_panel.locked_playlist";

  const formatDuration = value => {
    if (value === null || value === undefined || value === "") return "–:––";
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric < 0) return "–:––";
    const totalSeconds = Math.floor(numeric);
    const seconds = totalSeconds % 60;
    const totalMinutes = Math.floor(totalSeconds / 60);
    if (totalMinutes < 60) return `${totalMinutes}:${String(seconds).padStart(2, "0")}`;
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  };

  const lockedPlaylistStateRows = state => {
    const itemCount = Number.isSafeInteger(state.item_count) && state.item_count >= 0
      ? state.item_count : 0;
    const activeIndex = Number.isSafeInteger(state.active_index) ? state.active_index : -1;
    const position = activeIndex >= 0 && activeIndex < itemCount
      ? `${activeIndex + 1} of ${itemCount}` : itemCount > 0 ? `— of ${itemCount}` : "No items";
    const duration = Number(state.item_duration_seconds);
    const elapsed = Number(state.item_elapsed_seconds);
    const safeDuration = Number.isFinite(duration) && duration >= 0 ? duration : null;
    const safeElapsed = Number.isFinite(elapsed) && elapsed >= 0
      ? (safeDuration === null ? elapsed : Math.min(elapsed, safeDuration)) : null;
    const phase = state.phase === "transition" ? "Transitioning" : "Playing";
    return [
      {label: "Playlist", value: state.playlist_title || "Untitled playlist", wide: true},
      {label: "Current item", value: position},
      {label: "Item", value: state.active_label || "Unnamed item"},
      {label: "Status", value: state.paused === true ? "Paused" : phase},
      {
        label: "Item time",
        value: `${formatDuration(safeElapsed)} / ${formatDuration(safeDuration)}`,
        progress: safeDuration > 0 && safeElapsed !== null ? safeElapsed / safeDuration : null,
        wide: true,
      },
    ];
  };

  const surfaceStateRows = surface => {
    if (surface.surface_id === LOCKED_PLAYLIST_SURFACE_ID) {
      return lockedPlaylistStateRows(surface.state || {});
    }
    return Object.entries(surface.state || {}).map(([name, value]) => ({
      label: name.replaceAll("_", " "),
      value: String(value),
    }));
  };

  if (globalThis.RustyConnectionHubTestHooks
      && typeof globalThis.RustyConnectionHubTestHooks === "object") {
    Object.assign(globalThis.RustyConnectionHubTestHooks, {
      formatDuration,
      lockedPlaylistStateRows,
    });
  }

  // crypto.subtle is intentionally unavailable to ordinary HTTP pages in
  // Chromium. Trusted-LAN experimental mode is explicitly HTTP/plaintext, so
  // controller identity hashing must not depend on a secure-context API.
  const sha256Hex = text => {
    const bytes = new TextEncoder().encode(text);
    const words = [];
    const bitLength = bytes.length * 8;
    for (let index = 0; index < bytes.length; index += 1) {
      words[index >> 2] = (words[index >> 2] || 0)
        | (bytes[index] << (24 - (index % 4) * 8));
    }
    words[bitLength >> 5] = (words[bitLength >> 5] || 0)
      | (0x80 << (24 - bitLength % 32));
    words[(((bitLength + 64) >> 9) << 4) + 15] = bitLength;
    const constants = [
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
      0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
      0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
      0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
      0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
      0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
      0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
      0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
      0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    const rotate = (value, count) => (value >>> count) | (value << (32 - count));
    let hash = [
      0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    for (let offset = 0; offset < words.length; offset += 16) {
      const schedule = new Array(64);
      for (let round = 0; round < 64; round += 1) {
        if (round < 16) schedule[round] = words[offset + round] | 0;
        else {
          const x = schedule[round - 15];
          const y = schedule[round - 2];
          const sigma0 = rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3);
          const sigma1 = rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10);
          schedule[round] = (schedule[round - 16] + sigma0
            + schedule[round - 7] + sigma1) | 0;
        }
      }
      const state = hash.slice();
      for (let round = 0; round < 64; round += 1) {
        const upper = rotate(state[4], 6) ^ rotate(state[4], 11) ^ rotate(state[4], 25);
        const choose = (state[4] & state[5]) ^ (~state[4] & state[6]);
        const temp1 = (state[7] + upper + choose + constants[round] + schedule[round]) | 0;
        const lower = rotate(state[0], 2) ^ rotate(state[0], 13) ^ rotate(state[0], 22);
        const majority = (state[0] & state[1]) ^ (state[0] & state[2]) ^ (state[1] & state[2]);
        const temp2 = (lower + majority) | 0;
        state.unshift((temp1 + temp2) | 0);
        state[4] = (state[4] + temp1) | 0;
        state.pop();
      }
      hash = hash.map((value, index) => (value + state[index]) | 0);
    }
    return hash.map(value => (value >>> 0).toString(16).padStart(8, "0")).join("");
  };

  const requestNonce = () => {
    if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0"));
    return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10).join("")}`;
  };

  const stopKeepalive = () => {
    if (keepaliveTimer !== null) clearInterval(keepaliveTimer);
    keepaliveTimer = null;
  };

  const validPairingCode = () => /^[0-9]{6}$/.test(pairingCode.value);

  const syncPairingControl = () => {
    pairButton.disabled = Boolean(session) || !validPairingCode();
  };

  const resetSession = status => {
    const activeSocket = socket;
    socket = null;
    stopKeepalive();
    if (reconnectTimer !== null) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    nextSequence = null;
    requestInFlight = false;
    authenticatedInPage = false;
    unconfirmedReconnects = 0;
    session = "";
    sessionStorage.removeItem("rustyHubSession");
    if (activeSocket && (activeSocket.readyState === WebSocket.CONNECTING
        || activeSocket.readyState === WebSocket.OPEN)) activeSocket.close();
    surfaces.clear();
    render();
    syncPairingControl();
    disconnectButton.disabled = true;
    pairStatus.textContent = status;
  };

  const applyNextSequence = message => {
    if (!Number.isSafeInteger(message.next_external_request_sequence)
        || message.next_external_request_sequence < 1) {
      throw new Error("authority sequence projection missing");
    }
    nextSequence = message.next_external_request_sequence;
    requestInFlight = false;
  };

  const sendSequenced = message => {
    if (!socket || socket.readyState !== WebSocket.OPEN
        || nextSequence === null || requestInFlight) return false;
    requestInFlight = true;
    socket.send(canonicalJson({...message, request_sequence: nextSequence}));
    return true;
  };

  const startKeepalive = () => {
    stopKeepalive();
    keepaliveTimer = setInterval(() => {
      sendSequenced({
        $schema: protocol.schemas.keepalive,
        type: protocol.types.keepalive,
      });
    }, 5000);
  };

  const acceptedRevokeReceipt = (response, receipt) => {
    const required = [
      "$schema",
      "type",
      "transport_epoch",
      "listener_instance_id",
      "surface_revision",
      "transport_classification",
      "confidentiality",
      "production_eligible",
      "applied",
      "status",
    ];
    const allowed = new Set([...required, "authority_receipt"]);
    if (!response.ok || !receipt || typeof receipt !== "object" || Array.isArray(receipt)) return false;
    if (Object.keys(receipt).some(key => !allowed.has(key))
        || required.some(key => !Object.hasOwn(receipt, key))) return false;
    const expectedSchema = protocol.schemas.revoke_request.replace(
      ".revoke_request.v1",
      ".revoke_receipt.v1",
    );
    return receipt.$schema === expectedSchema
      && receipt.type === "revoke_receipt"
      && Number.isSafeInteger(receipt.transport_epoch)
      && receipt.transport_epoch >= 0
      && typeof receipt.listener_instance_id === "string"
      && /^[0-9a-f]{32}$/.test(receipt.listener_instance_id)
      && Number.isSafeInteger(receipt.surface_revision)
      && receipt.surface_revision >= 0
      && receipt.transport_classification === "trusted_lan_experimental"
      && receipt.confidentiality === "none"
      && receipt.production_eligible === false
      && receipt.applied === true
      && receipt.status === "applied"
      && (!Object.hasOwn(receipt, "authority_receipt")
        || (receipt.authority_receipt !== null
          && typeof receipt.authority_receipt === "object"
          && !Array.isArray(receipt.authority_receipt)));
  };

  const controllerIdentity = () => {
    let seed = localStorage.getItem("rustyHubPublicControllerIdentity");
    if (!seed) {
      const bytes = crypto.getRandomValues(new Uint8Array(32));
      seed = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
      localStorage.setItem("rustyHubPublicControllerIdentity", seed);
    }
    return sha256Hex(seed);
  };

  const pair = async () => {
    if (!validPairingCode()) {
      pairStatus.textContent = "Enter the six-digit headset code.";
      syncPairingControl();
      return;
    }
    const code = pairingCode.value;
    pairButton.disabled = true;
    pairStatus.textContent = "Pairing…";
    try {
      const response = await fetch(protocol.routes.pair, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          $schema: protocol.schemas.pair_request,
          pairing_code: code,
          controller_identity_sha256: controllerIdentity(),
        }),
      });
      const receipt = await response.json();
      if (!receipt.accepted) throw new Error(receipt.status || "pairing rejected");
      session = receipt.session;
      sessionStorage.setItem("rustyHubSession", session);
      pairingCode.value = "";
      authenticatedInPage = false;
      unconfirmedReconnects = 0;
      connect();
    } catch (error) {
      pairStatus.textContent = `Pairing failed: ${error.message}`;
      syncPairingControl();
    }
  };

  const connect = () => {
    if (!session) return;
    if (reconnectTimer !== null) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    const previousSocket = socket;
    socket = null;
    if (previousSocket) previousSocket.close();
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    const activeSocket = new WebSocket(`${scheme}://${location.host}${protocol.routes.socket}`);
    let activeSocketAuthenticated = false;
    socket = activeSocket;
    activeSocket.addEventListener("open", () => {
      activeSocket.send(canonicalJson({
        $schema: protocol.schemas.socket_authenticate,
        type: protocol.types.authenticate,
        session,
      }));
    });
    activeSocket.addEventListener("message", event => {
      if (handle(JSON.parse(event.data))) activeSocketAuthenticated = true;
    });
    activeSocket.addEventListener("close", () => {
      if (socket !== activeSocket) return;
      socket = null;
      stopKeepalive();
      nextSequence = null;
      requestInFlight = false;
      disconnectButton.disabled = true;
      if (!activeSocketAuthenticated) {
        unconfirmedReconnects += 1;
        const retryLimit = authenticatedInPage ? 3 : 1;
        if (unconfirmedReconnects >= retryLimit) {
          resetSession("Stored session unavailable. Pair again.");
          return;
        }
      }
      pairStatus.textContent = "Connection closed; retrying…";
      if (session) reconnectTimer = setTimeout(connect, 1000);
    });
    activeSocket.addEventListener("error", () => {
      if (socket === activeSocket) pairStatus.textContent = "Connection error";
    });
  };

  const handle = message => {
    if (message.type === protocol.types.authentication_receipt) {
      if (message.$schema !== protocol.schemas.socket_authentication_receipt) {
        throw new Error("authentication receipt schema mismatch");
      }
      if (message.accepted !== true) {
        resetSession("Stored session rejected. Pair again.");
        return false;
      }
      authenticatedInPage = true;
      unconfirmedReconnects = 0;
      applyNextSequence(message);
      startKeepalive();
      pairStatus.textContent = "Connected";
      syncPairingControl();
      disconnectButton.disabled = false;
    } else if (message.type === protocol.types.surface_snapshot) {
      surfaces.clear();
      message.surfaces.forEach(surface => surfaces.set(surface.surface_id, surface));
      render();
    } else if (message.type === protocol.types.surface_available) {
      surfaces.set(message.surface.surface_id, message.surface);
      render();
    } else if (message.type === protocol.types.surface_removed) {
      surfaces.delete(message.surface_id);
      render();
    } else if (message.type === protocol.types.surface_state) {
      const surface = surfaces.get(message.surface_id);
      if (surface) {
        surface.state = message.state;
        surface.state_revision = message.state_revision;
        render();
      }
    } else if (message.type === protocol.types.command_receipt) {
      if (message.$schema !== protocol.schemas.command_receipt) {
        throw new Error("command receipt schema mismatch");
      }
      applyNextSequence(message);
      const card = document.querySelector(`[data-surface-id="${CSS.escape(message.surface_id)}"]`);
      if (card) card.querySelector(".receipt").textContent = `${message.command}: ${message.status}`;
    } else if (message.type === protocol.types.keepalive_receipt) {
      if (message.$schema !== protocol.schemas.keepalive_receipt) {
        throw new Error("keepalive receipt schema mismatch");
      }
      applyNextSequence(message);
    } else if (message.type === protocol.types.protocol_error) {
      applyNextSequence(message);
      pairStatus.textContent = `Protocol error: ${message.status}`;
    }
    return message.type === protocol.types.authentication_receipt;
  };

  const render = () => {
    root.replaceChildren();
    if (surfaces.size === 0) {
      const empty = document.createElement("p");
      empty.className = "empty";
      empty.textContent = "No provider app is currently offering a surface.";
      root.append(empty);
      return;
    }
    surfaces.forEach(surface => {
      const card = template.content.firstElementChild.cloneNode(true);
      card.dataset.surfaceId = surface.surface_id;
      card.querySelector("h3").textContent = surface.display_label;
      card.querySelector(".description").textContent = surface.description;
      card.querySelector(".provider").textContent = surface.provider_package;
      const state = card.querySelector(".state");
      surfaceStateRows(surface).forEach(presentation => {
        const row = document.createElement("div");
        const term = document.createElement("dt");
        const detail = document.createElement("dd");
        term.textContent = presentation.label;
        detail.textContent = presentation.value;
        if (presentation.wide) row.classList.add("wide");
        row.append(term, detail);
        if (presentation.progress !== null && presentation.progress !== undefined) {
          const progress = document.createElement("progress");
          progress.max = 1;
          progress.value = Math.max(0, Math.min(1, presentation.progress));
          progress.setAttribute("aria-label", presentation.label);
          row.append(progress);
        }
        state.append(row);
      });
      surface.commands.forEach(descriptor => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = descriptor.display_label;
        button.addEventListener("click", () => sendCommand(card, surface.surface_id, descriptor.command));
        card.querySelector(".commands").append(button);
      });
      root.append(card);
    });
  };

  const sendCommand = (card, surfaceId, command) => {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    try {
      const args = {};
      if (!sendSequenced({
        $schema: protocol.schemas.surface_command,
        type: protocol.types.surface_command,
        request_id: `browser.${requestNonce()}`,
        surface_id: surfaceId,
        command,
        args,
      })) {
        card.querySelector(".receipt").textContent = "Command deferred until sequence resync";
      }
    } catch (error) {
      card.querySelector(".receipt").textContent = `Arguments rejected: ${error.message}`;
    }
  };

  const disconnect = async () => {
    if (!session) return;
    disconnectButton.disabled = true;
    pairStatus.textContent = "Disconnecting…";
    try {
      const response = await fetch(protocol.routes.revoke, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          $schema: protocol.schemas.revoke_request,
          session,
          reason: "user_request",
        }),
      });
      const receipt = await response.json();
      if (!acceptedRevokeReceipt(response, receipt)) {
        throw new Error(receipt && typeof receipt.status === "string"
          ? receipt.status
          : `revoke rejected (${response.status})`);
      }
    } catch (error) {
      pairStatus.textContent = `Disconnect failed: ${error.message}`;
      disconnectButton.disabled = false;
      return;
    }
    resetSession("Disconnected");
  };

  pairButton.addEventListener("click", pair);
  pairingCode.addEventListener("input", () => {
    pairingCode.value = pairingCode.value.replace(/[^0-9]/g, "").slice(0, 6);
    syncPairingControl();
  });
  pairingCode.addEventListener("keydown", event => {
    if (event.key === "Enter" && !pairButton.disabled) {
      event.preventDefault();
      return pair();
    }
  });
  disconnectButton.addEventListener("click", disconnect);
  syncPairingControl();
  if (session) {
    connect();
  }
})();
