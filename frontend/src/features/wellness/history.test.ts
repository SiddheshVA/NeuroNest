/// <reference types="node" />
// Run with `npm test` (Node's built-in test runner; no extra dependencies).
import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { INITIAL_DELETE_STATE, canConfirm, deleteReducer, pageAfterDelete, type DeleteState } from "./history.ts";

describe("delete one", () => {
  test("ask, confirm, done", () => {
    let s: DeleteState = deleteReducer(INITIAL_DELETE_STATE, { type: "askOne", id: "a" });
    assert.deepEqual(s, { kind: "confirmOne", id: "a" });
    assert.ok(canConfirm(s));
    s = deleteReducer(s, { type: "confirm" });
    assert.deepEqual(s, { kind: "deleting", target: { one: "a" } });
    assert.deepEqual(deleteReducer(s, { type: "done" }), INITIAL_DELETE_STATE);
  });

  test("cancel closes without deleting; a failure reopens the dialog with an error", () => {
    const asked = deleteReducer(INITIAL_DELETE_STATE, { type: "askOne", id: "a" });
    assert.deepEqual(deleteReducer(asked, { type: "cancel" }), INITIAL_DELETE_STATE);
    const deleting = deleteReducer(asked, { type: "confirm" });
    assert.deepEqual(deleteReducer(deleting, { type: "failed", message: "oops" }), { kind: "confirmOne", id: "a", error: "oops" });
  });

  test("while deleting, cancel and new requests are ignored", () => {
    const deleting = deleteReducer(deleteReducer(INITIAL_DELETE_STATE, { type: "askOne", id: "a" }), { type: "confirm" });
    assert.equal(deleteReducer(deleting, { type: "cancel" }), deleting);
    assert.equal(deleteReducer(deleting, { type: "askAll" }), deleting);
  });
});

describe("delete all (stronger confirmation)", () => {
  test("cannot confirm until the word is typed", () => {
    let s = deleteReducer(INITIAL_DELETE_STATE, { type: "askAll" });
    assert.equal(canConfirm(s), false);
    assert.equal(deleteReducer(s, { type: "confirm" }), s);
    s = deleteReducer(s, { type: "typed", text: "delet" });
    assert.equal(canConfirm(s), false);
    s = deleteReducer(s, { type: "typed", text: "  delete " });
    assert.equal(canConfirm(s), true);
    assert.deepEqual(deleteReducer(s, { type: "confirm" }), { kind: "deleting", target: "all" });
  });
});

describe("paging after a delete", () => {
  test("an emptied page steps back; page 0 stays", () => {
    assert.equal(pageAfterDelete(2, 0), 1);
    assert.equal(pageAfterDelete(2, 3), 2);
    assert.equal(pageAfterDelete(0, 0), 0);
  });
});
