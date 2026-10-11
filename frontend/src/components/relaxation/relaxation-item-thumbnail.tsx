import Image from "next/image";

import type { RelaxationItem } from "@/data/relaxation";
import { cn } from "@/lib/utils";

/**
 * The item's own visual: its photo (breathing, meditation) or its own icon
 * on its own gradient (Mindful Reset, sleep sounds) — the same treatment
 * those sections use on their own pages.
 */
function RelaxationItemThumbnail({
  item,
  className,
  sizes = "44px",
  iconClassName = "size-5",
}: {
  item: RelaxationItem;
  className?: string;
  sizes?: string;
  iconClassName?: string;
}) {
  if (item.image) {
    return (
      <div className={cn("relative shrink-0 overflow-hidden rounded-lg", className)}>
        <Image src={item.image} alt="" fill sizes={sizes} className="object-cover" />
      </div>
    );
  }

  const Icon = item.icon;

  return (
    <div
      className={cn(
        "flex shrink-0 items-center justify-center rounded-lg bg-gradient-to-br",
        item.gradientClassName,
        item.gradientTone === "dark" ? "text-white" : "text-primary",
        className,
      )}
      aria-hidden="true"
    >
      {Icon ? <Icon className={iconClassName} /> : null}
    </div>
  );
}

export { RelaxationItemThumbnail };
