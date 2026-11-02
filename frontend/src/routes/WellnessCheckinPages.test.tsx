// Component tests for the Wellness Snapshot pages (Vitest + React Testing Library). The API module is mocked;
// nothing reaches a server.
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

const next = () => userEvent.click(screen.getByRole("button", { name: /^next/i }));

/** Fills all four steps as a working person; returns before submitting. */
async function fillRequired(segment: "Studying" | "Working" | "Both" = "Working") {
  await userEvent.click(screen.getByLabelText(segment));
  await userEvent.click(screen.getByRole("button", { name: "One year older" }));
  await userEvent.click(screen.getByLabelText("Some (30–60 min)"));
  await next();
  await userEvent.click(screen.getByLabelText("6–7 h"));
  // 6 is where an untouched slider sits; picking the middle is recorded on key-up (or pointer-up).
  const rested = screen.getByRole("slider", { name: /rested/i });
  expect(rested).toHaveAttribute("aria-valuetext", "Not answered yet");
  fireEvent.keyUp(rested, { key: "Enter" });
  expect(rested).toHaveAttribute("aria-valuetext", "6 out of 10");
  await next();
  await userEvent.click(screen.getByLabelText("Heavy"));
  await userEvent.click(screen.getByLabelText("Not great"));
  await userEvent.click(screen.getByLabelText("Sometimes"));
  await next();
  fireEvent.change(screen.getByRole("slider", { name: /stressed/i }), { target: { value: "7" } });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("questionnaire", () => {
  test("required answers are enforced before moving on", async () => {
    renderAt(<WellnessCheckinPage />);
    await next();
    expect(screen.getAllByText("Please choose an answer to continue.")).toHaveLength(3);
    expect(screen.getByRole("heading", { name: "Your day" })).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  test("wording adapts to studying vs working", async () => {
    renderAt(<WellnessCheckinPage />);
    await userEvent.click(screen.getByLabelText("Studying"));
    await userEvent.click(screen.getByRole("button", { name: "One year older" }));
    await userEvent.click(screen.getByLabelText("A bit (15–30 min)"));
    await next();
    await userEvent.click(screen.getByLabelText("7–8 h"));
    fireEvent.change(screen.getByRole("slider", { name: /rested/i }), { target: { value: "8" } });
    await next();
    expect(screen.getByText("How heavy has your study load felt lately?")).toBeInTheDocument();
    expect(screen.getByText(/fees, rent, loans/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /back/i }));
    await userEvent.click(screen.getByRole("button", { name: /back/i }));
    await userEvent.click(screen.getByLabelText("Working"));
    await next();
    await next();
    expect(screen.getByText("How heavy has your workload felt lately?")).toBeInTheDocument();
    expect(screen.getByText(/EMIs/)).toBeInTheDocument();
  });

  test("the payload has numbers from the chips; skipped optionals are omitted; no BMI is asked", async () => {
    submit.mockResolvedValue(response("Moderate"));
    renderAt(<WellnessCheckinPage />);
    await fillRequired("Working");
    await userEvent.click(screen.getByRole("button", { name: /sharpen your result/i }));
    expect(screen.queryByText(/bmi|weight/i)).not.toBeInTheDocument();
    await userEvent.click(screen.getByLabelText("60–70 bpm"));
    await userEvent.click(screen.getByLabelText("Prefer not to say"));
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
      heartRate: 65,
    });
    expect(await screen.findByRole("heading", { name: "Your Wellness Snapshot" })).toBeInTheDocument();
  });

  test("a 503 keeps the answers and offers a retry", async () => {
    submit.mockRejectedValueOnce(new WellnessApiError(503, "x", "WELLNESS_UNAVAILABLE"));
    submit.mockResolvedValueOnce(response("Low"));
    renderAt(<WellnessCheckinPage />);
    await fillRequired();
    await userEvent.click(screen.getByRole("button", { name: /see my result/i }));

    expect(await screen.findByText(/resting for a moment/i)).toBeInTheDocument();
    expect(screen.getByRole("slider", { name: /stressed/i })).toHaveValue("7");
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2));
    expect(submit.mock.calls[1][0]).toEqual(submit.mock.calls[0][0]);
    expect(await screen.findByRole("heading", { name: "Your Wellness Snapshot" })).toBeInTheDocument();
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

    expect(await screen.findByRole("heading", { name: "Your load" })).toBeInTheDocument();
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
    expect(screen.getByText("Low")).toBeInTheDocument();
  });

  test("delete all needs DELETE typed first", async () => {
    deleteAll.mockResolvedValue();
    renderAt(<WellnessHistoryPage />, "/wellness-snapshot/history");
    await screen.findAllByRole("listitem");

    await userEvent.click(screen.getByRole("button", { name: "Delete all" }));
    const dialog = await screen.findByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Delete all" });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/type DELETE/i), "delete");
    expect(confirm).toBeEnabled();
    await userEvent.click(confirm);
    await waitFor(() => expect(deleteAll).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("No snapshots yet")).toBeInTheDocument();
  });
});
