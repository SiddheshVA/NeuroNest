import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Container } from "@/components/layout/container";
import { AssessmentHero } from "@/components/assessment/assessment-hero";
import { BackLink } from "@/components/navigation/back-link";
import { AssessmentLibrary } from "@/components/assessment/assessment-library";
import { WellbeingAreas } from "@/components/assessment/wellbeing-areas";
import { ReminderCard } from "@/components/assessment/reminder-card";
import { AssessmentModal } from "@/components/assessment/assessment-modal";
import { QuestionnairePage } from "@/components/assessment/questionnaire-page";
import { GAD7_OPTIONS, GAD7_QUESTIONS, calculateGAD7 } from "@/lib/assessments/gad7";
import { PHQ9_OPTIONS, PHQ9_QUESTIONS, calculatePHQ9 } from "@/lib/assessments/phq9";
import { PSS10_OPTIONS, PSS10_QUESTIONS, calculatePSS10 } from "@/lib/assessments/pss10";
import { K10_OPTIONS, K10_QUESTIONS, calculateK10 } from "@/lib/assessments/k10";
import { SCI_QUESTIONS, calculateSCI } from "@/lib/assessments/sci";

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
        <BackLink className="mt-2" />
        <AssessmentHero onBeginCheckIn={() => setIsModalOpen(true)} />
        <AssessmentLibrary onStartWho5={() => setIsModalOpen(true)} />
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
      assessmentType="gad7"
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
      assessmentType="phq9"
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
      assessmentType="pss10"
      eyebrow="PSS-10 • Stress screening"
      description="In the last month, how often have you felt or experienced each of the following?"
      questions={PSS10_QUESTIONS}
      options={PSS10_OPTIONS}
      calculate={calculatePSS10}
      resultRoute="/assessment/result/pss10"
    />
  );
}

export function K10Page() {
  return (
    <QuestionnairePage
      assessmentType="k10"
      eyebrow="K10 • General distress check"
      description="These questions are about how you have been feeling over the past 4 weeks."
      questions={K10_QUESTIONS}
      options={K10_OPTIONS}
      calculate={calculateK10}
      resultRoute="/assessment/result/k10"
    />
  );
}

export function SciPage() {
  return (
    <QuestionnairePage
      assessmentType="sci"
      eyebrow="SCI • Sleep check"
      description="These questions are about your sleep over the last month."
      questions={SCI_QUESTIONS}
      calculate={calculateSCI}
      resultRoute="/assessment/result/sci"
    />
  );
}
