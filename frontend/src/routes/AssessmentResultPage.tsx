import { BackLink } from "@/components/navigation/back-link";
import { useCallback, useEffect, useLayoutEffect, useSyncExternalStore } from "react";
import { Navigate, useParams } from "react-router-dom";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Container } from "@/components/layout/container";
import { AssessmentResultBackground } from "@/components/assessment/assessment-result-background";
import { AssessmentResultCard } from "@/components/assessment/assessment-result-card";
import { ASSESSMENTS } from "@/lib/assessments/registry";
import {
  clearAssessmentResult,
  giveUpOnServerResult,
  loadAssessmentResult,
  loadServerResult,
  subscribeAssessmentResults,
} from "@/lib/assessments/storage";
import type { AssessmentType } from "@/lib/assessments/types";

const VALID_TYPES: AssessmentType[] = ["who5", "gad7", "phq9", "pss10", "k10", "sci"];
// How long the page waits for the backend's answer (riskFlag, crisis lines) before going without it.
const SERVER_WAIT_MS = 6000;

export default function AssessmentResultPage() {
  const { type = "" } = useParams();
  if (!VALID_TYPES.includes(type as AssessmentType)) {
    return <Navigate to="/assessment" replace />;
  }
  return <AssessmentResult type={type as AssessmentType} />;
}

function AssessmentResult({ type }: { type: AssessmentType }) {
  const router = useRouter();

  const getSnapshot = useCallback(() => loadAssessmentResult(type), [type]);
  const result = useSyncExternalStore(subscribeAssessmentResults, getSnapshot, () => null);
  const getServerSnapshot = useCallback(() => loadServerResult(type), [type]);
  const server = useSyncExternalStore(subscribeAssessmentResults, getServerSnapshot, getServerSnapshot);

  // The questionnaire is long: open the result at its top, not at the scroll position the answers
  // were submitted from (which left the back link under the sticky nav bar).
  useLayoutEffect(() => {
    window.scrollTo(0, 0);
  }, [type]);

  useEffect(() => {
    if (server.status !== "pending") return;
    const timer = window.setTimeout(() => giveUpOnServerResult(type), SERVER_WAIT_MS);
    return () => window.clearTimeout(timer);
  }, [server.status, type]);

  const handleRetake = () => {
    clearAssessmentResult(type);
    router.push(ASSESSMENTS[type].route);
  };

  if (!result) {
    return (
      <div className="min-h-screen text-[#1C352D]">
        <AssessmentResultBackground />
        <Container className="flex flex-col items-center gap-4 py-24 text-center">
          <BackLink className="self-start" />
          <h1 className="font-heading text-2xl font-normal sm:text-3xl">No result to show yet</h1>
          <p className="max-w-md text-sm leading-relaxed text-[#4A5750] sm:text-base">
            We couldn&apos;t find a completed {ASSESSMENTS[type].label} check-in. Take a couple of minutes to
            answer the questions and we&apos;ll put together your snapshot.
          </p>
          <Link
            href={ASSESSMENTS[type].route}
            className="mt-2 inline-flex items-center gap-2 rounded-full bg-[#233E33] px-6 py-3 text-sm font-medium text-white hover:bg-[#192E26] transition-colors"
          >
            Start the check-in
          </Link>
        </Container>
      </div>
    );
  }

  return (
    <div className="min-h-screen text-[#1C352D]">
      <AssessmentResultBackground />
      <Container className="pt-6 pb-10 sm:pt-8 sm:pb-14">
        <BackLink className="mb-6" />
        <AssessmentResultCard result={result} server={server} onRetake={handleRetake} />
      </Container>
    </div>
  );
}
