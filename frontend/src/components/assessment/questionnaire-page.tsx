
import { BackLink } from "@/components/navigation/back-link";
import { useRouter } from "next/navigation";
import { Container } from "@/components/layout/container";
import { LikertQuestionnaire } from "@/components/assessment/likert-questionnaire";
import { AssessmentSourceNote } from "@/components/assessment/assessment-source-note";
import { saveAssessmentResult } from "@/lib/assessments/storage";
import type { AnyAssessmentResult, AssessmentOption, AssessmentQuestion, AssessmentType } from "@/lib/assessments/types";

interface QuestionnairePageProps {
  assessmentType: AssessmentType;
  eyebrow: string;
  description: string;
  questions: AssessmentQuestion[];
  /** Omit when every question carries its own options (SCI). */
  options?: AssessmentOption[];
  calculate: (answers: Record<number, number>) => AnyAssessmentResult;
  resultRoute: string;
}

/**
 * Full-page wrapper around the shared LikertQuestionnaire for the targeted
 * screenings (GAD-7, PHQ-9, PSS-10, K10, SCI) reached from the hub or the
 * WHO-5 "what's affecting you" branch — same visual language as the WHO-5 modal, just laid out as a
 * standalone page instead of an overlay.
 */
export function QuestionnairePage({
  assessmentType,
  eyebrow,
  description,
  questions,
  options,
  calculate,
  resultRoute,
}: QuestionnairePageProps) {
  const router = useRouter();

  const handleComplete = (answers: Record<number, number>) => {
    const result = calculate(answers);
    saveAssessmentResult(result);
    router.push(resultRoute);
  };

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="py-10 sm:py-14">
        <div className="mx-auto max-w-xl">
          <BackLink className="mb-4" />

          <div className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
            <div className="mb-6">
              <h1 className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">{eyebrow}</h1>
              <p className="mt-2 text-sm leading-relaxed text-[#4A5750]">{description}</p>
            </div>

            <LikertQuestionnaire questions={questions} options={options} onComplete={handleComplete} />
          </div>

          <AssessmentSourceNote type={assessmentType} className="mt-4" />
        </div>
      </Container>
    </div>
  );
}
