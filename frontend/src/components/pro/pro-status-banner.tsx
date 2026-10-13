import { AlertTriangle, BadgeCheck, CircleDashed, Hourglass, MessageSquareWarning, XCircle, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ProMe, ProVerificationStatus } from "@/lib/pro-client";

type Tone = "neutral" | "info" | "success" | "warning" | "danger";

const TONES: Record<Tone, string> = {
  neutral: "border-border bg-muted/60 text-foreground",
  info: "border-sky-200 bg-sky-50 text-sky-900 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-100",
  success: "border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-100",
  warning: "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100",
  danger: "border-destructive/30 bg-destructive/5 text-destructive",
};

const STATUS: Record<ProVerificationStatus, { tone: Tone; icon: LucideIcon; title: string; text: string }> = {
  DRAFT: {
    tone: "neutral",
    icon: CircleDashed,
    title: "Your profile is not submitted yet.",
    text: "Complete your details, upload your documents and submit your profile for review when you are ready.",
  },
  SUBMITTED: {
    tone: "info",
    icon: Hourglass,
    title: "Your profile is under review. You will be notified when it is approved.",
    text: "You can still update your practice details while you wait.",
  },
  VERIFIED: {
    tone: "success",
    icon: BadgeCheck,
    title: "Your profile is approved and visible, if your listing is active.",
    text: "You can keep your practice details up to date at any time.",
  },
  NEEDS_INFO: {
    tone: "warning",
    icon: MessageSquareWarning,
    title: "The NeuroNest team needs more information before approving your profile.",
    text: "Fix what the note below asks for, save your changes, upload any missing documents and submit your profile again.",
  },
  REJECTED: {
    tone: "danger",
    icon: XCircle,
    title: "Your profile was not approved.",
    text: "Please contact NeuroNest support if you have questions about this decision.",
  },
  EXPIRED: {
    tone: "danger",
    icon: AlertTriangle,
    title: "Your registration has expired, so your profile is no longer listed.",
    text: "Please contact NeuroNest support to update your registration details.",
  },
  SUSPENDED: {
    tone: "danger",
    icon: AlertTriangle,
    title: "Your profile has been suspended and is not listed.",
    text: "Please contact NeuroNest support to find out more.",
  },
};

const NOTE_STATUSES = new Set<ProVerificationStatus>(["NEEDS_INFO", "REJECTED", "EXPIRED", "SUSPENDED"]);

function ProStatusBanner({ me }: { me: ProMe }) {
  const status = STATUS[me.profile.verificationStatus] ?? STATUS.DRAFT;
  const note = me.verificationNote?.note;
  const Icon = status.icon;
  // Only where the note explains the current state; an earlier "needs information" note would be
  // stale once the profile is resubmitted or approved.
  const showNote = Boolean(note) && NOTE_STATUSES.has(me.profile.verificationStatus);

  return (
    <div role="status" className={cn("flex flex-col gap-3 rounded-2xl border p-5", TONES[status.tone])}>
      <div className="flex items-start gap-3">
        <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <div className="flex flex-col gap-1">
          <p className="font-semibold">{status.title}</p>
          <p className="text-sm opacity-90">{status.text}</p>
        </div>
      </div>
      {showNote ? (
        <div
          className={cn(
            "rounded-xl bg-background/80 px-4 py-3 text-sm text-foreground ring-1 ring-foreground/10",
            me.profile.verificationStatus === "NEEDS_INFO" && "ring-2 ring-amber-400",
          )}
        >
          <p className="mb-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">Note from the NeuroNest team</p>
          <p className="whitespace-pre-line">{note}</p>
        </div>
      ) : null}
      {me.profile.verificationStatus === "VERIFIED" ? (
        <p className="text-sm">
          {me.profile.active ? "Your listing is active and appears in the directory." : "Your listing is not active right now, so it does not appear in the directory."}
        </p>
      ) : null}
    </div>
  );
}

export { ProStatusBanner };
