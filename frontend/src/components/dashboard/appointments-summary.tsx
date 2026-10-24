import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@clerk/clerk-react";
import { CalendarClock, CreditCard, Hourglass, MessageSquareReply } from "lucide-react";

import { getBookingSummary, METHOD_LABELS, type BookingSummary } from "@/lib/bookings-client";
import { formatIstDateTime, todayInIndia } from "@/lib/ist-time";
import { CREDENTIAL_TYPE_LABELS, type CredentialType } from "@/lib/professionals-client";
import { cn } from "@/lib/utils";
import { DashboardSectionHeader } from "@/components/dashboard/dashboard-section-header";
import { RecentSessions } from "@/components/dashboard/recent-sessions";

type State = { status: "loading" } | { status: "error" } | { status: "ready"; summary: BookingSummary };

const indiaDate = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" });

/**
 * "Your appointments" on the user's dashboard: the next confirmed appointment, the bookings waiting for
 * payment and the open requests, from
 * GET /api/bookings/summary. Shows only what that endpoint returns, and never a professional's contact
 * details. Loaded again whenever the window gets focus.
 */
export function AppointmentsSummary() {
  const { isLoaded, isSignedIn } = useAuth();
  const [state, setState] = useState<State>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    const onFocus = () => setReloadCount((n) => n + 1);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    getBookingSummary()
      .then((summary) => !cancelled && setState({ status: "ready", summary }))
      // A failed background refresh keeps what is already on screen.
      .catch(() => !cancelled && setState((current) => (current.status === "ready" ? current : { status: "error" })));
    return () => {
      cancelled = true;
    };
  }, [ready, reloadCount]);

  return (
    <section className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8" aria-labelledby="your-appointments">
      <DashboardSectionHeader icon={CalendarClock} title="Your appointments" id="your-appointments" />

      {state.status === "loading" ? (
        <div className="grid gap-4 md:grid-cols-[3fr_2fr]" aria-busy="true">
          <span className="sr-only">Loading your appointments.</span>
          {/* Static placeholders: no shimmer. */}
          <div className="h-40 rounded-2xl bg-[#F5F7F2]" aria-hidden="true" />
          <div className="h-40 rounded-2xl bg-[#F5F7F2]" aria-hidden="true" />
        </div>
      ) : state.status === "error" ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          <span>Your appointments could not be loaded. Please try again.</span>
          <button
            type="button"
            onClick={() => {
              setState({ status: "loading" });
              setReloadCount((n) => n + 1);
            }}
            className="cursor-pointer rounded-sm font-medium underline underline-offset-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            Try again
          </button>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-[3fr_2fr]">
          <NextAppointmentCard next={state.summary.nextAppointment} />
          <div className="flex flex-col gap-4">
            {(state.summary.awaitingPayment ?? []).length > 0 ? (
              <Counter
                icon={CreditCard}
                value={(state.summary.awaitingPayment ?? []).length}
                label="Awaiting payment"
                highlight
                sentence="A professional accepted your request. Open your appointments to pay and confirm your slot."
              />
            ) : null}
            <Counter
              icon={Hourglass}
              value={state.summary.waitingForProfessional}
              label="Waiting for the professional's reply"
            />
            <Counter
              icon={MessageSquareReply}
              value={state.summary.needsYourAnswer}
              label="Needs your answer"
              highlight={state.summary.needsYourAnswer > 0}
              sentence={
                state.summary.needsYourAnswer > 0
                  ? "A professional suggested another time. Open your appointments to accept or cancel."
                  : undefined
              }
            />
          </div>
        </div>
      )}

      <RecentSessions />
    </section>
  );
}

function NextAppointmentCard({ next }: { next: BookingSummary["nextAppointment"] }) {
  if (!next) {
    return (
      <div className="flex flex-col items-start gap-3 rounded-2xl bg-[#FBF9F4] p-5">
        <h3 className="text-sm font-semibold">Next appointment</h3>
        <p className="text-sm text-[#4A5750]">You have no upcoming appointments.</p>
        <Link
          href="/professionals"
          className="inline-flex items-center rounded-full bg-[#233E33] px-5 py-2.5 text-sm font-medium text-white outline-none transition-colors duration-150 hover:bg-[#192E26] focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          Find a professional
        </Link>
      </div>
    );
  }

  const p = next.professional;
  const type = p ? (CREDENTIAL_TYPE_LABELS[p.credentialType as CredentialType] ?? p.credentialType) : null;
  const isToday = indiaDate.format(new Date(next.scheduledTime)) === todayInIndia();

  return (
    <div className="flex flex-col gap-3 rounded-2xl bg-[#FBF9F4] p-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">Next appointment</h3>
        {isToday ? (
          <span className="rounded-full bg-[#233E33] px-3 py-1 text-xs font-semibold text-white">Today</span>
        ) : null}
      </div>
      {p ? (
        <div className="flex flex-col">
          <Link href={`/professionals/${p.id}`} className="rounded-sm text-base font-semibold text-[#1C352D] underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50">
            {p.fullName}
          </Link>
          <span className="text-sm text-[#4A5750]">{[type, p.city].filter(Boolean).join(", ")}</span>
        </div>
      ) : (
        <p className="text-sm text-[#4A5750]">This professional is no longer listed.</p>
      )}
      <p className="text-sm font-medium">{formatIstDateTime(next.scheduledTime)}</p>
      <MeetingLine next={next} />
      <Link
        href="/appointments"
        className="w-fit rounded-sm text-sm font-medium text-[#233E33] underline underline-offset-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        View all my appointments
      </Link>
    </div>
  );
}

function MeetingLine({ next }: { next: NonNullable<BookingSummary["nextAppointment"]> }) {
  if (next.meetingMethod === "PHONE") {
    return <p className="text-sm text-[#4A5750]">Phone call. The professional will call you on the phone number you gave.</p>;
  }
  if (next.meetingMethod === "VIDEO") {
    return next.meetingLink?.startsWith("https://") ? (
      <p className="text-sm text-[#4A5750]">
        Video call:{" "}
        <a
          href={next.meetingLink}
          target="_blank"
          rel="noopener noreferrer"
          className="font-medium break-all text-[#233E33] underline underline-offset-4"
        >
          {next.meetingLink}
        </a>
      </p>
    ) : (
      <p className="text-sm text-[#4A5750]">Video call.</p>
    );
  }
  if (next.meetingMethod === "IN_PERSON") {
    return (
      <p className="text-sm whitespace-pre-line text-[#4A5750]">
        {METHOD_LABELS.IN_PERSON}
        {next.meetingAddress ? `: ${next.meetingAddress}` : "."}
      </p>
    );
  }
  return null;
}

function Counter({
  icon: Icon,
  value,
  label,
  highlight,
  sentence,
}: {
  icon: typeof Hourglass;
  value: number;
  label: string;
  highlight?: boolean;
  sentence?: string;
}) {
  return (
    <Link
      href="/appointments"
      className={cn(
        "flex flex-col gap-1 rounded-2xl p-4 outline-none transition-colors duration-150 focus-visible:ring-3 focus-visible:ring-ring/50",
        highlight ? "border border-amber-300 bg-amber-50 hover:bg-amber-100" : "bg-[#FBF9F4] hover:bg-[#F3F1EA]",
      )}
    >
      <span className="flex items-center gap-3">
        <Icon className={cn("size-4", highlight ? "text-amber-700" : "text-[#233E33]")} aria-hidden="true" />
        <span className="text-2xl font-semibold tabular-nums">{value}</span>
      </span>
      <span className="text-sm text-[#4A5750]">{label}</span>
      {sentence ? <span className="text-sm font-medium text-amber-900">{sentence}</span> : null}
    </Link>
  );
}
