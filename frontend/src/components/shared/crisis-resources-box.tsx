import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowRight, LifeBuoy } from "lucide-react";
import { getCrisisResources } from "@/lib/ai/crisis-resources";
import { URGENT_DIRECTORY_HREF } from "@/lib/professionals-client";
import { cn } from "@/lib/utils";

/**
 * Emergency number + mental-health helplines, read from crisis-resources.ts
 * (the single source for these numbers). Shared by the assessment safety
 * screen and the Wellness Resources pages — never hardcode numbers elsewhere.
 */
function CrisisResourcesBox({
  countryCode,
  intro = (
    <>
      If you&apos;re thinking about harming yourself or are in immediate danger, please contact emergency services
      or a crisis line right away:
    </>
  ),
  className,
  showProfessionalsLink = false,
}: {
  countryCode?: string;
  intro?: ReactNode;
  className?: string;
  /** Adds a link to the professional directory (opened with the helplines on top). */
  showProfessionalsLink?: boolean;
}) {
  const resources = getCrisisResources(countryCode);

  return (
    <div
      className={cn(
        "flex items-start gap-3 rounded-[28px] border border-[#E7C8C0] bg-[#FBEBE7] p-6 text-[#7A3226] sm:p-8",
        className,
      )}
    >
      <LifeBuoy className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <div className="text-sm leading-relaxed sm:text-base">
        <p className="mb-3">{intro}</p>
        <ul className="flex flex-col gap-1 font-medium">
          <li>
            {resources.emergencyLabel}: {resources.emergencyNumber}
          </li>
          {resources.helplines.map((helpline) => (
            <li key={helpline.label}>
              {helpline.label}: {helpline.numbers.join(" / ")}
            </li>
          ))}
        </ul>
        {showProfessionalsLink ? (
          <Link
            href={URGENT_DIRECTORY_HREF}
            className="mt-4 inline-flex items-center gap-1.5 font-semibold underline underline-offset-2"
          >
            Talk to a licensed professional
            <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
        ) : null}
      </div>
    </div>
  );
}

export { CrisisResourcesBox };
