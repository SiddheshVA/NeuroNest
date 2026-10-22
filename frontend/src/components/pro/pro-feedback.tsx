import { useEffect } from "react";
import { CheckCircle2, Copy, X } from "lucide-react";

import { secondaryButtonClass } from "@/components/pro/pro-ui";
import { cn } from "@/lib/utils";

/** A short message at the bottom of the screen that goes away by itself. Pass a stable onClose. */
export function ProSnackbar({ message, onClose }: { message: string; onClose: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onClose, 5000);
    return () => window.clearTimeout(timer);
  }, [message, onClose]);
  return (
    <div className="fixed inset-x-4 bottom-4 z-50 flex justify-center">
      <div role="status" className="flex items-center gap-3 rounded-xl bg-foreground px-4 py-3 text-sm text-background shadow-lg">
        <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
        <span>{message}</span>
        <button type="button" onClick={onClose} className="rounded-full p-0.5 hover:bg-background/20" aria-label="Close message">
          <X className="size-4" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}

/** Copies a value and reports the result through onDone. */
export function CopyButton({ value, label, onDone }: { value: string; label: string; onDone: (message: string) => void }) {
  return (
    <button
      type="button"
      className={cn(secondaryButtonClass, "h-8 px-3 text-xs")}
      aria-label={label}
      onClick={() => {
        navigator.clipboard
          .writeText(value)
          .then(() => onDone("Copied."))
          .catch(() => onDone("This could not be copied. Please copy it by hand."));
      }}
    >
      <Copy className="size-3.5" aria-hidden="true" />
      Copy
    </button>
  );
}
