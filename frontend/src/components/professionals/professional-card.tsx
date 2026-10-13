import Link from "next/link";
import { ArrowRight, Briefcase, IndianRupee, Languages, MapPin, Video } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  CONSULTATION_MODE_LABELS,
  CREDENTIAL_TYPE_LABELS,
  formatExperience,
  formatFee,
  formatLocation,
  type PublicProfessional,
} from "@/lib/professionals-client";
import { CredentialsChip, ProfessionalAvatar } from "@/components/professionals/professional-bits";

// label is read to screen readers only; omit it when the visible text already names the detail.
function Detail({ icon: Icon, label, children }: { icon: LucideIcon; label?: string; children: string }) {
  return (
    <li className="flex items-start gap-2">
      <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <span>
        {label ? <span className="sr-only">{label}: </span> : null}
        {children}
      </span>
    </li>
  );
}

function ProfessionalCard({ professional }: { professional: PublicProfessional }) {
  const location = formatLocation(professional.city, null);
  const fee = formatFee(professional.consultationFee);
  const experience = formatExperience(professional.yearsExperience);

  return (
    <article className="flex flex-col gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10 transition-shadow duration-300 hover:shadow-lg">
      <div className="flex items-start gap-3">
        <ProfessionalAvatar professional={professional} className="size-14 text-lg" />
        <div className="flex min-w-0 flex-col gap-1">
          <h3 className="font-heading text-lg leading-snug font-semibold text-foreground">{professional.fullName}</h3>
          <p className="text-sm text-muted-foreground">
            {CREDENTIAL_TYPE_LABELS[professional.credentialType] ?? professional.credentialType}
          </p>
          <CredentialsChip professional={professional} />
        </div>
      </div>

      <ul className="flex flex-col gap-1.5 text-sm text-foreground">
        <Detail icon={MapPin} label="City">{location ?? "City not listed"}</Detail>
        <Detail icon={Video} label="Consultation mode">
          {CONSULTATION_MODE_LABELS[professional.consultationMode] ?? professional.consultationMode}
        </Detail>
        <Detail icon={Languages} label="Languages">
          {professional.languages.length > 0 ? professional.languages.join(", ") : "Languages not listed"}
        </Detail>
        {fee ? <Detail icon={IndianRupee} label="Consultation fee">{fee}</Detail> : null}
        {experience ? <Detail icon={Briefcase} label="Experience">{experience}</Detail> : null}
      </ul>

      <Link
        href={`/professionals/${professional.id}`}
        className="mt-auto inline-flex h-9 items-center justify-center gap-1.5 self-start rounded-full bg-primary px-4 text-sm font-medium text-primary-foreground outline-none transition-colors hover:bg-primary/85 focus-visible:ring-3 focus-visible:ring-ring/50"
        aria-label={`View profile of ${professional.fullName}`}
      >
        View profile
        <ArrowRight className="size-4" aria-hidden="true" />
      </Link>
    </article>
  );
}

export { ProfessionalCard };
