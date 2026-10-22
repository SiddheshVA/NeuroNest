import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, CheckCircle2 } from "lucide-react";
import type { AssessmentOption, AssessmentQuestion } from "@/lib/assessments/types";

interface LikertQuestionnaireProps {
  questions: AssessmentQuestion[];
  /** Shared answer options; a question with its own `options` (SCI) overrides these. */
  options?: AssessmentOption[];
  onComplete: (answers: Record<number, number>) => void;
}

/** How long a selected answer stays on screen before the next question appears. */
const ADVANCE_DELAY_MS = 1000;
const ADVANCE_ANNOUNCEMENT = "Answer saved. Moving to the next question.";
const NO_BREAK_SPACE = String.fromCharCode(160);

/**
 * Shared single-select question flow (progress bar, question text, option
 * list, prev/next nav) used by every Likert-style screening — WHO-5, GAD-7,
 * PHQ-9, PSS-10, K10, SCI. Extracted from the original WHO-5 modal so its visual
 * design and interaction stay identical across assessments.
 *
 * Selecting an answer moves on to the next question after one second, except on the last question,
 * which is only ever submitted with "See Results". Only a selection starts that timer; viewing a
 * question never does. Selecting again restarts it, and Previous, Next, leaving the page or any other
 * change of question cancels it, so each selection moves on exactly one question.
 */
export function LikertQuestionnaire({ questions, options, onComplete }: LikertQuestionnaireProps) {
  // `reviewing`: the question already had an answer when the person arrived on it (shows "Next").
  const [position, setPosition] = useState({ index: 0, reviewing: false });
  const [answers, setAnswers] = useState<Record<number, number>>({});
  const [announcement, setAnnouncement] = useState("");

  const currentIndex = position.index;
  const currentQuestion = questions[currentIndex];
  const selectedScore = answers[currentQuestion.id];
  const currentOptions = currentQuestion.options ?? options ?? [];
  const isLast = currentIndex === questions.length - 1;

  // Kept in step with `answers` so a delayed move can tell whether the next question is answered.
  const answersRef = useRef(answers);
  const timerRef = useRef<number | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const shownIndexRef = useRef(currentIndex);

  const cancelAdvance = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const goTo = useCallback(
    (index: number) => {
      cancelAdvance();
      setPosition({ index, reviewing: answersRef.current[questions[index].id] !== undefined });
    },
    [cancelAdvance, questions],
  );

  // No move is ever left pending when the questionnaire closes.
  useEffect(() => cancelAdvance, [cancelAdvance]);

  // After the question changes (not on first display), any pending move is dropped and focus goes to
  // the new question, so keyboard and screen-reader users continue from there.
  useEffect(() => {
    if (shownIndexRef.current === currentIndex) return;
    shownIndexRef.current = currentIndex;
    cancelAdvance();
    headingRef.current?.focus();
  }, [currentIndex, cancelAdvance]);

  const handleSelectOption = (score: number) => {
    const next = { ...answersRef.current, [currentQuestion.id]: score };
    answersRef.current = next;
    setAnswers(next);
    if (isLast) return;

    // A new selection replaces any pending move; the move always starts from this question.
    cancelAdvance();
    const from = currentIndex;
    // The trailing no-break space alternates so the same sentence is announced again.
    setAnnouncement((previous) => (previous === ADVANCE_ANNOUNCEMENT ? `${ADVANCE_ANNOUNCEMENT}${NO_BREAK_SPACE}` : ADVANCE_ANNOUNCEMENT));
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      setPosition((current) =>
        current.index === from
          ? { index: from + 1, reviewing: answersRef.current[questions[from + 1].id] !== undefined }
          : current,
      );
    }, ADVANCE_DELAY_MS);
  };

  const handleNext = () => {
    if (!isLast) goTo(currentIndex + 1);
  };

  const handleSeeResults = () => {
    cancelAdvance();
    onComplete(answersRef.current);
  };

  const handlePrev = () => {
    if (currentIndex > 0) goTo(currentIndex - 1);
  };

  return (
    <div>
      {/* Header & Progress */}
      <div className="mb-6 flex flex-col gap-2">
        <span className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">
          Question {currentIndex + 1} of {questions.length}
          {currentQuestion.area ? ` • ${currentQuestion.area}` : ""}
        </span>
        <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#E5E8E1]">
          <div
            className="h-full bg-[#233E33] transition-all duration-300 motion-reduce:transition-none"
            style={{
              width: `${((currentIndex + 1) / questions.length) * 100}%`,
            }}
          />
        </div>
      </div>

      {/* Question Text: focused when the question changes. */}
      <h3
        ref={headingRef}
        tabIndex={-1}
        className="font-heading text-xl font-normal leading-snug text-[#1C352D] sm:text-2xl mb-6 outline-none"
      >
        {currentQuestion.text}
      </h3>

      {/* Options */}
      <div className="flex flex-col gap-2.5 mb-8">
        {currentOptions.map((opt) => {
          const isSelected = selectedScore === opt.score;
          return (
            <button
              key={opt.score}
              onClick={() => handleSelectOption(opt.score)}
              className={`flex items-center justify-between rounded-xl border p-4 text-left font-medium text-sm sm:text-base transition-all duration-150 motion-reduce:transition-none cursor-pointer ${
                isSelected
                  ? "border-[#233E33] bg-[#233E33] text-white shadow-xs"
                  : "border-[#E5E8E1] bg-white text-[#1C352D] hover:border-[#CBD4C5] hover:bg-[#F5F7F2]"
              }`}
            >
              <span>{opt.label}</span>
              {isSelected && <CheckCircle2 className="size-5 text-white shrink-0" />}
            </button>
          );
        })}
      </div>

      {/* Announces each saved answer that moves on; not visible. */}
      <p className="sr-only" aria-live="polite">
        {announcement}
      </p>

      {/* Navigation buttons */}
      <div className="flex items-center justify-between pt-2 border-t border-[#E5E8E1]">
        <button
          onClick={handlePrev}
          disabled={currentIndex === 0}
          className="inline-flex items-center gap-1.5 text-sm font-medium text-[#4A5750] disabled:opacity-40 hover:text-[#1C352D] cursor-pointer disabled:cursor-not-allowed"
        >
          <ArrowLeft className="size-4" />
          Previous
        </button>

        {isLast ? (
          <button
            onClick={handleSeeResults}
            disabled={selectedScore === undefined}
            className="inline-flex items-center gap-2 rounded-full bg-[#233E33] px-6 py-2.5 text-sm font-medium text-white transition-all disabled:opacity-50 hover:bg-[#192E26] cursor-pointer disabled:cursor-not-allowed"
          >
            <span>See Results</span>
            <ArrowRight className="size-4" />
          </button>
        ) : position.reviewing && selectedScore !== undefined ? (
          // Only on a question that was already answered when the person came back to it.
          <button
            onClick={handleNext}
            className="inline-flex items-center gap-1.5 text-sm font-medium text-[#4A5750] hover:text-[#1C352D] cursor-pointer"
          >
            Next
            <ArrowRight className="size-4" />
          </button>
        ) : null}
      </div>
    </div>
  );
}
