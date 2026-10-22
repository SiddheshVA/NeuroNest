import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import {
  ArrowLeft,
  Briefcase,
  CalendarX,
  GraduationCap,
  IndianRupee,
  Languages,
  MapPin,
  RefreshCw,
  Video,
  type LucideIcon,
} from "lucide-react";

import { Container } from "@/components/layout/container";
import { BookingSection, BookingStatusCard } from "@/components/professionals/booking-section";
import { CredentialsChip, DirectoryDisclaimer, ProfessionalAvatar } from "@/components/professionals/professional-bits";
import { WeeklyAvailabilityTable } from "@/components/professionals/weekly-availability";
import {
  CONSULTATION_MODE_LABELS,
  CREDENTIAL_TYPE_LABELS,
  formatExperience,
  formatFee,
  formatLocation,
  getPublicProfessional,
  ProfessionalsApiError,
  type PublicProfessional,
} from "@/lib/professionals-client";

const REGISTRATION_BODY_LABELS: Record<string, string> = {
  RCI: "Rehabilitation Council of India (RCI)",
  NMC: "National Medical Commission (NMC)",
  OTHER: "Another registration body",
};

function BackToDirectory() {
  return (
    <Link
      href="/professionals"
      className="inline-flex w-fit items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline"
    >
      <ArrowLeft className="size-4" aria-hidden="true" />
      Back to the directory
    </Link>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2.5">
      <h2 className="font-heading text-lg font-semibold text-foreground">{title}</h2>
      {children}
    </section>
  );
}

function Fact({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: string }) {
  return (
    <div className="flex items-start gap-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Icon className="size-4" aria-hidden="true" />
      </span>
      <div className="flex flex-col">
        <span className="text-xs text-muted-foreground">{label}</span>
        <span className="text-sm font-medium text-foreground">{value}</span>
      </div>
    </div>
  );
}

function NotAvailable() {
  return (
    <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-border bg-card/60 px-6 py-14 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary">
        <CalendarX className="size-6" aria-hidden="true" />
      </span>
      <h1 className="font-heading text-lg font-semibold text-foreground">This listing is not available.</h1>
      <BackToDirectory />
    </div>
  );
}

type State =
  | { status: "loading" }
  | { status: "not-found" }
  | { status: "error"; message: string }
  | { status: "ready"; professional: PublicProfessional };

function ProfessionalProfile({ id }: { id: string }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getPublicProfessional(id, controller.signal)
      .then((professional) => setState({ status: "ready", professional }))
      .catch((e) => {
        if (controller.signal.aborted) return;
        if (e instanceof ProfessionalsApiError && e.status === 404) {
          setState({ status: "not-found" });
        } else {
          setState({
            status: "error",
            message:
              e instanceof ProfessionalsApiError && e.status === 0
                ? e.message
                : "This profile could not be loaded. Please try again in a moment.",
          });
        }
      });
    return () => controller.abort();
  }, [id, retryCount]);

  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-4xl flex-col gap-6">
        {state.status === "loading" ? (
          <div className="flex flex-col gap-4" aria-busy="true">
            <span className="sr-only">Loading the profile.</span>
            <div className="h-28 animate-pulse rounded-2xl bg-muted" aria-hidden="true" />
            <div className="h-64 animate-pulse rounded-2xl bg-muted" aria-hidden="true" />
          </div>
        ) : state.status === "not-found" ? (
          <NotAvailable />
        ) : state.status === "error" ? (
          <>
            <BackToDirectory />
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
              <span>{state.message}</span>
              <button
                type="button"
                onClick={() => {
                  setState({ status: "loading" });
                  setRetryCount((n) => n + 1);
                }}
                className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline"
              >
                <RefreshCw className="size-4" aria-hidden="true" />
                Try again
              </button>
            </div>
          </>
        ) : (
          <ProfileBody professional={state.professional} />
        )}
      </Container>
    </section>
  );
}

function ProfileBody({ professional: p }: { professional: PublicProfessional }) {
  const location = formatLocation(p.city, p.state);
  const fee = formatFee(p.consultationFee);
  const experience = formatExperience(p.yearsExperience);

  return (
    <>
      <BackToDirectory />

      <header className="flex flex-col gap-4 sm:flex-row sm:items-center">
        <ProfessionalAvatar professional={p} className="size-24 text-3xl" />
        <div className="flex flex-col gap-1.5">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground">{p.fullName}</h1>
          <p className="text-base text-muted-foreground">
            {CREDENTIAL_TYPE_LABELS[p.credentialType] ?? p.credentialType}
          </p>
          <CredentialsChip professional={p} />
        </div>
      </header>

      <DirectoryDisclaimer />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
        <div className="flex flex-col gap-7 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8">
          <Section title="About">
            <p className="text-sm leading-relaxed whitespace-pre-line text-foreground sm:text-base">
              {p.bio ?? "This professional has not added a bio yet."}
            </p>
          </Section>

          <Section title="Specialties">
            {p.specialties.length > 0 ? (
              <ul className="flex flex-wrap gap-2">
                {p.specialties.map((specialty) => (
                  <li key={specialty} className="rounded-full bg-secondary px-3 py-1 text-sm text-secondary-foreground">
                    {specialty}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-sm text-muted-foreground">No specialties are listed.</p>
            )}
          </Section>

          <Section title="Qualifications">
            <p className="text-sm text-foreground sm:text-base">
              {p.qualifications ?? "Qualifications are not listed."}
            </p>
          </Section>

          <Section title="Weekly availability">
            <div className="flex flex-col gap-3">
              <WeeklyAvailabilityTable hours={p.weeklyHours} sessionMinutes={p.sessionMinutes} />
              {p.availabilityNote ? (
                <p className="text-sm text-foreground sm:text-base">{p.availabilityNote}</p>
              ) : null}
            </div>
          </Section>
        </div>

        <aside className="flex flex-col gap-5">
          <div className="flex flex-col gap-4 rounded-2xl bg-card p-6 ring-1 ring-foreground/10">
            <Fact icon={Video} label="Consultation mode" value={CONSULTATION_MODE_LABELS[p.consultationMode] ?? p.consultationMode} />
            <Fact icon={MapPin} label="Location" value={location ?? "Location not listed"} />
            <Fact
              icon={Languages}
              label="Languages"
              value={p.languages.length > 0 ? p.languages.join(", ") : "Languages not listed"}
            />
            {fee ? <Fact icon={IndianRupee} label="Consultation fee" value={fee} /> : null}
            {experience ? <Fact icon={Briefcase} label="Experience" value={experience} /> : null}
            {p.registrationBody && !p.isSampleData ? (
              <Fact
                icon={GraduationCap}
                label="Registration body"
                value={REGISTRATION_BODY_LABELS[p.registrationBody] ?? p.registrationBody}
              />
            ) : null}
          </div>

          <BookingStatusCard professional={p} />
        </aside>
      </div>

      <BookingSection professional={p} />
    </>
  );
}

export { ProfessionalProfile };
