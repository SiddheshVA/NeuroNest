import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ArrowRight, CheckCircle2, LogIn, RotateCcw, Stethoscope } from "lucide-react";

import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { loginHref } from "@/lib/safe-redirect";
import { INSIGHT, RESULT } from "../copy.ts";
import {
  MOVEMENT_METER,
  SLEEP_METER,
  WELLNESS_ROUTES,
  bandCopy,
  friendlyConfidenceReasons,
  saveState,
  showCrisisResources,
  skippedOptional,
  sleepView,
  weeklyMinutes,
} from "../results.ts";
import { previewCheckin } from "../api.ts";
import type { CheckinRequest, CheckinResponse } from "../types.ts";
import { BandGauge, Meter } from "./charts.tsx";
import { ActionGrid, DriverList } from "./result-parts.tsx";
import { eyebrow, ghostButton, h1, h2, h3, linkButton, primaryButton } from "./styles.ts";
import { BandPill, Card, CardTint, DisclaimerStrip, Pill } from "./ui.tsx";
import { WhatIfPanel } from "./what-if-panel.tsx";

const CRISIS_ID = "wellness-crisis-resources";

/** The results screen for one check-in. */
export function CheckinResults({
  response,
  request,
  onStartAgain,
  onEdit,
  onAddOptional,
}: {
  response: CheckinResponse;
  request: CheckinRequest;
  onStartAgain: () => void;
  onEdit: () => void;
  /** Back to the last step with the optional answers open ("Add it now"). */
  onAddOptional?: () => void;
}) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => headingRef.current?.focus(), []);
  const [crisisOpen, setCrisisOpen] = useState(false);

  const { result, suggestedActions } = response;
  const band = result.mood.risk_band;
  const copy = bandCopy(band);
  const sleep = sleepView(result.sleep, suggestedActions);
  const reasons = friendlyConfidenceReasons(result.mood.confidence_reasons);
  const saved = saveState(response) === "saved";
  const high = showCrisisResources(band);
  const weekly = weeklyMinutes(request.physicalActivityMinutes);

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-5">
        <div className="flex max-w-2xl flex-col gap-2">
          <p className={eyebrow}>{RESULT.eyebrow}</p>
          <h1 ref={headingRef} tabIndex={-1} className={`${h1} outline-none`}>
            {RESULT.heading}
          </h1>
          <p className="text-[17px] text-(--wn-ink-2)">{RESULT.lede}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {saved ? (
            <Link
              href={WELLNESS_ROUTES.history}
              className="inline-flex min-h-11 items-center gap-2 rounded-full bg-(--wn-brand-soft) px-4 text-[15px] font-bold text-(--wn-brand-dark) hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-(--wn-brand) outline-none"
            >
              <CheckCircle2 className="size-[18px]" strokeWidth={2} aria-hidden="true" />
              {RESULT.savedPill}
            </Link>
          ) : (
            <div className="flex flex-wrap items-center gap-3">
              <p className="max-w-[16rem] text-sm font-semibold text-(--wn-ink)">{RESULT.anonTitle}</p>
              <Link href={loginHref(WELLNESS_ROUTES.checkin)} className={primaryButton}>
                <LogIn className="size-4" strokeWidth={2} aria-hidden="true" />
                {RESULT.anonButton}
              </Link>
            </div>
          )}
          <button type="button" className={ghostButton} onClick={onStartAgain}>
            <RotateCcw className="size-4" strokeWidth={2} aria-hidden="true" />
            {RESULT.checkAgain}
          </button>
        </div>
      </header>

      {high ? (
        <div id={CRISIS_ID}>
          <CrisisResourcesBox intro={RESULT.crisisIntro} showProfessionalsLink />
        </div>
      ) : null}

      <div className="grid grid-cols-[1.35fr_1fr] items-start gap-6 max-[960px]:grid-cols-1">
        <Card aria-labelledby="mood-title" className="flex flex-col gap-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 id="mood-title" className={h2}>
              {RESULT.moodTitle}
            </h2>
            <Pill variant="ghost">{RESULT.confidence.label(result.mood.confidence)}</Pill>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <BandPill band={band} large />
            {copy ? <p className="min-w-0 flex-1 basis-60 text-[15px] text-(--wn-ink)">{copy.text}</p> : null}
          </div>
          <BandGauge band={band} />

          <div className="flex flex-col gap-3">
            <h3 className={h3}>{RESULT.driversTitle}</h3>
            <DriverList drivers={result.mood.drivers} />
          </div>

          <CardTint className="flex flex-col gap-2">
            <h3 className={h3}>{RESULT.confidence.whyTitle(result.mood.confidence)}</h3>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-[15px] text-(--wn-ink)">
              {reasons.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
            {onAddOptional && skippedOptional(result.mood) ? (
              <button type="button" className={`${linkButton} self-start`} onClick={onAddOptional}>
                {RESULT.confidence.addNow}
                <ArrowRight className="size-4" strokeWidth={2} aria-hidden="true" />
              </button>
            ) : null}
          </CardTint>
        </Card>

        <Card aria-labelledby="sleep-title" className="flex flex-col gap-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 id="sleep-title" className={h2}>
              {RESULT.sleepTitle}
            </h2>
            {result.sleep.confidence ? <Pill variant="ghost">{RESULT.confidence.label(result.sleep.confidence)}</Pill> : null}
          </div>

          {sleep.mode === "pattern" ? (
            <p className="text-[17px] font-semibold leading-snug text-(--wn-ink)">{sleep.displayText}</p>
          ) : (
            <div className="flex flex-col gap-3">
              <p className="text-[17px] font-semibold leading-snug text-(--wn-ink)">{RESULT.sleepGuidanceIntro}</p>
              <ul className="flex flex-col gap-2">
                {sleep.guidance.messages.map((m) => (
                  <li key={m.code} className="rounded-[14px] bg-(--wn-bg) px-3.5 py-2.5 text-[15px] text-(--wn-ink)">
                    {m.text}
                  </li>
                ))}
              </ul>
              {sleep.reason ? <p className="text-sm text-(--wn-ink-2)">{sleep.reason}</p> : null}
            </div>
          )}

          <Meter
            label={RESULT.sleepMeter.label}
            valueText={RESULT.sleepMeter.value(request.sleepDuration)}
            value={request.sleepDuration}
            scale={SLEEP_METER}
            minLabel={RESULT.sleepMeter.min}
            maxLabel={RESULT.sleepMeter.max}
            zoneLabel={RESULT.sleepMeter.zone}
            ariaLabel={RESULT.sleepMeter.aria(request.sleepDuration)}
          />
          <Meter
            label={RESULT.movementMeter.label}
            valueText={RESULT.movementMeter.value(weekly)}
            value={weekly}
            scale={MOVEMENT_METER}
            minLabel={RESULT.movementMeter.min}
            maxLabel={RESULT.movementMeter.max}
            zoneLabel={RESULT.movementMeter.zone}
            ariaLabel={RESULT.movementMeter.aria(weekly)}
          />
          <p className="text-[15px] text-(--wn-ink-2)">
            {RESULT.restedNote(INSIGHT.restedWord(request.qualityOfSleep), sleep.guidance.meets_activity_guideline)}
          </p>

          {sleep.showSeeDoctor ? (
            <DisclaimerStrip>
              <span className="flex items-start gap-2">
                <Stethoscope className="mt-0.5 size-4 shrink-0 text-(--wn-ink-2)" strokeWidth={2} aria-hidden="true" />
                {RESULT.seeDoctor}
              </span>
            </DisclaimerStrip>
          ) : null}
        </Card>
      </div>

      <WhatIfPanel base={request} baseBand={band} preview={previewCheckin} />

      <section aria-labelledby="actions-title" className="flex flex-col gap-4">
        <h2 id="actions-title" className={h2}>
          {RESULT.actionsTitle}
        </h2>
        <ActionGrid codes={suggestedActions} />
        {suggestedActions.includes("FIND_PROFESSIONAL") ? (
          <Link href="/professionals" className={`${linkButton} self-start text-[15px]`}>
            {RESULT.browseProfessionals}
          </Link>
        ) : null}
      </section>

      <DisclaimerStrip
        label={RESULT.disclaimerTitle}
        after={
          high ? (
            <a href={`#${CRISIS_ID}`} className={`${linkButton} text-sm`}>
              {RESULT.crisisLink}
            </a>
          ) : (
            <>
              <button
                type="button"
                aria-expanded={crisisOpen}
                aria-controls={CRISIS_ID}
                className={`${linkButton} text-sm`}
                onClick={() => setCrisisOpen((o) => !o)}
              >
                {crisisOpen ? RESULT.crisisHide : RESULT.crisisLink}
              </button>
              <div id={CRISIS_ID} hidden={!crisisOpen} className="mt-2">
                {crisisOpen ? <CrisisResourcesBox intro={RESULT.crisisIntro} showProfessionalsLink /> : null}
              </div>
            </>
          )
        }
      >
        {result.disclaimer}
      </DisclaimerStrip>

      <div>
        <button type="button" className={linkButton} onClick={onEdit}>
          {RESULT.editAnswers}
        </button>
      </div>
    </div>
  );
}
