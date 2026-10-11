import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useSearchParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { Container } from "@/components/layout/container";
import { AssessmentHero } from "@/components/assessment/assessment-hero";
import { WellbeingAreas } from "@/components/assessment/wellbeing-areas";
import { ReminderCard } from "@/components/assessment/reminder-card";
import { AssessmentModal } from "@/components/assessment/assessment-modal";
import { QuestionnairePage } from "@/components/assessment/questionnaire-page";
import { PsqiQuestionnaire } from "@/components/assessment/psqi-questionnaire";
import { GAD7_OPTIONS, GAD7_QUESTIONS, calculateGAD7 } from "@/lib/assessments/gad7";
import { PHQ9_OPTIONS, PHQ9_QUESTIONS, calculatePHQ9 } from "@/lib/assessments/phq9";
import { PSS10_OPTIONS, PSS10_QUESTIONS, calculatePSS10 } from "@/lib/assessments/pss10";
import { calculatePSQI, type PsqiAnswers } from "@/lib/assessments/psqi";
import { saveAssessmentResult } from "@/lib/assessments/storage";

export function AssessmentPage() {
  // WHO-5 has no route of its own; links elsewhere (e.g. Wellness Resources
  // articles) open it here with /assessment?start=who5.
  const [searchParams, setSearchParams] = useSearchParams();
  const [isModalOpen, setIsModalOpen] = useState(() => searchParams.get("start") === "who5");

  function closeModal() {
    setIsModalOpen(false);
    if (searchParams.has("start")) setSearchParams({}, { replace: true });
  }

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D] selection:bg-[#233E33]/15 selection:text-[#1C352D]">
      <Container className="py-2 sm:py-4">
        <AssessmentHero onBeginCheckIn={() => setIsModalOpen(true)} />
        <WellbeingAreas />
        <ReminderCard />
      </Container>

      <AssessmentModal isOpen={isModalOpen} onClose={closeModal} />
    </div>
  );
}

export function Gad7Page() {
  return (
    <QuestionnairePage
      eyebrow="GAD-7 • Anxiety screening"
      description="Over the last 2 weeks, how often have you been bothered by any of the following problems?"
      questions={GAD7_QUESTIONS}
      options={GAD7_OPTIONS}
      calculate={calculateGAD7}
      resultRoute="/assessment/result/gad7"
    />
  );
}

export function Phq9Page() {
  return (
    <QuestionnairePage
      eyebrow="PHQ-9 • Mood screening"
      description="Over the last 2 weeks, how often have you been bothered by any of the following problems?"
      questions={PHQ9_QUESTIONS}
      options={PHQ9_OPTIONS}
      calculate={calculatePHQ9}
      resultRoute="/assessment/result/phq9"
    />
  );
}

export function Pss10Page() {
  return (
    <QuestionnairePage
      eyebrow="PSS-10 • Stress screening"
      description="In the last month, how often have you felt or experienced each of the following?"
      questions={PSS10_QUESTIONS}
      options={PSS10_OPTIONS}
      calculate={calculatePSS10}
      resultRoute="/assessment/result/pss10"
    />
  );
}

export function PsqiPage() {
  const router = useRouter();

  const handleComplete = (answers: PsqiAnswers) => {
    saveAssessmentResult(calculatePSQI(answers));
    router.push("/assessment/result/psqi");
  };

  return (
    <div className="min-h-screen bg-[#FBF9F4] text-[#1C352D]">
      <Container className="py-10 sm:py-14">
        <div className="mx-auto max-w-xl">
          <Link
            href="/assessment"
            className="mb-6 inline-flex items-center gap-1.5 text-sm font-medium text-[#4A5750] hover:text-[#1C352D]"
          >
            <ArrowLeft className="size-4" />
            Back to Assessment
          </Link>

          <div className="rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8">
            <div className="mb-6">
              <h1 className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">
                PSQI • Sleep quality screening
              </h1>
              <p className="mt-2 text-sm leading-relaxed text-[#4A5750]">
                The following questions relate to your usual sleep habits during the past month only.
              </p>
            </div>

            <PsqiQuestionnaire onComplete={handleComplete} />
          </div>
        </div>
      </Container>
    </div>
  );
}
