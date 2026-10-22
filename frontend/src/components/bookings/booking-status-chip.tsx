import type { BookingStatus } from "@/lib/bookings-client";
import { cn } from "@/lib/utils";

/** The same colours on the user's and the professional's pages. */
const STATUS_CHIP_CLASSES: Record<BookingStatus, string> = {
  PENDING: "bg-amber-50 text-amber-800 ring-amber-200",
  CONFIRMED: "bg-emerald-50 text-emerald-800 ring-emerald-200",
  DECLINED: "bg-red-50 text-red-800 ring-red-200",
  ALTERNATE_PROPOSED: "bg-sky-50 text-sky-800 ring-sky-200",
  CANCELLED: "bg-muted text-muted-foreground ring-border",
  EXPIRED: "bg-muted text-muted-foreground ring-border",
};

export function BookingStatusChip({ status, label }: { status: BookingStatus; label: string }) {
  return (
    <span className={cn("shrink-0 rounded-full px-3 py-1 text-xs font-medium ring-1", STATUS_CHIP_CLASSES[status])}>
      {label}
    </span>
  );
}
