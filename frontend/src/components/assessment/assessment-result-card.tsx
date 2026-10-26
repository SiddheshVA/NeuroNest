import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@clerk/clerk-react";
import { ChevronDown, Compass, MessageCircle, Sparkles } from "lucide-react";
import { ScoreGauge } from "@/components/assessment/score-gauge";
import { ScoreRangeCard } from "@/components/assessment/score-range-card";
import { SupportCard } from "@/components/assessment/support-card";
import { AssessmentSourceNote } from "@/components/assessment/assessment-source-note";
import { getCrisisResources } from "@/lib/ai/crisis-resources";
import {
  ASSESSMENTS,
  EXPLORATION_OPTIONS,
  GENERAL_CHECKIN_OPTION,
  TARGETED_ASSESSMENT_TYPES,
} from "@/lib/assessments/registry";
import { getRecommendations, type RecommendationItem } from "@/lib/assessments/recommendations";
import { SCORE_RANGES } from "@/lib/assessments/ranges";
import { getSafetyGuidance } from "@/lib/assessments/safety";
import type { ServerResultState } from "@/lib/assessments/storage";
import type { ServerCrisisResource } from "@/lib/assessments/sync";
import type { AnyAssessmentResult, AssessmentType } from "@/lib/assessments/types";
import { fetchDashboard, toCheckIn } from "@/lib/dashboard-data";

interface AssessmentResultCardProps {
  result: AnyAssessmentResult;
  /** The backend's answer for this result: its riskFlag and crisis lines. */
  server: ServerResultState;
  onRetake: () => void;
}

const PRIMARY_BUTTON =
  "inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-full bg-[#233E33] px-6 py-3 text-sm font-medium text-white outline-none transition-colors duration-150 hover:bg-[#192E26] focus-visible:ring-3 focus-visible:ring-[#233E33]/40 focus-visible:ring-offset-2 sm:w-auto";
const QUIET_LINK =
  "inline-flex min-h-11 items-center justify-center rounded-full px-3 text-sm font-medium text-[#233E33] underline-offset-4 outline-none transition-colors duration-150 hover:underline focus-visible:ring-3 focus-visible:ring-[#233E33]/40 cursor-pointer";
const FIND_PROFESSIONAL_HREF = "/professionals";

// Bands at "moderate" or above, in each instrument's own band keys (see ranges.ts). WHO-5 never
// points to a professional directly: a low score is a reason to explore further, not a verdict.
const MODERATE_OR_HIGHER: Record<AssessmentType, string[]> = {
  who5: [],
  gad7: ["moderate", "severe"],
  phq9: ["moderate", "moderately-severe", "severe"],
  pss10: ["moderate", "high"],
  k10: ["moderate", "severe"],
  sci: [],
};

// The crisis lines from the backend; when its answer never came, the same lines from the app's own list.
function supportResources(server: ServerResultState): ServerCrisisResource[] {
  if (server.status === "saved" && server.response.crisisResources?.length) {
    return server.response.crisisResources;
  }
  return getCrisisResources().helplines.map((h) => ({ name: h.label, phone: h.numbers.join(" or "), availability: null }));
}

/**
 * Reusable NeuroNest assessment result presentation — same layout for every screening (WHO-5,
 * GAD-7, PHQ-9, PSS-10, K10, SCI), in two variants chosen by the backend's riskFlag (or a local
 * PHQ-9 item 9 answer): a flagged result leads with one calm support card; any other result keeps
 * the usual flow with a single primary next step. All interpretation copy and recommendation logic
 * is computed elsewhere (scoring modules + the centralized recommendation service); this component
 * only renders it.
 */
export function AssessmentResultCard({ result, server, onRetake }: AssessmentResultCardProps) {
  const meta = ASSESSMENTS[result.assessmentType];
  const riskFlag = result.safetyFlag || (server.status === "saved" && server.response.riskFlag);
  const waiting = server.status === "pending" && !riskFlag;

  const recommendations = getRecommendations({
    assessmentType: result.assessmentType,
    score: result.rawScore,
    level: result.levelKey,
    safetyFlag: result.safetyFlag,
    suggestProfessionalSupport: result.suggestProfessionalSupport,
  });

  const showExplorationPrompt = result.assessmentType === "who5" && result.suggestFurtherExploration;
  const showTakeAnotherCheckIn = result.assessmentType !== "who5";

  return (
    <div className="mx-auto max-w-3xl">
      {/* Header */}
      <div className="mb-8 flex flex-col items-center gap-2 text-center">
        <span className="text-xs font-semibold uppercase tracking-[0.2em] text-[#4F624F]">
          {`${meta.label} • ${meta.resultEyebrow}`}
        </span>
        <h1 className="font-heading text-3xl font-normal leading-tight text-[#1C352D] sm:text-4xl">
          {result.snapshotTitle}
        </h1>
      </div>

      {/* Score */}
      <div className="mb-3 flex flex-col items-center gap-8 rounded-[28px] border border-[#E5E8E1] bg-white p-8 shadow-xs sm:flex-row sm:gap-10 sm:p-10">
        <ScoreGauge rawScore={result.rawScore} maxScore={result.maxScore} tone={result.tone} />
        <div className="flex flex-1 flex-col items-center gap-3 text-center sm:items-start sm:text-left">
          <span className="rounded-full bg-[#ECEFE6] px-4 py-1 text-xs font-semibold uppercase tracking-[0.15em] text-[#233E33]">
            {result.level}
          </span>
          <p className="text-sm leading-relaxed text-[#4A5750] sm:text-base">{result.interpretation}</p>
        </div>
      </div>

      {/* Disclaimer and the last result, right under the score */}
      <div className="mb-8 flex flex-col items-center gap-1 px-2 text-center">
        <p className="text-sm leading-relaxed text-[#4A5750]">
          This result is a snapshot of how you&apos;ve been feeling. It isn&apos;t a diagnosis.
        </p>
        <PreviousResult type={result.assessmentType} server={server} />
      </div>

      {waiting ? (
        // Held for a moment while the backend answers, so the page doesn't rearrange itself.
        <div aria-busy="true" className="mb-8 min-h-[24rem] rounded-[28px] border border-[#E5E8E1] bg-white/60 p-6 sm:p-8">
          <p className="text-sm text-[#4A5750]">Getting your result ready…</p>
        </div>
      ) : riskFlag ? (
        <>
          <SupportCard
            resources={supportResources(server)}
            note={result.safetyFlag ? getSafetyGuidance().acknowledgement : undefined}
          />
          <p className="-mt-5 mb-8 px-2 text-center text-sm leading-relaxed text-[#4A5750]">
            {getSafetyGuidance().notEmergencyServiceNotice}
          </p>
          <ScoreRangeCard ranges={SCORE_RANGES[result.assessmentType]} currentScore={result.rawScore} />
          <GentleIdeas recommendations={recommendations} />
          {showExplorationPrompt && <ExplorationPrompt />}
          {showTakeAnotherCheckIn && (
            <details className="group mb-8 rounded-[28px] border border-[#E5E8E1] bg-white shadow-xs">
              <summary className="flex min-h-11 cursor-pointer list-none items-center justify-between gap-3 rounded-[28px] px-6 py-4 text-sm font-medium text-[#1C352D] outline-none focus-visible:ring-3 focus-visible:ring-[#233E33]/40 sm:px-8 [&::-webkit-details-marker]:hidden">
                More check-ins (optional)
                <ChevronDown className="size-4 shrink-0 text-[#4F624F] group-open:rotate-180" aria-hidden="true" />
              </summary>
              <div className="px-6 pb-6 sm:px-8 sm:pb-8">
                <OtherCheckIns current={result.assessmentType} />
              </div>
            </details>
          )}
          <nav aria-label="More options" className="mb-8 flex flex-col items-center justify-center gap-1 sm:flex-row sm:gap-4">
            <button type="button" onClick={onRetake} className={QUIET_LINK}>
              Retake this check-in
            </button>
            <Link href="/assessment" className={QUIET_LINK}>
              Return to Assessment Hub
            </Link>
          </nav>
        </>
      ) : (
        <>
          <ScoreRangeCard ranges={SCORE_RANGES[result.assessmentType]} currentScore={result.rawScore} />
          <GentleIdeas recommendations={recommendations} />

          {/* Professional support — one short, calm note, never repeated elsewhere */}
          {result.suggestProfessionalSupport && (
            <div className="mb-8 rounded-[28px] border border-[#E5E8E1] bg-[#ECEFE6] p-6 sm:p-8">
              <p className="text-sm leading-relaxed text-[#1C352D] sm:text-base">
                If things have felt hard to manage, talking with a mental health professional can help.
              </p>
            </div>
          )}

          {showExplorationPrompt && <ExplorationPrompt />}

          {/* Take another relevant check-in (targeted results only) */}
          {showTakeAnotherCheckIn && (
            <section
              aria-labelledby="other-checkins-heading"
              className="mb-8 rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8"
            >
              <div className="mb-5 flex items-center gap-2">
                <Compass className="size-5 text-[#4F624F]" aria-hidden="true" />
                <h2 id="other-checkins-heading" className="font-heading text-lg font-normal text-[#1C352D] sm:text-xl">
                  Want to check in on something else?
                </h2>
              </div>
              <OtherCheckIns current={result.assessmentType} />
            </section>
          )}

          {/* One clear next step, then quiet links */}
          <div className="mb-8 flex flex-col items-center gap-3">
            <PrimaryAction result={result} />
            <nav aria-label="More options" className="flex flex-col items-center justify-center gap-1 sm:flex-row sm:flex-wrap sm:gap-4">
              {MODERATE_OR_HIGHER[result.assessmentType].includes(result.levelKey) && (
                <Link href={FIND_PROFESSIONAL_HREF} className={QUIET_LINK}>
                  Find a licensed professional
                </Link>
              )}
              <button type="button" onClick={onRetake} className={QUIET_LINK}>
                Retake this check-in
              </button>
              <Link href="/assessment" className={QUIET_LINK}>
                Return to Assessment Hub
              </Link>
            </nav>
          </div>
        </>
      )}

      <AssessmentSourceNote type={result.assessmentType} className="mx-auto mb-4 max-w-xl text-center" />
    </div>
  );
}

// "Open Relaxation Hub" for the calmest band, "Talk with Neuro" otherwise.
function PrimaryAction({ result }: { result: AnyAssessmentResult }) {
  if (result.tone === "positive") {
    return (
      <Link href="/relaxation" className={PRIMARY_BUTTON}>
        <Sparkles className="size-4" aria-hidden="true" />
        Open Relaxation Hub
      </Link>
    );
  }
  return (
    <Link href="/ai-companion" className={PRIMARY_BUTTON}>
      <MessageCircle className="size-4" aria-hidden="true" />
      Talk with Neuro
    </Link>
  );
}

/** "A few gentle ideas": each card leads to its page (when that page exists). */
function GentleIdeas({ recommendations }: { recommendations: RecommendationItem[] }) {
  if (recommendations.length === 0) return null;
  return (
    <section
      aria-labelledby="gentle-ideas-heading"
      className="mb-8 rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8"
    >
      <h2 id="gentle-ideas-heading" className="mb-5 font-heading text-lg font-normal text-[#1C352D] sm:text-xl">
        A few gentle ideas
      </h2>
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {recommendations.map((recommendation) => {
          const body = (
            <>
              <span className="mb-1 block text-sm font-medium text-[#1C352D] sm:text-base">{recommendation.title}</span>
              <span className="block text-sm leading-relaxed text-[#4A5750]">{recommendation.description}</span>
            </>
          );
          return (
            <li key={recommendation.kind} className="flex">
              {recommendation.href ? (
                <Link
                  href={recommendation.href}
                  className="block w-full rounded-xl border border-[#E5E8E1] bg-[#FBF9F4] p-4 outline-none transition-colors duration-150 hover:border-[#CBD4C5] hover:bg-[#F5F7F2] focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
                >
                  {body}
                </Link>
              ) : (
                <div className="w-full rounded-xl border border-[#E5E8E1] bg-[#FBF9F4] p-4">{body}</div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** WHO-5 branching: explore what's been affecting you. */
function ExplorationPrompt() {
  return (
    <section
      aria-labelledby="explore-heading"
      className="mb-8 rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8"
    >
      <h2 id="explore-heading" className="mb-2 font-heading text-lg font-normal text-[#1C352D] sm:text-xl">
        Want to explore what&apos;s been affecting you?
      </h2>
      <p className="mb-6 text-sm leading-relaxed text-[#4A5750] sm:text-base">
        Totally optional — pick whatever feels closest, or skip it for now.
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {EXPLORATION_OPTIONS.map((option) => (
          <Link
            key={option.type}
            href={ASSESSMENTS[option.type].route}
            className="flex flex-col gap-1 rounded-xl border border-[#E5E8E1] bg-[#FBF9F4] p-4 outline-none transition-colors duration-150 hover:border-[#CBD4C5] hover:bg-[#F5F7F2] focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
          >
            <TestChip type={option.type} />
            <span className="font-medium text-[#1C352D]">{option.label}</span>
            <span className="text-sm text-[#4A5750]">{option.description}</span>
            <TestLength type={option.type} />
          </Link>
        ))}
        <Link
          href={ASSESSMENTS[GENERAL_CHECKIN_OPTION.type].route}
          className="flex flex-col items-start gap-1 rounded-xl border border-dashed border-[#CBD4C5] bg-white p-4 text-left outline-none transition-colors duration-150 hover:border-[#233E33] focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
        >
          <TestChip type={GENERAL_CHECKIN_OPTION.type} />
          <span className="font-medium text-[#1C352D]">{GENERAL_CHECKIN_OPTION.label}</span>
          <span className="text-sm text-[#4A5750]">{GENERAL_CHECKIN_OPTION.description}</span>
          <TestLength type={GENERAL_CHECKIN_OPTION.type} />
        </Link>
      </div>
    </section>
  );
}

function OtherCheckIns({ current }: { current: AssessmentType }) {
  return (
    <>
      <p className="mb-5 text-sm leading-relaxed text-[#4A5750] sm:text-base">
        Entirely optional — only if it feels useful.
      </p>
      <div className="flex flex-wrap gap-3">
        {TARGETED_ASSESSMENT_TYPES.filter((type) => type !== current).map((type) => (
          <Link
            key={type}
            href={ASSESSMENTS[type].route}
            className="inline-flex min-h-11 items-center rounded-full border border-[#E5E8E1] bg-[#FBF9F4] px-4 py-2 text-sm font-medium text-[#1C352D] outline-none transition-colors duration-150 hover:border-[#CBD4C5] hover:bg-[#F5F7F2] focus-visible:ring-3 focus-visible:ring-[#233E33]/40"
          >
            {ASSESSMENTS[type].fullName}
          </Link>
        ))}
      </div>
    </>
  );
}

/**
 * "Last time: 14 (Moderate)" for a signed-in person with an earlier result for the same check-in,
 * from the dashboard endpoint. Waits for this result to be saved, so it can be told apart from the
 * earlier ones. Keeps its line's height while loading for signed-in people, so nothing jumps.
 */
function PreviousResult({ type, server }: { type: AssessmentType; server: ServerResultState }) {
  const { isLoaded, isSignedIn } = useAuth();
  const currentId = server.status === "saved" ? server.response.id : null;
  const [found, setFound] = useState<{ id: string; text: string | null } | null>(null);

  useEffect(() => {
    if (!isSignedIn || !currentId) return;
    let cancelled = false;
    fetchDashboard()
      .then((data) => {
        const earlier = data.assessments.find((a) => a.instrument === type.toUpperCase() && a.id !== currentId);
        const checkIn = earlier ? toCheckIn(earlier) : null;
        const text = checkIn ? `Last time: ${checkIn.result.rawScore} (${checkIn.result.level})` : null;
        if (!cancelled) setFound({ id: currentId, text });
      })
      .catch(() => {
        if (!cancelled) setFound({ id: currentId, text: null });
      });
    return () => {
      cancelled = true;
    };
  }, [isSignedIn, currentId, type]);

  if (!isLoaded || !isSignedIn || server.status === "unavailable") return null;
  const text = found && found.id === currentId ? found.text : null;
  return <p className="min-h-5 text-sm text-[#4A5750]">{text}</p>;
}

function TestChip({ type }: { type: AssessmentType }) {
  return (
    <span className="mb-1 self-start rounded-full bg-[#ECEFE6] px-2.5 py-0.5 text-xs font-semibold text-[#233E33]">
      {ASSESSMENTS[type].label}
    </span>
  );
}

function TestLength({ type }: { type: AssessmentType }) {
  const meta = ASSESSMENTS[type];
  return (
    <span className="mt-1 text-xs text-[#4A5750]">
      {meta.questionCount} questions • ~{meta.minutes} min
    </span>
  );
}
