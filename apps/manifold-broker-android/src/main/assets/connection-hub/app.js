(() => {
  "use strict";
  const surfaces = new Map();
  const root = document.querySelector("#surfaces");
  const template = document.querySelector("#surface-template");
  const pairStatus = document.querySelector("#pair-status");
  const pairButton = document.querySelector("#pair-button");
  const disconnectButton = document.querySelector("#disconnect-button");
  let session = sessionStorage.getItem("rustyHubSession") || "";
  let socket = null;

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
      const response = await fetch("/v1/pair", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          $schema: "rusty.quest.connection_hub.pair_request.v1",
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
    if (socket) socket.close();
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    socket = new WebSocket(`${scheme}://${location.host}/v1/socket`);
    socket.addEventListener("open", () => {
      socket.send(JSON.stringify({
        $schema: "rusty.quest.connection_hub.socket_authenticate.v1",
        type: "authenticate",
        session,
      }));
    });
    socket.addEventListener("message", event => handle(JSON.parse(event.data)));
    socket.addEventListener("close", () => {
      pairStatus.textContent = "Connection closed";
      disconnectButton.disabled = true;
    });
    socket.addEventListener("error", () => { pairStatus.textContent = "Connection error"; });
  };

  const handle = message => {
    if (message.type === "authentication_receipt") {
      pairStatus.textContent = "Connected";
      pairButton.disabled = false;
      disconnectButton.disabled = false;
    } else if (message.type === "surface_snapshot") {
      surfaces.clear();
      message.surfaces.forEach(surface => surfaces.set(surface.surface_id, surface));
      render();
    } else if (message.type === "surface_available") {
      surfaces.set(message.surface.surface_id, message.surface);
      render();
    } else if (message.type === "surface_removed") {
      surfaces.delete(message.surface_id);
      render();
    } else if (message.type === "surface_state") {
      const surface = surfaces.get(message.surface_id);
      if (surface) {
        surface.state = message.state;
        surface.state_revision = message.state_revision;
        render();
      }
    } else if (message.type === "command_receipt") {
      const card = document.querySelector(`[data-surface-id="${CSS.escape(message.surface_id)}"]`);
      if (card) card.querySelector(".receipt").textContent = `${message.command}: ${message.status}`;
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
      socket.send(JSON.stringify({
        $schema: "rusty.quest.connection_hub.surface_command.v1",
        type: "surface.command",
        request_id: `browser.${crypto.randomUUID()}`,
        surface_id: surfaceId,
        command,
        args,
      }));
    } catch (error) {
      card.querySelector(".receipt").textContent = `Arguments rejected: ${error.message}`;
    }
  };

  const disconnect = async () => {
    if (session) {
      await fetch("/v1/revoke", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          $schema: "rusty.quest.connection_hub.revoke_request.v1",
          session,
          reason: "user_request",
        }),
      }).catch(() => {});
    }
    if (socket) socket.close();
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
