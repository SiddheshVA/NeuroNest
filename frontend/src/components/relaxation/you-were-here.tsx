import Link from "next/link";
import { Play, RotateCcw } from "lucide-react";

import { useLastOpenedItem } from "@/lib/relaxation/use-relaxation-activity";
import { RelaxationItemThumbnail } from "@/components/relaxation/relaxation-item-thumbnail";
import { Button } from "@/components/ui/button";

function formatAmount(seconds: number) {
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} sec`;
  return `${Math.floor(seconds / 60)} min`;
}

/** "Last time: 8 of 20 min", or "Last time: 40 sec of 5 min" under a minute. */
function formatLastTime(progressSeconds: number, durationSeconds: number) {
  const totalMinutes = Math.max(1, Math.round(durationSeconds / 60));
  const done =
    progressSeconds < 60
      ? `${Math.max(1, Math.round(progressSeconds))} sec`
      : `${Math.min(Math.floor(progressSeconds / 60), totalMinutes)}`;
  return `Last time: ${done} of ${totalMinutes} min`;
}

/**
 * "You were here": the last thing opened in the Relaxation Hub, of any type.
 * Sleep sounds reopen selected on the Sleep page; meditation resumes from the
 * saved position when it wasn't finished; breathing and Mindful Reset engines
 * always start from the top, so those say "Start again". Renders nothing
 * until something has been opened.
 */
function YouWereHere() {
  const lastOpened = useLastOpenedItem();
  if (!lastOpened) return null;

  const { item, entry } = lastOpened;
  const isSleep = item.kind === "sleep";
  const canResume =
    item.kind === "meditation" && !entry.completed && entry.progressSeconds < entry.durationSeconds;

  const heading = isSleep ? `You were listening to ${item.title}` : `You were doing ${item.title}`;
  const detail = isSleep
    ? `Listened for ${formatAmount(entry.progressSeconds)}`
    : canResume
      ? `${formatAmount(entry.durationSeconds - entry.progressSeconds)} left`
      : formatLastTime(entry.progressSeconds, entry.durationSeconds);
  const href = canResume ? `${item.href}?resume=1` : item.href;
  const actionLabel = isSleep ? "Play" : canResume ? "Resume" : "Start again";
  const progressPercent = Math.min(
    100,
    Math.round((entry.progressSeconds / Math.max(entry.durationSeconds, 1)) * 100),
  );

  return (
    <section
      aria-labelledby="you-were-here-heading"
      className="flex flex-col gap-4 rounded-2xl bg-card p-4 ring-1 ring-foreground/10 sm:flex-row sm:items-center"
    >
      <RelaxationItemThumbnail
        item={item}
        className="aspect-video w-full rounded-xl sm:aspect-square sm:w-16"
        sizes="(min-width: 640px) 64px, 100vw"
        iconClassName="size-7"
      />

      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <h2
          id="you-were-here-heading"
          className="font-heading text-base font-semibold text-foreground sm:text-lg"
        >
          {heading}
        </h2>
        <p className="text-sm text-muted-foreground">{item.category}</p>
        <div className="mt-1 flex items-center gap-3">
          {isSleep ? null : (
            <div
              className="h-1.5 w-full max-w-48 overflow-hidden rounded-full bg-muted"
              role="progressbar"
              aria-label={`${item.title} progress last time`}
              aria-valuenow={progressPercent}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              <div className="h-full rounded-full bg-primary" style={{ width: `${progressPercent}%` }} />
            </div>
          )}
          <span className="shrink-0 text-xs text-muted-foreground">{detail}</span>
        </div>
      </div>

      <Button
        size="lg"
        className="h-9 gap-1.5 self-start px-4 sm:self-center"
        nativeButton={false}
        render={<Link href={href} />}
      >
        {actionLabel === "Start again" ? (
          <RotateCcw data-icon="inline-start" aria-hidden="true" />
        ) : (
          <Play data-icon="inline-start" aria-hidden="true" />
        )}
        {actionLabel}
      </Button>
    </section>
  );
}

export { YouWereHere };
