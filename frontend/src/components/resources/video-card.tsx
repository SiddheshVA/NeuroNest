import Link from "next/link";
import { Clock, Play } from "lucide-react";
import type { Video } from "@/data/wellness-resources";

function VideoCard({ video }: { video: Video }) {
  return (
    <Link
      href={`/resources/videos/${video.slug}`}
      className="group flex flex-col overflow-hidden rounded-2xl bg-card outline-none ring-1 ring-foreground/10 transition-all duration-300 hover:-translate-y-1 hover:shadow-lg focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <div className="relative aspect-video w-full overflow-hidden bg-muted">
        <img
          src={`https://i.ytimg.com/vi/${video.youtubeId}/hqdefault.jpg`}
          alt=""
          loading="lazy"
          className="size-full object-cover transition-transform duration-500 ease-out group-hover:scale-105"
        />
        <span className="absolute inset-0 flex items-center justify-center">
          <span className="flex size-11 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm">
            <Play className="size-5 translate-x-px" aria-hidden="true" />
          </span>
        </span>
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <h3 className="font-heading text-base font-semibold text-foreground">{video.title}</h3>
        <span className="mt-auto flex items-center gap-1 text-xs text-muted-foreground">
          <Clock className="size-3.5" aria-hidden="true" />
          {video.durationMinutes} min
        </span>
      </div>
    </Link>
  );
}

export { VideoCard };
