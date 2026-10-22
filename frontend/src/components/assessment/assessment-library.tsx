import Link from "next/link";
import { ASSESSMENTS, ASSESSMENT_DISPLAY_ORDER, type AssessmentMeta } from "@/lib/assessments/registry";

interface AssessmentLibraryProps {
  onStartWho5: () => void;
}

const CARD_BASE =
  "flex h-full flex-col items-start gap-3 rounded-[22px] border bg-white p-6 text-left shadow-2xs transition-all duration-300 hover:shadow-md";

function CardContent({ meta, suggested }: { meta: AssessmentMeta; suggested: boolean }) {
  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-full bg-[#ECEFE6] px-2.5 py-0.5 text-xs font-semibold text-[#233E33]">
          {meta.label}
        </span>
        {suggested && (
          <span className="rounded-full bg-[#233E33] px-2.5 py-0.5 text-xs font-semibold text-white">Suggested</span>
        )}
      </div>
      <h3 className="font-medium text-lg text-[#1C352D]">{meta.fullName}</h3>
      <p className="flex-1 text-sm leading-relaxed text-[#5A6860]">{meta.shortDescription}</p>
      <span className="text-xs font-medium text-[#66746E]">
        {meta.questionCount} questions • ~{meta.minutes} min
      </span>
    </>
  );
}

/**
 * Every check-in on the hub, WHO-5 first as the suggested starting point.
 * All card copy comes from the registry, so adding a test there is enough
 * for it to show up here.
 */
export function AssessmentLibrary({ onStartWho5 }: AssessmentLibraryProps) {
  return (
    <section id="all-checkins" className="scroll-mt-6 py-12 sm:py-16">
      <div className="mb-10 text-center sm:mb-12">
        <span className="mb-3 block text-xs font-semibold uppercase tracking-[0.25em] text-[#5A6E5A]">
          Check-ins
        </span>
        <h2 className="font-heading text-3xl font-normal text-[#1C352D] sm:text-4xl lg:text-[42px]">
          Choose a check-in
        </h2>
        <p className="mx-auto mt-4 max-w-2xl text-sm leading-relaxed text-[#4A5750] sm:text-base">
          Start with the suggested one, or pick whatever feels closest to what&apos;s on your mind. Nothing here is
          locked or required.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {ASSESSMENT_DISPLAY_ORDER.map((type) => {
          const meta = ASSESSMENTS[type];
          if (type === "who5") {
            return (
              <button
                key={type}
                type="button"
                onClick={onStartWho5}
                className={`${CARD_BASE} border-[#233E33] cursor-pointer`}
              >
                <CardContent meta={meta} suggested />
              </button>
            );
          }
          return (
            <Link key={type} href={meta.route} className={`${CARD_BASE} border-[#E5E8E1] hover:border-[#CBD4C5]`}>
              <CardContent meta={meta} suggested={false} />
            </Link>
          );
        })}
      </div>
    </section>
  );
}
