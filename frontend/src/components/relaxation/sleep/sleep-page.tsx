
import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { RelaxationTopbar } from "@/components/relaxation/relaxation-topbar";
import { SleepHero } from "@/components/relaxation/sleep/sleep-hero";
import { SleepExperiences } from "@/components/relaxation/sleep/sleep-experiences";
import { WindDownTips } from "@/components/relaxation/sleep/wind-down-tips";
import { SleepPlayer } from "@/components/relaxation/sleep/sleep-player";
import { useSleepPlayer } from "@/components/relaxation/sleep/use-sleep-player";
import { useTrackListening } from "@/lib/relaxation/use-relaxation-activity";
import {
  sleepExperiences,
  sleepIntentExperienceIds,
} from "@/data/sleep-support";

function SleepPage() {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const player = useSleepPlayer(audioRef);
  const [searchParams] = useSearchParams();
  const requestedSoundId = searchParams.get("sound");
  const appliedSoundIdRef = useRef<string | null>(null);

  useTrackListening({
    itemKey: `sleep:${player.experience.id}`,
    isPlaying: player.isPlaying,
    timerSeconds: player.durationMinutes * 60,
  });

  // Deep link from the hub or search (?sound=<id>): select that sound exactly
  // as if its card had been tapped. If the browser blocks autoplay it simply
  // stays selected, one tap on Play away.
  useEffect(() => {
    if (!requestedSoundId || appliedSoundIdRef.current === requestedSoundId) return;
    const requested = sleepExperiences.find((item) => item.id === requestedSoundId);
    if (!requested) return;
    appliedSoundIdRef.current = requestedSoundId;
    player.selectExperience(requested);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestedSoundId]);
  const [selectedIntentId, setSelectedIntentId] = useState<string | null>(
    null,
  );

  // "all" (or no selection) shows every Sleep Experience; an intent chip
  // narrows the grid to its mapped ids. This never touches the player —
  // filtering only changes which cards are visible, not playback.
  const visibleExperiences = useMemo(() => {
    if (!selectedIntentId || selectedIntentId === "all") {
      return sleepExperiences;
    }
    const allowedIds = sleepIntentExperienceIds[selectedIntentId];
    if (!allowedIds) return sleepExperiences;
    return sleepExperiences.filter((experience) =>
      allowedIds.includes(experience.id),
    );
  }, [selectedIntentId]);

  return (
    <div className="flex flex-col gap-6 pb-24">
      <audio
        ref={audioRef}
        src={player.experience.audioUrl}
        loop
        preload="metadata"
      />

      <RelaxationTopbar currentPage="Sleep Support" />

      <div className="flex flex-col gap-8 sm:gap-9">
        <SleepHero
          selectedIntentId={selectedIntentId}
          onSelectIntent={setSelectedIntentId}
        />
        <SleepExperiences
          experiences={visibleExperiences}
          activeExperienceId={player.experience.id}
          isPlaying={player.isPlaying}
          onSelect={player.selectExperience}
        />
        <WindDownTips />
      </div>

      <SleepPlayer
        experience={player.experience}
        isPlaying={player.isPlaying}
        currentTime={player.currentTime}
        trackDuration={player.trackDuration}
        volume={player.volume}
        isMuted={player.isMuted}
        isShuffleOn={player.isShuffleOn}
        durationMinutes={player.durationMinutes}
        onTogglePlay={player.togglePlay}
        onToggleShuffle={player.toggleShuffle}
        onPrevious={player.previous}
        onNext={player.next}
        onSeek={player.seekTo}
        onVolumeChange={player.changeVolume}
        onToggleMute={player.toggleMute}
        onDurationChange={player.changeDuration}
      />
    </div>
  );
}

export { SleepPage };
