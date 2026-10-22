
import { useState } from "react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useSearchParams } from "react-router-dom";
import { Clock, Headphones, Play } from "lucide-react";

import type { Meditation } from "@/data/meditations";
import { Button } from "@/components/ui/button";
import { RelaxationBenefitsCard } from "@/components/relaxation/relaxation-benefits-card";
import { MeditationExpectations } from "@/components/relaxation/meditation/meditation-expectations";
import { MeditationSession } from "@/components/relaxation/meditation/meditation-session";
import { MeditationComplete } from "@/components/relaxation/meditation/meditation-complete";
import { FavoriteButton } from "@/components/relaxation/favorite-button";
import { useResumePosition } from "@/lib/relaxation/use-relaxation-activity";

function formatMinutesSeconds(totalSeconds: number) {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.floor(totalSeconds % 60);
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

type Mode = "detail" | "session" | "complete";

function MeditationDetail({ meditation }: { meditation: Meditation }) {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("detail");
  const [startAtSeconds, setStartAtSeconds] = useState(0);
  const resumePosition = useResumePosition(`meditation:${meditation.slug}`);
  const [searchParams] = useSearchParams();
  const [resumeLinkHandled, setResumeLinkHandled] = useState(false);

  // Arriving from the hub's "Resume" (?resume=1) jumps straight back in at the
  // saved position. Adjusted during render (React's pattern for deriving state
  // from changing inputs) because the saved position can arrive a moment after
  // mount, once sign-in state is known.
  if (searchParams.get("resume") === "1" && !resumeLinkHandled && resumePosition !== null) {
    setResumeLinkHandled(true);
    setStartAtSeconds(resumePosition);
    setMode("session");
  }

  if (mode === "session") {
    return (
      <MeditationSession
        meditation={meditation}
        startAtSeconds={startAtSeconds}
        onComplete={() => setMode("complete")}
        onEnd={() => setMode("detail")}
      />
    );
  }

  if (mode === "complete") {
    return (
      <MeditationComplete
        meditationTitle={meditation.title}
        durationMinutes={meditation.durationMinutes}
        onDone={() => router.push("/relaxation")}
        onTryAnother={() => router.push("/relaxation/meditation")}
      />
    );
  }

  return (
    <div className="flex flex-col gap-8 pb-4">

      <div className="relative w-full overflow-hidden rounded-2xl lg:h-[300px]">
        <Image
          src={meditation.image}
          alt={meditation.imageAlt}
          fill
          sizes="(min-width: 1024px) 768px, 100vw"
          className="object-cover"
          priority
        />
        <div
          className="absolute inset-0 bg-gradient-to-b from-black/55 via-black/15 to-black/0 lg:bg-gradient-to-r lg:from-black/60 lg:via-black/20 lg:to-transparent"
          aria-hidden="true"
        />
        <div className="relative flex flex-col gap-5 p-5 sm:p-7 lg:h-full lg:flex-row lg:items-center lg:justify-between lg:gap-8 lg:py-8">
          <div className="flex flex-col gap-1.5 lg:max-w-md">
            <span className="w-fit rounded-full bg-white/90 px-2.5 py-1 text-xs font-medium text-foreground">
              {meditation.category}
            </span>
            <h1 className="font-heading text-2xl font-semibold text-white sm:text-3xl">
              {meditation.title}
            </h1>
            <p className="max-w-md text-sm leading-relaxed text-white/85 sm:text-base">
              {meditation.description}
            </p>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <span className="flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-1 text-xs font-medium text-white ring-1 ring-white/25 backdrop-blur-sm">
                <Clock className="size-3.5" aria-hidden="true" />
                {meditation.durationMinutes} min
              </span>
              <span className="flex items-center gap-1.5 rounded-full bg-white/15 px-2.5 py-1 text-xs font-medium text-white ring-1 ring-white/25 backdrop-blur-sm">
                <Headphones className="size-3.5" aria-hidden="true" />
                Guided
              </span>
            </div>
          </div>

          <RelaxationBenefitsCard
            benefits={meditation.benefits}
            className="lg:w-[380px] lg:shrink-0"
          />
        </div>
      </div>

      <MeditationExpectations />

      <div className="flex flex-col gap-2 rounded-2xl bg-muted/50 p-5">
        <h2 className="text-sm font-semibold text-foreground">
          About this practice
        </h2>
        <p className="text-sm leading-relaxed text-muted-foreground">
          {meditation.about}
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        {resumePosition !== null ? (
          <Button
            size="lg"
            className="h-12 w-fit gap-1.5 rounded-full px-8 text-base"
            onClick={() => {
              setStartAtSeconds(resumePosition);
              setMode("session");
            }}
          >
            <Play data-icon="inline-start" aria-hidden="true" />
            Resume from {formatMinutesSeconds(resumePosition)}
          </Button>
        ) : null}
        <Button
          size="lg"
          variant={resumePosition !== null ? "outline" : "default"}
          className="h-12 w-fit rounded-full px-8 text-base"
          onClick={() => {
            setStartAtSeconds(0);
            setMode("session");
          }}
        >
          {resumePosition !== null ? "Start from the beginning" : "Start Meditation"}
        </Button>
        <FavoriteButton
          itemKey={`meditation:${meditation.slug}`}
          title={meditation.title}
          variant="pill"
        />
      </div>
    </div>
  );
}

export { MeditationDetail };
