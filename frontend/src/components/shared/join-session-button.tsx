import { useState } from "react";
import { Loader2, Video } from "lucide-react";

import { primaryButtonClass } from "@/components/pro/pro-ui";
import { joinRefusalMessage, type JoinLink } from "@/lib/join-session";
import { cn } from "@/lib/utils";

type JoinError = { status: number; body?: unknown; message: string };

/**
 * "Join session": asks the server for the video link (the only place it is given out) and opens it in a new
 * tab. Outside the session's window it shows "Opens at …" or "This session has ended" instead.
 */
export function JoinSessionButton({ join, className }: { join: () => Promise<JoinLink>; className?: string }) {
  const [joining, setJoining] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const onJoin = async () => {
    if (joining) return;
    setJoining(true);
    setMessage(null);
    // Opened now, while the click still counts, so the browser does not block it as a pop-up.
    const tab = window.open("", "_blank");
    try {
      const { meetingUri } = await join();
      if (tab) {
        tab.opener = null;
        tab.location.href = meetingUri;
      } else {
        window.open(meetingUri, "_blank", "noopener,noreferrer");
      }
    } catch (e) {
      tab?.close();
      const error = e as Partial<JoinError>;
      setMessage(
        joinRefusalMessage(error.status ?? 0, error.body, error.message || "The session could not be opened. Please try again."),
      );
    } finally {
      setJoining(false);
    }
  };

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <button type="button" className={cn(primaryButtonClass, "w-fit")} disabled={joining} onClick={() => void onJoin()}>
        {joining ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : <Video className="size-4" aria-hidden="true" />}
        Join session
      </button>
      {message ? (
        <p role="status" className="text-sm text-muted-foreground">
          {message}
        </p>
      ) : null}
    </div>
  );
}
