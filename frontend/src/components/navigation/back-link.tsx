import { useEffect, useMemo } from "react";
import { Link, useLocation, useNavigationType } from "react-router-dom";
import { ArrowLeft } from "lucide-react";

import { backTargetFor, recordNavigation, useBackNavigation, type BackTarget } from "@/lib/back-navigation";
import { cn } from "@/lib/utils";

/**
 * "Back to <label>" for the public app and the professional portal, with the parent taken from the
 * central map (lib/back-navigation) for the current page. Renders nothing on a page without a parent.
 * A real link, so it also works with the keyboard, a middle click and the profile page's
 * unsaved-changes check, which already guards every in-app link.
 */
export function BackLink({ className }: { className?: string }) {
  const { pathname } = useLocation();
  const target: BackTarget | null = useMemo(() => backTargetFor(pathname), [pathname]);
  const onClick = useBackNavigation(target);
  if (!target) return null;
  return (
    <Link
      to={target.parentPath}
      onClick={onClick}
      className={cn(
        // At least 44 px tall to tap; pulled left so the arrow lines up with the content edge.
        "-ml-2 inline-flex min-h-11 w-fit items-center gap-1.5 rounded-full px-2 text-sm font-medium text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50",
        className,
      )}
    >
      <ArrowLeft className="size-4 shrink-0" aria-hidden="true" />
      Back to {target.label}
    </Link>
  );
}

/** Keeps track of the previous in-app page for the back controls. Mounted once, inside the router. */
export function NavigationTracker() {
  const location = useLocation();
  const type = useNavigationType();
  useEffect(() => {
    recordNavigation(location.key, location.pathname, type);
  }, [location.key, location.pathname, type]);
  return null;
}
