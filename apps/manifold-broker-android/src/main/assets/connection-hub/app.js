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
  const disconnectButton = document.querySelector("#disconnect-button");
  let session = sessionStorage.getItem("rustyHubSession") || "";
  let socket = null;
  let nextSequence = null;
  let requestInFlight = false;
  let keepaliveTimer = null;
  let reconnectTimer = null;

  const stopKeepalive = () => {
    if (keepaliveTimer !== null) clearInterval(keepaliveTimer);
    keepaliveTimer = null;
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

  const controllerIdentity = async () => {
    let seed = localStorage.getItem("rustyHubPublicControllerIdentity");
    if (!seed) {
      const bytes = crypto.getRandomValues(new Uint8Array(32));
      seed = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
      localStorage.setItem("rustyHubPublicControllerIdentity", seed);
    }
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(seed));
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
  };

  const pair = async () => {
    pairButton.disabled = true;
    pairStatus.textContent = "Pairing…";
    try {
      const response = await fetch(protocol.routes.pair, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          $schema: protocol.schemas.pair_request,
          pairing_code: document.querySelector("#pairing-code").value.trim(),
          controller_identity_sha256: await controllerIdentity(),
        }),
      });
      const receipt = await response.json();
      if (!receipt.accepted) throw new Error(receipt.status || "pairing rejected");
      session = receipt.session;
      sessionStorage.setItem("rustyHubSession", session);
      connect();
    } catch (error) {
      pairStatus.textContent = `Pairing failed: ${error.message}`;
      pairButton.disabled = false;
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
    socket = activeSocket;
    activeSocket.addEventListener("open", () => {
      activeSocket.send(canonicalJson({
        $schema: protocol.schemas.socket_authenticate,
        type: protocol.types.authenticate,
        session,
      }));
    });
    activeSocket.addEventListener("message", event => handle(JSON.parse(event.data)));
    activeSocket.addEventListener("close", () => {
      if (socket !== activeSocket) return;
      socket = null;
      stopKeepalive();
      nextSequence = null;
      requestInFlight = false;
      pairStatus.textContent = "Connection closed";
      disconnectButton.disabled = true;
      if (session) reconnectTimer = setTimeout(connect, 1000);
    });
    activeSocket.addEventListener("error", () => {
      if (socket === activeSocket) pairStatus.textContent = "Connection error";
    });
  };

  const handle = message => {
    if (message.type === protocol.types.authentication_receipt) {
      if (message.$schema !== protocol.schemas.socket_authentication_receipt
          || message.accepted !== true) throw new Error("authentication receipt rejected");
      applyNextSequence(message);
      startKeepalive();
      pairStatus.textContent = "Connected";
      pairButton.disabled = false;
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
      Object.entries(surface.state || {}).forEach(([name, value]) => {
        const row = document.createElement("div");
        const term = document.createElement("dt");
        const detail = document.createElement("dd");
        term.textContent = name.replaceAll("_", " ");
        detail.textContent = String(value);
        row.append(term, detail);
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
        request_id: `browser.${crypto.randomUUID()}`,
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
    if (socket) socket.close();
    stopKeepalive();
    if (reconnectTimer !== null) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    nextSequence = null;
    requestInFlight = false;
    session = "";
    sessionStorage.removeItem("rustyHubSession");
    surfaces.clear();
    render();
    pairStatus.textContent = "Disconnected";
  };

  pairButton.addEventListener("click", pair);
  disconnectButton.addEventListener("click", disconnect);
  if (session) connect();
})();
