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
      socket_authenticate: "rusty.quest.connection_hub.socket_authenticate.v1",
      surface_command: "rusty.quest.connection_hub.surface_command.v1",
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
    },
  };
  Object.values(protocol).forEach(Object.freeze);
  globalThis.RustyConnectionHubProtocol = Object.freeze(protocol);
})();
