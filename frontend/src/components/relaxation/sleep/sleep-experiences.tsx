
import { useId, useState } from "react";
import { Info } from "lucide-react";

import type { SleepExperience } from "@/data/sleep-support";
import { SleepExperienceCard } from "@/components/relaxation/sleep/sleep-experience-card";

function SleepExperiences({
  experiences,
  activeExperienceId,
  isPlaying,
  onSelect,
}: {
  experiences: SleepExperience[];
  activeExperienceId: string;
  isPlaying: boolean;
  onSelect: (experience: SleepExperience) => void;
}) {
  const [showHowItHelps, setShowHowItHelps] = useState(false);
  const howItHelpsId = useId();

  return (
    <section aria-labelledby="sleep-experiences-heading">
      <div className="flex items-center justify-between gap-2">
        <h2
          id="sleep-experiences-heading"
          className="font-heading text-base font-semibold text-foreground sm:text-lg"
        >
          Sleep Experiences
        </h2>
        <button
          type="button"
          aria-expanded={showHowItHelps}
          aria-controls={howItHelpsId}
          onClick={() => setShowHowItHelps((prev) => !prev)}
          className="flex items-center gap-1 text-xs font-medium text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          How it helps
          <Info className="size-3.5" aria-hidden="true" />
        </button>
      </div>

      {showHowItHelps ? (
        <p
          id={howItHelpsId}
          className="mt-3 rounded-xl bg-muted/50 px-4 py-3 text-sm leading-relaxed text-muted-foreground"
        >
          A steady, gentle sound can soften sudden noises around you, like
          traffic or a door closing, so they&apos;re less likely to catch your
          attention. It also gives your mind something calm and predictable to
          rest on, which can make it easier to let the day&apos;s thoughts go.
          Keep the volume low and use the timer so it fades out on its own.
        </p>
      ) : null}

      <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {experiences.map((experience) => (
          <SleepExperienceCard
            key={experience.id}
            experience={experience}
            isActive={activeExperienceId === experience.id}
            isPlaying={isPlaying}
            onSelect={() => onSelect(experience)}
          />
        ))}
      </div>
    </section>
  );
}

export { SleepExperiences };
