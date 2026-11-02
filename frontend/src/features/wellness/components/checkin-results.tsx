import { useEffect, useRef } from "react";
import Link from "next/link";
import { BedDouble, CheckCircle2, Gauge, Info, LogIn, Sparkles } from "lucide-react";

import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { loginHref } from "@/lib/safe-redirect";
import { NAV, RESULT } from "../copy.ts";
import {
  WELLNESS_ROUTES,
  bandCopy,
  friendlyConfidenceReasons,
  saveState,
  showCrisisResources,
  sleepView,
} from "../results.ts";
import type { CheckinRequest, CheckinResponse } from "../types.ts";
import { ActionList, BandScale, DriverList } from "./result-parts.tsx";
import { card, muted, primaryButton, secondaryButton, textLink } from "./styles.ts";
import { WhatIfPanel } from "./what-if-panel.tsx";

/** The results screen for one check-in. */
export function CheckinResults({
  response,
  request,
  onStartAgain,
  onEdit,
}: {
  response: CheckinResponse;
  request: CheckinRequest;
  onStartAgain: () => void;
  onEdit: () => void;
}) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => headingRef.current?.focus(), []);

  const { result, suggestedActions } = response;
  const band = result.mood.risk_band;
  const copy = bandCopy(band);
  const sleep = sleepView(result.sleep, suggestedActions);
  const reasons = friendlyConfidenceReasons(result.mood.confidence_reasons);
  const saved = saveState(response) === "saved";

  return (
    <div className="flex flex-col gap-6">
      <h2 ref={headingRef} tabIndex={-1} className="font-heading text-3xl font-normal text-[#1C352D] outline-none sm:text-4xl">
        {RESULT.heading}
      </h2>

      {saved ? (
        <div role="status" className="flex flex-col items-start gap-3 rounded-2xl border border-[#CFE0CB] bg-[#EEF5EC] p-4 sm:flex-row sm:items-center sm:justify-between">
          <span className="flex items-center gap-2 text-sm font-semibold text-[#1F3A2C]">
            <CheckCircle2 className="size-5" aria-hidden="true" />
            {RESULT.savedTitle}
          </span>
          <Link href={WELLNESS_ROUTES.history} className={textLink}>
            {RESULT.savedLink}
          </Link>
        </div>
      ) : (
        <div className="flex flex-col items-start gap-3 rounded-2xl border border-[#E5E8E1] bg-white p-4 sm:flex-row sm:items-center sm:justify-between">
          <span>
            <span className="block text-sm font-semibold text-[#1C352D]">{RESULT.anonTitle}</span>
            <span className={`block text-xs ${muted}`}>{RESULT.anonText}</span>
          </span>
          <Link href={loginHref(WELLNESS_ROUTES.checkin)} className={primaryButton}>
            <LogIn className="size-4" aria-hidden="true" />
            {RESULT.anonButton}
          </Link>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <section className={card} aria-labelledby="mood-title">
          <div className="mb-4 flex items-center gap-2">
            <Gauge className="size-5 text-[#233E33]" aria-hidden="true" />
            <h3 id="mood-title" className="text-lg font-semibold text-[#1C352D]">
              {RESULT.moodTitle}
            </h3>
          </div>
          <p className={`mb-4 text-sm ${muted}`}>{RESULT.moodSubtitle}</p>
          <p className="mb-3 text-3xl font-semibold text-[#1C352D]">{copy?.label ?? band}</p>
          <BandScale band={band} />
          {copy ? <p className="mt-4 text-sm text-[#3D4A43]">{copy.text}</p> : null}

          <div className="mt-5 rounded-2xl bg-[#FBF9F4] p-4">
            <span className="inline-flex rounded-full border border-[#C8CEBE] bg-white px-3 py-1 text-xs font-semibold text-[#233E33]">
              {RESULT.confidence.label(result.mood.confidence)}
            </span>
            <p className="mt-3 text-xs font-semibold text-[#4A5750]">{RESULT.confidence.whyTitle}</p>
            <ul className="mt-1 list-disc pl-5 text-sm text-[#3D4A43]">
              {reasons.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          </div>

          <h4 className="mb-3 mt-6 text-sm font-semibold text-[#1C352D]">{RESULT.driversTitle}</h4>
          <DriverList drivers={result.mood.drivers} kind="mood" />
        </section>

        <section className={card} aria-labelledby="sleep-title">
          <div className="mb-4 flex items-center gap-2">
            <BedDouble className="size-5 text-[#233E33]" aria-hidden="true" />
            <h3 id="sleep-title" className="text-lg font-semibold text-[#1C352D]">
              {RESULT.sleepTitle}
            </h3>
          </div>
          {sleep.mode === "pattern" ? (
            <>
              <p className="text-base leading-relaxed text-[#1C352D]">{sleep.displayText}</p>
              {sleep.showSeeDoctor ? <p className="mt-3 text-sm text-[#3D4A43]">{RESULT.seeDoctor}</p> : null}
              <h4 className="mb-3 mt-6 text-sm font-semibold text-[#1C352D]">{RESULT.driversTitle}</h4>
              <DriverList drivers={result.sleep.drivers} kind="sleep" />
            </>
          ) : (
            <>
              <p className="text-base leading-relaxed text-[#1C352D]">{RESULT.sleepGuidanceIntro}</p>
              {sleep.reason ? <p className={`mt-2 text-sm ${muted}`}>{sleep.reason}</p> : null}
            </>
          )}
          <h4 className="mb-3 mt-6 text-sm font-semibold text-[#1C352D]">{RESULT.sleepGuidanceTitle}</h4>
          <ul className="flex flex-col gap-2">
            {sleep.guidance.messages.map((m) => (
              <li key={m.code} className="flex gap-2 rounded-xl bg-[#F5F7F2] px-3 py-2.5 text-sm text-[#3D4A43]">
                <Sparkles className="mt-0.5 size-4 shrink-0 text-[#5A6E5A]" aria-hidden="true" />
                {m.text}
              </li>
            ))}
          </ul>
          <p className={`mt-3 text-xs ${muted}`}>{sleep.guidance.source}</p>
        </section>
      </div>

      {showCrisisResources(band) ? <CrisisResourcesBox intro={RESULT.crisisIntro} showProfessionalsLink /> : null}

      <section className={card} aria-labelledby="actions-title">
        <h3 id="actions-title" className="mb-4 text-lg font-semibold text-[#1C352D]">
          {RESULT.actionsTitle}
        </h3>
        <ActionList codes={suggestedActions} />
      </section>

      <WhatIfPanel base={request} />

      <aside className="flex gap-3 rounded-2xl border border-[#E5E8E1] bg-[#FBF9F4] p-4 text-sm text-[#3D4A43]" aria-label={RESULT.disclaimerTitle}>
        <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <p>{result.disclaimer}</p>
      </aside>

      <div className="flex flex-col gap-3 sm:flex-row">
        <button type="button" className={secondaryButton} onClick={onEdit}>
          {NAV.editAnswers}
        </button>
        <button type="button" className={secondaryButton} onClick={onStartAgain}>
          {NAV.startAgain}
        </button>
      </div>
    </div>
  );
}
