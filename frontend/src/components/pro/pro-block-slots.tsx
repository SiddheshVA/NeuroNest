import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@clerk/clerk-react";
import { Ban, Loader2 } from "lucide-react";

import { ProSnackbar } from "@/components/pro/pro-feedback";
import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import { DisabledAccountNotice, ErrorNotice, LoadingBlock } from "@/components/pro/pro-ui";
import { formatIstDay, formatIstTime } from "@/lib/ist-time";
import { ProApiError, useProApi } from "@/lib/pro-client";
import type { ProBlockSlot, ProBlockWeek } from "@/lib/pro-home";
import { cn } from "@/lib/utils";

const EXPLANATION =
  "Block any of your free times in the next 7 days, for an emergency, a meeting or an event. Clients cannot book a blocked time. Tap a blocked time to unblock it. All times are Indian Standard Time.";

type WeekState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; week: ProBlockWeek };

/** "Thu, 29 Oct, 7:45 pm to 8:45 pm IST": the session itself, never the break after it. */
function slotText(date: string, slot: ProBlockSlot) {
  return `${formatIstDay(date)}, ${formatIstTime(slot.start)} to ${formatIstTime(slot.end)} IST`;
}

/** /pro/block-slots: the next 7 days of the professional's slots, each one a chip that blocks or unblocks it. */
export function ProBlockSlotsPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const [state, setState] = useState<WeekState>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);
  // The slot being changed (its start), so only that chip shows it is busy.
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);
  const closeSnackbar = useCallback(() => setSnackbar(null), []);

  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .blockedSlots()
      .then((week) => !cancelled && setState({ status: "ready", week }))
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({ status: "error", message: e instanceof Error && e.message ? e.message : "Your times could not be loaded. Please try again." });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, reloadCount]);

  const reload = () => setReloadCount((n) => n + 1);

  const toggle = async (date: string, slot: ProBlockSlot) => {
    if (busy || slot.status === "BOOKED") return;
    setBusy(slot.start);
    setActionError(null);
    try {
      if (slot.status === "FREE") {
        setState({ status: "ready", week: await api.blockSlot(slot.start) });
        setSnackbar(`Blocked ${slotText(date, slot)}. Clients cannot book this time now.`);
      } else if (slot.blockId) {
        setState({ status: "ready", week: await api.unblockSlot(slot.blockId) });
        setSnackbar(`Unblocked ${slotText(date, slot)}. Clients can book this time again.`);
      }
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      // 400, 404 and 409 messages (for example a time a client just booked) are shown as the server wrote them.
      setActionError(e instanceof Error && e.message ? e.message : "This time could not be changed. Please try again.");
      reload();
    } finally {
      setBusy(null);
    }
  };

  if (disabled) {
    return (
      <ProPortalFrame title="Block slots">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  return (
    <ProPortalFrame title="Block slots" intro={EXPLANATION}>
      <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm text-muted-foreground" aria-label="What the colours mean">
        <li className="flex items-center gap-2">
          <span className="size-3 rounded-full bg-card ring-1 ring-border" aria-hidden="true" /> Free: tap to block
        </li>
        <li className="flex items-center gap-2">
          <span className="size-3 rounded-full bg-foreground" aria-hidden="true" /> Blocked: tap to unblock
        </li>
        <li className="flex items-center gap-2">
          <span className="size-3 rounded-full bg-muted ring-1 ring-border" aria-hidden="true" /> Booked by a client
        </li>
      </ul>

      {actionError ? <ErrorNotice message={actionError} /> : null}

      {state.status === "loading" ? (
        <LoadingBlock label="Loading your times for the next 7 days." />
      ) : state.status === "error" ? (
        <ErrorNotice message={state.message} onRetry={reload} />
      ) : (
        <>
          <ol className="flex flex-col gap-3">
            {state.week.days.map((day) => (
              <li key={day.date} className="flex flex-col gap-3 rounded-2xl bg-card p-5 ring-1 ring-foreground/10">
                <h2 className="text-sm font-semibold text-foreground">{formatIstDay(day.date)}</h2>
                {day.slots.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No times left on this day.</p>
                ) : (
                  <div className="flex flex-wrap gap-2" role="group" aria-label={`Times on ${formatIstDay(day.date)}`}>
                    {day.slots.map((slot) => (
                      <SlotChip
                        key={slot.start}
                        slot={slot}
                        busy={busy === slot.start}
                        disabled={busy !== null}
                        onToggle={() => void toggle(day.date, slot)}
                      />
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ol>
          <p className="text-sm text-muted-foreground">
            Booked times have a client&apos;s booking (paid, or waiting for payment) and cannot be blocked. To change a
            booked session, please contact NeuroNest support. Each time covers one {state.week.sessionMinutes}-minute
            session and the {state.week.breakMinutes}-minute break after it.
          </p>
        </>
      )}

      {snackbar ? <ProSnackbar message={snackbar} onClose={closeSnackbar} /> : null}
    </ProPortalFrame>
  );
}

function SlotChip({
  slot,
  busy,
  disabled,
  onToggle,
}: {
  slot: ProBlockSlot;
  busy: boolean;
  disabled: boolean;
  onToggle: () => void;
}) {
  const time = formatIstTime(slot.start);
  if (slot.status === "BOOKED") {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1.5 text-sm tabular-nums text-muted-foreground ring-1 ring-border">
        {time}
        <span className="text-xs font-medium">Booked</span>
      </span>
    );
  }
  const blocked = slot.status === "BLOCKED";
  return (
    <button
      type="button"
      aria-pressed={blocked}
      aria-label={blocked ? `${time}, blocked. Unblock this time.` : `${time}, free. Block this time.`}
      disabled={disabled}
      onClick={onToggle}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm tabular-nums ring-1 transition-colors disabled:cursor-not-allowed disabled:opacity-60",
        blocked
          ? "bg-foreground text-background ring-foreground hover:bg-foreground/85"
          : "bg-card text-foreground ring-border hover:bg-muted",
      )}
    >
      {busy ? <Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> : blocked ? <Ban className="size-3.5" aria-hidden="true" /> : null}
      {time}
      {blocked ? <span className="text-xs font-medium">Blocked</span> : null}
    </button>
  );
}
