import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useUser } from "@clerk/clerk-react";
import {
  AlertTriangle,
  ChevronRight,
  ClipboardCheck,
  HeartHandshake,
  Leaf,
  Loader2,
  MessageCircle,
  type LucideIcon,
} from "lucide-react";
import { AppointmentsSummary } from "@/components/dashboard/appointments-summary";
import { DashboardSectionHeader } from "@/components/dashboard/dashboard-section-header";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { apiFetch } from "@/lib/api";
import { SCORE_RANGES } from "@/lib/assessments/ranges";
import { ASSESSMENTS } from "@/lib/assessments/registry";
import { checkInHref, fetchDashboard, toCheckIns, type CheckIn, type DashboardData } from "@/lib/dashboard-data";

interface ConversationSummary {
  sessionId: string;
  startedAt: string;
  preview: string;
  messageCount: number;
}

const CHECK_INS_SHOWN = 5;

const cardClassName = "rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8";
const focusRing = "outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
const primaryButtonClassName = `inline-flex items-center rounded-full bg-[#233E33] px-5 py-2.5 text-sm font-medium text-white transition-colors duration-150 hover:bg-[#192E26] ${focusRing}`;
const secondaryButtonClassName = `inline-flex items-center rounded-full border border-[#233E33]/20 px-5 py-2.5 text-sm font-medium text-[#233E33] transition-colors duration-150 hover:bg-[#233E33]/5 ${focusRing}`;

const QUICK_ACTIONS: { href: string; icon: LucideIcon; title: string; description: string }[] = [
  { href: "/ai-companion", icon: MessageCircle, title: "Talk with Neuro", description: "Talk through what's on your mind" },
  { href: "/assessment", icon: ClipboardCheck, title: "Take a check-in", description: "A short self-check, about 2 minutes" },
  { href: "/relaxation", icon: Leaf, title: "Relaxation", description: "Breathing, meditation and sleep sounds" },
  {
    href: "/professionals",
    icon: HeartHandshake,
    title: "Find a Licensed Professional",
    description: "Verified psychologists, psychiatrists and registered counsellors",
  },
];

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

const relativeTime = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

/** "5 minutes ago", "yesterday", "3 weeks ago"; older than a year falls back to the date. */
function formatRelative(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const seconds = Math.round((date.getTime() - Date.now()) / 1000);
  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ["second", 60],
    ["minute", 60],
    ["hour", 24],
    ["day", 7],
    ["week", 4.35],
    ["month", 12],
  ];
  let amount = seconds;
  for (const [unit, size] of steps) {
    if (Math.abs(amount) < size) return relativeTime.format(Math.round(amount), unit);
    amount /= size;
  }
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export default function DashboardPage() {
  const { user } = useUser();
  const [data, setData] = useState<DashboardData | null>(null);
  // Null when the list couldn't be loaded, so no count is shown for it.
  const [conversations, setConversations] = useState<ConversationSummary[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showAllCheckIns, setShowAllCheckIns] = useState(false);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      fetchDashboard(),
      apiFetch("/me/conversations")
        .then((res) => (res.ok ? (res.json() as Promise<ConversationSummary[]>) : null))
        .catch(() => null),
    ])
      .then(([dashboard, convos]) => {
        if (cancelled) return;
        setData(dashboard);
        setConversations(convos);
      })
      .catch(() => {
        if (!cancelled) setError("We couldn't load your dashboard. Please check the server is running and try again.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const displayName = data?.name || user?.firstName || "";
  // Scored by the results screen's own code, so bands and scales match the check-in page.
  const checkIns = data ? toCheckIns(data.assessments) : [];
  const visibleCheckIns = showAllCheckIns ? checkIns : checkIns.slice(0, CHECK_INS_SHOWN);

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="py-10 sm:py-14">
        <BackLink className="mb-4" />
        {loading && (
          <div className="flex justify-center py-24 text-[#4A5750]">
            <Loader2 className="size-6 animate-spin" aria-label="Loading" />
          </div>
        )}

        {error && (
          <div className="rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">{error}</div>
        )}

        {!loading && data && (
          <div className="flex flex-col gap-8">
            <header className="flex flex-col gap-6">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">Dashboard</p>
                <h1 className="mt-2 font-heading text-3xl font-normal sm:text-4xl">
                  Welcome back{displayName ? `, ${displayName}` : ""}
                </h1>
              </div>
              <dl className="grid grid-cols-2 gap-3 sm:max-w-md">
                {conversations ? <StatTile icon={MessageCircle} value={conversations.length} label="Conversations" /> : null}
                <StatTile icon={ClipboardCheck} value={checkIns.length} label="Check-ins" />
              </dl>
            </header>

            <section aria-labelledby="quick-actions">
              <h2 id="quick-actions" className="mb-3 text-sm font-semibold text-[#4A5750]">
                Quick actions
              </h2>
              <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {QUICK_ACTIONS.map((action) => (
                  <li key={action.href}>
                    <Link
                      href={action.href}
                      className={`group flex h-full items-center gap-3 rounded-2xl border border-[#E5E8E1] bg-white p-4 transition-colors duration-150 hover:border-[#CBD4C5] hover:bg-[#F5F7F2] ${focusRing}`}
                    >
                      <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
                        <action.icon className="size-5" aria-hidden="true" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block text-sm font-semibold">{action.title}</span>
                        <span className="block text-xs text-[#4A5750]">{action.description}</span>
                      </span>
                      <RowChevron />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>

            <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
              <section className={cardClassName} aria-labelledby="your-check-ins">
                <DashboardSectionHeader icon={ClipboardCheck} title="Your check-ins" id="your-check-ins" />

                {checkIns.length === 0 ? (
                  <EmptyState
                    icon={ClipboardCheck}
                    title="No check-ins yet"
                    description="A short check-in helps you notice how you've been feeling."
                    action={
                      <Link href="/assessment" className={primaryButtonClassName}>
                        Take a 2-minute check-in
                      </Link>
                    }
                  />
                ) : (
                  <>
                    <ul id="check-in-list" className="flex flex-col divide-y divide-[#EEF0EA]">
                      {visibleCheckIns.map((checkIn) => (
                        <CheckInRow key={checkIn.saved.id} checkIn={checkIn} />
                      ))}
                    </ul>
                    {checkIns.length > CHECK_INS_SHOWN ? (
                      <button
                        type="button"
                        onClick={() => setShowAllCheckIns((shown) => !shown)}
                        aria-expanded={showAllCheckIns}
                        aria-controls="check-in-list"
                        className={`mt-4 cursor-pointer rounded-full px-1 text-sm font-medium text-[#233E33] underline underline-offset-4 transition-colors duration-150 hover:text-[#192E26] ${focusRing}`}
                      >
                        {showAllCheckIns ? "Show fewer" : `Show all ${checkIns.length}`}
                      </button>
                    ) : null}
                  </>
                )}
              </section>

              <section className={cardClassName} aria-labelledby="recent-conversations">
                <DashboardSectionHeader icon={MessageCircle} title="Recent conversations" id="recent-conversations" />

                {conversations === null ? (
                  <p className="text-sm text-[#4A5750]">Your conversations could not be loaded right now.</p>
                ) : conversations.length === 0 ? (
                  <EmptyState
                    icon={MessageCircle}
                    title="No conversations yet"
                    description="Neuro is here whenever you'd like to talk."
                  />
                ) : (
                  <ul className="flex flex-col gap-3">
                    {conversations.slice(0, 5).map((c) => (
                      <li key={c.sessionId}>
                        {/* The chat page loads this conversation from its URL, with its own not-found handling. */}
                        <Link
                          href={`/ai-companion/${encodeURIComponent(c.sessionId)}`}
                          className={`group flex items-center gap-3 rounded-2xl bg-[#FBF9F4] px-4 py-3 transition-colors duration-150 hover:bg-[#F1F4EE] ${focusRing}`}
                        >
                          <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-medium">{c.preview}</span>
                            <span className="block text-xs text-[#4A5750]">
                              <time dateTime={c.startedAt} title={formatDate(c.startedAt)}>
                                {formatRelative(c.startedAt)}
                              </time>{" "}
                              · {c.messageCount} message{c.messageCount === 1 ? "" : "s"}
                            </span>
                          </span>
                          <RowChevron />
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}

                <Link href="/ai-companion" className={`mt-5 ${secondaryButtonClassName}`}>
                  Talk with Neuro
                </Link>
              </section>
            </div>

            <AppointmentsSummary />
          </div>
        )}
      </Container>
    </div>
  );
}

function RowChevron() {
  return (
    <ChevronRight
      className="size-4 shrink-0 text-[#4A5750] transition-colors duration-150 group-hover:text-[#233E33]"
      aria-hidden="true"
    />
  );
}

function StatTile({ icon: Icon, value, label }: { icon: LucideIcon; value: number; label: string }) {
  return (
    <div className="flex items-center gap-3 rounded-2xl border border-[#E5E8E1] bg-white p-4 shadow-xs">
      <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
        <Icon className="size-5" aria-hidden="true" />
      </span>
      <div className="flex flex-col-reverse">
        <dt className="text-xs text-[#4A5750]">{label}</dt>
        <dd className="text-2xl font-semibold tabular-nums">{value}</dd>
      </div>
    </div>
  );
}

function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-start gap-3 rounded-2xl bg-[#FBF9F4] p-5">
      <span className="flex size-10 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
        <Icon className="size-5" aria-hidden="true" />
      </span>
      <div>
        <p className="text-sm font-semibold">{title}</p>
        <p className="text-sm text-[#4A5750]">{description}</p>
      </div>
      {action}
    </div>
  );
}

/** Instrument, date, band chip, score, where the score sits in the instrument's range, chevron. */
function CheckInRow({ checkIn: { saved, result } }: { checkIn: CheckIn }) {
  const meta = ASSESSMENTS[result.assessmentType];
  const min = SCORE_RANGES[result.assessmentType][0]?.min ?? 0;
  const ratio = result.maxScore > min ? (result.rawScore - min) / (result.maxScore - min) : 0;
  const percent = Math.max(0, Math.min(100, Math.round(ratio * 100)));

  return (
    <li>
      <Link
        href={checkInHref(saved.id)}
        className={`group -mx-3 flex items-center gap-3 rounded-2xl px-3 py-3.5 transition-colors duration-150 hover:bg-[#F5F7F2] ${focusRing}`}
      >
        <span className="flex min-w-0 flex-1 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
          <span className="min-w-0">
            <span className="block font-medium">
              {meta.label} <span className="font-normal text-[#4A5750]">· {meta.resultEyebrow}</span>
            </span>
            <span className="block text-xs text-[#4A5750]">{formatDate(saved.createdAt)}</span>
          </span>
          <span className="flex shrink-0 flex-wrap items-center gap-3">
            {saved.riskFlag && <AlertTriangle className="size-4 text-amber-700" aria-label="Worth following up" />}
            <span className="rounded-full bg-[#EEF3EC] px-3 py-1 text-xs font-medium text-[#233E33]">{result.level}</span>
            <span className="flex items-center gap-2">
              <span className="text-sm font-semibold tabular-nums">
                {result.rawScore}
                <span className="font-normal text-[#4A5750]">/{result.maxScore}</span>
              </span>
              {/* Decorative: the score is already stated as text. */}
              <span className="h-1.5 w-14 overflow-hidden rounded-full bg-[#E5E8E1]" aria-hidden="true">
                <span className="block h-full rounded-full bg-[#233E33]" style={{ width: `${percent}%` }} />
              </span>
            </span>
          </span>
        </span>
        <RowChevron />
      </Link>
    </li>
  );
}
