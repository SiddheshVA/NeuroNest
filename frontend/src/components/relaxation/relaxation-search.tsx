import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useNavigate } from "react-router-dom";
import { Search } from "lucide-react";

import { searchRelaxationItems } from "@/data/relaxation";
import { RelaxationItemThumbnail } from "@/components/relaxation/relaxation-item-thumbnail";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/**
 * In-browser search across every Breathing, Meditation, Mindful Reset and
 * Sleep item (title, category, tags). Shared by the desktop and mobile
 * Relaxation top bars so both behave identically.
 */
function RelaxationSearch({ className }: { className?: string }) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const listId = useId();

  const results = useMemo(() => searchRelaxationItems(query), [query]);
  const showResults = open && query.trim().length > 0;

  useEffect(() => {
    if (!showResults) return;
    function handlePointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener("pointerdown", handlePointerDown);
    return () => document.removeEventListener("pointerdown", handlePointerDown);
  }, [showResults]);

  function reset() {
    setQuery("");
    setOpen(false);
  }

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      <Search
        className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden="true"
      />
      <Input
        type="search"
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            setOpen(false);
          } else if (event.key === "Enter" && results[0]) {
            event.preventDefault();
            navigate(results[0].href);
            reset();
          }
        }}
        placeholder="Search meditations, sounds or topics..."
        aria-label="Search meditations, sounds or topics"
        aria-expanded={showResults}
        aria-controls={listId}
        className="h-9 rounded-full border-border/60 bg-card/70 pl-9 text-sm"
      />

      {showResults ? (
        <div
          id={listId}
          className="absolute top-full right-0 left-0 z-40 mt-2 overflow-hidden rounded-xl bg-card shadow-lg ring-1 ring-foreground/10"
        >
          {results.length === 0 ? (
            <p className="px-4 py-3 text-sm text-muted-foreground">No matches</p>
          ) : (
            <ul className="max-h-96 overflow-y-auto py-1">
              {results.map((item) => (
                <li key={item.key}>
                  <Link
                    href={item.href}
                    onClick={reset}
                    className="flex items-center gap-3 px-3 py-2 outline-none transition-colors hover:bg-muted/60 focus-visible:bg-muted/60"
                  >
                    <RelaxationItemThumbnail item={item} className="size-9" />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-semibold text-foreground">
                        {item.title}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground">
                        {item.category} &middot; {item.duration}
                      </span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}

export { RelaxationSearch };
