// History screen logic: the delete-confirmation flow and paging after a delete. Pure, unit-tested.

import { HISTORY } from "./copy.ts";

export const HISTORY_PAGE_SIZE = 10;

export type DeleteState =
  | { kind: "idle" }
  | { kind: "confirmOne"; id: string; error?: string }
  | { kind: "confirmAll"; typed: string; error?: string }
  | { kind: "deleting"; target: { one: string } | "all" };

export type DeleteAction =
  | { type: "askOne"; id: string }
  | { type: "askAll" }
  | { type: "typed"; text: string }
  | { type: "cancel" }
  | { type: "confirm" }
  | { type: "failed"; message: string }
  | { type: "done" };

export const INITIAL_DELETE_STATE: DeleteState = { kind: "idle" };

/** Delete-all needs the confirmation word typed exactly (case-insensitive, surrounding spaces ignored). */
export function canConfirm(state: DeleteState): boolean {
  if (state.kind === "confirmOne") return true;
  if (state.kind === "confirmAll") return state.typed.trim().toUpperCase() === HISTORY.confirmWord;
  return false;
}

export function deleteReducer(state: DeleteState, action: DeleteAction): DeleteState {
  switch (action.type) {
    case "askOne":
      return state.kind === "deleting" ? state : { kind: "confirmOne", id: action.id };
    case "askAll":
      return state.kind === "deleting" ? state : { kind: "confirmAll", typed: "" };
    case "typed":
      return state.kind === "confirmAll" ? { kind: "confirmAll", typed: action.text } : state;
    case "cancel":
      return state.kind === "deleting" ? state : INITIAL_DELETE_STATE;
    case "confirm":
      if (!canConfirm(state)) return state;
      return { kind: "deleting", target: state.kind === "confirmOne" ? { one: state.id } : "all" };
    case "failed":
      if (state.kind !== "deleting") return state;
      return state.target === "all"
        ? { kind: "confirmAll", typed: HISTORY.confirmWord, error: action.message }
        : { kind: "confirmOne", id: state.target.one, error: action.message };
    case "done":
      return INITIAL_DELETE_STATE;
  }
}

/** After deleting from a page that is now empty, step back a page (never below 0). */
export function pageAfterDelete(page: number, itemsLeftOnPage: number): number {
  return itemsLeftOnPage === 0 && page > 0 ? page - 1 : page;
}
