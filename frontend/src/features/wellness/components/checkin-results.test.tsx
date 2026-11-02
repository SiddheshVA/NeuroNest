// Component tests for the results screen, the what-if panel and the dashboard card (Vitest + RTL).
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";

import * as api from "../api.ts";
import { DISCLAIMER, REQUEST, item, response, sleepInRange, sleepOutOfRange } from "../test-fixtures.ts";
import type { CheckinResponse } from "../types.ts";
import { CheckinResults } from "./checkin-results.tsx";
import { WellnessDashboardCard } from "./dashboard-card.tsx";

vi.mock("../api.ts", () => ({
  submitCheckin: vi.fn(),
  previewCheckin: vi.fn(),
  listCheckins: vi.fn(),
  latestCheckin: vi.fn(),
  deleteCheckin: vi.fn(),
  deleteAllCheckins: vi.fn(),
}));

const preview = vi.mocked(api.previewCheckin);
const submit = vi.mocked(api.submitCheckin);
const latest = vi.mocked(api.latestCheckin);

function show(r: CheckinResponse) {
  return render(
    <MemoryRouter>
      <CheckinResults response={r} request={REQUEST} onEdit={() => {}} onStartAgain={() => {}} />
    </MemoryRouter>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe("mood card", () => {
  test.each(["Low", "Moderate", "High"] as const)("renders the %s band as text and on the scale", (band) => {
    show(response(band));
    expect(screen.getByRole("img", { name: `Mood strain: ${band}` })).toBeInTheDocument();
    expect(screen.getAllByText(band).length).toBeGreaterThan(0);
    expect(screen.getByText(DISCLAIMER)).toBeInTheDocument();
    expect(screen.getByText("Low confidence")).toBeInTheDocument();
    expect(screen.getByText("A few optional answers were skipped.")).toBeInTheDocument();
    expect(screen.getByText("Study / work load")).toBeInTheDocument();
  });

  test("the existing crisis resources appear only with a High band", () => {
    const { unmount } = show(response("High", { actions: ["ASSESSMENT_PHQ9", "FIND_PROFESSIONAL"] }));
    expect(screen.getByText(/if things feel like too much right now/i)).toBeInTheDocument();
    unmount();
    show(response("Moderate"));
    expect(screen.queryByText(/if things feel like too much right now/i)).not.toBeInTheDocument();
  });
});

describe("sleep card", () => {
  test("in range: the display text, and the doctor line when SEE_DOCTOR is suggested", () => {
    show(response("Low", { sleep: sleepInRange("insomnia_like"), actions: ["KEEP_TRACKING", "RELAXATION_SLEEP", "SEE_DOCTOR"] }));
    expect(screen.getByText(/insomnia-like/)).toBeInTheDocument();
    expect(screen.getByText(/worth mentioning to a doctor/i)).toBeInTheDocument();
  });

  test("out of range: guidance only, shown as a normal result (no error, no doctor line)", () => {
    show(response("Low", { sleep: sleepOutOfRange(), actions: ["KEEP_TRACKING"] }));
    expect(screen.getByText(/what general sleep and activity guidelines say/i)).toBeInTheDocument();
    expect(screen.getByText(/built from adults aged 27-59/)).toBeInTheDocument();
    expect(screen.getByText(/you're sleeping about 6.5 hours/i)).toBeInTheDocument();
    expect(screen.queryByText(/worth mentioning to a doctor/i)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("suggested actions", () => {
  test("known codes link to existing routes; unknown codes are ignored", () => {
    show(response("Moderate", { actions: ["RELAXATION", "SOMETHING_NEW", "ASSESSMENT_PSS10"] }));
    const section = screen.getByRole("heading", { name: "Next steps you might like" }).closest("section")!;
    const links = within(section).getAllByRole("link");
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/relaxation", "/assessment/pss10"]);
    expect(within(section).queryByText(/SOMETHING_NEW/)).not.toBeInTheDocument();
  });
});

describe("save state", () => {
  test("guests see the sign-in call to action and nothing about saving", () => {
    show(response("Low", { saved: false }));
    expect(screen.getByText("Sign in to save your snapshots and see your progress")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /sign in or create an account/i })).toHaveAttribute(
      "href",
      "/login?redirect_url=%2Fwellness-snapshot",
    );
    expect(screen.queryByText("Saved to your snapshots")).not.toBeInTheDocument();
  });

  test("signed-in users see Saved with a link to history", () => {
    show(response("Low", { saved: true }));
    expect(screen.getByText("Saved to your snapshots")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See your history" })).toHaveAttribute("href", "/wellness-snapshot/history");
    expect(screen.queryByText(/sign in to save/i)).not.toBeInTheDocument();
  });
});

describe("what-if", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  test("debounced re-runs use only the no-save preview, never the saving call", async () => {
    preview.mockResolvedValue(response("Low"));
    show(response("High", { saved: true }));
    const panel = screen.getByRole("heading", { name: "What-if" }).closest("section")!;
    expect(within(panel).getByText("Not saved")).toBeInTheDocument();
    const [sleep, stress] = within(panel).getAllByRole("slider");

    fireEvent.change(sleep, { target: { value: "7.5" } });
    fireEvent.change(stress, { target: { value: "4" } });
    fireEvent.change(stress, { target: { value: "3" } });
    await act(async () => {
      vi.advanceTimersByTime(399);
    });
    expect(preview).not.toHaveBeenCalled();
    await act(async () => {
      vi.advanceTimersByTime(1);
    });

    expect(preview).toHaveBeenCalledTimes(1);
    expect(preview.mock.calls[0][0]).toEqual({ ...REQUEST, sleepDuration: 7.5, stressLevel: 3 });
    expect(submit).not.toHaveBeenCalled();
    // The preview promise settled inside the act() above (fake timers: no polling queries here).
    expect(within(panel).getByText("With these changes")).toBeInTheDocument();
    expect(within(panel).getByText("Low")).toBeInTheDocument();
  });
});

describe("dashboard card", () => {
  test("no snapshots yet (204) shows a friendly prompt", async () => {
    latest.mockResolvedValue(null);
    render(
      <MemoryRouter>
        <WellnessDashboardCard />
      </MemoryRouter>,
    );
    expect(await screen.findByText("No Wellness Snapshots yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Take a snapshot" })).toHaveAttribute("href", "/wellness-snapshot");
  });

  test("the latest snapshot shows its band, sleep flag and a history link", async () => {
    latest.mockResolvedValue(item("x", "Moderate", "2026-11-02T10:00:00Z"));
    render(
      <MemoryRouter>
        <WellnessDashboardCard />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Moderate")).toBeInTheDocument();
    expect(screen.getByText("Sleep worth a look")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See history" })).toHaveAttribute("href", "/wellness-snapshot/history");
  });
});
