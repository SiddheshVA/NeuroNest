import Chip, { type ChipProps } from "@mui/material/Chip";
import { BOOKING_STATUS_LABELS, type BookingStatus } from "./types";

const STATUS_COLORS: Record<BookingStatus, ChipProps["color"]> = {
  PENDING: "warning",
  CONFIRMED: "success",
  ALTERNATE_PROPOSED: "info",
  DECLINED: "error",
  CANCELLED: "default",
  EXPIRED: "default",
};

export function BookingStatusChip({ status }: { status: BookingStatus }) {
  return (
    <Chip
      size="small"
      color={STATUS_COLORS[status] ?? "default"}
      variant={status === "CANCELLED" || status === "EXPIRED" ? "outlined" : "filled"}
      label={BOOKING_STATUS_LABELS[status] ?? status}
    />
  );
}
