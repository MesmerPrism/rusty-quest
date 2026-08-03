"use strict";

const fs = require("fs");
const vm = require("vm");

if (process.argv.length !== 5) {
  throw new Error("usage: node test.js <vectors.json> <protocol.js> <app.js>");
}
const vectors = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const protocolSource = fs.readFileSync(process.argv[3], "utf8");
const appSource = fs.readFileSync(process.argv[4], "utf8");
const context = vm.createContext({});
vm.runInContext(protocolSource, context, {filename: "protocol.js"});
const actual = JSON.parse(JSON.stringify(context.RustyConnectionHubProtocol));

const exactKeySet = (actualValue, expectedValue, name) => {
  const actualKeys = Object.keys(actualValue).sort();
  const expectedKeys = Object.keys(expectedValue).sort();
  if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys)) {
    throw new Error(`${name} keys differ`);
  }
};
const exactKeys = (actualValue, expectedValue, name) => {
  exactKeySet(actualValue, expectedValue, name);
  const expectedKeys = Object.keys(expectedValue).sort();
  for (const key of expectedKeys) {
    if (actualValue[key] !== expectedValue[key]) throw new Error(`${name}.${key} differs`);
  }
};
const validateProjection = projection => {
  exactKeySet(projection, vectors.browser_projection, "browser_projection");
  for (const section of ["routes", "schemas", "types"]) {
    exactKeys(projection[section], vectors.browser_projection[section], section);
  }
};

validateProjection(actual);
for (const damage of [
  projection => { delete projection.routes.socket; },
  projection => { projection.schemas.unknown = "rusty.damaged.v1"; },
  projection => { projection.types.surface_command = "damaged"; },
]) {
  const damaged = JSON.parse(JSON.stringify(actual));
  damage(damaged);
  let rejected = false;
  try { validateProjection(damaged); } catch (_) { rejected = true; }
  if (!rejected) throw new Error("damaged browser projection accepted");
}

for (const literal of Object.values(vectors.browser_projection.schemas)) {
  if (appSource.includes(literal)) throw new Error(`app.js bypasses protocol projection: ${literal}`);
}
for (const route of Object.values(vectors.browser_projection.routes)) {
  if (appSource.includes(`"${route}"`) || appSource.includes(`'${route}'`)) {
    throw new Error(`app.js bypasses protocol projection: ${route}`);
  }
}
for (const access of [
  "protocol.schemas.pair_request",
  "protocol.schemas.revoke_request",
  "protocol.schemas.socket_authenticate",
  "protocol.schemas.surface_command",
  "protocol.types.command_receipt",
]) {
  if (!appSource.includes(access)) throw new Error(`app.js does not consume ${access}`);
}

class FakeElement {
  constructor() {
    this.textContent = "";
    this.disabled = false;
    this.value = "";
    this.listeners = new Map();
  }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  async click() {
    const listener = this.listeners.get("click");
    if (listener) await listener();
  }
  replaceChildren() {}
  append() {}
}

const storage = initial => {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
  };
};

const runDisconnect = async fetchImplementation => {
  const elements = new Map([
    ["#surfaces", new FakeElement()],
    ["#surface-template", new FakeElement()],
    ["#pair-status", new FakeElement()],
    ["#pair-button", new FakeElement()],
    ["#disconnect-button", new FakeElement()],
    ["#pairing-code", new FakeElement()],
  ]);
  const sessionStore = storage({rustyHubSession: "stale-test-bearer"});
  const sockets = [];
  class FakeWebSocket {
    static OPEN = 1;
    constructor() {
      this.readyState = FakeWebSocket.OPEN;
      this.listeners = new Map();
      this.closed = false;
      sockets.push(this);
    }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    send() {}
    close() {
      this.closed = true;
      this.readyState = 3;
      const listener = this.listeners.get("close");
      if (listener) listener({code: 1000});
    }
  }
  const context = vm.createContext({
    RustyConnectionHubProtocol: actual,
    document: {
      querySelector: selector => elements.get(selector),
      createElement: () => new FakeElement(),
    },
    sessionStorage: sessionStore,
    localStorage: storage({}),
    fetch: fetchImplementation,
    crypto: require("crypto").webcrypto,
    TextEncoder,
    location: {protocol: "http:", host: "hub.test"},
    WebSocket: FakeWebSocket,
    CSS: {escape: value => value},
  });
  vm.runInContext(appSource, context, {filename: "app.js"});
  await elements.get("#disconnect-button").click();
  return {
    status: elements.get("#pair-status").textContent,
    bearer: sessionStore.getItem("rustyHubSession"),
    socketClosed: sockets[0].closed,
  };
};

const response = (ok, status, receipt) => ({
  ok,
  status,
  json: async () => receipt,
});

(async () => {
  const accepted = JSON.parse(JSON.stringify(vectors.messages.revoke_receipt.example));
  const networkFailure = await runDisconnect(async () => { throw new Error("network unavailable"); });
  if (networkFailure.bearer !== "stale-test-bearer"
      || networkFailure.socketClosed
      || !networkFailure.status.startsWith("Disconnect failed:")) {
    throw new Error("fetch failure discarded the bearer or presented disconnect success");
  }

  const rejected = JSON.parse(JSON.stringify(accepted));
  rejected.applied = false;
  rejected.status = "session_not_found";
  const rejection = await runDisconnect(async () => response(false, 403, rejected));
  if (rejection.bearer !== "stale-test-bearer"
      || rejection.socketClosed
      || rejection.status !== "Disconnect failed: session_not_found") {
    throw new Error("rejected revoke discarded the bearer or presented disconnect success");
  }

  const openReceipt = JSON.parse(JSON.stringify(accepted));
  openReceipt.unexpected = true;
  const openReceiptResult = await runDisconnect(async () => response(true, 200, openReceipt));
  if (openReceiptResult.bearer !== "stale-test-bearer"
      || openReceiptResult.socketClosed
      || !openReceiptResult.status.startsWith("Disconnect failed:")) {
    throw new Error("open revoke receipt shape was accepted");
  }

  const success = await runDisconnect(async () => response(true, 200, accepted));
  if (success.bearer !== null || !success.socketClosed || success.status !== "Disconnected") {
    throw new Error("exact applied revoke receipt did not clear the bearer and close the socket");
  }
  console.log("Connection Hub browser protocol and revoke behavior passed");
})().catch(error => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
