import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { AlertTriangle, CalendarClock, CalendarOff, Clock, CreditCard, Inbox, OctagonAlert } from "lucide-react";

import { ProPortalFrame } from "@/components/pro/pro-portal-nav";
import { ProStatusBanner } from "@/components/pro/pro-status-banner";
import { DisabledAccountNotice, ErrorNotice, LoadingBlock } from "@/components/pro/pro-ui";
import { formatIstDate, formatIstDateTime } from "@/lib/ist-time";
import { ProApiError, useProApi, type ProMe } from "@/lib/pro-client";
import type { ProAwaitingPaymentItem, ProDashboard, ProWarning } from "@/lib/pro-home";
import { cn } from "@/lib/utils";

const RED_WARNINGS = new Set(["REGISTRATION_EXPIRED", "SUSPENDED"]);
const LISTING_WARNINGS = new Set(["NOT_LISTED", "SUSPENDED", "REGISTRATION_EXPIRED", "NO_WEEKLY_HOURS"]);
const REGISTRATION_SENTENCE = "Contact the NeuroNest team to update your registration.";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; me: ProMe; summary: ProDashboard };

/** "31 Oct 2026" for a YYYY-MM-DD date (noon UTC is the same date in India). */
function formatDay(isoDate: string) {
  return formatIstDate(`${isoDate}T12:00:00Z`);
}

function meetingText(method: string | null): string {
  if (method === "PHONE") return "Phone call. You will call the person.";
  if (method === "VIDEO") return "Video call.";
  if (method === "IN_PERSON") return "In person.";
  return "The way to meet has not been set.";
}

/** /pro/dashboard: what needs the professional's attention, loaded again whenever the window gets focus. */
export function ProDashboardPage() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const navigate = useNavigate();
  const [state, setState] = useState<State>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);

  useEffect(() => {
    const onFocus = () => setReloadCount((n) => n + 1);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  // Nothing is requested until Clerk has loaded and reports a signed-in user. Someone without a
  // professional account yet goes to the profile page, where the first save creates the account.
  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    (async () => {
      try {
        const me = await api.me();
        const summary = await api.dashboard();
        if (!cancelled) setState({ status: "ready", me, summary });
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ProApiError && e.kind === "not-registered") {
          navigate("/pro/profile", { replace: true });
          return;
        }
        if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
        setState((current) =>
          // A failed background refresh keeps what is on screen.
          current.status === "ready"
            ? current
            : { status: "error", message: e instanceof Error && e.message ? e.message : "Your dashboard could not be loaded. Please try again." },
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ready, api, navigate, reloadCount]);

  if (disabled) {
    return (
      <ProPortalFrame title="Dashboard">
        <DisabledAccountNotice />
      </ProPortalFrame>
    );
  }

  return (
    <ProPortalFrame title="Dashboard" intro="Your requests, appointments and listing at a glance. All times are in Indian Standard Time.">
      {state.status === "loading" ? (
        <LoadingBlock label="Loading your dashboard." />
      ) : state.status === "error" ? (
        <ErrorNotice
          message={state.message}
          onRetry={() => {
            setState({ status: "loading" });
            setReloadCount((n) => n + 1);
          }}
        />
      ) : (
        <DashboardContent me={state.me} summary={state.summary} />
      )}
    </ProPortalFrame>
  );
}

function DashboardContent({ me, summary: s }: { me: ProMe; summary: ProDashboard }) {
  const listed = s.verificationStatus === "VERIFIED" && s.active;
  const listingReasons = s.warnings.filter((w) => LISTING_WARNINGS.has(w.code));

  return (
    <>
      <ProStatusBanner me={me} />

      {s.warnings.length > 0 ? (
        <div className="flex flex-col gap-3">
          {s.warnings.map((warning) => (
            <WarningAlert key={warning.code} warning={warning} />
          ))}
        </div>
      ) : null}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <StatCard
          icon={Inbox}
          value={s.pendingRequests}
          label="Requests waiting for your reply"
          highlight={s.pendingRequests > 0}
          link={{ href: "/pro/requests", label: "Reply to requests" }}
        />
        <StatCard icon={Clock} value={s.awaitingUser} label="Waiting for the person" />
        <StatCard
          icon={CreditCard}
          value={s.awaitingPayment}
          label="Accepted, waiting for payment"
          link={s.awaitingPayment > 0 ? { href: "/pro/requests?status=AWAITING_PAYMENT", label: "See these bookings" } : undefined}
        />
        <StatCard
          icon={CalendarClock}
          value={s.upcomingConfirmed}
          label="Upcoming appointments"
          link={{ href: "/pro/appointments", label: "See your appointments" }}
        />
        <StatCard
          icon={CalendarOff}
          value={s.daysOffCount}
          label="Days off marked"
          link={{ href: "/pro/days-off", label: "Manage your days off" }}
        />
      </div>

      {s.awaitingPaymentBookings.length > 0 ? <AwaitingPaymentCard items={s.awaitingPaymentBookings} /> : null}

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Card title="Next appointment">
          {s.nextAppointment ? (
            <>
              <p className="text-base font-semibold text-foreground">{s.nextAppointment.contactName}</p>
              <p className="text-sm text-foreground">{formatIstDateTime(s.nextAppointment.scheduledTime)}</p>
              <p className="text-sm text-muted-foreground">{meetingText(s.nextAppointment.meetingMethod)}</p>
              <Link href="/pro/appointments" className="w-fit text-sm font-medium text-primary underline-offset-4 hover:underline">
                View
              </Link>
            </>
          ) : (
            <p className="text-sm text-foreground">You have no upcoming appointments.</p>
          )}
        </Card>

        <Card title="Your listing">
          <p className={cn("text-base font-semibold", listed ? "text-emerald-700 dark:text-emerald-400" : "text-foreground")}>
            {listed ? "Listed in the directory" : "Not listed"}
          </p>
          {!listed && listingReasons.length > 0 ? (
            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-muted-foreground">
              {listingReasons.map((w) => (
                <li key={w.code}>{w.message}</li>
              ))}
            </ul>
          ) : null}
          {s.registrationExpiry ? (
            <p className="text-sm text-foreground">
              Registration valid until {formatDay(s.registrationExpiry)}
              {s.registrationDaysLeft !== null && s.registrationDaysLeft >= 0
                ? s.registrationDaysLeft === 0
                  ? ". It expires today."
                  : `, ${s.registrationDaysLeft} ${s.registrationDaysLeft === 1 ? "day" : "days"} left.`
                : ". It has expired."}
            </p>
          ) : (
            <p className="text-sm text-muted-foreground">No registration expiry date is recorded.</p>
          )}
        </Card>
      </div>
    </>
  );
}

/**
 * Bookings the professional accepted that are waiting for the person's payment: name, time, how they
 * meet and the deadline. Never a phone number or email (those are shown once it is paid).
 */
function AwaitingPaymentCard({ items }: { items: ProAwaitingPaymentItem[] }) {
  return (
    <Card title="Accepted, waiting for payment">
      <p className="text-sm text-muted-foreground">
        The time is held for the person until their payment deadline. It becomes an appointment once they pay.
      </p>
      <ul className="flex flex-col divide-y divide-border">
        {items.map((item) => (
          <li key={item.id} className="flex flex-col gap-0.5 py-3 first:pt-1 last:pb-0">
            <Link
              href={`/pro/requests/${item.id}`}
              className="w-fit text-base font-semibold text-foreground underline-offset-4 hover:underline"
            >
              {item.contactName}
            </Link>
            {item.scheduledTime ? <p className="text-sm text-foreground">{formatIstDateTime(item.scheduledTime)}</p> : null}
            <p className="text-sm text-muted-foreground">{meetingText(item.meetingMethod)}</p>
            {item.paymentDueAt ? (
              <p className="text-sm text-foreground">Pay by {formatIstDateTime(item.paymentDueAt)}</p>
            ) : null}
          </li>
        ))}
      </ul>
    </Card>
  );
}

function WarningAlert({ warning }: { warning: ProWarning }) {
  const red = RED_WARNINGS.has(warning.code);
  const Icon = red ? OctagonAlert : AlertTriangle;
  return (
    <div
      role="alert"
      className={cn(
        "flex items-start gap-3 rounded-2xl border px-4 py-3",
        red
          ? "border-destructive/30 bg-destructive/5 text-destructive"
          : "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100",
      )}
    >
      <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <div className="flex flex-col gap-1 text-sm">
        <p className="font-medium">{warning.message}</p>
        {warning.code === "NO_WEEKLY_HOURS" ? (
          <Link href="/pro/profile" className="w-fit font-medium underline underline-offset-4">
            Set your weekly hours on your profile
          </Link>
        ) : warning.code === "DAY_OFF_CONFLICTS" ? (
          <Link href="/pro/appointments" className="w-fit font-medium underline underline-offset-4">
            See your appointments
          </Link>
        ) : warning.code === "DAY_OFF_AWAITING_PAYMENT" ? (
          <Link href="/pro/requests?status=AWAITING_PAYMENT" className="w-fit font-medium underline underline-offset-4">
            See the bookings waiting for payment
          </Link>
        ) : warning.code === "REGISTRATION_EXPIRING" || warning.code === "REGISTRATION_EXPIRED" ? (
          <p>{REGISTRATION_SENTENCE}</p>
        ) : null}
      </div>
    </div>
  );
}

function StatCard({
  icon: Icon,
  value,
  label,
  highlight,
  link,
}: {
  icon: typeof Inbox;
  value: number;
  label: string;
  highlight?: boolean;
  link?: { href: string; label: string };
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-2 rounded-2xl bg-card p-5 ring-1",
        highlight ? "ring-2 ring-primary" : "ring-foreground/10",
      )}
    >
      <div className="flex items-center gap-3">
        <span className={cn("flex size-9 items-center justify-center rounded-full", highlight ? "bg-primary text-primary-foreground" : "bg-primary/10 text-primary")}>
          <Icon className="size-4" aria-hidden="true" />
        </span>
        <span className="font-heading text-3xl font-semibold text-foreground">{value}</span>
      </div>
      <p className="text-sm text-muted-foreground">{label}</p>
      {link ? (
        <Link href={link.href} className="w-fit text-sm font-medium text-primary underline-offset-4 hover:underline">
          {link.label}
        </Link>
      ) : null}
    </div>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2 rounded-2xl bg-card p-5 ring-1 ring-foreground/10">
      <h2 className="font-heading text-lg font-semibold text-foreground">{title}</h2>
      {children}
    </section>
  );
}
