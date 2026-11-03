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
const list = vi.mocked(api.listCheckins);

function show(r: CheckinResponse, onAddOptional = vi.fn()) {
  return render(
    <MemoryRouter>
      <CheckinResults response={r} request={REQUEST} onEdit={() => {}} onStartAgain={() => {}} onAddOptional={onAddOptional} />
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
    expect(screen.getByRole("heading", { name: "Why low confidence?" })).toBeInTheDocument();
    expect(screen.getByText("A few optional answers were skipped.")).toBeInTheDocument();
    expect(screen.getByText("Study / work load")).toBeInTheDocument();
  });

  test("drivers show the value the API returned and a Raises / Eases tag", () => {
    show(response("Moderate"));
    const drivers = screen.getByRole("heading", { name: "What is moving this" }).parentElement!;
    const rows = within(drivers).getAllByRole("listitem");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("You rated it 4 out of 5")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Raises")).toBeInTheDocument();
    expect(within(rows[2]).getByText("About 7-8 h a night")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Eases")).toBeInTheDocument();
  });

  test("skipped optional answers offer Add it now", async () => {
    const onAdd = vi.fn();
    show(response("Low"), onAdd);
    fireEvent.click(screen.getByRole("button", { name: /add it now/i }));
    expect(onAdd).toHaveBeenCalledTimes(1);
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
    expect(screen.getByText("Medium confidence")).toBeInTheDocument();
  });

  test("sleep and movement meters describe the user's values against the two guidelines", () => {
    show(response("Low"));
    expect(
      screen.getByRole("img", {
        name: "Sleep: about 6.5 hours a night. Adults are generally advised to get 7 or more hours.",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("img", {
        name: "Movement: about 315 minutes a week. The WHO suggests adults get at least 150 minutes a week.",
      }),
    ).toBeInTheDocument();
    expect(screen.getByText(/You wake up feeling so-so/)).toBeInTheDocument();
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
    const section = screen.getByRole("heading", { name: "Suggested next steps" }).closest("section")!;
    const links = within(section).getAllByRole("link");
    expect(links.map((l) => l.getAttribute("href"))).toEqual(["/relaxation", "/assessment/pss10"]);
    expect(within(section).queryByText(/SOMETHING_NEW/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Browse verified professionals" })).not.toBeInTheDocument();
  });

  test("Browse verified professionals appears only with FIND_PROFESSIONAL", () => {
    show(response("Moderate", { actions: ["FIND_PROFESSIONAL"] }));
    expect(screen.getByRole("link", { name: "Browse verified professionals" })).toHaveAttribute("href", "/professionals");
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
    expect(screen.queryByText("Saved to your history")).not.toBeInTheDocument();
  });

  test("signed-in users see Saved to your history, linking to the history", () => {
    show(response("Low", { saved: true }));
    expect(screen.getByRole("link", { name: "Saved to your history" })).toHaveAttribute("href", "/wellness-snapshot/history");
    expect(screen.queryByText(/sign in to save/i)).not.toBeInTheDocument();
  });
});

describe("what-if", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  test("debounced re-runs use only the no-save preview, never the saving call", async () => {
    preview.mockResolvedValue(response("Low"));
    show(response("High", { saved: true }));
    const panel = screen.getByRole("heading", { name: "What if things shifted a little?" }).closest("section")!;
    expect(within(panel).getByText("Illustrative / not saved")).toBeInTheDocument();
    const [sleep, stress, movement] = within(panel).getAllByRole("slider");
    expect(sleep).toHaveAccessibleName("Sleep");
    expect(movement).toHaveAccessibleName("Movement");
    expect(within(panel).getByText("High")).toBeInTheDocument();

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
    expect(within(panel).getByText("Lower than your snapshot.")).toBeInTheDocument();
  });
});

describe("dashboard card", () => {
  test("no snapshots yet shows a friendly prompt", async () => {
    list.mockResolvedValue({ items: [], page: 0, size: 6, totalElements: 0, totalPages: 0 });
    render(
      <MemoryRouter>
        <WellnessDashboardCard />
      </MemoryRouter>,
    );
    expect(await screen.findByText("No Wellness Snapshots yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Take your first snapshot" })).toHaveAttribute("href", "/wellness-snapshot");
    expect(list).toHaveBeenCalledWith(0, 6);
  });

  test("the latest snapshot shows its band, sleep flag, a trend and the two buttons", async () => {
    list.mockResolvedValue({
      items: [item("c", "Moderate", "2026-11-02T10:00:00Z"), item("b", "High", "2026-10-26T10:00:00Z"), item("a", "Low", "2026-10-19T10:00:00Z")],
      page: 0,
      size: 6,
      totalElements: 3,
      totalPages: 1,
    });
    render(
      <MemoryRouter>
        <WellnessDashboardCard />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Sleep worth a look")).toBeInTheDocument();
    // The latest band sits next to "Latest" (the trend's guide lines also name the bands).
    expect(screen.getByText("Latest").parentElement).toHaveTextContent(/Latest\s*Moderate/);
    expect(screen.getByRole("img", { name: "Your last 3 snapshots, oldest to newest: Low, High, Moderate." })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See full history" })).toHaveAttribute("href", "/wellness-snapshot/history");
    expect(screen.getByRole("link", { name: "Check in again" })).toHaveAttribute("href", "/wellness-snapshot");
  });
});
