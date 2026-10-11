import type { ReactNode } from "react";
import { LifeBuoy } from "lucide-react";
import { getCrisisResources } from "@/lib/ai/crisis-resources";
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
}: {
  countryCode?: string;
  intro?: ReactNode;
  className?: string;
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
      </div>
    </div>
  );
}

export { CrisisResourcesBox };
