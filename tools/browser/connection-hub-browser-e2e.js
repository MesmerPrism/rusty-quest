"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key || !key.startsWith("--") || value === undefined) throw new Error("closed argument pairs required");
  if (args.has(key)) throw new Error("duplicate argument rejected");
  args.set(key, value);
}
const allowedArguments = new Set([
  "--origin",
  "--playwright-package-json",
  "--browser-executable",
  "--bridge-receipt",
  "--bridge-receipt-sha256",
]);
if ([...args.keys()].some(key => !allowedArguments.has(key))) throw new Error("unknown argument rejected");
for (const required of allowedArguments) {
  if (!args.has(required)) throw new Error(`missing ${required}`);
}

const fixed = Object.freeze({
  providerPackage: "io.github.mesmerprism.rustyquest.spatial_camera_panel",
  surface: "surface.spatial_camera_panel.locked_playlist",
  previous: "command.spatial_camera_panel.locked_playlist.previous",
  next: "command.spatial_camera_panel.locked_playlist.next",
  pause: "command.spatial_camera_panel.locked_playlist.pause",
  resume: "command.spatial_camera_panel.locked_playlist.resume",
  bridgeSchema: "rusty.quest.connection_hub.typed_bridge_receipt.v1",
  bridgeHostEndpoint: "tcp:18765",
  bridgeDeviceEndpoint: "tcp:8876",
  browserOrigin: "http://127.0.0.1:18765",
});

if (args.get("--origin") !== fixed.browserOrigin) throw new Error("browser origin substitution rejected");

const sha256 = value => crypto.createHash("sha256").update(value).digest("hex");

const readBridgeReceipt = () => {
  const bytes = fs.readFileSync(args.get("--bridge-receipt"));
  if (sha256(bytes) !== args.get("--bridge-receipt-sha256")) {
    throw new Error("bridge receipt digest mismatch");
  }
  const outer = JSON.parse(bytes.toString("utf8"));
  const receipt = outer?.details?.bridge_receipt;
  if (outer?.$schema !== "rusty.quest.connection_hub.operator_receipt.v1"
      || outer?.operation !== "published-bridge-open"
      || outer?.provider !== "published-fixed-adb-bridge"
      || outer?.status !== "passed"
      || receipt?.$schema !== fixed.bridgeSchema
      || receipt?.operation !== "open"
      || receipt?.provider_id !== "android-platform-tools-adb"
      || !/^[0-9a-f]{64}$/.test(receipt?.provider_executable_sha256 || "")
      || receipt?.host_endpoint !== fixed.bridgeHostEndpoint
      || receipt?.device_endpoint !== fixed.bridgeDeviceEndpoint
      || receipt?.browser_origin !== fixed.browserOrigin
      || receipt?.terminal_status !== "confirmed"
      || receipt?.readback_confirmed !== true
      || receipt?.secrets_in_receipt !== false
      || receipt?.caller_selected_endpoint !== false
      || receipt?.caller_selected_identity !== false
      || receipt?.caller_selected_capability !== false
      || receipt?.owner_effect_claimed !== false) {
    throw new Error("bridge receipt failed closed validation");
  }
  return receipt;
};

const readPairingSecret = async () => {
  const chunks = [];
  let total = 0;
  for await (const chunk of process.stdin) {
    total += chunk.length;
    if (total > 16) throw new Error("pairing secret input too long");
    chunks.push(chunk);
  }
  const buffer = Buffer.concat(chunks);
  try {
    const value = buffer.toString("ascii").trim();
    if (!/^\d{6}$/.test(value)) throw new Error("pairing secret input invalid");
    return value;
  } finally {
    for (const chunk of chunks) chunk.fill(0);
    buffer.fill(0);
  }
};

const main = async () => {
  const bridgeReceipt = readBridgeReceipt();
  const packageJson = JSON.parse(fs.readFileSync(args.get("--playwright-package-json"), "utf8"));
  const playwright = require(path.dirname(args.get("--playwright-package-json")));
  if (!playwright.chromium || typeof packageJson.version !== "string") throw new Error("Playwright provider invalid");
  let pairingSecret = await readPairingSecret();
  const browser = await playwright.chromium.launch({
    headless: true,
    executablePath: args.get("--browser-executable"),
  });
  const consoleErrors = [];
  const pageErrors = [];
  const observations = [];
  try {
    const page = await browser.newPage({viewport: {width: 1280, height: 720}});
    await page.addInitScript(() => {
      const NativeWebSocket = globalThis.WebSocket;
      globalThis.__rustyConnectionHubSocketWitnesses = [];
      globalThis.WebSocket = new Proxy(NativeWebSocket, {
        construct(Target, constructorArgs) {
          const webSocket = Reflect.construct(Target, constructorArgs);
          const witness = {opened: false, closed: false, close_code: null};
          globalThis.__rustyConnectionHubSocketWitnesses.push(witness);
          webSocket.addEventListener("open", () => { witness.opened = true; });
          webSocket.addEventListener("close", event => {
            witness.closed = true;
            witness.close_code = event.code;
          });
          return webSocket;
        },
      });
    });
    page.on("console", message => { if (message.type() === "error") consoleErrors.push(message.text()); });
    page.on("pageerror", error => pageErrors.push(error.message));
    await page.goto(args.get("--origin"), {waitUntil: "domcontentloaded", timeout: 20000});
    await page.locator("#pairing-code").fill(pairingSecret);
    pairingSecret = null;
    await page.locator("#pair-button").click();
    await page.locator("#pair-status").filter({hasText: "Connected"}).waitFor({timeout: 15000});
    const staleBearer = await page.evaluate(() => sessionStorage.getItem("rustyHubSession"));
    if (typeof staleBearer !== "string" || staleBearer.length === 0) {
      throw new Error("paired browser session bearer unavailable");
    }
    const authenticatedSocketIndex = await page.evaluate(() => {
      const index = globalThis.__rustyConnectionHubSocketWitnesses.findIndex(entry => entry.opened);
      if (index < 0) throw new Error("authenticated socket witness unavailable");
      return index;
    });

    const spatial = page.locator(`[data-surface-id="${fixed.surface}"]`);
    await spatial.waitFor({state: "visible", timeout: 20000});
    if ((await spatial.locator(".provider").textContent())?.trim() !== fixed.providerPackage) {
      throw new Error("locked-playlist provider package mismatch");
    }

    const readPresentedState = () => spatial.evaluate(card => {
      const rows = Object.fromEntries([...card.querySelectorAll(".state div")].map(row => [
        row.querySelector("dt")?.textContent ?? "",
        row.querySelector("dd")?.textContent ?? "",
      ]));
      const progress = card.querySelector('progress[aria-label="Item time"]');
      return {
        playlist: rows.Playlist ?? null,
        current_item: rows["Current item"] ?? null,
        item: rows.Item ?? null,
        item_time: rows["Item time"] ?? null,
        progress: progress ? Number(progress.value) : null,
        playback_toggle: card.querySelector("button.playback-toggle")?.textContent ?? null,
      };
    });
    const waitForPresentedItemChange = before => page.waitForFunction(({surfaceId, previous}) => {
      const card = document.querySelector(`[data-surface-id="${CSS.escape(surfaceId)}"]`);
      const rows = Object.fromEntries([...(card?.querySelectorAll(".state div") || [])].map(row => [
        row.querySelector("dt")?.textContent ?? "",
        row.querySelector("dd")?.textContent ?? "",
      ]));
      return rows["Current item"] !== previous.current_item || rows.Item !== previous.item;
    }, {surfaceId: fixed.surface, previous: before}, {timeout: 10000});
    const issue = async (label, command, expectedToggle = null) => {
      const before = await readPresentedState();
      const button = expectedToggle
        ? spatial.getByRole("button", {name: new RegExp(`^${label} · `)})
        : spatial.getByRole("button", {name: label, exact: true});
      await button.click();
      await spatial.locator(".receipt")
        .filter({hasText: `${command}: provider_effect_observed`})
        .waitFor({timeout: 10000});
      if (expectedToggle) {
        await spatial.getByRole("button", {name: expectedToggle, exact: true}).waitFor({timeout: 10000});
      } else {
        await waitForPresentedItemChange(before);
      }
      observations.push(`${label.toLowerCase()}-owner-effect-confirmed-once`);
    };

    const baseline = await readPresentedState();
    const position = /^(\d+) of (\d+)$/.exec(baseline.current_item || "");
    if (!baseline.playlist || baseline.playlist === "Untitled playlist"
        || !position || Number(position[1]) < 1 || Number(position[1]) > Number(position[2])
        || Number(position[2]) < 2 || !baseline.item || baseline.item === "Unnamed item"
        || !/^\d+:\d{2} \/ \d+:\d{2}$/.test(baseline.item_time || "")
        || !Number.isFinite(baseline.progress) || baseline.progress < 0 || baseline.progress > 1
        || !/^Pause · (Playing|Transitioning)$/.test(baseline.playback_toggle || "")) {
      throw new Error("locked playlist presentation is incomplete or not effectively running");
    }
    observations.push("locked-playlist-title-count-index-label-state-progress-observed");
    await issue("Previous", fixed.previous);
    await issue("Next", fixed.next);
    await issue("Pause", fixed.pause, "Resume · Paused");
    const pausedTime = (await readPresentedState()).item_time;
    await page.waitForTimeout(1500);
    if ((await readPresentedState()).item_time !== pausedTime) {
      throw new Error("paused playlist clock advanced");
    }
    observations.push("paused-clock-frozen");
    await issue("Resume", fixed.resume, "Pause · Playing");

    await page.locator("#disconnect-button").click();
    await page.locator("#pair-status").filter({hasText: "Disconnected"}).waitFor({timeout: 10000});
    await page.waitForFunction(index => {
      const witness = globalThis.__rustyConnectionHubSocketWitnesses[index];
      return Boolean(witness && witness.opened && witness.closed);
    }, authenticatedSocketIndex, {timeout: 10000});
    if (await page.evaluate(() => sessionStorage.getItem("rustyHubSession") !== null)) {
      throw new Error("accepted revoke retained browser session bearer");
    }
    observations.push("accepted-revoke-closed-authenticated-socket");

    const staleAuthentication = await page.evaluate(({bearer}) => new Promise((resolve, reject) => {
      const protocol = globalThis.RustyConnectionHubProtocol;
      const scheme = location.protocol === "https:" ? "wss" : "ws";
      const webSocket = new WebSocket(`${scheme}://${location.host}${protocol.routes.socket}`);
      let authenticationReceiptObserved = false;
      const timeout = setTimeout(() => {
        webSocket.close();
        reject(new Error("stale bearer authentication socket did not close"));
      }, 10000);
      webSocket.addEventListener("open", () => {
        webSocket.send(JSON.stringify({
          $schema: protocol.schemas.socket_authenticate,
          type: protocol.types.authenticate,
          session: bearer,
        }));
      });
      webSocket.addEventListener("message", event => {
        const message = JSON.parse(event.data);
        if (message.type === protocol.types.authentication_receipt) {
          authenticationReceiptObserved = true;
        }
      });
      webSocket.addEventListener("error", () => {});
      webSocket.addEventListener("close", event => {
        clearTimeout(timeout);
        resolve({
          closed: true,
          close_code: event.code,
          authentication_receipt_observed: authenticationReceiptObserved,
        });
      });
    }), {bearer: staleBearer});
    if (!staleAuthentication.closed || staleAuthentication.authentication_receipt_observed) {
      throw new Error("revoked stale bearer authenticated on a fresh socket");
    }
    observations.push("revoked-stale-bearer-fresh-auth-rejected");
    if (consoleErrors.length || pageErrors.length) throw new Error("browser console/page errors observed");
    process.stdout.write(JSON.stringify({
      $schema: "rusty.quest.connection_hub.browser_e2e_receipt.v1",
      result: "pass",
      playwright_version: packageJson.version,
      provider_package: fixed.providerPackage,
      surface_id: fixed.surface,
      commands: [fixed.previous, fixed.next, fixed.pause, fixed.resume],
      bridge_receipt_sha256: args.get("--bridge-receipt-sha256"),
      bridge_provider_id: bridgeReceipt.provider_id,
      browser_origin: fixed.browserOrigin,
      observations,
      console_error_count: 0,
      page_error_count: 0,
      pairing_secret_in_receipt: false,
      owner_effect_confirmed: true,
      transport_only_acceptance: false,
    }));
  } finally {
    pairingSecret = null;
    await browser.close();
  }
};

main().catch(error => {
  process.stderr.write(`browser_e2e_failed:${error.message}\n`);
  process.exitCode = 1;
});
