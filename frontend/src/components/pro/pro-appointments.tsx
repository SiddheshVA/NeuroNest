import { useCallback, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useSearchParams } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";

import { CopyButton, ProSnackbar } from "@/components/pro/pro-feedback";
import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import { DisabledAccountNotice, ErrorNotice, LoadingBlock, secondaryButtonClass } from "@/components/pro/pro-ui";
import { METHOD_LABELS } from "@/lib/bookings-client";
import { formatMeetingTime, todayInIndia } from "@/lib/ist-time";
import { ProApiError, useProApi } from "@/lib/pro-client";
import type { ProAppointment, ProAppointmentPage } from "@/lib/pro-home";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;

type When = "upcoming" | "past";
type State =
  | { key: string; status: "error"; message: string }
  | { key: string; status: "ready"; data: ProAppointmentPage };

const indiaDate = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" });

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words text-foreground">{value}</dd>
    </div>
  );
}

function AppointmentItem({
  appointment: a,
  today,
  past,
  onCopied,
}: {
  appointment: ProAppointment;
  today: string;
  past: boolean;
  onCopied: (message: string) => void;
}) {
  const isToday = indiaDate.format(new Date(a.scheduledTime)) === today;
  const mode = a.preferredMode === "IN_PERSON" ? "In person" : a.preferredMode === "ONLINE" ? "Online" : null;

  return (
    <li className="flex flex-col gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-heading text-lg font-semibold text-foreground">{a.contactName}</h2>
        {isToday ? (
          <span className="rounded-full bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground">Today</span>
        ) : null}
      </div>

      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Detail label="Date and time" value={formatMeetingTime(a.scheduledTime, a.endDatetime, a.sessionCount)} />
        {mode ? <Detail label="Mode" value={mode} /> : null}
        {a.meetingMethod ? <Detail label="How you meet" value={METHOD_LABELS[a.meetingMethod]} /> : null}
        {a.meetingMethod === "VIDEO" && a.meetingLink ? (
          <Detail
            label="Video call link"
            value={
              a.meetingLink.startsWith("https://") ? (
                <a href={a.meetingLink} target="_blank" rel="noopener noreferrer" className="font-medium break-all text-primary underline underline-offset-4">
                  {a.meetingLink}
                </a>
              ) : (
                <span className="break-all">{a.meetingLink}</span>
              )
            }
          />
        ) : null}
        {a.meetingMethod === "IN_PERSON" && a.meetingAddress ? (
          <Detail label="Address" value={<span className="whitespace-pre-line">{a.meetingAddress}</span>} />
        ) : null}
      </dl>

      {a.meetingMethod === "PHONE" ? (
        <p className="text-sm text-foreground">You call the person on the phone number below.</p>
      ) : null}

      {a.professionalNote?.trim() ? (
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-muted-foreground">Your note</span>
          <p className="text-sm whitespace-pre-line text-foreground">{a.professionalNote}</p>
        </div>
      ) : null}

      <div className="flex flex-col gap-3 rounded-xl bg-muted/50 p-4">
        {a.contactPhone || a.contactEmail ? (
          <>
            {a.contactPhone ? (
              <div className="flex flex-wrap items-center justify-between gap-2">
                <dl>
                  <Detail label="Phone number" value={a.contactPhone} />
                </dl>
                <CopyButton value={a.contactPhone} label="Copy the phone number" onDone={onCopied} />
              </div>
            ) : null}
            {a.contactEmail ? (
              <div className="flex flex-wrap items-center justify-between gap-2">
                <dl>
                  <Detail label="Email address" value={a.contactEmail} />
                </dl>
                <CopyButton value={a.contactEmail} label="Copy the email address" onDone={onCopied} />
              </div>
            ) : null}
            <p className="text-xs text-muted-foreground">You can see these details until 7 days after the appointment.</p>
          </>
        ) : (
          <p className="text-sm text-foreground">
            {past ? "Contact details are no longer shown." : "Contact details are not available for this appointment."}
          </p>
        )}
      </div>

      <Link href={`/pro/requests/${a.id}`} className="w-fit text-sm font-medium text-primary underline-offset-4 hover:underline">
        View request
      </Link>
    </li>
  );
}

/** /pro/appointments: the professional's confirmed appointments, upcoming or past. */
export function ProAppointmentsPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const [params, setParams] = useSearchParams();
  const when: When = params.get("when") === "past" ? "past" : "upcoming";
  const page = Math.max(0, Number.parseInt(params.get("page") ?? "0", 10) || 0);
  const [reloadCount, setReloadCount] = useState(0);
  const [state, setState] = useState<State | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);
  const closeSnackbar = useCallback(() => setSnackbar(null), []);
  const [today] = useState(todayInIndia);

  const key = `${when}|${page}|${reloadCount}`;
  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .appointments(when, page, PAGE_SIZE)
      .then((data) => {
        if (!cancelled) setState({ key, status: "ready", data });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({
          key,
          status: "error",
          message: e instanceof Error && e.message ? e.message : "Your appointments could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, when, page, key]);

  const go = (nextWhen: When, nextPage: number) => {
    const next = new URLSearchParams();
    if (nextWhen === "past") next.set("when", "past");
    if (nextPage > 0) next.set("page", String(nextPage));
    setParams(next);
  };

  if (disabled) {
    return (
      <ProPortalFrame title="Appointments">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  const current = state && state.key === key ? state : null;
  return (
    <ProPortalFrame title="Appointments" intro="Your confirmed appointments. All times are in Indian Standard Time.">
      <div className="flex gap-2" role="group" aria-label="Show appointments">
        {(["upcoming", "past"] as const).map((option) => (
          <button
            key={option}
            type="button"
            aria-pressed={when === option}
            onClick={() => go(option, 0)}
            className={cn(
              "rounded-full px-4 py-1.5 text-sm font-medium ring-1 transition-colors",
              when === option ? "bg-primary text-primary-foreground ring-primary" : "bg-card text-foreground ring-border hover:bg-muted",
            )}
          >
            {option === "upcoming" ? "Upcoming" : "Past"}
          </button>
        ))}
      </div>

      {!current ? (
        <LoadingBlock label="Loading your appointments." />
      ) : current.status === "error" ? (
        <ErrorNotice message={current.message} onRetry={() => setReloadCount((n) => n + 1)} />
      ) : current.data.items.length === 0 ? (
        <p className="rounded-2xl bg-card p-6 text-sm text-foreground ring-1 ring-foreground/10">
          {when === "upcoming" ? "You have no upcoming appointments." : "You have no past appointments."}
        </p>
      ) : (
        <>
          <ul className="flex flex-col gap-3">
            {current.data.items.map((appointment) => (
              <AppointmentItem
                key={appointment.id}
                appointment={appointment}
                today={today}
                past={when === "past"}
                onCopied={setSnackbar}
              />
            ))}
          </ul>
          {current.data.totalPages > 1 ? (
            <nav className="flex flex-wrap items-center justify-between gap-3" aria-label="Pages">
              <button type="button" className={secondaryButtonClass} disabled={page === 0} onClick={() => go(when, page - 1)}>
                {when === "upcoming" ? "Sooner appointments" : "Newer appointments"}
              </button>
              <span className="text-sm text-muted-foreground">
                Page {page + 1} of {current.data.totalPages}
              </span>
              <button
                type="button"
                className={secondaryButtonClass}
                disabled={page + 1 >= current.data.totalPages}
                onClick={() => go(when, page + 1)}
              >
                {when === "upcoming" ? "Later appointments" : "Older appointments"}
              </button>
            </nav>
          ) : null}
        </>
      )}

      {snackbar ? <ProSnackbar message={snackbar} onClose={closeSnackbar} /> : null}
    </ProPortalFrame>
  );
}
