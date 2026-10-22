import { useCallback, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { CheckCircle2, ChevronRight, Copy, Inbox, X } from "lucide-react";

import { BookingStatusChip } from "@/components/bookings/booking-status-chip";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { ProPortalNav } from "@/components/pro/pro-portal-nav";
import { ReplyDialog, type ReplyKind } from "@/components/pro/pro-request-dialogs";
import {
  DisabledAccountNotice,
  ErrorNotice,
  LoadingBlock,
  primaryButtonClass,
  secondaryButtonClass,
} from "@/components/pro/pro-ui";
import { displayStatus, METHOD_LABELS, type BookingStatus } from "@/lib/bookings-client";
import { formatIstDate, formatIstDateTime, formatRelative } from "@/lib/ist-time";
import { ProApiError, useProApi } from "@/lib/pro-client";
import { PRO_STATUS_FILTERS, PRO_STATUS_LABELS, type ProBooking, type ProBookingPage } from "@/lib/pro-bookings";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;
const VALID_FILTERS = new Set<string>(PRO_STATUS_FILTERS.map((f) => f.value));

const SUCCESS_MESSAGES: Record<ReplyKind, string> = {
  confirm: "Appointment confirmed.",
  propose: "New time suggested.",
  decline: "Request declined.",
};

// ---- Shared pieces ----

function Frame({ title, intro, back, children }: { title: string; intro?: string; back: ReactNode; children: ReactNode }) {
  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-4xl flex-col gap-6">
        <ProPortalNav />
        <header className="flex flex-col gap-2">
          {back}
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">{title}</h1>
          {intro ? <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">{intro}</p> : null}
        </header>
        {children}
      </Container>
    </section>
  );
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words text-foreground">{value}</dd>
    </div>
  );
}

function modeLabel(b: ProBooking): string | null {
  if (b.preferredMode === "ONLINE") return "Online";
  if (b.preferredMode === "IN_PERSON") return "In person";
  return null;
}

function preferredMethodLabel(b: ProBooking): string | null {
  if (b.preferredMode === "IN_PERSON") return "In person";
  return b.preferredMethod ? METHOD_LABELS[b.preferredMethod] : null;
}

/** A short message at the bottom of the screen that goes away by itself. */
function Snackbar({ message, onClose }: { message: string; onClose: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onClose, 5000);
    return () => window.clearTimeout(timer);
  }, [message, onClose]);
  return (
    <div className="fixed inset-x-4 bottom-4 z-50 flex justify-center">
      <div
        role="status"
        className="flex items-center gap-3 rounded-xl bg-foreground px-4 py-3 text-sm text-background shadow-lg"
      >
        <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
        <span>{message}</span>
        <button type="button" onClick={onClose} className="rounded-full p-0.5 hover:bg-background/20" aria-label="Close message">
          <X className="size-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

// ---- The list ----

type ListState =
  | { key: string; status: "error"; message: string }
  | { key: string; status: "ready"; data: ProBookingPage };

function RequestRow({ booking: b, now }: { booking: ProBooking; now: number }) {
  const status = displayStatus(b, now);
  const mode = modeLabel(b);
  const method = preferredMethodLabel(b);
  return (
    <li>
      <Link
        href={`/pro/requests/${b.id}`}
        className="flex items-center gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10 transition-colors hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="font-heading text-lg font-semibold text-foreground">{b.contactName}</span>
            <BookingStatusChip status={status} label={PRO_STATUS_LABELS[status]} />
          </div>
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {b.preferredDatetime ? (
              <Detail
                label={b.status === "CONFIRMED" ? "Appointment time" : "Requested time"}
                value={formatIstDateTime(b.preferredDatetime)}
              />
            ) : null}
            {b.alternateDatetime && b.status !== "CONFIRMED" ? (
              <Detail label="Second choice" value={formatIstDateTime(b.alternateDatetime)} />
            ) : null}
            {mode ? <Detail label="Mode" value={mode} /> : null}
            {method && b.preferredMode !== "IN_PERSON" ? <Detail label="Prefers to meet by" value={method} /> : null}
          </dl>
          <p className="text-xs text-muted-foreground">
            Sent {formatRelative(b.createdAt, now)}.
            {status === "PENDING" && b.expiresAt ? ` Expires ${formatRelative(b.expiresAt, now)}.` : ""}
          </p>
        </div>
        <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      </Link>
    </li>
  );
}

/** /pro/requests: the signed-in professional's booking requests, newest first. */
export function ProRequestsPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const now = useNow();
  const [params, setParams] = useSearchParams();
  const rawStatus = params.get("status") ?? "";
  const status = VALID_FILTERS.has(rawStatus) ? rawStatus : "";
  const page = Math.max(0, Number.parseInt(params.get("page") ?? "0", 10) || 0);
  const [reloadCount, setReloadCount] = useState(0);
  const [state, setState] = useState<ListState | null>(null);

  // Each result remembers what it was loaded for, so a change of filter or page shows loading until
  // the matching result arrives.
  const key = `${status}|${page}|${reloadCount}`;
  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .bookings(status, page, PAGE_SIZE)
      .then((data) => {
        if (cancelled) return;
        // The last page emptied in the meantime: show the one before it.
        if (data.items.length === 0 && page > 0) {
          setParams(
            (prev) => {
              const next = new URLSearchParams(prev);
              next.set("page", String(page - 1));
              return next;
            },
            { replace: true },
          );
        } else {
          setState({ key, status: "ready", data });
        }
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({
          key,
          status: "error",
          message: e instanceof Error && e.message ? e.message : "Your appointment requests could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, status, page, key, setParams]);

  const go = (nextStatus: string, nextPage: number) => {
    const next = new URLSearchParams();
    if (nextStatus) next.set("status", nextStatus);
    if (nextPage > 0) next.set("page", String(nextPage));
    setParams(next);
  };

  const frame = (children: ReactNode) => (
    <Frame
      title="Appointment requests"
      intro="Reply to people who asked for an appointment with you. All times are in Indian Standard Time."
      back={<BackLink />}
    >
      {children}
    </Frame>
  );

  if (disabled) return frame(<DisabledAccountNotice />);

  const current = state && state.key === key ? state : null;
  return frame(
    <>
      <div className="flex flex-wrap gap-2" role="group" aria-label="Show requests by status">
        {PRO_STATUS_FILTERS.map((filter) => (
          <button
            key={filter.value || "all"}
            type="button"
            aria-pressed={status === filter.value}
            onClick={() => go(filter.value, 0)}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm ring-1 transition-colors",
              status === filter.value ? "bg-primary text-primary-foreground ring-primary" : "bg-card text-foreground ring-border hover:bg-muted",
            )}
          >
            {filter.label}
          </button>
        ))}
      </div>

      {!current ? (
        <LoadingBlock label="Loading your appointment requests." />
      ) : current.status === "error" ? (
        <ErrorNotice message={current.message} onRetry={() => setReloadCount((n) => n + 1)} />
      ) : current.data.items.length === 0 ? (
        <div className="flex items-center gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
          <Inbox className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <p className="text-sm text-foreground">
            {status ? "You have no appointment requests with this status." : "You have no appointment requests yet."}
          </p>
        </div>
      ) : (
        <>
          <ul className="flex flex-col gap-3">
            {current.data.items.map((booking) => (
              <RequestRow key={booking.id} booking={booking} now={now} />
            ))}
          </ul>
          {current.data.totalPages > 1 ? (
            <nav className="flex items-center justify-between gap-3" aria-label="Pages">
              <button type="button" className={secondaryButtonClass} disabled={page === 0} onClick={() => go(status, page - 1)}>
                Newer requests
              </button>
              <span className="text-sm text-muted-foreground">
                Page {page + 1} of {current.data.totalPages}
              </span>
              <button
                type="button"
                className={secondaryButtonClass}
                disabled={page + 1 >= current.data.totalPages}
                onClick={() => go(status, page + 1)}
              >
                Older requests
              </button>
            </nav>
          ) : null}
        </>
      )}
    </>,
  );
}

// ---- One request ----

function statusSentence(b: ProBooking, status: BookingStatus): string {
  switch (status) {
    case "PENDING":
      return b.expiresAt ? `Please reply before ${formatIstDateTime(b.expiresAt)}.` : "Please reply to this request.";
    case "ALTERNATE_PROPOSED":
      return "You suggested another time. Waiting for the person to accept or cancel.";
    case "CONFIRMED":
      return "This appointment is confirmed.";
    case "DECLINED":
      return "You declined this request.";
    case "CANCELLED":
      return "The person cancelled this request.";
    case "EXPIRED":
      return "This request expired without a confirmed appointment.";
  }
}

function CopyButton({ value, label, onDone }: { value: string; label: string; onDone: (message: string) => void }) {
  return (
    <button
      type="button"
      className={cn(secondaryButtonClass, "h-8 px-3 text-xs")}
      aria-label={label}
      onClick={() => {
        navigator.clipboard
          .writeText(value)
          .then(() => onDone("Copied."))
          .catch(() => onDone("This could not be copied. Please copy it by hand."));
      }}
    >
      <Copy className="size-3.5" aria-hidden="true" />
      Copy
    </button>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
      <h2 className="font-heading text-lg font-semibold text-foreground">{title}</h2>
      {children}
    </section>
  );
}

function ContactBlock({ booking: b, onCopied }: { booking: ProBooking; onCopied: (message: string) => void }) {
  if (b.contactEmail || b.contactPhone) {
    return (
      <div className="flex flex-col gap-3">
        {b.contactPhone ? (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <dl><Detail label="Phone number" value={b.contactPhone} /></dl>
            <CopyButton value={b.contactPhone} label="Copy the phone number" onDone={onCopied} />
          </div>
        ) : null}
        {b.contactEmail ? (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <dl><Detail label="Email address" value={b.contactEmail} /></dl>
            <CopyButton value={b.contactEmail} label="Copy the email address" onDone={onCopied} />
          </div>
        ) : null}
        <p className="text-xs text-muted-foreground">You can see these details until 7 days after the appointment.</p>
      </div>
    );
  }
  return (
    <p className="text-sm text-foreground">
      {b.status === "CONFIRMED"
        ? "Contact details are no longer shown."
        : "Contact details are shown after you confirm the appointment."}
    </p>
  );
}

function MeetingDetails({ booking: b }: { booking: ProBooking }) {
  if (b.meetingMethod === "VIDEO" && b.meetingLink) {
    return (
      <Detail
        label="Video call link"
        value={
          b.meetingLink.startsWith("https://") ? (
            <a href={b.meetingLink} target="_blank" rel="noopener noreferrer" className="font-medium break-all text-primary underline underline-offset-4">
              {b.meetingLink}
            </a>
          ) : (
            b.meetingLink
          )
        }
      />
    );
  }
  if (b.meetingMethod === "IN_PERSON" && b.meetingAddress) {
    return <Detail label="Clinic address" value={<span className="whitespace-pre-line">{b.meetingAddress}</span>} />;
  }
  return null;
}

type DetailState =
  | { key: string; status: "error"; message: string; notFound: boolean }
  | { key: string; status: "ready"; booking: ProBooking };

/** /pro/requests/:id: one request, with the reply buttons while it is waiting for the professional. */
export function ProRequestPage() {
  const { id = "" } = useParams();
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const now = useNow();
  const [reloadCount, setReloadCount] = useState(0);
  const [state, setState] = useState<DetailState | null>(null);
  const [dialog, setDialog] = useState<ReplyKind | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);
  const closeSnackbar = useCallback(() => setSnackbar(null), []);

  const key = `${id}|${reloadCount}`;
  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .booking(id)
      .then((booking) => {
        if (!cancelled) setState({ key, status: "ready", booking });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState({
          key,
          status: "error",
          notFound: e instanceof ProApiError && e.status === 404,
          message: e instanceof Error && e.message ? e.message : "This request could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, id, key]);

  const reload = () => setReloadCount((n) => n + 1);
  const frame = (children: ReactNode) => (
    <Frame title="Appointment request" back={<BackLink />}>
      {children}
    </Frame>
  );

  if (disabled) return frame(<DisabledAccountNotice />);
  const current = state && state.key === key ? state : null;
  // While reloading after a reply the previous request stays on screen.
  const shown = current ?? (state?.status === "ready" ? state : null);

  if (!shown) return frame(<LoadingBlock label="Loading the appointment request." />);
  if (shown.status === "error") {
    return frame(
      shown.notFound ? (
        <div className="flex flex-col items-start gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
          <p className="text-sm text-foreground">This appointment request was not found.</p>
          <Link href="/pro/requests" className="font-medium text-primary underline underline-offset-4">
            Go to all appointment requests
          </Link>
        </div>
      ) : (
        <ErrorNotice message={shown.message} onRetry={reload} />
      ),
    );
  }

  const b = shown.booking;
  const status = displayStatus(b, now);
  const canReply = status === "PENDING";
  const mode = modeLabel(b);
  const method = preferredMethodLabel(b);
  const assessment = b.sharedAssessment;

  return frame(
    <>
      {conflict ? <ErrorNotice message={conflict} /> : null}

      <section className="flex flex-col gap-4 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-heading text-2xl font-semibold text-foreground">{b.contactName}</h2>
          <BookingStatusChip status={status} label={PRO_STATUS_LABELS[status]} />
        </div>
        <p className="text-sm font-medium text-foreground">{statusSentence(b, status)}</p>
        <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {b.preferredDatetime ? (
            <Detail
              label={b.status === "CONFIRMED" ? "Appointment time" : "Requested time"}
              value={formatIstDateTime(b.preferredDatetime)}
            />
          ) : null}
          {b.alternateDatetime && b.status !== "CONFIRMED" ? (
            <Detail label="Second choice" value={formatIstDateTime(b.alternateDatetime)} />
          ) : null}
          {b.proposedDatetime && b.status !== "CONFIRMED" ? (
            <Detail label="Time you suggested" value={formatIstDateTime(b.proposedDatetime)} />
          ) : null}
          {mode ? <Detail label="Mode" value={mode} /> : null}
          {method && b.preferredMode !== "IN_PERSON" ? <Detail label="Prefers to meet by" value={method} /> : null}
          <Detail label="Sent" value={`${formatIstDateTime(b.createdAt)} (${formatRelative(b.createdAt, now)})`} />
        </dl>

        {canReply ? (
          <div className="flex flex-wrap gap-3 pt-2">
            <button type="button" className={primaryButtonClass} onClick={() => { setConflict(null); setDialog("confirm"); }}>
              Confirm
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => { setConflict(null); setDialog("propose"); }}>
              Suggest another time
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => { setConflict(null); setDialog("decline"); }}>
              Decline
            </button>
          </div>
        ) : null}
      </section>

      <Card title="The person's note">
        <p className="text-sm whitespace-pre-line text-foreground">{b.note?.trim() ? b.note : "No note was added."}</p>
      </Card>

      {assessment ? (
        <Card title="Shared assessment result">
          <p className="text-sm text-foreground">
            The person shared: {assessment.instrumentLabel}, score {assessment.score}, {assessment.severityBand}, taken on{" "}
            {formatIstDate(assessment.completedAt)}.
          </p>
          <p className="text-sm text-muted-foreground">
            This is a screening result, not a diagnosis. The person did not share their individual answers or any AI chat.
          </p>
        </Card>
      ) : null}

      <Card title="Contact details">
        <ContactBlock booking={b} onCopied={setSnackbar} />
      </Card>

      {b.status === "CONFIRMED" ? (
        <Card title="Meeting details">
          <dl className="flex flex-col gap-3">
            {b.meetingMethod ? <Detail label="How you will meet" value={METHOD_LABELS[b.meetingMethod]} /> : null}
            <MeetingDetails booking={b} />
            <Detail
              label="Your note"
              value={b.professionalNote?.trim() ? <span className="whitespace-pre-line">{b.professionalNote}</span> : "You did not add a note."}
            />
          </dl>
          {b.meetingMethod === "PHONE" ? (
            <p className="text-sm text-foreground">You will call the person on the phone number above.</p>
          ) : null}
        </Card>
      ) : b.professionalNote?.trim() ? (
        <Card title="Your note">
          <p className="text-sm whitespace-pre-line text-foreground">{b.professionalNote}</p>
        </Card>
      ) : null}

      {dialog && canReply ? (
        <ReplyDialog
          kind={dialog}
          booking={b}
          api={api}
          onClose={() => setDialog(null)}
          onSuccess={() => {
            setSnackbar(SUCCESS_MESSAGES[dialog]);
            setDialog(null);
            reload();
          }}
          onConflict={(message) => {
            setConflict(message);
            setDialog(null);
            reload();
          }}
        />
      ) : null}

      {snackbar ? <Snackbar message={snackbar} onClose={closeSnackbar} /> : null}
    </>,
  );
}
