import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import Link from "next/link";
import { ChevronRight, Heart, Loader2, MessageCircle, RotateCcw, SearchX, TrendingUp } from "lucide-react";

import { AssessmentResultBackground } from "@/components/assessment/assessment-result-background";
import { ScoreGauge } from "@/components/assessment/score-gauge";
import { ScreeningToolDisclaimer } from "@/components/assessment/safety-result-state";
import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { ResourceEmptyState } from "@/components/resources/resource-empty-state";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { relaxationCatalog } from "@/data/relaxation";
import { articles } from "@/data/wellness-resources";
import {
  compareWithPrevious,
  isModerateOrAbove,
  levelFor,
  professionalReason,
  suggestionsFor,
  VARIABILITY_NOTE,
  type SuggestionRef,
} from "@/lib/assessments/insights";
import { SCORE_RANGES } from "@/lib/assessments/ranges";
import { ASSESSMENTS } from "@/lib/assessments/registry";
import { getSafetyGuidance } from "@/lib/assessments/safety";
import { clearAssessmentResult } from "@/lib/assessments/storage";
import type { AssessmentType } from "@/lib/assessments/types";
import { fetchDashboard, toCheckIns, type CheckIn } from "@/lib/dashboard-data";

type State = { status: "loading" } | { status: "error" } | { status: "ready"; checkIns: CheckIn[] };

const cardClassName = "rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8";
const linkCardClassName =
  "group flex items-center gap-3 rounded-xl border border-[#E5E8E1] bg-[#FBF9F4] p-4 outline-none transition-colors duration-150 hover:border-[#CBD4C5] hover:bg-[#F5F7F2] focus-visible:ring-3 focus-visible:ring-ring/50";
const primaryButtonClassName =
  "inline-flex w-full items-center justify-center gap-2 rounded-full bg-[#233E33] px-6 py-3 text-sm font-medium text-white outline-none transition-colors duration-150 hover:bg-[#192E26] focus-visible:ring-3 focus-visible:ring-ring/50 sm:w-auto cursor-pointer";
const secondaryButtonClassName =
  "inline-flex w-full items-center justify-center gap-2 rounded-full border border-[#C8CEBE] bg-white px-6 py-3 text-sm font-medium text-[#1C352D] outline-none transition-colors duration-150 hover:bg-[#F4F6F0] focus-visible:ring-3 focus-visible:ring-ring/50 sm:w-auto";

/** The results screen's own professional note, used where a band sentence wouldn't fit. */
const PROFESSIONAL_NOTE = "If things have felt hard to manage, talking with a mental health professional can help.";

function formatDate(value: string, withTime = true) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    ...(withTime ? { year: "numeric", hour: "numeric", minute: "2-digit" } : {}),
  });
}

/** Where Retake starts the instrument; WHO-5 opens in a dialog on the hub. */
function retakeHref(type: AssessmentType) {
  return type === "who5" ? "/assessment?start=who5" : ASSESSMENTS[type].route;
}

export default function CheckInResultPage() {
  const { id = "" } = useParams();
  const [state, setState] = useState<State>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    fetchDashboard()
      .then((data) => !cancelled && setState({ status: "ready", checkIns: toCheckIns(data.assessments) }))
      .catch(() => !cancelled && setState({ status: "error" }));
    return () => {
      cancelled = true;
    };
  }, []);

  // Only the signed-in user's own results are loaded, so an id that isn't theirs is simply not found.
  const current = state.status === "ready" ? state.checkIns.find((c) => c.saved.id === id) : undefined;

  return (
    <div className="min-h-screen text-[#1C352D]">
      <AssessmentResultBackground />
      <Container className="py-10 sm:py-14">
        <BackLink className="mb-4" />
        {state.status === "loading" ? (
          <div className="flex justify-center py-24 text-[#4A5750]">
            <Loader2 className="size-6 animate-spin" aria-label="Loading" />
          </div>
        ) : state.status === "error" ? (
          <div className="rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            We couldn&apos;t load this check-in. Please check the server is running and try again.
          </div>
        ) : current ? (
          <CheckInResult current={current} all={state.checkIns} />
        ) : (
          <ResourceEmptyState
            icon={SearchX}
            title="We couldn't find that check-in"
            description="Head back to your dashboard to see your check-ins."
            action={
              <Link href="/dashboard" className={`mt-2 ${primaryButtonClassName}`}>
                Go to dashboard
              </Link>
            }
          />
        )}
      </Container>
    </div>
  );
}

function CheckInResult({ current, all }: { current: CheckIn; all: CheckIn[] }) {
  const navigate = useNavigate();
  const { result, saved } = current;
  const type = result.assessmentType;
  const meta = ASSESSMENTS[type];
  // A high-risk result keeps the whole page neutral: no reassuring copy, numbers only.
  const atRisk = saved.riskFlag || result.safetyFlag;

  // Same instrument, oldest first; the previous result is the one just before this one.
  const history = all.filter((c) => c.result.assessmentType === type).reverse();
  const index = history.findIndex((c) => c.saved.id === saved.id);
  const previous = index > 0 ? history[index - 1] : null;

  const level = levelFor(type, result.levelKey);
  const previousLevel = previous ? levelFor(type, previous.result.levelKey) : null;
  const showProfessional = atRisk || isModerateOrAbove(level);
  const reason =
    atRisk && !isModerateOrAbove(level)
      ? PROFESSIONAL_NOTE
      : professionalReason(type, level, result.level, previousLevel, atRisk);
  const suggestions = atRisk ? [] : suggestionsFor(type, level);

  const handleRetake = () => {
    // As on the results screen: a fresh start for the questionnaire.
    clearAssessmentResult(type);
    navigate(retakeHref(type));
  };

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-8">
      {atRisk ? <CrisisResourcesBox countryCode={getSafetyGuidance().crisisResources.countryCode} showProfessionalsLink /> : null}

      {/* 1. Result */}
      <section aria-labelledby="check-in-heading">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <span className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">
            {`${meta.label} • ${meta.resultEyebrow}`}
          </span>
          <h1 id="check-in-heading" className="font-heading text-3xl font-normal leading-tight sm:text-4xl">
            {result.snapshotTitle}
          </h1>
          <p className="text-sm text-[#4A5750]">{formatDate(saved.createdAt)}</p>
        </div>
        <div className={`flex flex-col items-center gap-8 sm:flex-row sm:gap-10 ${cardClassName} sm:p-10`}>
          <ScoreGauge rawScore={result.rawScore} maxScore={result.maxScore} tone={result.tone} />
          <div className="flex flex-1 flex-col items-center gap-3 text-center sm:items-start sm:text-left">
            <span className="rounded-full bg-[#ECEFE6] px-4 py-1 text-xs font-semibold uppercase tracking-[0.15em] text-[#233E33]">
              {result.level}
            </span>
            <p className="text-sm leading-relaxed text-[#4A5750] sm:text-base">
              {atRisk ? getSafetyGuidance().guidance : result.interpretation}
            </p>
          </div>
        </div>
      </section>

      {/* 3. Your progress */}
      <section className={cardClassName} aria-labelledby="progress-heading">
        <div className="mb-5 flex items-center gap-2">
          <TrendingUp className="size-5 text-[#233E33]" aria-hidden="true" />
          <h2 id="progress-heading" className="text-lg font-semibold">
            Your progress
          </h2>
        </div>
        {history.length > 1 ? <TrendBars type={type} history={history} currentId={saved.id} numericOnly={atRisk} /> : null}
        <p className="text-sm leading-relaxed text-[#1C352D]">
          {compareWithPrevious(type, result.rawScore, previous ? previous.result.rawScore : null, atRisk)}
        </p>
        <p className="mt-2 text-sm leading-relaxed text-[#4A5750]">{VARIABILITY_NOTE}</p>
      </section>

      {/* 4. Recommended for you */}
      {suggestions.length > 0 || showProfessional ? (
        <section className={cardClassName} aria-labelledby="recommended-heading">
          <h2 id="recommended-heading" className="mb-5 text-lg font-semibold">
            Recommended for you
          </h2>
          <ul className="flex flex-col gap-3">
            {suggestions.map((ref) => (
              <SuggestionItem key={ref.kind === "article" ? ref.slug : ref.key} suggestion={ref} />
            ))}
            {showProfessional ? (
              <li>
                <Link href="/find-professional" className={linkCardClassName}>
                  <Heart className="size-5 shrink-0 text-[#233E33]" aria-hidden="true" />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium sm:text-base">Talk to a professional</span>
                    <span className="block text-sm leading-relaxed text-[#4A5750]">{reason}</span>
                  </span>
                  <Chevron />
                </Link>
              </li>
            ) : null}
          </ul>
        </section>
      ) : null}

      {/* 5. Actions */}
      <div className="flex flex-col items-center justify-center gap-3 sm:flex-row">
        <button type="button" onClick={handleRetake} className={primaryButtonClassName}>
          <RotateCcw className="size-4" aria-hidden="true" />
          Retake
        </button>
        <Link href="/ai-companion" className={secondaryButtonClassName}>
          <MessageCircle className="size-4" aria-hidden="true" />
          Talk with Neuro
        </Link>
      </div>

      <ScreeningToolDisclaimer label={meta.label} />
    </div>
  );
}

function Chevron() {
  return (
    <ChevronRight
      className="size-4 shrink-0 text-[#4A5750] transition-colors duration-150 group-hover:text-[#233E33]"
      aria-hidden="true"
    />
  );
}

/** An existing Relaxation item or article, with its real title and route. Unknown refs are skipped. */
function SuggestionItem({ suggestion }: { suggestion: SuggestionRef }) {
  let item: { title: string; detail: string; href: string } | null = null;
  if (suggestion.kind === "relaxation") {
    const found = relaxationCatalog.find((candidate) => candidate.key === suggestion.key);
    if (found) item = { title: found.title, detail: `${found.category} · ${found.duration}`, href: found.href };
  } else {
    const found = articles.find((candidate) => candidate.slug === suggestion.slug);
    if (found) {
      item = {
        title: found.title,
        detail: `Article · ${found.readingTimeMinutes} min read`,
        href: `/resources/articles/${found.slug}`,
      };
    }
  }
  if (!item) return null;
  return (
    <li>
      <Link href={item.href} className={linkCardClassName}>
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-medium sm:text-base">{item.title}</span>
          <span className="block text-xs text-[#4A5750]">{item.detail}</span>
        </span>
        <Chevron />
      </Link>
    </li>
  );
}

/** Plain CSS bars, oldest to newest, this result highlighted. A text list carries the same data. */
function TrendBars({
  type,
  history,
  currentId,
  numericOnly,
}: {
  type: AssessmentType;
  history: CheckIn[];
  currentId: string;
  numericOnly: boolean;
}) {
  const min = SCORE_RANGES[type][0]?.min ?? 0;
  const max = history[0].result.maxScore;
  const label = ASSESSMENTS[type].label;
  return (
    <figure className="mb-5">
      <div className="overflow-x-auto" aria-hidden="true">
        <div className="flex min-w-fit items-end gap-2 sm:gap-3">
          {history.map((c) => {
            const isCurrent = c.saved.id === currentId;
            const ratio = max > min ? (c.result.rawScore - min) / (max - min) : 0;
            return (
              <div key={c.saved.id} className="flex w-10 shrink-0 flex-col items-center gap-1">
                <span className={`text-xs tabular-nums ${isCurrent ? "font-semibold text-[#1C352D]" : "text-[#4A5750]"}`}>
                  {c.result.rawScore}
                </span>
                <div className="flex h-28 w-6 items-end">
                  <div
                    className={`w-full rounded-t-md ${isCurrent ? "bg-[#233E33]" : "bg-[#C8D3C4]"}`}
                    style={{ height: `${Math.max(4, Math.round(ratio * 100))}%` }}
                  />
                </div>
                <span className="text-[11px] whitespace-nowrap text-[#4A5750]">{formatDate(c.saved.createdAt, false)}</span>
              </div>
            );
          })}
        </div>
      </div>
      <figcaption className="mt-2 text-xs text-[#4A5750]">
        Your {label} scores over time, out of {max}.
        {numericOnly ? null : ` ${ASSESSMENTS[type].higherIsBetter ? "Higher" : "Lower"} scores are the better direction.`}
      </figcaption>
      <ul className="sr-only">
        {history.map((c) => (
          <li key={c.saved.id}>
            {formatDate(c.saved.createdAt)}: {c.result.rawScore} out of {max}
            {c.saved.id === currentId ? " (this check-in)" : ""}
          </li>
        ))}
      </ul>
    </figure>
  );
}
