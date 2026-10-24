import Link from "next/link";
import { Heart, HeartHandshake, MessageCircle } from "lucide-react";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { getResultActions } from "@/lib/assessments/recommendations";
import { getSafetyGuidance } from "@/lib/assessments/safety";
import type { AnyAssessmentResult } from "@/lib/assessments/types";

const ACTION_ICONS: Record<string, typeof MessageCircle> = {
  "talk-to-nuro": MessageCircle,
  "professional-support": Heart,
};

interface SafetyResultStateProps {
  result: AnyAssessmentResult;
}

/**
 * Dedicated safety result screen. Rendered by AssessmentResultCard INSTEAD
 * OF the normal score/interpretation/recommendation layout whenever
 * isSafetyTriggered(result) is true — ordinary wellness recommendations
 * never appear alongside this state, by design.
 */
export function SafetyResultState({ result }: SafetyResultStateProps) {
  const guidance = getSafetyGuidance();
  const actions = getResultActions({
    assessmentType: result.assessmentType,
    score: result.rawScore,
    level: result.levelKey,
    safetyFlag: true,
    suggestProfessionalSupport: true,
  });

  return (
    <div className="mx-auto max-w-2xl">
      {/* Header */}
      <div className="mb-8 flex flex-col items-center gap-3 text-center">
        <div className="mb-1 flex size-14 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
          <HeartHandshake className="size-7" aria-hidden="true" />
        </div>
        <span className="text-xs font-semibold uppercase tracking-[0.2em] text-[#5A6E5A]">
          {result.assessmentLabel} check-in
        </span>
        <h1 className="font-heading text-3xl font-normal leading-tight text-[#1C352D] sm:text-4xl">
          {guidance.headline}
        </h1>
      </div>

      {/* Acknowledgement + guidance */}
      <div className="mb-8 flex flex-col gap-4 rounded-[28px] border border-[#E5E8E1] bg-white p-8 shadow-xs sm:p-10">
        <p className="text-sm leading-relaxed text-[#1C352D] sm:text-base">{guidance.acknowledgement}</p>
        <p className="text-sm leading-relaxed text-[#4A5750] sm:text-base">{guidance.guidance}</p>
        <p className="text-xs text-[#8A9690]">{guidance.notEmergencyServiceNotice}</p>
      </div>

      {/* Crisis resources */}
      <CrisisResourcesBox countryCode={guidance.crisisResources.countryCode} className="mb-8" showProfessionalsLink />

      {/* Actions */}
      <div className="mb-8 flex flex-col items-center justify-center gap-3 sm:flex-row sm:flex-wrap">
        {actions.map((action, index) => {
          const Icon = ACTION_ICONS[action.kind] ?? MessageCircle;
          return (
            <Link
              key={action.kind}
              href={action.href}
              className={
                index === 0
                  ? "w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full bg-[#233E33] px-6 py-3 text-sm font-medium text-white hover:bg-[#192E26] transition-colors"
                  : "w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full border border-[#C8CEBE] bg-white px-6 py-3 text-sm font-medium text-[#1C352D] hover:bg-[#F4F6F0] transition-colors"
              }
            >
              <Icon className="size-4" />
              <span>{action.label}</span>
            </Link>
          );
        })}
        <Link
          href="/assessment"
          className="w-full sm:w-auto inline-flex items-center justify-center gap-2 rounded-full border border-[#C8CEBE] bg-white px-6 py-3 text-sm font-medium text-[#1C352D] hover:bg-[#F4F6F0] transition-colors"
        >
          <span>Return to Assessment Hub</span>
        </Link>
      </div>

      <ScreeningToolDisclaimer label={result.assessmentLabel} />
    </div>
  );
}

/** "Screening tool, not a diagnosis", shared with the dashboard's check-in page. */
export function ScreeningToolDisclaimer({ label }: { label: string }) {
  return (
    <p className="mx-auto max-w-xl text-center text-xs leading-relaxed text-[#8A9690]">
      {label} is a brief self-reflection screening tool. It is not a diagnostic instrument and
      it does not determine risk on its own — it does not replace a professional evaluation.
    </p>
  );
}
