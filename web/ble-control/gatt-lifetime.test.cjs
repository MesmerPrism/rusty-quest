const test = require("node:test"), assert = require("node:assert/strict");
const {OperationQueue, commandReceipt} = require("./gatt-lifetime.js");

test("one GATT operation runs at a time even when a previous operation rejects", async () => {
  let active = 0, maximum = 0;
  const queue = new OperationQueue(() => {});
  const calls = Array.from({length: 5}, (_, index) => queue.run(0, async () => {
    active++; maximum = Math.max(maximum, active);
    await Promise.resolve(); active--;
    if (index === 1) throw Error("read failed");
    return index;
  }));
  const result = await Promise.allSettled(calls);
  assert.equal(maximum, 1);
  assert.equal(result[1].status, "rejected");
  assert.equal(result[4].value, 4);
});

test("retiring a connection suppresses an in-flight result and queued writes", async () => {
  let current = 0, release, writes = 0;
  const blocked = new Promise(resolve => { release = resolve; });
  const queue = new OperationQueue(generation => {
    if (generation !== current) throw Error("retired connection");
  });
  const read = queue.run(0, async () => { await blocked; return "stale receipt"; });
  const write = queue.run(0, async () => { writes++; });
  await Promise.resolve(); current++; release();
  const result = await Promise.allSettled([read, write]);
  assert.ok(result.every(row => row.status === "rejected"));
  assert.equal(writes, 0);
  assert.equal(await queue.run(1, async () => "fresh"), "fresh");
});

test("only exact terminal app receipts can claim confirmed effects", () => {
  for (const state of ["accepted", "pending", "confirmed", "rejected", "outcome_unknown"]) {
    const receipt = commandReceipt({v: 1, id: "current", state}, "current");
    assert.equal(receipt.applied, state === "confirmed");
    assert.equal(receipt.terminal, !["accepted", "pending"].includes(state));
  }
  assert.equal(commandReceipt({v: 1, id: "previous", state: "confirmed"}, "current"), null);
  assert.equal(commandReceipt({v: 2, id: "current", state: "confirmed"}, "current"), null);
  assert.throws(() => commandReceipt({v: 1, id: "current", state: "written"}, "current"));
});
