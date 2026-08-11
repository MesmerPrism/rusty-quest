"use strict";

const fs = require("fs");
const nodeCrypto = require("crypto");
const vm = require("vm");

if (process.argv.length !== 6) {
  throw new Error("usage: node test.js <v1.json> <v2.json> <protocol.js> <app.js>");
}
const legacyVectorBytes = fs.readFileSync(process.argv[2]);
const legacyVectors = JSON.parse(legacyVectorBytes.toString("utf8"));
const vectors = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
const protocolSource = fs.readFileSync(process.argv[4], "utf8");
const appSource = fs.readFileSync(process.argv[5], "utf8");
const context = vm.createContext({});
vm.runInContext(protocolSource, context, {filename: "protocol.js"});
const actual = JSON.parse(JSON.stringify(context.RustyConnectionHubProtocol));
const canonicalJsonEncoder = context.RustyConnectionHubCanonicalJson;
const legacySha256 = nodeCrypto.createHash("sha256").update(legacyVectorBytes).digest("hex");
if (vectors.legacy_protocol_sha256 !== `sha256:${legacySha256}`) {
  throw new Error("v2 vector does not bind the exact legacy v1 vector bytes");
}
for (const frame of Object.values(vectors.canonical_frames)) {
  const canonical = context.RustyConnectionHubCanonicalJson(JSON.parse(frame.utf8));
  if (canonical !== frame.utf8) throw new Error("browser canonical JSON bytes differ");
  const digest = nodeCrypto.createHash("sha256").update(canonical, "utf8").digest("hex");
  if (frame.sha256 !== `sha256:${digest}`) throw new Error("browser canonical digest differs");
}

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
  "protocol.schemas.keepalive",
  "protocol.types.command_receipt",
  "protocol.types.keepalive_receipt",
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
  async dispatch(type, event = {}) {
    const listener = this.listeners.get(type);
    if (listener) await listener(event);
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
    RustyConnectionHubCanonicalJson: canonicalJsonEncoder,
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
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: () => 1,
    clearTimeout: () => {},
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

const runPlainHttpPair = async () => {
  const elements = new Map([
    ["#surfaces", new FakeElement()],
    ["#surface-template", new FakeElement()],
    ["#pair-status", new FakeElement()],
    ["#pair-button", new FakeElement()],
    ["#disconnect-button", new FakeElement()],
    ["#pairing-code", new FakeElement()],
  ]);
  elements.get("#pairing-code").value = "12a 3456";
  let pairRequest = null;
  class FakeWebSocket {
    static OPEN = 1;
    constructor() { this.readyState = FakeWebSocket.OPEN; this.listeners = new Map(); }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    send() {}
    close() { this.readyState = 3; }
  }
  const insecureCrypto = {
    getRandomValues: bytes => { bytes.fill(0x2a); return bytes; },
  };
  const browserContext = vm.createContext({
    RustyConnectionHubProtocol: actual,
    RustyConnectionHubCanonicalJson: canonicalJsonEncoder,
    document: {
      querySelector: selector => elements.get(selector),
      createElement: () => new FakeElement(),
    },
    sessionStorage: storage({}),
    localStorage: storage({}),
    fetch: async (_route, request) => {
      pairRequest = JSON.parse(request.body);
      return response(true, 200, {accepted: true, session: "plain-http-session"});
    },
    crypto: insecureCrypto,
    TextEncoder,
    Uint8Array,
    location: {protocol: "http:", host: "hub.test"},
    WebSocket: FakeWebSocket,
    CSS: {escape: value => value},
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: () => 1,
    clearTimeout: () => {},
  });
  vm.runInContext(appSource, browserContext, {filename: "app.js"});
  await elements.get("#pairing-code").dispatch("keydown", {
    key: "Enter",
    preventDefault: () => { throw new Error("invalid pairing input prevented default"); },
  });
  if (pairRequest !== null || !elements.get("#pair-button").disabled) {
    throw new Error("invalid pairing code remained submit-capable");
  }
  await elements.get("#pairing-code").dispatch("input");
  if (elements.get("#pairing-code").value !== "123456"
      || elements.get("#pair-button").disabled) {
    throw new Error("pairing input was not normalized into a submit-capable code");
  }
  let prevented = false;
  await elements.get("#pairing-code").dispatch("keydown", {
    key: "Enter",
    preventDefault: () => { prevented = true; },
  });
  if (!prevented) throw new Error("valid pairing Enter key was not consumed");
  const seed = "2a".repeat(32);
  const expectedIdentity = nodeCrypto.createHash("sha256").update(seed, "utf8").digest("hex");
  if (!pairRequest || pairRequest.pairing_code !== "123456"
      || pairRequest.controller_identity_sha256 !== expectedIdentity) {
    throw new Error("plain-HTTP browser pairing did not derive the exact controller identity");
  }
  if (elements.get("#pairing-code").value !== "") {
    throw new Error("accepted browser pairing retained the one-use pairing code");
  }
};

const runStoredSessionRecovery = () => {
  const elements = new Map([
    ["#surfaces", new FakeElement()],
    ["#surface-template", new FakeElement()],
    ["#pair-status", new FakeElement()],
    ["#pair-button", new FakeElement()],
    ["#disconnect-button", new FakeElement()],
    ["#pairing-code", new FakeElement()],
  ]);
  const sessionStore = storage({rustyHubSession: "expired-test-bearer"});
  const sockets = [];
  const timeouts = new Map();
  let timerId = 0;
  class FakeWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;
    constructor() {
      this.readyState = FakeWebSocket.OPEN;
      this.listeners = new Map();
      sockets.push(this);
    }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    send() {}
    emit(type, value = {}) {
      const listener = this.listeners.get(type);
      if (listener) listener(value);
    }
    close() {
      this.readyState = FakeWebSocket.CLOSED;
      this.emit("close", {code: 1000});
    }
  }
  const browserContext = vm.createContext({
    RustyConnectionHubProtocol: actual,
    RustyConnectionHubCanonicalJson: canonicalJsonEncoder,
    document: {
      querySelector: selector => elements.get(selector),
      createElement: () => new FakeElement(),
    },
    sessionStorage: sessionStore,
    localStorage: storage({}),
    fetch: async () => { throw new Error("unexpected fetch"); },
    crypto: require("crypto").webcrypto,
    TextEncoder,
    location: {protocol: "http:", host: "hub.test"},
    WebSocket: FakeWebSocket,
    CSS: {escape: value => value},
    setInterval: () => 1,
    clearInterval: () => {},
    setTimeout: callback => { const id = ++timerId; timeouts.set(id, callback); return id; },
    clearTimeout: id => timeouts.delete(id),
  });
  vm.runInContext(appSource, browserContext, {filename: "app.js"});
  if (!elements.get("#pair-button").disabled) {
    throw new Error("stored-session reconnect left pairing enabled");
  }
  sockets[0].close();
  if (sessionStore.getItem("rustyHubSession") !== null
      || !elements.get("#pair-button").disabled
      || !elements.get("#disconnect-button").disabled
      || elements.get("#pair-status").textContent !== "Stored session unavailable. Pair again."
      || timeouts.size !== 0) {
    throw new Error("unconfirmed stored session did not return to clean pairing state");
  }
};

const runV2SequenceFlow = () => {
  const elements = new Map([
    ["#surfaces", new FakeElement()],
    ["#surface-template", new FakeElement()],
    ["#pair-status", new FakeElement()],
    ["#pair-button", new FakeElement()],
    ["#disconnect-button", new FakeElement()],
    ["#pairing-code", new FakeElement()],
  ]);
  const sockets = [];
  const intervals = new Map();
  const timeouts = new Map();
  let timerId = 0;
  class FakeWebSocket {
    static OPEN = 1;
    constructor() {
      this.readyState = FakeWebSocket.OPEN;
      this.listeners = new Map();
      this.sent = [];
      sockets.push(this);
    }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    send(value) { this.sent.push(value); }
    emit(type, value = {}) {
      const listener = this.listeners.get(type);
      if (listener) listener(value);
    }
    close() {
      this.readyState = 3;
      this.emit("close", {code: 1000});
    }
  }
  const browserContext = vm.createContext({
    RustyConnectionHubProtocol: actual,
    RustyConnectionHubCanonicalJson: canonicalJsonEncoder,
    document: {
      querySelector: selector => elements.get(selector),
      createElement: () => new FakeElement(),
    },
    sessionStorage: storage({rustyHubSession: "sequence-test-bearer"}),
    localStorage: storage({}),
    fetch: async () => { throw new Error("unexpected fetch"); },
    crypto: require("crypto").webcrypto,
    TextEncoder,
    location: {protocol: "http:", host: "hub.test"},
    WebSocket: FakeWebSocket,
    CSS: {escape: value => value},
    setInterval: callback => { const id = ++timerId; intervals.set(id, callback); return id; },
    clearInterval: id => intervals.delete(id),
    setTimeout: callback => { const id = ++timerId; timeouts.set(id, callback); return id; },
    clearTimeout: id => timeouts.delete(id),
  });
  vm.runInContext(appSource, browserContext, {filename: "app.js"});
  const first = sockets[0];
  first.emit("open");
  if (JSON.parse(first.sent[0]).$schema !== vectors.messages.socket_authenticate.schema) {
    throw new Error("browser did not negotiate v2 authentication");
  }
  const authentication = JSON.parse(JSON.stringify(
    vectors.messages.socket_authentication_receipt.example));
  authentication.next_external_request_sequence = 7;
  first.emit("message", {data: JSON.stringify(authentication)});
  if (!elements.get("#pair-button").disabled
      || elements.get("#disconnect-button").disabled) {
    throw new Error("authenticated browser did not lock pairing and enable disconnect");
  }
  Array.from(intervals.values())[0]();
  const firstKeepalive = first.sent[1];
  if (firstKeepalive !== context.RustyConnectionHubCanonicalJson({
    $schema: vectors.messages.keepalive.schema,
    type: "keepalive",
    request_sequence: 7,
  })) throw new Error("keepalive did not use the exact authority sequence/canonical bytes");
  const keepaliveReceipt = JSON.parse(JSON.stringify(vectors.messages.keepalive_receipt.example));
  keepaliveReceipt.request_sequence = 7;
  keepaliveReceipt.next_external_request_sequence = 8;
  first.emit("message", {data: JSON.stringify(keepaliveReceipt)});
  first.close();
  const reconnect = Array.from(timeouts.values())[0];
  if (!reconnect) throw new Error("authenticated close did not schedule reconnect");
  reconnect();
  const second = sockets[1];
  second.emit("open");
  const reauthentication = JSON.parse(JSON.stringify(authentication));
  reauthentication.transport_epoch += 1;
  reauthentication.next_external_request_sequence = 19;
  second.emit("message", {data: JSON.stringify(reauthentication)});
  Array.from(intervals.values())[0]();
  if (JSON.parse(second.sent[1]).request_sequence !== 19) {
    throw new Error("reconnect did not resynchronize the exact authority sequence");
  }
};

(async () => {
  await runPlainHttpPair();
  runStoredSessionRecovery();
  runV2SequenceFlow();
  const accepted = JSON.parse(JSON.stringify(legacyVectors.messages.revoke_receipt.example));
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
