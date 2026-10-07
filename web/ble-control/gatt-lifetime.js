"use strict";
// Protocol-neutral carrier lifetime. Authority and app effects remain external.
(() => {
  class OperationQueue {
    constructor(assertCurrent) {
      if (typeof assertCurrent !== "function") throw new TypeError("Current-generation guard required");
      this.assertCurrent = assertCurrent;
      this.tail = Promise.resolve();
    }
    run(generation, task) {
      const operation = this.tail.then(async () => {
        this.assertCurrent(generation);
        const value = await task();
        this.assertCurrent(generation);
        return value;
      });
      this.tail = operation.catch(() => {});
      return operation;
    }
  }

  // A matching transport acknowledgement is not a final application result.
  function commandReceipt(receipt, requestId) {
    if (!receipt || receipt.v !== 1 || receipt.id !== requestId) return null;
    if (!["accepted", "pending", "confirmed", "rejected", "outcome_unknown"].includes(receipt.state)) {
      throw new Error("Unsupported application receipt state");
    }
    return Object.freeze({
      state: receipt.state,
      terminal: ["confirmed", "rejected", "outcome_unknown"].includes(receipt.state),
      applied: receipt.state === "confirmed",
      detail: typeof receipt.detail === "string" ? receipt.detail : "",
    });
  }
  const api = Object.freeze({ OperationQueue, commandReceipt });
  if (typeof module !== "undefined") module.exports = api;
  else globalThis.QuestBleLifetime = api;
})();
