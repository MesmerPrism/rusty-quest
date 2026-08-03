"use strict";

const fs = require("fs");
const path = require("path");
const {spawnSync} = require("child_process");

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const key = process.argv[index];
  const value = process.argv[index + 1];
  if (!key || !key.startsWith("--") || value === undefined) throw new Error("closed argument pairs required");
  args.set(key, value);
}
for (const required of ["--origin", "--adb", "--serial", "--playwright-package-json", "--browser-executable"]) {
  if (!args.has(required)) throw new Error(`missing ${required}`);
}

const fixed = Object.freeze({
  spatialSurface: "surface.spatial_video_control.media",
  sampleSurface: "surface.connection_hub_sample.toggle",
  spatialPlay: "command.spatial_video_control.play",
  spatialPause: "command.spatial_video_control.pause",
  sampleToggle: "command.connection_hub_sample.toggle",
  spatialComponent: "io.github.mesmerprism.rustyquest.spatial_video_control_example/io.github.mesmerprism.rustyquest.spatial_video_control.SpatialVideoControlActivity",
  sampleComponent: "io.github.mesmerprism.rustyquest.connection_hub_sample/.ConnectionHubSampleActivity",
});

const adbStart = component => {
  const result = spawnSync(args.get("--adb"), ["-s", args.get("--serial"), "shell", "am", "start", "-W", "-n", component], {
    encoding: "utf8",
    windowsHide: true,
    timeout: 20000,
  });
  if (result.status !== 0) throw new Error(`fixed provider launch failed (${result.status})`);
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

    const spatial = page.locator(`[data-surface-id="${fixed.spatialSurface}"]`);
    const sample = page.locator(`[data-surface-id="${fixed.sampleSurface}"]`);
    adbStart(fixed.spatialComponent);
    await spatial.waitFor({state: "visible", timeout: 20000});
    await sample.waitFor({state: "detached", timeout: 20000});
    await spatial.getByRole("button", {name: "Play", exact: true}).click();
    await spatial.locator(".receipt").filter({hasText: `${fixed.spatialPlay}: provider_effect_observed`}).waitFor({timeout: 10000});
    await spatial.locator(".state").filter({hasText: /playing\s*true/i}).waitFor({timeout: 10000});
    observations.push("spatial-present-command-applied");

    adbStart(fixed.sampleComponent);
    await spatial.waitFor({state: "detached", timeout: 20000});
    await sample.waitFor({state: "visible", timeout: 20000});
    await sample.getByRole("button", {name: "Toggle", exact: true}).click();
    await sample.locator(".receipt").filter({hasText: `${fixed.sampleToggle}: provider_effect_observed`}).waitFor({timeout: 10000});
    await sample.locator(".state").filter({hasText: /toggled\s*true/i}).waitFor({timeout: 10000});
    observations.push("spatial-removed-sample-present-command-applied");

    adbStart(fixed.spatialComponent);
    await sample.waitFor({state: "detached", timeout: 20000});
    await spatial.waitFor({state: "visible", timeout: 20000});
    await spatial.getByRole("button", {name: "Pause", exact: true}).click();
    await spatial.locator(".receipt").filter({hasText: `${fixed.spatialPause}: provider_effect_observed`}).waitFor({timeout: 10000});
    await spatial.locator(".state").filter({hasText: /playing\s*false/i}).waitFor({timeout: 10000});
    observations.push("sample-removed-spatial-returned-command-applied");

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
      observations,
      console_error_count: 0,
      page_error_count: 0,
      pairing_secret_in_receipt: false,
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
