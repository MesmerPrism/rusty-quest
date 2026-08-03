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
console.log("Connection Hub browser protocol projection passed");
