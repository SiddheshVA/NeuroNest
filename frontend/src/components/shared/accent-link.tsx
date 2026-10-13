import { Link } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * A compact, highlighted cross-link (a tinted rounded strip with an icon and a bold link). Used to
 * point between the user sign-in and the professional sign-in.
 */
function AccentLink({
  to,
  icon: Icon,
  children,
  className,
}: {
  to: string;
  icon: LucideIcon;
  children: string;
  className?: string;
}) {
  return (
    <Link
      to={to}
      className={cn(
        "group inline-flex w-full max-w-[25rem] items-center gap-2.5 rounded-full border border-primary/20 bg-primary/10 px-4 py-2.5 text-sm font-semibold text-primary outline-none transition-colors hover:bg-primary/15 focus-visible:ring-3 focus-visible:ring-ring/50",
        className,
      )}
    >
      <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary text-primary-foreground">
        <Icon className="size-4" aria-hidden="true" />
      </span>
      <span className="underline-offset-4 group-hover:underline">{children}</span>
    </Link>
  );
}

export { AccentLink };
