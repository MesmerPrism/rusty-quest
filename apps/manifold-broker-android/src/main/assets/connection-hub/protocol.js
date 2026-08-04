(() => {
  "use strict";
  const protocol = {
    routes: {
      pair: "/v1/pair",
      revoke: "/v1/revoke",
      socket: "/v1/socket",
    },
    schemas: {
      pair_request: "rusty.quest.connection_hub.pair_request.v1",
      revoke_request: "rusty.quest.connection_hub.revoke_request.v1",
      socket_authenticate: "rusty.quest.connection_hub.socket_authenticate.v2",
      surface_command: "rusty.quest.connection_hub.surface_command.v2",
      keepalive: "rusty.quest.connection_hub.keepalive.v2",
      socket_authentication_receipt: "rusty.quest.connection_hub.socket_authentication_receipt.v2",
      command_receipt: "rusty.quest.connection_hub.command_receipt.v2",
      keepalive_receipt: "rusty.quest.connection_hub.keepalive_receipt.v2",
      protocol_error: "rusty.quest.connection_hub.protocol_error.v2",
    },
    types: {
      authenticate: "authenticate",
      authentication_receipt: "authentication_receipt",
      surface_snapshot: "surface_snapshot",
      surface_available: "surface_available",
      surface_removed: "surface_removed",
      surface_state: "surface_state",
      surface_command: "surface.command",
      command_receipt: "command_receipt",
      keepalive: "keepalive",
      keepalive_receipt: "keepalive_receipt",
      protocol_error: "protocol_error",
    },
  };
  const asciiJsonString = value => JSON.stringify(value).replace(
    /[^\x00-\x7f]/g,
    character => `\\u${character.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
  const canonicalJson = value => {
    if (value === null || typeof value === "string" || typeof value === "boolean") {
      return asciiJsonString(value);
    }
    if (typeof value === "number") {
      if (!Number.isFinite(value)) throw new TypeError("canonical JSON numbers must be finite");
      return JSON.stringify(value);
    }
    if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
    if (typeof value !== "object") throw new TypeError("unsupported canonical JSON value");
    const keys = Object.keys(value).sort();
    if (keys.some(key => !/^[\x20-\x7e]+$/.test(key))) {
      throw new TypeError("canonical JSON object keys must be printable ASCII");
    }
    return `{${keys.map(key => `${asciiJsonString(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  };
  Object.values(protocol).forEach(Object.freeze);
  globalThis.RustyConnectionHubProtocol = Object.freeze(protocol);
  globalThis.RustyConnectionHubCanonicalJson = canonicalJson;
})();
