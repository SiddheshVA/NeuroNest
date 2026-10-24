import { useState } from "react";
import { BadgeCheck, FlaskConical, Info, ShieldCheck, Star } from "lucide-react";
import { cn } from "@/lib/utils";
import { formatRating, type PublicProfessional } from "@/lib/professionals-client";

/** Small building blocks shared by the directory cards and the profile page. */

function initialsOf(name: string): string {
  // Skip honorifics so "Dr. Ananya Rao" becomes "AR", not "DA".
  const words = name
    .replace(/\b(dr|mr|mrs|ms|prof)\.?\s+/gi, "")
    .split(/\s+/)
    .filter(Boolean);
  const letters = words.length > 1 ? [words[0], words[words.length - 1]] : words;
  return letters.map((word) => word[0]!.toUpperCase()).join("") || "?";
}

/** The listing's photo, or an initials avatar when there is none or it fails to load. */
function ProfessionalAvatar({
  professional,
  className,
}: {
  professional: Pick<PublicProfessional, "fullName" | "photoUrl">;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const showPhoto = Boolean(professional.photoUrl) && !failed;

  return (
    <span
      className={cn(
        "flex shrink-0 items-center justify-center overflow-hidden rounded-full bg-primary/10 font-heading font-semibold text-primary",
        className,
      )}
    >
      {showPhoto ? (
        <img
          src={professional.photoUrl!}
          alt={`Photo of ${professional.fullName}`}
          className="size-full object-cover"
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={() => setFailed(true)}
        />
      ) : (
        <span aria-hidden="true">{initialsOf(professional.fullName)}</span>
      )}
    </span>
  );
}

/**
 * The listing's credentials status. A sample listing's credentialsLabel is already "Sample listing",
 * so sample listings get a single, distinct "Sample listing" chip instead of two identical ones.
 */
function CredentialsChip({
  professional,
  className,
}: {
  professional: Pick<PublicProfessional, "credentialsLabel" | "isSampleData">;
  className?: string;
}) {
  const sample = professional.isSampleData;
  const Icon = sample ? FlaskConical : professional.credentialsLabel === "Registration verified" ? BadgeCheck : ShieldCheck;
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium whitespace-nowrap",
        sample
          ? "border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-200"
          : "border-transparent bg-secondary text-secondary-foreground",
        className,
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {sample ? "Sample listing" : professional.credentialsLabel}
    </span>
  );
}

const ENDORSEMENT_DISCLAIMER =
  "Listings are not an endorsement. NeuroNest does not recommend any individual professional.";
const NOT_EMERGENCY_DISCLAIMER = "This directory is not an emergency service.";
const RATINGS_DISCLAIMER =
  "Ratings come from clients who booked through NeuroNest and are not a clinical measure.";

/** Both directory disclaimers, shown above the listings so they are seen before any card. */
function DirectoryDisclaimer({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "flex items-start gap-2.5 rounded-2xl border border-border bg-muted/60 px-4 py-3 text-sm text-muted-foreground",
        className,
      )}
    >
      <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <div className="flex flex-col gap-0.5">
        <p>{ENDORSEMENT_DISCLAIMER}</p>
        <p>{RATINGS_DISCLAIMER}</p>
        <p className="font-medium text-foreground">{NOT_EMERGENCY_DISCLAIMER}</p>
      </div>
    </div>
  );
}

/**
 * Five stars for a rating, rounded to the nearest whole star. Decorative: the number is always given as
 * text next to it (or by the caller's own label).
 */
function StarRow({ rating, className }: { rating: number; className?: string }) {
  const filled = Math.round(rating);
  return (
    <span className={cn("inline-flex items-center gap-0.5", className)} aria-hidden="true">
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          className={cn("size-4 shrink-0", n <= filled ? "fill-amber-500 text-amber-600" : "text-muted-foreground/50")}
        />
      ))}
    </span>
  );
}

/** "★★★★★ 4.8 (52 reviews)", or a "New" chip for a listing without reviews yet. */
function RatingSummary({
  ratingAvg,
  ratingCount,
  className,
}: {
  ratingAvg: number | null;
  ratingCount: number;
  className?: string;
}) {
  if (!ratingCount || ratingAvg === null) {
    return (
      <span
        className={cn(
          "inline-flex w-fit items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground",
          className,
        )}
      >
        New
        <span className="sr-only">: no reviews yet</span>
      </span>
    );
  }
  const reviews = `${ratingCount} ${ratingCount === 1 ? "review" : "reviews"}`;
  return (
    <span className={cn("inline-flex flex-wrap items-center gap-1.5 text-sm", className)}>
      <StarRow rating={ratingAvg} />
      <span className="font-semibold text-foreground" aria-hidden="true">
        {formatRating(ratingAvg)}
      </span>
      <span className="text-muted-foreground" aria-hidden="true">
        ({reviews})
      </span>
      <span className="sr-only">
        Rated {formatRating(ratingAvg)} out of 5 from {reviews}
      </span>
    </span>
  );
}

export { CredentialsChip, DirectoryDisclaimer, ProfessionalAvatar, RatingSummary, StarRow };
