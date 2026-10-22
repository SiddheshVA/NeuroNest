import { useEffect, useState } from "react";
import Link from "next/link";
import { useNavigate } from "react-router-dom";
import { CalendarClock } from "lucide-react";

import { BookingStatusChip } from "@/components/bookings/booking-status-chip";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { ConfirmDialog, ErrorNotice, LoadingBlock, primaryButtonClass, secondaryButtonClass } from "@/components/pro/pro-ui";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import {
  acceptProposal,
  BookingApiError,
  cancelBooking,
  displayStatus,
  getMyBookings,
  requestedMethodLabel,
  STATUS_LABELS,
  type BookingPage,
  type BookingStatus,
  type BookingView,
} from "@/lib/bookings-client";
import { formatIstDate, formatIstDateTime } from "@/lib/ist-time";
import { CREDENTIAL_TYPE_LABELS, type CredentialType } from "@/lib/professionals-client";
import { loginHref } from "@/lib/safe-redirect";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;
const CANCELLABLE: BookingStatus[] = ["PENDING", "ALTERNATE_PROPOSED"];

function FindAnother({ children }: { children: string }) {
  return (
    <p className="text-sm text-foreground">
      {children}{" "}
      <Link href="/professionals" className="font-medium text-primary underline underline-offset-4">
        Browse professionals
      </Link>
    </p>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm text-foreground">{value}</dd>
    </div>
  );
}

// `status` is the status to show (see displayStatus): a stale PENDING or ALTERNATE_PROPOSED is EXPIRED.
function StatusText({ booking: b, status }: { booking: BookingView; status: BookingStatus }) {
  switch (status) {
    case "PENDING":
      return (
        <p className="text-sm text-foreground">
          Waiting for the professional to respond.
          {b.expiresAt ? ` This request expires on ${formatIstDateTime(b.expiresAt)}.` : ""}
        </p>
      );
    case "CONFIRMED":
      return (
        <div className="flex flex-col gap-1.5 text-sm text-foreground">
          <p className="font-medium">Your appointment is confirmed.</p>
          {b.meetingMethod === "PHONE" ? (
            <p>The professional will call you on the phone number you gave.</p>
          ) : b.meetingMethod === "VIDEO" && b.meetingLink?.startsWith("https://") ? (
            <p>
              Video call link:{" "}
              <a
                href={b.meetingLink}
                target="_blank"
                rel="noopener noreferrer"
                className="font-medium break-all text-primary underline underline-offset-4"
              >
                {b.meetingLink}
              </a>
            </p>
          ) : b.meetingMethod === "IN_PERSON" && b.meetingAddress ? (
            <p className="whitespace-pre-line">Address: {b.meetingAddress}</p>
          ) : null}
        </div>
      );
    case "DECLINED":
      return (
        <div className="flex flex-col gap-3">
          <FindAnother>The professional could not take this appointment. You can find another professional.</FindAnother>
          <CrisisResourcesBox className="rounded-2xl p-5 sm:p-6" />
        </div>
      );
    case "ALTERNATE_PROPOSED":
      return (
        <div className="flex flex-col gap-1.5 text-sm text-foreground">
          <p className="font-medium">
            The professional suggested another time:{" "}
            {b.proposedDatetime ? formatIstDateTime(b.proposedDatetime) : "the time is not available"}.
          </p>
          {b.expiresAt ? <p>Please accept this time or cancel the request before {formatIstDateTime(b.expiresAt)}.</p> : null}
        </div>
      );
    case "CANCELLED":
      return <p className="text-sm text-foreground">You cancelled this request.</p>;
    case "EXPIRED":
      if (b.status === "ALTERNATE_PROPOSED") {
        return <FindAnother>This suggestion expired. You can request another appointment.</FindAnother>;
      }
      return (
        <FindAnother>
          This request expired because the professional did not respond in time. You can request another professional.
        </FindAnother>
      );
  }
}

function BookingItem({
  booking: b,
  now,
  onCancel,
  onAccept,
}: {
  booking: BookingView;
  now: number;
  onCancel: (b: BookingView) => void;
  onAccept: (b: BookingView) => void;
}) {
  const status = displayStatus(b, now);
  const confirmed = b.status === "CONFIRMED";
  const professional = b.professional;
  const type = professional
    ? (CREDENTIAL_TYPE_LABELS[professional.credentialType as CredentialType] ?? professional.credentialType)
    : null;
  const method = requestedMethodLabel(b);

  return (
    <li className="flex flex-col gap-4 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          {professional ? (
            <Link
              href={`/professionals/${professional.id}`}
              className="font-heading text-lg font-semibold text-foreground underline-offset-4 hover:underline"
            >
              {professional.fullName}
            </Link>
          ) : (
            <span className="font-heading text-lg font-semibold text-foreground">This professional is no longer listed.</span>
          )}
          {professional ? (
            <span className="text-sm text-muted-foreground">
              {[type, professional.city].filter(Boolean).join(", ")}
            </span>
          ) : null}
        </div>
        <BookingStatusChip status={status} label={STATUS_LABELS[status]} />
      </div>

      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {/* Once confirmed, preferredDatetime is the appointment time (the chosen or accepted one). */}
        {b.preferredDatetime ? (
          <Detail label={confirmed ? "Appointment time" : "Requested time"} value={formatIstDateTime(b.preferredDatetime)} />
        ) : null}
        {b.alternateDatetime && !confirmed ? <Detail label="Second choice" value={formatIstDateTime(b.alternateDatetime)} /> : null}
        {method ? <Detail label="How you asked to meet" value={method} /> : null}
        {b.proposedDatetime && !confirmed && status !== "ALTERNATE_PROPOSED" ? (
          <Detail label="Proposed time" value={formatIstDateTime(b.proposedDatetime)} />
        ) : null}
      </dl>

      {b.professionalNote ? (
        <div className="flex flex-col gap-0.5">
          <span className="text-xs text-muted-foreground">Note from the professional</span>
          <p className="text-sm whitespace-pre-line text-foreground">{b.professionalNote}</p>
        </div>
      ) : null}

      <StatusText booking={b} status={status} />

      {b.sharedAssessment ? (
        <p className="text-sm text-muted-foreground">
          You shared: {b.sharedAssessment.instrumentLabel}, score {b.sharedAssessment.score},{" "}
          {b.sharedAssessment.severityBand}, on {formatIstDate(b.sharedAssessment.completedAt)}.
        </p>
      ) : null}

      {CANCELLABLE.includes(status) ? (
        <div className="flex flex-wrap gap-3">
          {status === "ALTERNATE_PROPOSED" ? (
            <button type="button" className={primaryButtonClass} onClick={() => onAccept(b)}>
              Accept this time
            </button>
          ) : null}
          <button type="button" className={cn(secondaryButtonClass, "w-fit")} onClick={() => onCancel(b)}>
            Cancel request
          </button>
        </div>
      ) : null}
    </li>
  );
}

type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: BookingPage };

export default function AppointmentsPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [state, setState] = useState<State>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);
  const [toCancel, setToCancel] = useState<BookingView | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [toAccept, setToAccept] = useState<BookingView | null>(null);
  const [accepting, setAccepting] = useState(false);
  const now = useNow();

  useEffect(() => {
    let cancelled = false;
    getMyBookings(page, PAGE_SIZE)
      .then((data) => {
        if (cancelled) return;
        // The last page emptied (for example after a cancel elsewhere): show the one before it.
        if (data.items.length === 0 && page > 0) setPage(page - 1);
        else setState({ status: "ready", data });
      })
      .catch((error) => {
        if (cancelled) return;
        if (error instanceof BookingApiError && error.status === 401) {
          navigate(loginHref("/appointments"), { replace: true });
          return;
        }
        setState({
          status: "error",
          message: error instanceof Error ? error.message : "Your requests could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [page, reloadCount, navigate]);

  const reload = () => {
    setState({ status: "loading" });
    setReloadCount((n) => n + 1);
  };

  const confirmCancel = async () => {
    if (!toCancel) return;
    setCancelling(true);
    setCancelError(null);
    try {
      await cancelBooking(toCancel.id);
      setToCancel(null);
      reload();
    } catch (error) {
      if (error instanceof BookingApiError && error.status === 401) {
        navigate(loginHref("/appointments"));
        return;
      }
      setToCancel(null);
      setCancelError(error instanceof Error ? error.message : "The request could not be cancelled. Please try again.");
      reload();
    } finally {
      setCancelling(false);
    }
  };

  // A 409 (for example "This time is no longer available.") is shown as the server wrote it, then the
  // list is reloaded so the request shows its current state.
  const confirmAccept = async () => {
    if (!toAccept || accepting) return;
    setAccepting(true);
    setCancelError(null);
    try {
      await acceptProposal(toAccept.id);
      setToAccept(null);
      reload();
    } catch (error) {
      if (error instanceof BookingApiError && error.status === 401) {
        navigate(loginHref("/appointments"));
        return;
      }
      setToAccept(null);
      setCancelError(error instanceof Error ? error.message : "The time could not be accepted. Please try again.");
      reload();
    } finally {
      setAccepting(false);
    }
  };

  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-3xl flex-col gap-6">
        <BackLink className="-mb-4" />
        <header className="flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-full bg-primary/10 text-primary">
            <CalendarClock className="size-5" aria-hidden="true" />
          </span>
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground">My appointment requests</h1>
        </header>

        {cancelError ? <ErrorNotice message={cancelError} /> : null}

        {state.status === "loading" ? (
          <LoadingBlock label="Loading your appointment requests." />
        ) : state.status === "error" ? (
          <ErrorNotice message={state.message} onRetry={reload} />
        ) : state.data.items.length === 0 && page === 0 ? (
          <div className="flex flex-col items-start gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
            <p className="text-sm text-foreground">You have not requested any appointments yet.</p>
            <Link href="/professionals" className="font-medium text-primary underline underline-offset-4">
              Browse professionals
            </Link>
          </div>
        ) : (
          <>
            <ul className="flex flex-col gap-4">
              {state.data.items.map((booking) => (
                <BookingItem key={booking.id} booking={booking} now={now} onCancel={setToCancel} onAccept={setToAccept} />
              ))}
            </ul>
            {state.data.totalPages > 1 ? (
              <nav className="flex items-center justify-between gap-3" aria-label="Pages">
                <button
                  type="button"
                  className={secondaryButtonClass}
                  disabled={page === 0}
                  onClick={() => {
                    setState({ status: "loading" });
                    setPage((p) => p - 1);
                  }}
                >
                  Newer requests
                </button>
                <span className="text-sm text-muted-foreground">
                  Page {page + 1} of {state.data.totalPages}
                </span>
                <button
                  type="button"
                  className={secondaryButtonClass}
                  disabled={page + 1 >= state.data.totalPages}
                  onClick={() => {
                    setState({ status: "loading" });
                    setPage((p) => p + 1);
                  }}
                >
                  Older requests
                </button>
              </nav>
            ) : null}
          </>
        )}
      </Container>

      <ConfirmDialog
        open={toCancel !== null}
        title="Cancel this request?"
        description="The professional will no longer see this request. You can send a new request at any time."
        confirmLabel="Cancel request"
        cancelLabel="Keep request"
        busy={cancelling}
        onConfirm={() => void confirmCancel()}
        onOpenChange={(open) => {
          if (!open && !cancelling) setToCancel(null);
        }}
      />

      <ConfirmDialog
        open={toAccept !== null}
        title="Accept this time?"
        description={
          toAccept?.proposedDatetime
            ? `Your appointment will be confirmed for ${formatIstDateTime(toAccept.proposedDatetime)}. The meeting details will then be shown on this page.`
            : "Your appointment will be confirmed for the suggested time. The meeting details will then be shown on this page."
        }
        confirmLabel="Accept this time"
        cancelLabel="Go back"
        busy={accepting}
        onConfirm={() => void confirmAccept()}
        onOpenChange={(open) => {
          if (!open && !accepting) setToAccept(null);
        }}
      />
    </section>
  );
}
