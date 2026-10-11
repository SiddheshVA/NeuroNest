import { cn } from "@/lib/utils";

/** Single-select chip row; clicking the active chip clears the filter. */
function ResourceFilterChips({
  options,
  selected,
  onSelect,
  label,
}: {
  options: readonly string[];
  selected: string | null;
  onSelect: (value: string | null) => void;
  label: string;
}) {
  if (options.length === 0) return null;

  return (
    <div
      className="flex flex-wrap gap-2"
      role="group"
      aria-label={label}
    >
      {["All", ...options].map((option) => {
        const value = option === "All" ? null : option;
        const isSelected = selected === value;
        return (
          <button
            key={option}
            type="button"
            aria-pressed={isSelected}
            onClick={() => onSelect(isSelected && value !== null ? null : value)}
            className={cn(
              "shrink-0 rounded-full border px-3.5 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
              isSelected
                ? "border-transparent bg-primary text-primary-foreground"
                : "border-border bg-card text-foreground hover:bg-muted",
            )}
          >
            {option}
          </button>
        );
      })}
    </div>
  );
}

export { ResourceFilterChips };
