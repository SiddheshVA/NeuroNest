import { cn } from "@/lib/utils";

const RESOURCE_DISCLAIMER =
  "Information on this page is for general wellbeing and is not a medical diagnosis or a substitute for professional care.";

function ResourceDisclaimer({ className }: { className?: string }) {
  return (
    <p className={cn("text-xs leading-relaxed text-muted-foreground", className)}>
      {RESOURCE_DISCLAIMER}
    </p>
  );
}

export { ResourceDisclaimer };
