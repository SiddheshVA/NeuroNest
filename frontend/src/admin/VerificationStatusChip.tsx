import Chip, { type ChipProps } from "@mui/material/Chip";
import { VERIFICATION_STATUS_LABELS, type VerificationStatus } from "./types";

const STATUS_COLORS: Record<VerificationStatus, ChipProps["color"]> = {
  VERIFIED: "success",
  DRAFT: "default",
  SUBMITTED: "info",
  NEEDS_INFO: "warning",
  REJECTED: "error",
  EXPIRED: "error",
  SUSPENDED: "error",
};

export function VerificationStatusChip({ status, size = "small" }: { status: VerificationStatus; size?: ChipProps["size"] }) {
  return (
    <Chip
      size={size}
      color={STATUS_COLORS[status] ?? "default"}
      variant={status === "DRAFT" ? "outlined" : "filled"}
      label={VERIFICATION_STATUS_LABELS[status] ?? status}
    />
  );
}

export function SampleChip() {
  return <Chip size="small" variant="outlined" color="secondary" label="Sample" />;
}
