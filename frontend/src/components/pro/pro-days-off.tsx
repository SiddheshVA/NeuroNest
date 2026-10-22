import { useCallback, useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useAuth } from "@clerk/clerk-react";
import { CalendarOff, Loader2 } from "lucide-react";

import { ProSnackbar } from "@/components/pro/pro-feedback";
import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import {
  ConfirmDialog,
  DisabledAccountNotice,
  ErrorNotice,
  LoadingBlock,
  primaryButtonClass,
  secondaryButtonClass,
  TextField,
} from "@/components/pro/pro-ui";
import { addDays, formatIstDate, todayInIndia } from "@/lib/ist-time";
import { ProApiError, useProApi } from "@/lib/pro-client";
import {
  daysBetween,
  MAX_DAY_OFF_DAYS_AHEAD,
  MAX_DAY_OFF_RANGE_DAYS,
  MAX_DAY_OFF_REASON,
  type ProDayOff,
  type ProDayOffCreated,
} from "@/lib/pro-home";
import { cn } from "@/lib/utils";

const EXPLANATION =
  "Mark the dates when you are away. People cannot book new appointments on these dates. Appointments you already confirmed are not cancelled automatically.";

type ListState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; entries: ProDayOff[] };

/** "14 Oct 2026" for a YYYY-MM-DD date (noon UTC is the same date in India). */
function formatDay(isoDate: string) {
  return formatIstDate(`${isoDate}T12:00:00Z`);
}

function rangeText(entry: { dateFrom: string; dateTo: string }) {
  return entry.dateFrom === entry.dateTo
    ? formatDay(entry.dateFrom)
    : `${formatDay(entry.dateFrom)} to ${formatDay(entry.dateTo)}`;
}

function plural(count: number, one: string, many: string) {
  return count === 1 ? one : many;
}

/** /pro/days-off: dates the professional is away. No new slots are offered on them. */
export function ProDaysOffPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const [today] = useState(todayInIndia);
  const lastAllowed = addDays(today, MAX_DAY_OFF_DAYS_AHEAD);

  const [list, setList] = useState<ListState>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);

  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [added, setAdded] = useState<ProDayOffCreated | null>(null);

  const [toRemove, setToRemove] = useState<ProDayOff | null>(null);
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);

  const [snackbar, setSnackbar] = useState<string | null>(null);
  const closeSnackbar = useCallback(() => setSnackbar(null), []);

  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .daysOff()
      .then((entries) => !cancelled && setList({ status: "ready", entries }))
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setList({ status: "error", message: e instanceof Error && e.message ? e.message : "Your days off could not be loaded. Please try again." });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, reloadCount]);

  const reload = () => setReloadCount((n) => n + 1);

  // The same rules as the server: from today, the last day not before the first, at most a year ahead,
  // and at most 60 days in one entry.
  const fromError = dateFrom && dateFrom < today ? "The first day off must be today or later." : null;
  const toError = !dateTo
    ? null
    : dateFrom && dateTo < dateFrom
      ? "The last day off must be on or after the first day off."
      : dateTo > lastAllowed
        ? "The last day off must be within the next year."
        : dateFrom && daysBetween(dateFrom, dateTo) + 1 > MAX_DAY_OFF_RANGE_DAYS
          ? `One entry can cover at most ${MAX_DAY_OFF_RANGE_DAYS} days. Add longer breaks as more than one entry.`
          : null;
  const valid = Boolean(dateFrom && dateTo) && !fromError && !toError && reason.trim().length <= MAX_DAY_OFF_REASON;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!valid || saving) return;
    setSaving(true);
    setFormError(null);
    setAdded(null);
    try {
      const created = await api.addDayOff({ dateFrom, dateTo, ...(reason.trim() ? { reason: reason.trim() } : {}) });
      setDateFrom("");
      setDateTo("");
      setReason("");
      setAdded(created);
      setSnackbar("Days off added.");
      reload();
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      // 400 and 409 messages (for example overlapping dates) are shown as the server wrote them.
      setFormError(e instanceof Error && e.message ? e.message : "Your days off could not be saved. Please try again.");
    } finally {
      setSaving(false);
    }
  };

  const confirmRemove = async () => {
    if (!toRemove || removing) return;
    setRemoving(true);
    setRemoveError(null);
    try {
      await api.deleteDayOff(toRemove.id);
      setToRemove(null);
      setSnackbar("Days off removed.");
      reload();
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      setToRemove(null);
      setRemoveError(e instanceof Error && e.message ? e.message : "These days off could not be removed. Please try again.");
      reload();
    } finally {
      setRemoving(false);
    }
  };

  if (disabled) {
    return (
      <ProPortalFrame title="Days off">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  return (
    <ProPortalFrame title="Days off" intro={EXPLANATION}>
      <form onSubmit={submit} noValidate className="flex flex-col gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10 sm:p-6">
        <h2 className="font-heading text-lg font-semibold text-foreground">Add days off</h2>
        {formError ? <ErrorNotice message={formError} /> : null}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            label="First day off"
            type="date"
            required
            value={dateFrom}
            min={today}
            max={lastAllowed}
            onChange={setDateFrom}
            error={fromError ?? undefined}
            disabled={saving}
          />
          <TextField
            label="Last day off"
            type="date"
            required
            value={dateTo}
            min={dateFrom && dateFrom >= today ? dateFrom : today}
            max={lastAllowed}
            onChange={setDateTo}
            error={toError ?? undefined}
            helper={`Use the same date for a single day. One entry can cover at most ${MAX_DAY_OFF_RANGE_DAYS} days.`}
            disabled={saving}
          />
        </div>
        <div className="flex flex-col gap-1">
          <TextField
            label="Reason (only you can see this)"
            placeholder="Holiday"
            value={reason}
            onChange={(value) => setReason(value.slice(0, MAX_DAY_OFF_REASON))}
            disabled={saving}
          />
          <p className="text-xs text-muted-foreground" aria-live="polite">
            {reason.length} of {MAX_DAY_OFF_REASON} characters used.
          </p>
        </div>
        <button type="submit" className={cn(primaryButtonClass, "w-full sm:w-fit")} disabled={!valid || saving}>
          {saving ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <CalendarOff className="size-4" aria-hidden="true" />}
          {saving ? "Adding your days off" : "Add days off"}
        </button>
      </form>

      {added && (added.affectedConfirmed > 0 || added.affectedPending > 0) ? (
        <div
          role="alert"
          className="flex flex-col gap-2 rounded-2xl border border-amber-300 bg-amber-50 px-4 py-4 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100"
        >
          {added.affectedConfirmed > 0 ? (
            <p className="font-medium">
              You have {added.affectedConfirmed} confirmed {plural(added.affectedConfirmed, "appointment", "appointments")} on
              these dates. They were not cancelled. Please contact the people or cancel from the Appointments page.{" "}
              <Link href="/pro/appointments" className="underline underline-offset-4">
                Go to your appointments
              </Link>
            </p>
          ) : null}
          {added.affectedPending > 0 ? (
            <p className="font-medium">
              {added.affectedPending} {plural(added.affectedPending, "request is", "requests are")} still waiting for your
              reply for these dates. You can decline them.{" "}
              <Link href="/pro/requests" className="underline underline-offset-4">
                Go to your requests
              </Link>
            </p>
          ) : null}
        </div>
      ) : null}

      <section className="flex flex-col gap-3">
        <h2 className="font-heading text-lg font-semibold text-foreground">Your days off</h2>
        {removeError ? <ErrorNotice message={removeError} /> : null}
        {list.status === "loading" ? (
          <LoadingBlock label="Loading your days off." />
        ) : list.status === "error" ? (
          <ErrorNotice message={list.message} onRetry={reload} />
        ) : list.entries.length === 0 ? (
          <p className="rounded-2xl bg-card p-6 text-sm text-foreground ring-1 ring-foreground/10">You have not marked any days off.</p>
        ) : (
          <ul className="flex flex-col gap-3">
            {list.entries.map((entry) => (
              <li
                key={entry.id}
                className="flex flex-col gap-3 rounded-2xl bg-card p-5 ring-1 ring-foreground/10 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="flex flex-col gap-0.5">
                  <span className="text-sm font-semibold text-foreground">{rangeText(entry)}</span>
                  <span className="text-sm text-muted-foreground">
                    {entry.reason ? `Reason: ${entry.reason}` : "No reason was added."}
                  </span>
                </div>
                <button
                  type="button"
                  className={cn(secondaryButtonClass, "w-full sm:w-fit")}
                  onClick={() => {
                    setRemoveError(null);
                    setToRemove(entry);
                  }}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <ConfirmDialog
        open={toRemove !== null}
        title="Remove these days off?"
        description={
          toRemove
            ? `People will be able to book you on ${rangeText(toRemove)} again, within your weekly hours.`
            : "People will be able to book you on these dates again."
        }
        confirmLabel="Remove days off"
        cancelLabel="Keep them"
        busy={removing}
        onConfirm={() => void confirmRemove()}
        onOpenChange={(open) => {
          if (!open && !removing) setToRemove(null);
        }}
      />

      {snackbar ? <ProSnackbar message={snackbar} onClose={closeSnackbar} /> : null}
    </ProPortalFrame>
  );
}
