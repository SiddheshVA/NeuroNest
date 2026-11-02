import { useEffect, useId, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, ChevronDown, Loader2, RotateCcw } from "lucide-react";

import { ERRORS, NAV, OPTIONAL, PAGE, Q, STEPS } from "../copy.ts";
import {
  AGE_MAX,
  AGE_MIN,
  AGE_START,
  DIET_OPTIONS,
  HEART_RATE_OPTIONS,
  MOVEMENT_OPTIONS,
  SEGMENTS,
  SLEEP_OPTIONS,
  STEPS_OPTIONS,
  WORK_STUDY_HOURS_OPTIONS,
  missingInStep,
  type Answers,
  type ChipOption,
  type FamilyHistoryAnswer,
} from "../questionnaire.ts";
import { AgeStepper, ChipGroup, Question, ScaleSlider, type Chip } from "./controls.tsx";
import { card, focusRing, muted, primaryButton, secondaryButton } from "./styles.ts";

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

const OPTIONAL_KEYS: (keyof Answers)[] = ["workStudyHours", "dietaryHabits", "familyHistory", "heartRate", "dailySteps"];

const FAMILY_CHIPS: Chip<FamilyHistoryAnswer>[] = [
  { key: "yes", value: "yes", label: OPTIONAL.familyHistory.options.yes },
  { key: "no", value: "no", label: OPTIONAL.familyHistory.options.no },
  { key: "prefer_not", value: "prefer_not", label: NAV.preferNotToSay },
];

/**
 * The four-step questionnaire. Answers live only in the parent's React state (never in browser storage or
 * the console). Field errors from the API are shown inline under their question.
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
}) {
  const [showMissing, setShowMissing] = useState(false);
  const [optionalOpen, setOptionalOpen] = useState(false);
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
    <form
      className={`${card} flex flex-col gap-8`}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        next();
      }}
    >
      <div className="flex flex-col gap-3">
        <div className={`flex items-center justify-between text-xs font-medium ${muted}`}>
          <span>{NAV.progress(step + 1, total)}</span>
          <span>{STEPS[step].subtitle}</span>
        </div>
        <div
          role="progressbar"
          aria-label={NAV.progress(step + 1, total)}
          aria-valuemin={1}
          aria-valuemax={total}
          aria-valuenow={step + 1}
          className="h-2 overflow-hidden rounded-full bg-[#E5E8E1]"
        >
          <div
            className="h-full rounded-full bg-[#233E33] motion-safe:transition-[width] motion-safe:duration-300"
            style={{ width: `${((step + 1) / total) * 100}%` }}
          />
        </div>
        <h2 ref={headingRef} tabIndex={-1} className="mt-2 font-heading text-2xl font-normal text-[#1C352D] outline-none sm:text-3xl">
          {STEPS[step].title}
        </h2>
      </div>

      {step === 0 && (
        <>
          <Question label={Q.segment.label} why={Q.segment.why} error={errorFor("segment")}>
            <ChipGroup
              name="segment"
              chips={SEGMENTS.map((seg) => ({ key: seg, value: seg, label: Q.segment.options[seg] }))}
              value={answers.segment}
              onChange={(segment) => onChange({ segment })}
              invalid={!!errorFor("segment")}
            />
          </Question>
          <Question label={Q.age.label} why={Q.age.why} error={errorFor("age")}>
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
              name="movement"
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
              name="sleepHours"
              chips={chips(SLEEP_OPTIONS, Q.sleepHours.options)}
              value={answers.sleepHours}
              onChange={(sleepHours) => onChange({ sleepHours })}
              invalid={!!errorFor("sleepHours")}
            />
          </Question>
          <Question label={Q.rested.label} why={Q.rested.why} error={errorFor("rested")}>
            <ScaleSlider
              label={Q.rested.label}
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
              name="pressure"
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
              name="satisfaction"
              chips={scaleChips(Q.satisfaction.options)}
              value={answers.satisfaction}
              onChange={(satisfaction) => onChange({ satisfaction })}
              invalid={!!errorFor("satisfaction")}
            />
          </Question>
          <Question label={Q.money.label} hint={Q.money.hint(s)} why={Q.money.why} error={errorFor("financialStress")}>
            <ChipGroup
              name="financialStress"
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
            <ScaleSlider
              label={Q.stress.label}
              value={answers.stress}
              onChange={(stress) => onChange({ stress })}
              lowLabel={Q.stress.low}
              highLabel={Q.stress.high}
              invalid={!!errorFor("stress")}
            />
          </Question>

          <section className="rounded-2xl border border-[#E5E8E1] bg-[#FBF9F4] p-4 sm:p-5">
            <button
              type="button"
              aria-expanded={optionalShown}
              aria-controls={optionalId}
              onClick={() => setOptionalOpen(!optionalShown)}
              className={`flex w-full cursor-pointer items-center justify-between gap-3 rounded-xl text-left ${focusRing}`}
            >
              <span>
                <span className="block text-base font-semibold text-[#1C352D]">{OPTIONAL.title}</span>
                <span className={`block text-sm ${muted}`}>{optionalShown ? OPTIONAL.subtitle : OPTIONAL.show}</span>
              </span>
              <ChevronDown className={`size-5 shrink-0 motion-safe:transition-transform ${optionalShown ? "rotate-180" : ""}`} aria-hidden="true" />
            </button>
            <div id={optionalId} hidden={!optionalShown} className="mt-5 flex flex-col gap-7">
              <Question
                label={OPTIONAL.workStudyHours.label(s)}
                extra={optionalExtra(answers.workStudyHours !== null, OPTIONAL.workStudyHours.hint)}
                error={fieldErrors.workStudyHours}
              >
                <ChipGroup
                  name="workStudyHours"
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
                  name="dietaryHabits"
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
                  name="familyHistory"
                  chips={FAMILY_CHIPS}
                  value={answers.familyHistory}
                  onChange={(familyHistory) => onChange({ familyHistory })}
                />
              </Question>

              <div className="flex flex-col gap-5 border-t border-[#E5E8E1] pt-5">
                <div>
                  <p className="text-sm font-semibold text-[#1C352D]">{OPTIONAL.sleepExtrasTitle}</p>
                  <p className="text-xs text-[#5A6E5A]">{OPTIONAL.sleepExtrasHint}</p>
                </div>
                <Question label={OPTIONAL.heartRate.label} error={fieldErrors.heartRate}>
                  <ChipGroup
                    name="heartRate"
                    chips={withSkip(chips(HEART_RATE_OPTIONS, OPTIONAL.heartRate.options))}
                    value={answers.heartRate}
                    onChange={(heartRate) => onChange({ heartRate })}
                  />
                </Question>
                <Question label={OPTIONAL.dailySteps.label} error={fieldErrors.dailySteps}>
                  <ChipGroup
                    name="dailySteps"
                    chips={withSkip(chips(STEPS_OPTIONS, OPTIONAL.dailySteps.options))}
                    value={answers.dailySteps}
                    onChange={(dailySteps) => onChange({ dailySteps })}
                  />
                </Question>
              </div>
            </div>
          </section>
        </>
      )}

      {banner ? (
        <div role="alert" className="flex flex-col items-start gap-3 rounded-2xl border border-[#E7D9B8] bg-[#FBF5E6] p-4 text-sm text-[#4A3A12] sm:flex-row sm:items-center sm:justify-between">
          <span>{banner}</span>
          {onRetry ? (
            <button type="button" onClick={onRetry} className={secondaryButton} disabled={submitting}>
              <RotateCcw className="size-4" aria-hidden="true" />
              {ERRORS.retry}
            </button>
          ) : null}
        </div>
      ) : null}

      <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-between">
        {step > 0 ? (
          <button type="button" onClick={back} className={secondaryButton} disabled={submitting}>
            <ArrowLeft className="size-4" aria-hidden="true" />
            {NAV.back}
          </button>
        ) : (
          <p className={`text-xs ${muted} sm:max-w-xs`}>{PAGE.privacy}</p>
        )}
        <button type="submit" className={primaryButton} disabled={submitting} aria-busy={submitting}>
          {submitting ? (
            <>
              <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
              {NAV.checking}
            </>
          ) : (
            <>
              {last ? NAV.seeResult : NAV.next}
              <ArrowRight className="size-4" aria-hidden="true" />
            </>
          )}
        </button>
      </div>
    </form>
  );
}
