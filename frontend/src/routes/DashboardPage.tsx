import { useEffect, useState } from "react";
import Link from "next/link";
import { useUser } from "@clerk/clerk-react";
import { AlertTriangle, ClipboardCheck, Loader2, MessageCircle } from "lucide-react";
import { AppointmentsSummary } from "@/components/dashboard/appointments-summary";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { apiFetch } from "@/lib/api";

interface DashboardAssessment {
  id: string;
  instrument: string;
  score: number;
  severityBand: string;
  riskFlag: boolean;
  createdAt: string;
}

interface DashboardData {
  name: string;
  email: string;
  totalSessions: number;
  assessments: DashboardAssessment[];
}

interface ConversationSummary {
  sessionId: string;
  startedAt: string;
  preview: string;
  messageCount: number;
}

const INSTRUMENTS: Record<string, { label: string; max: number; route: string }> = {
  WHO5: { label: "WHO-5 Wellbeing", max: 100, route: "/assessment" },
  PHQ9: { label: "PHQ-9 Mood", max: 27, route: "/assessment/phq9" },
  GAD7: { label: "GAD-7 Anxiety", max: 21, route: "/assessment/gad7" },
  PSS10: { label: "PSS-10 Stress", max: 40, route: "/assessment/pss10" },
  K10: { label: "K10 Distress", max: 50, route: "/assessment" },
};

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

export default function DashboardPage() {
  const { user } = useUser();
  const [data, setData] = useState<DashboardData | null>(null);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    Promise.all([
      apiFetch("/me/dashboard").then((res) => {
        if (!res.ok) throw new Error(String(res.status));
        return res.json() as Promise<DashboardData>;
      }),
      apiFetch("/me/conversations")
        .then((res) => (res.ok ? (res.json() as Promise<ConversationSummary[]>) : []))
        .catch(() => []),
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
            <header>
              <p className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">Dashboard</p>
              <h1 className="mt-2 font-heading text-3xl font-normal sm:text-4xl">
                Welcome back{displayName ? `, ${displayName}` : ""}
              </h1>
              <p className="mt-2 text-sm text-[#4A5750]">
                {data.totalSessions} session{data.totalSessions === 1 ? "" : "s"} so far ·{" "}
                {data.assessments.length} check-in{data.assessments.length === 1 ? "" : "s"}
              </p>
            </header>

            <div className="grid gap-6 lg:grid-cols-[3fr_2fr]">
              <section className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
                <div className="mb-5 flex items-center gap-2">
                  <ClipboardCheck className="size-5 text-[#233E33]" />
                  <h2 className="text-lg font-semibold">Your check-ins</h2>
                </div>

                {data.assessments.length === 0 ? (
                  <div className="flex flex-col items-start gap-3">
                    <p className="text-sm text-[#4A5750]">
                      You haven&apos;t taken a check-in yet. A short WHO-5 wellbeing check-in takes about two minutes.
                    </p>
                    <Link
                      href="/assessment"
                      className="inline-flex items-center rounded-full bg-[#233E33] px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-[#192E26]"
                    >
                      Take a check-in
                    </Link>
                  </div>
                ) : (
                  <ul className="flex flex-col divide-y divide-[#EEF0EA]">
                    {data.assessments.map((a) => {
                      const meta = INSTRUMENTS[a.instrument];
                      return (
                        <li key={a.id} className="flex items-center justify-between gap-4 py-3.5">
                          <div className="min-w-0">
                            <p className="font-medium">{meta?.label ?? a.instrument}</p>
                            <p className="text-xs text-[#4A5750]">{formatDate(a.createdAt)}</p>
                          </div>
                          <div className="flex shrink-0 items-center gap-3">
                            {a.riskFlag && (
                              <AlertTriangle className="size-4 text-amber-600" aria-label="Worth following up" />
                            )}
                            <span className="rounded-full bg-[#EEF3EC] px-3 py-1 text-xs font-medium capitalize text-[#233E33]">
                              {a.severityBand}
                            </span>
                            <span className="w-14 text-right text-sm font-semibold tabular-nums">
                              {a.score}
                              {meta ? <span className="font-normal text-[#4A5750]">/{meta.max}</span> : null}
                            </span>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>

              <section className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
                <div className="mb-5 flex items-center gap-2">
                  <MessageCircle className="size-5 text-[#233E33]" />
                  <h2 className="text-lg font-semibold">Recent conversations</h2>
                </div>

                {conversations.length === 0 ? (
                  <p className="text-sm text-[#4A5750]">No conversations with Neuro yet.</p>
                ) : (
                  <ul className="flex flex-col gap-3">
                    {conversations.slice(0, 5).map((c) => (
                      <li key={c.sessionId} className="rounded-2xl bg-[#FBF9F4] px-4 py-3">
                        <p className="truncate text-sm font-medium">{c.preview}</p>
                        <p className="text-xs text-[#4A5750]">
                          {formatDate(c.startedAt)} · {c.messageCount} messages
                        </p>
                      </li>
                    ))}
                  </ul>
                )}

                <Link
                  href="/ai-companion"
                  className="mt-5 inline-flex items-center rounded-full border border-[#233E33]/20 px-5 py-2.5 text-sm font-medium text-[#233E33] transition-colors hover:bg-[#233E33]/5"
                >
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
