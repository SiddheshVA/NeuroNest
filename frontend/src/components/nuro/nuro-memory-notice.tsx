import { useState } from "react";
import Link from "next/link";
import { Brain } from "lucide-react";

import { Button } from "@/components/ui/button";

// One-time disclosure about long-term memory, shown to each signed-in user the
// first time they open the chat. Dismissal is remembered per user in this
// browser; if storage is unavailable the notice just shows again next time.
function storageKey(userId: string) {
  return `nuronest:memory-notice-dismissed:${userId}`;
}

function wasDismissed(userId: string) {
  try {
    return window.localStorage.getItem(storageKey(userId)) === "1";
  } catch {
    return false;
  }
}

function rememberDismissal(userId: string) {
  try {
    window.localStorage.setItem(storageKey(userId), "1");
  } catch {
    // Storage unavailable (e.g. private mode) — nothing else to do.
  }
}

export function NuroMemoryNotice({ userId }: { userId: string }) {
  const [visible, setVisible] = useState(() => !wasDismissed(userId));

  if (!visible) return null;

  const dismiss = () => {
    rememberDismissal(userId);
    setVisible(false);
  };

  return (
    <div
      role="note"
      className="flex flex-col gap-3 rounded-2xl border border-border bg-background/95 px-4 py-3.5 text-sm shadow-xs sm:flex-row sm:items-center"
    >
      <div className="flex items-start gap-2.5">
        <Brain className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
        <p className="text-foreground">
          Neuro remembers key things you share (like goals or what helps you) to support you better
          over time. You can view, delete, or turn this off anytime in Settings.
        </p>
      </div>
      <div className="flex shrink-0 gap-2 pl-6 sm:pl-0">
        <Button size="sm" onClick={dismiss}>
          Got it
        </Button>
        <Button
          size="sm"
          variant="outline"
          nativeButton={false}
          render={<Link href="/settings/memory" onClick={dismiss} />}
        >
          Manage memory
        </Button>
      </div>
    </div>
  );
}
