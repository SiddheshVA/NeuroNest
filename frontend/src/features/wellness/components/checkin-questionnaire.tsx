import { useEffect, useId, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Check, ChevronDown, Loader2, RotateCcw } from "lucide-react";

import { ERRORS, INSIGHT, NAV, OPTIONAL, PAGE, Q, STEPS } from "../copy.ts";
import {
  AGE_MAX,
  AGE_MIN,
  AGE_START,
  DIET_OPTIONS,
  MOVEMENT_OPTIONS,
  SEGMENTS,
  SLEEP_OPTIONS,
  WORK_STUDY_HOURS_OPTIONS,
  missingInStep,
  type Answers,
  type ChipOption,
  type FamilyHistoryAnswer,
} from "../questionnaire.ts";
import { weeklyMinutes } from "../results.ts";
import { AgeStepper, ChipGroup, Question, ScaleButtons, type Chip } from "./controls.tsx";
import { card, eyebrow, fine, focusRing, ghostButton, h1, h2, primaryButton } from "./styles.ts";
import { InsightPanel } from "./ui.tsx";

type FieldErrors = Partial<Record<keyof Answers, string>>;

function chips<V>(options: ChipOption<V>[], labels: Record<string, string>): Chip<V>[] {
  return options.map((o) => ({ key: o.id, value: o.value, label: labels[o.id] ?? o.id }));
}

function scaleChips(labels: readonly string[]): Chip<number>[] {
  return labels.map((label, i) => ({ key: String(i + 1), value: i + 1, label }));
}

/** Optional chips start with Skip (null): skipped answers are left out of the request. */
function withSkip<V>(list: Chip<V>[]): Chip<V | null>[] {
  return [{ key: "skip", value: null, label: NAV.skip }, ...list];
}

const OPTIONAL_KEYS: (keyof Answers)[] = ["workStudyHours", "dietaryHabits", "familyHistory"];

const FAMILY_CHIPS: Chip<FamilyHistoryAnswer>[] = [
  { key: "yes", value: "yes", label: OPTIONAL.familyHistory.options.yes },
  { key: "no", value: "no", label: OPTIONAL.familyHistory.options.no },
  { key: "prefer_not", value: "prefer_not", label: NAV.preferNotToSay },
];

/** The left column: eyebrow, the one-line promise and the four steps (done / current / upcoming). */
function StepAside({ step }: { step: number }) {
  return (
    <aside className="flex w-full max-w-[270px] flex-[1_1_220px] flex-col gap-5 max-[600px]:max-w-none">
      <div className="flex flex-col gap-2">
        <p className={eyebrow}>{PAGE.eyebrow}</p>
        <p className="text-[15px] leading-relaxed text-(--wn-ink-2)">{PAGE.promise}</p>
      </div>
      <ol aria-label={PAGE.stepsLabel} className="flex flex-col gap-2 max-[600px]:grid max-[600px]:grid-cols-2">
        {STEPS.map((s, i) => {
          const done = i < step;
          const current = i === step;
          return (
            <li
              key={s.title}
              aria-current={current ? "step" : undefined}
              className={`flex items-center gap-3 rounded-[14px] border px-3 py-2.5 ${
                current
                  ? "border-(--wn-line) bg-(--wn-surface) shadow-[0_1px_2px_rgba(15,42,46,.06)]"
                  : "border-transparent"
              }`}
            >
              <span
                aria-hidden="true"
                className={`flex size-8 shrink-0 items-center justify-center rounded-full text-sm font-bold ${
                  done
                    ? "bg-(--wn-brand) text-white"
                    : current
                      ? "bg-(--wn-surface) text-(--wn-brand) ring-2 ring-(--wn-brand)"
                      : "border-[1.5px] border-(--wn-line) text-(--wn-ink-2)"
                }`}
              >
                {done ? <Check className="size-4" strokeWidth={2.5} /> : i + 1}
              </span>
              <span className={`text-[15px] ${current ? "font-bold text-(--wn-ink)" : "font-medium text-(--wn-ink-2)"}`}>
                {s.title}
                {done ? <span className="sr-only"> ({NAV.stepDone})</span> : null}
              </span>
            </li>
          );
        })}
      </ol>
    </aside>
  );
}

/** Live feedback for the current step, from the user's own answers and the two documented guidelines only. */
function StepInsight({ step, answers }: { step: number; answers: Answers }) {
  if (step === 0) {
    return answers.movementMinutes === null ? (
      <InsightPanel>{INSIGHT.movementPrompt}</InsightPanel>
    ) : (
      <InsightPanel title={INSIGHT.movementTitle}>{INSIGHT.movement(weeklyMinutes(answers.movementMinutes))}</InsightPanel>
    );
  }
  if (step === 1) {
    if (answers.sleepHours === null) {
      return <InsightPanel title={INSIGHT.sleepPromptTitle}>{INSIGHT.sleepPrompt}</InsightPanel>;
    }
    const option = SLEEP_OPTIONS.find((o) => o.value === answers.sleepHours);
    const chipLabel = option ? (Q.sleepHours.options as Record<string, string>)[option.id] : `${answers.sleepHours} h`;
    const word = answers.rested === null ? null : INSIGHT.restedWord(answers.rested);
    return <InsightPanel title={INSIGHT.sleepTitle(chipLabel, word)}>{INSIGHT.sleep(answers.sleepHours)}</InsightPanel>;
  }
  if (step === 2) return <InsightPanel title={INSIGHT.loadTitle}>{INSIGHT.load}</InsightPanel>;
  return <InsightPanel title={INSIGHT.stressTitle}>{INSIGHT.stress}</InsightPanel>;
}

/**
 * The four-step questionnaire: the step list on the left, one card on the right. Answers live only in the
 * parent's React state (never in browser storage or the console). Field errors from the API are shown inline
 * under their question.
 */
export function CheckinQuestionnaire({
  answers,
  onChange,
  step,
  onStep,
  onSubmit,
  submitting,
  fieldErrors,
  banner,
  onRetry,
  optionalOpenAtStart = false,
}: {
  answers: Answers;
  onChange: (patch: Partial<Answers>) => void;
  step: number;
  onStep: (step: number) => void;
  onSubmit: () => void;
  submitting: boolean;
  fieldErrors: FieldErrors;
  /** A friendly error above the buttons (503, network); the answers stay. */
  banner: string | null;
  onRetry?: () => void;
  /** Opens "Sharpen your result" straight away (coming from the result's "Add it now"). */
  optionalOpenAtStart?: boolean;
}) {
  const [showMissing, setShowMissing] = useState(false);
  const [optionalOpen, setOptionalOpen] = useState(optionalOpenAtStart);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const firstRender = useRef(true);
  const optionalId = useId();
  const total = STEPS.length;
  const last = step === total - 1;
  const s = answers.segment;

  // Move focus to the new step's heading, so keyboard and screen-reader users start at the top of it.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    headingRef.current?.focus();
  }, [step]);

  // The optional section is also open whenever the API rejected one of its answers.
  const optionalShown = optionalOpen || OPTIONAL_KEYS.some((k) => fieldErrors[k]);

  const missing = new Set(showMissing ? missingInStep(answers, step) : []);
  const errorFor = (key: keyof Answers) =>
    fieldErrors[key] ?? (missing.has(key as never) ? NAV.required : undefined);

  const next = () => {
    if (missingInStep(answers, step).length > 0) {
      setShowMissing(true);
      return;
    }
    setShowMissing(false);
    if (last) onSubmit();
    else onStep(step + 1);
  };

  const back = () => {
    setShowMissing(false);
    onStep(Math.max(0, step - 1));
  };

  const optionalExtra = (answered: boolean, hint: string) => (answered ? OPTIONAL.added(hint) : hint);

  return (
    <div className="flex flex-wrap items-start gap-8">
      <StepAside step={step} />
      <form
        className={`${card} flex min-w-0 flex-[999_1_560px] flex-col gap-8 p-9 max-[600px]:p-[22px]`}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          next();
        }}
      >
        <div className="flex flex-col gap-2">
          <p className={eyebrow}>{NAV.progress(step + 1, total)}</p>
          <h1 ref={headingRef} tabIndex={-1} className={`${h1} outline-none`}>
            {STEPS[step].heading}
          </h1>
        </div>

        {step === 0 && (
          <>
            <Question label={Q.segment.label} hint={Q.segment.hint} why={Q.segment.why} error={errorFor("segment")}>
              <ChipGroup
                chips={SEGMENTS.map((seg) => ({ key: seg, value: seg, label: Q.segment.options[seg] }))}
                value={answers.segment}
                onChange={(segment) => onChange({ segment })}
                invalid={!!errorFor("segment")}
              />
            </Question>
            <Question label={Q.age.label} hint={Q.age.hint} why={Q.age.why} error={errorFor("age")}>
              <AgeStepper
                value={answers.age}
                onChange={(age) => onChange({ age })}
                min={AGE_MIN}
                max={AGE_MAX}
                start={AGE_START[s ?? "both"]}
                invalid={!!errorFor("age")}
              />
            </Question>
            <Question label={Q.movement.label} hint={Q.movement.hint} why={Q.movement.why} error={errorFor("movementMinutes")}>
              <ChipGroup
                chips={chips(MOVEMENT_OPTIONS, Q.movement.options)}
                value={answers.movementMinutes}
                onChange={(movementMinutes) => onChange({ movementMinutes })}
                invalid={!!errorFor("movementMinutes")}
              />
            </Question>
          </>
        )}

        {step === 1 && (
          <>
            <Question label={Q.sleepHours.label} hint={Q.sleepHours.hint} why={Q.sleepHours.why} error={errorFor("sleepHours")}>
              <ChipGroup
                chips={chips(SLEEP_OPTIONS, Q.sleepHours.options)}
                value={answers.sleepHours}
                onChange={(sleepHours) => onChange({ sleepHours })}
                invalid={!!errorFor("sleepHours")}
              />
            </Question>
            <Question label={Q.rested.label} hint={Q.rested.hint} why={Q.rested.why} error={errorFor("rested")}>
              <ScaleButtons
                value={answers.rested}
                onChange={(rested) => onChange({ rested })}
                lowLabel={Q.rested.low}
                highLabel={Q.rested.high}
                invalid={!!errorFor("rested")}
              />
            </Question>
          </>
        )}

        {step === 2 && (
          <>
            <Question label={Q.pressure.label(s)} hint={Q.pressure.hint(s)} why={Q.pressure.why} error={errorFor("pressure")}>
              <ChipGroup
                chips={scaleChips(Q.pressure.options)}
                value={answers.pressure}
                onChange={(pressure) => onChange({ pressure })}
                invalid={!!errorFor("pressure")}
              />
            </Question>
            <Question
              label={Q.satisfaction.label(s)}
              hint={Q.satisfaction.hint(s)}
              why={Q.satisfaction.why}
              error={errorFor("satisfaction")}
            >
              <ChipGroup
                chips={scaleChips(Q.satisfaction.options)}
                value={answers.satisfaction}
                onChange={(satisfaction) => onChange({ satisfaction })}
                invalid={!!errorFor("satisfaction")}
              />
            </Question>
            <Question label={Q.money.label} hint={Q.money.hint(s)} why={Q.money.why} error={errorFor("financialStress")}>
              <ChipGroup
                chips={scaleChips(Q.money.options)}
                value={answers.financialStress}
                onChange={(financialStress) => onChange({ financialStress })}
                invalid={!!errorFor("financialStress")}
              />
            </Question>
          </>
        )}

        {step === 3 && (
          <>
            <Question label={Q.stress.label} hint={Q.stress.hint} why={Q.stress.why} error={errorFor("stress")}>
              <ScaleButtons
                value={answers.stress}
                onChange={(stress) => onChange({ stress })}
                lowLabel={Q.stress.low}
                highLabel={Q.stress.high}
                invalid={!!errorFor("stress")}
              />
            </Question>

            <section className="rounded-[18px] border border-(--wn-line) bg-(--wn-bg) p-5 max-[600px]:p-4">
              <button
                type="button"
                aria-expanded={optionalShown}
                aria-controls={optionalId}
                onClick={() => setOptionalOpen(!optionalShown)}
                className={`flex min-h-11 w-full cursor-pointer items-center justify-between gap-3 rounded-[14px] text-left ${focusRing}`}
              >
                <span>
                  <span className={`block ${h2}`}>{OPTIONAL.title}</span>
                  <span className={`block ${fine}`}>{optionalShown ? OPTIONAL.subtitle : OPTIONAL.show}</span>
                </span>
                <ChevronDown
                  className={`size-5 shrink-0 text-(--wn-ink-2) motion-safe:transition-transform ${optionalShown ? "rotate-180" : ""}`}
                  strokeWidth={2}
                  aria-hidden="true"
                />
              </button>
              <div id={optionalId} hidden={!optionalShown} className="mt-6 flex flex-col gap-7">
                <Question
                  label={OPTIONAL.workStudyHours.label(s)}
                  extra={optionalExtra(answers.workStudyHours !== null, OPTIONAL.workStudyHours.hint)}
                  error={fieldErrors.workStudyHours}
                >
                  <ChipGroup
                    chips={withSkip(chips(WORK_STUDY_HOURS_OPTIONS, OPTIONAL.workStudyHours.options))}
                    value={answers.workStudyHours}
                    onChange={(workStudyHours) => onChange({ workStudyHours })}
                  />
                </Question>
                <Question
                  label={OPTIONAL.dietaryHabits.label}
                  extra={optionalExtra(answers.dietaryHabits !== null, OPTIONAL.dietaryHabits.hint)}
                  error={fieldErrors.dietaryHabits}
                >
                  <ChipGroup
                    chips={withSkip(chips(DIET_OPTIONS, OPTIONAL.dietaryHabits.options))}
                    value={answers.dietaryHabits}
                    onChange={(dietaryHabits) => onChange({ dietaryHabits })}
                  />
                </Question>
                <Question
                  label={OPTIONAL.familyHistory.label}
                  extra={optionalExtra(
                    answers.familyHistory === "yes" || answers.familyHistory === "no",
                    OPTIONAL.familyHistory.hint,
                  )}
                  error={fieldErrors.familyHistory}
                >
                  <ChipGroup
                    chips={FAMILY_CHIPS}
                    value={answers.familyHistory}
                    onChange={(familyHistory) => onChange({ familyHistory })}
                  />
                </Question>
              </div>
            </section>
          </>
        )}

        <StepInsight step={step} answers={answers} />

        {banner ? (
          <div
            role="alert"
            className="flex flex-col items-start gap-3 rounded-[14px] border border-(--wn-mod-fill) bg-(--wn-mod-soft) p-4 text-[15px] text-(--wn-mod-text) sm:flex-row sm:items-center sm:justify-between"
          >
            <span>{banner}</span>
            {onRetry ? (
              <button type="button" onClick={onRetry} className={ghostButton} disabled={submitting}>
                <RotateCcw className="size-4" strokeWidth={2} aria-hidden="true" />
                {ERRORS.retry}
              </button>
            ) : null}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-(--wn-line) pt-6">
          {step > 0 ? (
            <button type="button" onClick={back} className={ghostButton} disabled={submitting}>
              <ArrowLeft className="size-4" strokeWidth={2} aria-hidden="true" />
              {NAV.back}
            </button>
          ) : (
            <span aria-hidden="true" />
          )}
          <p className="order-last w-full text-center text-sm text-(--wn-ink-2) sm:order-none sm:w-auto sm:max-w-xs">
            {step === 0 ? PAGE.privacy : NAV.hint}
          </p>
          <button type="submit" className={primaryButton} disabled={submitting} aria-busy={submitting}>
            {submitting ? (
              <>
                <Loader2 className="size-4 motion-safe:animate-spin" strokeWidth={2} aria-hidden="true" />
                {NAV.checking}
              </>
            ) : (
              <>
                {last ? NAV.seeResult : NAV.next}
                <ArrowRight className="size-4" strokeWidth={2} aria-hidden="true" />
              </>
            )}
          </button>
        </div>
      </form>
    </div>
  );
}
