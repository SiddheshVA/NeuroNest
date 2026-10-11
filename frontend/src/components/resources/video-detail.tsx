import { Clock, Tv } from "lucide-react";
import { ResourceBackLink } from "@/components/resources/resource-back-link";
import { ResourceDisclaimer } from "@/components/resources/resource-disclaimer";
import type { Video } from "@/data/wellness-resources";

function VideoDetail({ video }: { video: Video }) {
  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-6">
      <ResourceBackLink tab="videos" />

      <div className="aspect-video w-full overflow-hidden rounded-2xl bg-black ring-1 ring-foreground/10">
        <iframe
          src={`https://www.youtube-nocookie.com/embed/${video.youtubeId}`}
          title={video.title}
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
          allowFullScreen
          className="size-full"
        />
      </div>

      <header className="flex flex-col gap-3">
        <h1 className="font-heading text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
          {video.title}
        </h1>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
          <span className="flex items-center gap-1">
            <Tv className="size-4" aria-hidden="true" />
            {video.sourceName}
          </span>
          <span className="flex items-center gap-1">
            <Clock className="size-4" aria-hidden="true" />
            {video.durationMinutes} min
          </span>
        </div>
      </header>

      <p className="max-w-3xl text-base leading-relaxed text-foreground/85">{video.description}</p>

      <ResourceDisclaimer />
    </div>
  );
}

export { VideoDetail };
