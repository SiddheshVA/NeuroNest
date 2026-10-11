
import Link from "next/link";
import { useUser, Show, UserButton } from "@clerk/nextjs";
import { ChevronRight } from "lucide-react";

import { Button } from "@/components/ui/button";
import { RelaxationSearch } from "@/components/relaxation/relaxation-search";

/**
 * The single desktop header shared by every Relaxation Hub page (the hub
 * itself, Breathing, Meditation, Sleep Support, Relaxing Sounds,
 * Instrumental Music). Only `currentPage` changes per page — everything
 * else (search, Log In/account) stays identical so the hub never grows a
 * page-specific header again. Hidden on mobile in favor of the shared
 * `RelaxationMobileTopbar`, same as before.
 */
function RelaxationTopbar({ currentPage }: { currentPage: string }) {
  const { user } = useUser();
  const isHubRoot = currentPage === "Relaxation Hub";

  return (
    <div className="hidden items-center justify-between gap-4 border-b border-border/50 pb-3 lg:flex">
      <nav
        aria-label="Breadcrumb"
        className="flex min-w-0 items-center gap-1.5"
      >
        {isHubRoot ? null : (
          <>
            <Link
              href="/relaxation"
              className="shrink-0 rounded-sm text-sm font-medium text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              Relaxation Hub
            </Link>
            <ChevronRight
              className="size-3.5 shrink-0 text-muted-foreground/50"
              aria-hidden="true"
            />
          </>
        )}
        <span
          className="font-heading truncate text-base font-semibold text-foreground"
          aria-current="page"
        >
          {currentPage}
        </span>
      </nav>

      <div className="flex shrink-0 items-center gap-3">
        <RelaxationSearch className="w-64 max-w-full" />

        <Show
          when="signed-in"
          fallback={
            <Button
              size="lg"
              className="shrink-0 rounded-full px-4"
              nativeButton={false}
              render={<Link href="/login" />}
            >
              Log In
            </Button>
          }
        >
          <div className="flex shrink-0 items-center gap-2">
            <UserButton />
            <span className="sr-only">{user?.firstName ?? "Account"}</span>
          </div>
        </Show>
      </div>
    </div>
  );
}

export { RelaxationTopbar };
