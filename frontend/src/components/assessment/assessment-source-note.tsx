import { ASSESSMENT_SOURCES } from "@/lib/assessments/sources";
import type { AssessmentType } from "@/lib/assessments/types";

interface AssessmentSourceNoteProps {
  type: AssessmentType;
  className?: string;
}

/** Copyright credit and citation for a screening instrument — plain text, no links. */
export function AssessmentSourceNote({ type, className = "" }: AssessmentSourceNoteProps) {
  const source = ASSESSMENT_SOURCES[type];
  return (
    <div className={`text-xs leading-relaxed text-[#8A9690] ${className}`}>
      <p className="mb-1 font-semibold uppercase tracking-[0.15em] text-[#66746E]">About this test</p>
      <p>{source.credit}</p>
      <p className="mt-1">{source.citation}</p>
    </div>
  );
}
