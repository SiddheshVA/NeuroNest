import { useEffect, useState } from "react";
import Link from "next/link";
import { useNavigate } from "react-router-dom";
import { CalendarClock, CheckCircle2, CreditCard, Info } from "lucide-react";

import { BookingStatusChip } from "@/components/bookings/booking-status-chip";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { ConfirmDialog, ErrorNotice, LoadingBlock, primaryButtonClass, secondaryButtonClass } from "@/components/pro/pro-ui";
import { SessionReviewControl } from "@/components/reviews/session-review-control";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import {
  acceptProposal,
  BookingApiError,
  cancelBooking,
  displayStatus,
  formatAmount,
  getBookingSummary,
  getMyBookings,
  requestedMethodLabel,
  STATUS_LABELS,
  type AwaitingPaymentBooking,
  type BookingPage,
  type BookingStatus,
  type BookingView,
} from "@/lib/bookings-client";
import { payForBooking } from "@/lib/razorpay-checkout";
import { formatIstDate, formatIstDateTime, formatMeetingTime } from "@/lib/ist-time";
import { CREDENTIAL_TYPE_LABELS, type CredentialType } from "@/lib/professionals-client";
import { getReviewableBookings, type MyReview, type ReviewableBooking } from "@/lib/reviews-client";
import { loginHref } from "@/lib/safe-redirect";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 10;
const CANCELLABLE: BookingStatus[] = ["PENDING", "ALTERNATE_PROPOSED", "AWAITING_PAYMENT"];

/** What is being paid for or cancelled: from the list or from the "Awaiting payment" section. */
type Target = { id: string; professionalName: string; awaitingPayment: boolean; paymentDueAt?: string | null };

type Notice = { kind: "success" | "info"; message: string };

function NoticeBox({ notice }: { notice: Notice }) {
  const Icon = notice.kind === "success" ? CheckCircle2 : Info;
  return (
    <div
      role="status"
      className={cn(
        "flex items-start gap-3 rounded-2xl border px-4 py-3 text-sm",
        notice.kind === "success"
          ? "border-emerald-200 bg-emerald-50 text-emerald-900"
          : "border-sky-200 bg-sky-50 text-sky-900",
      )}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <span>{notice.message}</span>
    </div>
  );
}

const MODE_LABELS = { ONLINE: "Online", IN_PERSON: "In person" } as const;

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
    case "AWAITING_PAYMENT":
      return (
        <div className="flex flex-col gap-1.5 text-sm text-foreground">
          <p className="font-medium">Your time is held while you pay.</p>
          <p>
            {b.paymentDueAt
              ? `Please pay by ${formatIstDateTime(b.paymentDueAt)} to confirm your booking.`
              : "Please pay to confirm your booking."}{" "}
            If it is not paid by then, the time is released. The professional's contact details are shown here once
            the payment is done.
          </p>
        </div>
      );
    case "CONFIRMED":
      return (
        <div className="flex flex-col gap-1.5 text-sm text-foreground">
          <p className="font-medium">
            Your {b.sessionCount > 1 ? `${b.sessionCount} sessions are` : "appointment is"} confirmed.
          </p>
          {formatAmount(b.paidAmount, b.paidCurrency) ? <p>Paid {formatAmount(b.paidAmount, b.paidCurrency)}</p> : null}
          {b.professionalEmail || b.professionalPhone ? (
            <p>
              Contact {b.professional?.fullName ?? "your professional"}:{" "}
              {b.professionalPhone ? (
                <a href={`tel:${b.professionalPhone}`} className="font-medium text-primary underline underline-offset-4">
                  {b.professionalPhone}
                </a>
              ) : null}
              {b.professionalPhone && b.professionalEmail ? " · " : null}
              {b.professionalEmail ? (
                <a
                  href={`mailto:${b.professionalEmail}`}
                  className="font-medium break-all text-primary underline underline-offset-4"
                >
                  {b.professionalEmail}
                </a>
              ) : null}
            </p>
          ) : null}
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
      // Agreed but not paid in time (paymentDueAt is only set once a time was agreed).
      if (b.status === "AWAITING_PAYMENT" || b.paymentDueAt) {
        return (
          <FindAnother>
            {`${
              b.paymentDueAt
                ? `The payment window ended on ${formatIstDateTime(b.paymentDueAt)}, so this slot was released.`
                : "The payment window ended, so this slot was released."
            } You can book again with this or another professional.`}
          </FindAnother>
        );
      }
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
  paying,
  onCancel,
  onAccept,
  onPay,
  review,
  onReviewChanged,
}: {
  booking: BookingView;
  now: number;
  /** Set only for a confirmed appointment whose session has ended: it can be rated (or already was). */
  review?: ReviewableBooking;
  onReviewChanged: (bookingId: string, review: MyReview | null) => void;
  /** The id of the booking being paid for, if any: every Pay now is disabled meanwhile. */
  paying: string | null;
  onCancel: (target: Target) => void;
  onAccept: (b: BookingView) => void;
  onPay: (target: Target) => void;
}) {
  const status = displayStatus(b, now);
  const confirmed = b.status === "CONFIRMED";
  const professional = b.professional;
  const type = professional
    ? (CREDENTIAL_TYPE_LABELS[professional.credentialType as CredentialType] ?? professional.credentialType)
    : null;
  const method = requestedMethodLabel(b);
  const target: Target = {
    id: b.id,
    professionalName: professional?.fullName ?? "your professional",
    awaitingPayment: status === "AWAITING_PAYMENT",
    paymentDueAt: b.paymentDueAt,
  };

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
          <Detail
            label={confirmed ? "Appointment time" : "Requested time"}
            value={formatMeetingTime(b.preferredDatetime, b.endDatetime, b.sessionCount)}
          />
        ) : null}
        {b.alternateDatetime && !confirmed ? <Detail label="Second choice" value={formatIstDateTime(b.alternateDatetime)} /> : null}
        {method ? <Detail label="How you asked to meet" value={method} /> : null}
        {status === "AWAITING_PAYMENT" && formatAmount(b.amount, b.currency) ? (
          <Detail label="Fee" value={formatAmount(b.amount, b.currency) ?? ""} />
        ) : null}
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

      {review ? (
        <SessionReviewControl entry={review} onChanged={(next) => onReviewChanged(b.id, next)} />
      ) : null}

      {CANCELLABLE.includes(status) ? (
        <div className="flex flex-wrap gap-3">
          {status === "ALTERNATE_PROPOSED" ? (
            <button type="button" className={primaryButtonClass} onClick={() => onAccept(b)}>
              Accept this time
            </button>
          ) : null}
          {status === "AWAITING_PAYMENT" ? (
            <PayButton paying={paying} id={b.id} amount={formatAmount(b.amount, b.currency)} onPay={() => onPay(target)} />
          ) : null}
          <button
            type="button"
            className={cn(secondaryButtonClass, "w-fit")}
            disabled={paying === b.id}
            onClick={() => onCancel(target)}
          >
            {status === "AWAITING_PAYMENT" ? "Cancel booking" : "Cancel request"}
          </button>
        </div>
      ) : null}
    </li>
  );
}

/** "Pay ₹1,500" when the amount is known (from the server, the same amount the order charges), else "Pay now". */
function PayButton({
  paying,
  id,
  amount,
  onPay,
}: {
  paying: string | null;
  id: string;
  amount: string | null;
  onPay: () => void;
}) {
  return (
    <button type="button" className={primaryButtonClass} disabled={paying !== null} onClick={onPay}>
      <CreditCard className="size-4" aria-hidden="true" />
      {paying === id ? "Payment in progress..." : amount ? `Pay ${amount}` : "Pay now"}
    </button>
  );
}

/** The bookings a professional accepted that are waiting for the user's payment (from the summary). */
function AwaitingPaymentSection({
  items,
  paying,
  onPay,
  onCancel,
}: {
  items: AwaitingPaymentBooking[];
  paying: string | null;
  onPay: (target: Target) => void;
  onCancel: (target: Target) => void;
}) {
  return (
    <section className="flex flex-col gap-3" aria-labelledby="awaiting-payment">
      <h2 id="awaiting-payment" className="font-heading text-xl font-semibold text-foreground">
        Awaiting payment
      </h2>
      <ul className="flex flex-col gap-4">
        {items.map((a) => {
          const p = a.professional;
          const type = p ? (CREDENTIAL_TYPE_LABELS[p.credentialType as CredentialType] ?? p.credentialType) : null;
          const target: Target = {
            id: a.id,
            professionalName: p?.fullName ?? "your professional",
            awaitingPayment: true,
            paymentDueAt: a.paymentDueAt,
          };
          const fee = formatAmount(a.amount, a.currency);
          return (
            <li key={a.id} className="flex flex-col gap-4 rounded-2xl bg-card p-6 ring-2 ring-violet-200">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="flex flex-col gap-0.5">
                  {p ? (
                    <Link
                      href={`/professionals/${p.id}`}
                      className="font-heading text-lg font-semibold text-foreground underline-offset-4 hover:underline"
                    >
                      {p.fullName}
                    </Link>
                  ) : (
                    <span className="font-heading text-lg font-semibold text-foreground">This professional is no longer listed.</span>
                  )}
                  {p ? (
                    <span className="text-sm text-muted-foreground">{[type, p.city].filter(Boolean).join(", ")}</span>
                  ) : null}
                </div>
                <BookingStatusChip status="AWAITING_PAYMENT" label={STATUS_LABELS.AWAITING_PAYMENT} />
              </div>
              <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <Detail label="Date and time" value={formatMeetingTime(a.scheduledTime, a.endDatetime, a.sessionCount)} />
                {a.preferredMode ? <Detail label="Meeting" value={MODE_LABELS[a.preferredMode]} /> : null}
                {fee ? <Detail label="Fee" value={fee} /> : null}
              </dl>
              <p className="text-sm font-medium text-foreground">
                Pay by {formatIstDateTime(a.paymentDueAt)} to confirm your slot.
              </p>
              <div className="flex flex-wrap gap-3">
                <PayButton paying={paying} id={a.id} amount={fee} onPay={() => onPay(target)} />
                <button
                  type="button"
                  className={cn(secondaryButtonClass, "w-fit")}
                  disabled={paying === a.id}
                  onClick={() => onCancel(target)}
                >
                  Cancel booking
                </button>
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: BookingPage };

export default function AppointmentsPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [state, setState] = useState<State>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);
  const [toCancel, setToCancel] = useState<Target | null>(null);
  // The summary only feeds the "Awaiting payment" section; if it fails, the list below still offers Pay now.
  const [awaiting, setAwaiting] = useState<AwaitingPaymentBooking[]>([]);
  const [paying, setPaying] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [toAccept, setToAccept] = useState<BookingView | null>(null);
  const [accepting, setAccepting] = useState(false);
  // Past confirmed sessions that can be (or were) rated, by booking id. Only adds the rating control; if it
  // fails to load, the list works exactly as before.
  const [reviewable, setReviewable] = useState<Record<string, ReviewableBooking>>({});
  const now = useNow();

  useEffect(() => {
    let cancelled = false;
    getReviewableBookings()
      .then((items) => !cancelled && setReviewable(Object.fromEntries(items.map((item) => [item.bookingId, item]))))
      .catch(() => !cancelled && setReviewable({}));
    return () => {
      cancelled = true;
    };
  }, [reloadCount]);

  const reviewChanged = (bookingId: string, review: MyReview | null) =>
    setReviewable((current) =>
      current[bookingId] ? { ...current, [bookingId]: { ...current[bookingId], review, canReview: review === null } } : current,
    );

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
    getBookingSummary()
      .then((summary) => !cancelled && setAwaiting(summary.awaitingPayment ?? []))
      .catch(() => !cancelled && setAwaiting([]));
    return () => {
      cancelled = true;
    };
  }, [page, reloadCount, navigate]);

  // Only bookings whose deadline has not passed can still be paid.
  const payable = awaiting.filter((a) => Date.parse(a.paymentDueAt) > now);

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

  // One payment at a time. The amount, currency and key id come from the server's order; nothing about
  // the payment is logged. After a verified payment the list is reloaded so the booking shows as confirmed.
  const pay = async (target: Target) => {
    if (paying) return;
    setPaying(target.id);
    setNotice(null);
    setCancelError(null);
    try {
      const result = await payForBooking(target.id, target.professionalName, target.paymentDueAt);
      switch (result.outcome) {
        case "paid":
          setNotice({ kind: "success", message: "Payment received. Your appointment is confirmed and the meeting details are below." });
          reload();
          break;
        case "dismissed":
          setNotice({ kind: "info", message: "The payment was not completed. You can pay whenever you are ready, before the deadline." });
          break;
        case "failed":
          setNotice({ kind: "info", message: "The payment did not go through. Nothing has changed, and you can try again before the deadline." });
          break;
        case "unverified":
          setNotice({ kind: "info", message: result.message });
          reload();
          break;
      }
    } catch (error) {
      if (error instanceof BookingApiError && error.status === 401) {
        navigate(loginHref("/appointments"));
        return;
      }
      setCancelError(error instanceof Error ? error.message : "The payment could not be started. Please try again.");
      // For example the deadline passed or the booking changed: show its current state.
      if (error instanceof BookingApiError && error.status === 409) reload();
    } finally {
      setPaying(null);
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
        {notice ? <NoticeBox notice={notice} /> : null}

        {state.status === "ready" && payable.length > 0 ? (
          <AwaitingPaymentSection
            items={payable}
            paying={paying}
            onPay={(target) => void pay(target)}
            onCancel={setToCancel}
          />
        ) : null}

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
                <BookingItem
                  key={booking.id}
                  booking={booking}
                  now={now}
                  paying={paying}
                  onCancel={setToCancel}
                  onAccept={setToAccept}
                  onPay={(target) => void pay(target)}
                  review={reviewable[booking.id]}
                  onReviewChanged={reviewChanged}
                />
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
        title={toCancel?.awaitingPayment ? "Cancel this booking?" : "Cancel this request?"}
        description={
          toCancel?.awaitingPayment
            ? "Nothing has been paid, so nothing will be charged. The time will be released and the professional will be told."
            : "The professional will no longer see this request. You can send a new request at any time."
        }
        confirmLabel={toCancel?.awaitingPayment ? "Cancel booking" : "Cancel request"}
        cancelLabel={toCancel?.awaitingPayment ? "Keep booking" : "Keep request"}
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
          `The professional will hold ${toAccept?.proposedDatetime ? formatIstDateTime(toAccept.proposedDatetime) : "the suggested time"} for you. ` +
          "If they charge a fee, you then pay on this page to confirm it; a free session is confirmed straight away. " +
          "The meeting details are shown here once it is confirmed."
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
