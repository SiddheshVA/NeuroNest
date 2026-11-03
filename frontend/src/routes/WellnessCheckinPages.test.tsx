// Component tests for the Wellness Snapshot pages (Vitest + React Testing Library). The API module is mocked;
// nothing reaches a server.
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, test, vi } from "vitest";

import * as api from "@/features/wellness/api.ts";
import { WellnessApiError } from "@/features/wellness/errors.ts";
import { item, response } from "@/features/wellness/test-fixtures.ts";
import { WellnessCheckinPage, WellnessHistoryPage } from "@/routes/WellnessCheckinPages";

vi.mock("@/features/wellness/api.ts", () => ({
  submitCheckin: vi.fn(),
  previewCheckin: vi.fn(),
  listCheckins: vi.fn(),
  latestCheckin: vi.fn(),
  deleteCheckin: vi.fn(),
  deleteAllCheckins: vi.fn(),
}));

const submit = vi.mocked(api.submitCheckin);
const list = vi.mocked(api.listCheckins);
const deleteOne = vi.mocked(api.deleteCheckin);
const deleteAll = vi.mocked(api.deleteAllCheckins);

function renderAt(ui: React.ReactElement, path = "/wellness-snapshot") {
  return render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);
}

const next = () => userEvent.click(screen.getByRole("button", { name: /^continue/i }));
const pick = (name: string | RegExp) => userEvent.click(screen.getByRole("button", { name }));

/** Fills all four steps as a working person; returns before submitting. */
async function fillRequired(segment: "Studying" | "Working" | "Both" = "Working") {
  await pick(segment);
  await pick("One year older");
  await pick("Some (30–60 min)");
  await next();
  await pick("6–7 h");
  const rested = screen.getByRole("button", { name: "6 out of 10" });
  expect(rested).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(rested);
  expect(rested).toHaveAttribute("aria-pressed", "true");
  await next();
  await pick("Heavy");
  await pick("Not great");
  await pick("Sometimes");
  await next();
  await pick("7 out of 10");
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("questionnaire", () => {
  test("required answers are enforced before moving on", async () => {
    renderAt(<WellnessCheckinPage />);
    await next();
    expect(screen.getAllByText("Please choose an answer to continue.")).toHaveLength(3);
    expect(screen.getByRole("heading", { level: 1, name: "Let's start with your day" })).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  test("the step list marks the current step; chips and the 1-10 scale are toggle buttons", async () => {
    renderAt(<WellnessCheckinPage />);
    const stepItems = () => within(screen.getByRole("list", { name: "Snapshot steps" })).getAllByRole("listitem");
    expect(stepItems()[0]).toHaveAttribute("aria-current", "step");
    expect(stepItems()[1]).not.toHaveAttribute("aria-current");
    const working = screen.getByRole("button", { name: "Working" });
    expect(working).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(working);
    expect(working).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("group", { name: "Right now, are you mostly…" })).toContainElement(working);
    await pick("One year older");
    await pick("Plenty (1 hr+)");
    expect(screen.getByText(/Roughly 525 minutes a week, which meets the WHO's 150\+ minutes/)).toBeInTheDocument();
    await next();
    expect(stepItems()[1]).toHaveAttribute("aria-current", "step");
    expect(stepItems()[0]).toHaveTextContent("(done)");
  });

  test("live sleep feedback follows the chip and the rested score", async () => {
    renderAt(<WellnessCheckinPage />);
    await pick("Both");
    await pick("One year older");
    await pick("A bit (15–30 min)");
    await next();
    await pick("Under 5 h");
    expect(screen.getByText(/Well under the 7\+ hours most adults are advised to get/)).toBeInTheDocument();
    await pick("6–7 h");
    await pick("5 out of 10");
    expect(screen.getByText("6–7 h, feeling so-so")).toBeInTheDocument();
    expect(screen.getByText("Close, but a little under the 7+ hours adults are generally advised to get.")).toBeInTheDocument();
    await pick("8 h+");
    expect(screen.getByText(/Plenty of time asleep/)).toBeInTheDocument();
  });

  test("Add it now returns to the last step with the optional answers open", async () => {
    submit.mockResolvedValue(response("Moderate"));
    renderAt(<WellnessCheckinPage />);
    await fillRequired();
    await pick(/see my result/i);
    await userEvent.click(await screen.findByRole("button", { name: /add it now/i }));
    expect(screen.getByRole("heading", { level: 1, name: "Last one: how this week felt" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /sharpen your result/i })).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("button", { name: "7 out of 10" })).toHaveAttribute("aria-pressed", "true");
  });

  test("wording adapts to studying vs working", async () => {
    renderAt(<WellnessCheckinPage />);
    await pick("Studying");
    await pick("One year older");
    await pick("A bit (15–30 min)");
    await next();
    await pick("7–8 h");
    await pick("8 out of 10");
    await next();
    expect(screen.getByText("How heavy has your study load felt lately?")).toBeInTheDocument();
    expect(screen.getByText(/fees, rent, loans/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /back/i }));
    await userEvent.click(screen.getByRole("button", { name: /back/i }));
    await pick("Working");
    await next();
    await next();
    expect(screen.getByText("How heavy has your workload felt lately?")).toBeInTheDocument();
    expect(screen.getByText(/EMIs/)).toBeInTheDocument();
  });

  test("the payload has numbers from the chips; skipped optionals are omitted; no sleep boosters are asked", async () => {
    submit.mockResolvedValue(response("Moderate"));
    renderAt(<WellnessCheckinPage />);
    await fillRequired("Working");
    await userEvent.click(screen.getByRole("button", { name: /sharpen your result/i }));
    expect(screen.queryByText(/bmi|weight|heart rate|daily steps|bpm/i)).not.toBeInTheDocument();
    await pick("8–10 h");
    await pick("Prefer not to say");
    await userEvent.click(screen.getByRole("button", { name: /see my result/i }));

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    expect(submit.mock.calls[0][0]).toEqual({
      segment: "working",
      age: 30,
      physicalActivityMinutes: 45,
      sleepDuration: 6.5,
      qualityOfSleep: 6,
      pressure: 4,
      satisfaction: 2,
      financialStress: 3,
      stressLevel: 7,
      workStudyHours: 9,
    });
    expect(await screen.findByRole("heading", { name: "Here is your snapshot" })).toBeInTheDocument();
  });

  test("a 503 keeps the answers and offers a retry", async () => {
    submit.mockRejectedValueOnce(new WellnessApiError(503, "x", "WELLNESS_UNAVAILABLE"));
    submit.mockResolvedValueOnce(response("Low"));
    renderAt(<WellnessCheckinPage />);
    await fillRequired();
    await userEvent.click(screen.getByRole("button", { name: /see my result/i }));

    expect(await screen.findByText(/resting for a moment/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "7 out of 10" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
    expect(submit.mock.calls[1][0]).toEqual(submit.mock.calls[0][0]);
    expect(await screen.findByRole("heading", { name: "Here is your snapshot" })).toBeInTheDocument();
  });

  test("a 400 shows the field message inline on the right step", async () => {
    submit.mockRejectedValue(
      new WellnessApiError(400, "invalid", "WELLNESS_INVALID_INPUT", [
        { field: "pressure", message: "Input should be less than or equal to 5" },
      ]),
    );
    renderAt(<WellnessCheckinPage />);
    await fillRequired();
    await userEvent.click(screen.getByRole("button", { name: /see my result/i }));

    expect(await screen.findByRole("heading", { name: "What's been on your plate" })).toBeInTheDocument();
    expect(screen.getByText("Input should be less than or equal to 5")).toBeInTheDocument();
  });
});

describe("history", () => {
  const items = [item("a1", "High", "2026-11-02T10:00:00Z"), item("a2", "Low", "2026-11-01T10:00:00Z")];

  beforeEach(() => {
    list.mockResolvedValue({ items, page: 0, size: 10, totalElements: 2, totalPages: 1 });
  });

  test("lists snapshots newest first with band chips; page 0, 10 per page", async () => {
    renderAt(<WellnessHistoryPage />, "/wellness-snapshot/history");
    const rows = await screen.findAllByRole("listitem");
    expect(list).toHaveBeenCalledWith(0, 10);
    expect(within(rows[0]).getByText("High")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Low")).toBeInTheDocument();
  });

  test("delete one asks first; Keep it cancels; Delete removes it without a full reload", async () => {
    deleteOne.mockResolvedValue();
    renderAt(<WellnessHistoryPage />, "/wellness-snapshot/history");
    await screen.findAllByRole("listitem");

    const deleteButtons = screen.getAllByRole("button", { name: /^delete:/i });
    await userEvent.click(deleteButtons[0]);
    expect(await screen.findByText("Delete this snapshot?")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Keep it" }));
    expect(deleteOne).not.toHaveBeenCalled();

    list.mockResolvedValue({ items: [items[1]], page: 0, size: 10, totalElements: 1, totalPages: 1 });
    await userEvent.click(screen.getAllByRole("button", { name: /^delete:/i })[0]);
    await userEvent.click(await screen.findByRole("button", { name: "Delete" }));
    await waitFor(() => expect(deleteOne).toHaveBeenCalledWith("a1"));
    await waitFor(() => expect(screen.queryByText("High")).not.toBeInTheDocument());
    expect(screen.queryByText("Delete this snapshot?")).not.toBeInTheDocument();
    expect(screen.getByText("Low")).toBeInTheDocument();
  });

  test("delete all needs DELETE typed first", async () => {
    deleteAll.mockResolvedValue();
    renderAt(<WellnessHistoryPage />, "/wellness-snapshot/history");
    await screen.findAllByRole("listitem");

    await userEvent.click(screen.getByRole("button", { name: "Delete all history" }));
    const dialog = await screen.findByRole("group", { name: "Delete all your snapshots?" });
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    const confirm = within(dialog).getByRole("button", { name: "Delete all" });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/type DELETE/i), "delete");
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    await waitFor(() => expect(deleteAll).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("No snapshots yet")).toBeInTheDocument();
  });
});
