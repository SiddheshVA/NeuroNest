import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";

/** Heading row shared by the dashboard's sections: icon chip, title and an optional action on the right. */
export function DashboardSectionHeader({
  icon: Icon,
  title,
  id,
  action,
}: {
  icon: LucideIcon;
  title: string;
  id: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-5 flex items-center justify-between gap-3">
      <div className="flex items-center gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-[#ECEFE6] text-[#233E33]">
          <Icon className="size-4.5" aria-hidden="true" />
        </span>
        <h2 id={id} className="text-lg font-semibold">
          {title}
        </h2>
      </div>
      {action}
    </div>
  );
}
