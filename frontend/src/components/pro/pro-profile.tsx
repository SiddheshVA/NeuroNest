import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { CheckCircle2, Circle, Send } from "lucide-react";

import { Container } from "@/components/layout/container";
import { ProDocuments } from "@/components/pro/pro-documents";
import { ProStatusBanner } from "@/components/pro/pro-status-banner";
import {
  CheckboxField,
  ChipInputField,
  ConfirmDialog,
  DisabledAccountNotice,
  ErrorNotice,
  LoadingBlock,
  SelectField,
  TextAreaField,
  TextField,
  primaryButtonClass,
  secondaryButtonClass,
} from "@/components/pro/pro-ui";
import { cn } from "@/lib/utils";
import {
  PRO_CONSULTATION_MODE_LABELS,
  PRO_CONSULTATION_MODES,
  PRO_CREDENTIAL_TYPE_LABELS,
  PRO_CREDENTIAL_TYPES,
  PRO_REGISTRATION_BODY_LABELS,
  PRO_SOCIAL_PLATFORM_INFO,
  PRO_SOCIAL_PLATFORMS,
  PRO_SPECIALIZATION_OTHER,
  PRO_SPECIALIZATIONS,
  ProApiError,
  allowedRegistrationBodies,
  normalizeIndianMobile,
  useProApi,
  type ProConsultationMode,
  type ProCredentialType,
  type ProMe,
  type ProMissingItem,
  type ProProfile,
  type ProRegistrationBody,
  type ProSocialPlatform,
  type ProUpdate,
} from "@/lib/pro-client";

const LOCKED_HELPER = "These details can only be changed by the admin after submission.";
const UNSAVED_MESSAGE = "You have changes that are not saved. If you leave now, you will lose them.";
const CHOOSE_OPTION = "Please choose an option.";
const FILL_IN = "Please fill in this field.";
const CHOOSE_CREDENTIAL_FIRST = "Choose your credential type first.";
const AVAILABILITY_HELPER =
  "This is shown on your public profile so people know when to ask for an appointment. It does not set your calendar.";
const SPECIALIZATIONS_HELPER = "Choose all professional roles that apply to you.";
const SOCIAL_HELPER = "Only the NeuroNest team will see these links. They help us verify who you are.";
const DECLARATION_START =
  "I certify that all information provided is true. I consent to background verification. I agree to NeuroNest";
const DECLARATION_END = "I understand submitting this application does not guarantee approval.";
const SUBMIT_CONFIRMATION =
  "After you submit, you can't change your name or registration details until the admin has reviewed your profile. Do you want to continue?";

// Limits mirror ProProfileService so most problems are caught before saving.
const MAX_TEXT = 200;
const MAX_LONG_TEXT = 4000;
const MAX_LIST_ITEMS = 20;
const MAX_LIST_ITEM = 60;
const MIN_REGISTRATION_YEAR = 1950;
const MAX_CONSULTATION_FEE = 100000;
const MAX_YEARS_EXPERIENCE = 60;
const MIN_AVAILABILITY_NOTE = 10;
const MAX_SPECIALTIES = 15;
const MIN_SPECIALTY = 2;
const MIN_AGE = 21;
const MAX_AGE = 90;
const MAX_SOCIAL_LINK = 200;

interface FormValues {
  fullName: string;
  /** YYYY-MM-DD, or empty. */
  dateOfBirth: string;
  credentialType: ProCredentialType | "";
  qualifications: string;
  registrationBody: ProRegistrationBody | "";
  registrationNumber: string;
  registrationCouncil: string;
  registrationYear: string;
  registrationExpiry: string;
  bio: string;
  /** Ticked fixed specializations, always in the order of PRO_SPECIALIZATIONS. */
  specializations: string[];
  otherSpecializationTicked: boolean;
  otherSpecialization: string;
  languages: string[];
  city: string;
  state: string;
  consultationMode: ProConsultationMode | "";
  consultationFee: string;
  yearsExperience: string;
  availabilityNote: string;
  photoUrl: string;
  contactPhone: string;
  /** A key is present while its platform is ticked; keys are kept in PRO_SOCIAL_PLATFORMS order. */
  socialLinks: Partial<Record<ProSocialPlatform, string>>;
}
type FieldName = keyof FormValues;
// "specialties" is the backend's name for the whole specializations group.
type ErrorKey = FieldName | "specialties" | `socialLinks.${ProSocialPlatform}`;
type FieldErrors = Partial<Record<ErrorKey, string>>;

const LOCKED_FIELDS: FieldName[] = [
  "fullName",
  "dateOfBirth",
  "credentialType",
  "qualifications",
  "registrationBody",
  "registrationNumber",
  "registrationCouncil",
  "registrationYear",
  "registrationExpiry",
];
const ALL_FIELDS: FieldName[] = [
  ...LOCKED_FIELDS,
  "bio",
  "languages",
  "city",
  "state",
  "consultationMode",
  "consultationFee",
  "yearsExperience",
  "availabilityNote",
  "photoUrl",
  "contactPhone",
];
const ERROR_KEYS: string[] = [
  ...ALL_FIELDS,
  "specialties",
  ...PRO_SOCIAL_PLATFORMS.map((platform) => `socialLinks.${platform}`),
];

const FIXED_SPECIALIZATIONS: readonly string[] = PRO_SPECIALIZATIONS;

// Today's date in India as YYYY-MM-DD, since the backend checks ages in Asia/Kolkata.
function todayInIndia(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date());
}

// The same day of the year, a number of years earlier (29 February becomes 28 February).
function yearsBefore(isoDate: string, years: number): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  const year = y - years;
  const lastDay = new Date(Date.UTC(year, m, 0)).getUTCDate();
  return `${String(year).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(Math.min(d, lastDay)).padStart(2, "0")}`;
}

function ageOn(dateOfBirth: string, today: string): number {
  const [by, bm, bd] = dateOfBirth.split("-").map(Number);
  const [ty, tm, td] = today.split("-").map(Number);
  return ty - by - (tm < bm || (tm === bm && td < bd) ? 1 : 0);
}

// Saved specialties: entries matching a fixed label tick it; anything else becomes the "Other" text.
function splitSpecialties(saved: string[]) {
  const ticked = new Set<string>();
  const others: string[] = [];
  for (const entry of saved) {
    const match = FIXED_SPECIALIZATIONS.find((label) => label.toLowerCase() === entry.trim().toLowerCase());
    if (match) ticked.add(match);
    else others.push(entry.trim());
  }
  return {
    specializations: FIXED_SPECIALIZATIONS.filter((label) => ticked.has(label)),
    otherSpecializationTicked: others.length > 0,
    otherSpecialization: others.join(", "),
  };
}

function specialtiesOf(v: FormValues): string[] {
  const other = v.otherSpecializationTicked ? v.otherSpecialization.trim() : "";
  return other ? [...v.specializations, other] : [...v.specializations];
}

function orderedLinks(links: Partial<Record<ProSocialPlatform, string>>): Partial<Record<ProSocialPlatform, string>> {
  const ordered: Partial<Record<ProSocialPlatform, string>> = {};
  for (const platform of PRO_SOCIAL_PLATFORMS) {
    if (links[platform] !== undefined) ordered[platform] = links[platform];
  }
  return ordered;
}

// Mirrors SocialLinks.java. Returns an error sentence, or null when the link is acceptable.
function socialLinkProblem(platform: ProSocialPlatform, raw: string): string | null {
  const link = raw.trim();
  const { label, hosts } = PRO_SOCIAL_PLATFORM_INFO[platform];
  if (!link) return `Please enter your ${label} link, or untick ${label}.`;
  if (link.length > MAX_SOCIAL_LINK) return `The link must be at most ${MAX_SOCIAL_LINK} characters.`;
  if (!/^https:\/\//i.test(link)) return "The link must start with https://.";
  let host: string;
  try {
    const url = new URL(link);
    host = url.hostname.toLowerCase();
    if (url.username || url.password || !host.includes(".") || /\s/.test(link)) throw new Error("invalid");
  } catch {
    return "Please enter a valid link.";
  }
  if (hosts.length > 0 && !hosts.some((domain) => host === domain || host.endsWith(`.${domain}`))) {
    return `The link must be on ${hosts.join(" or ")}.`;
  }
  return null;
}

// The issuing council field is labelled for the chosen registration body.
function councilField(body: ProRegistrationBody | ""): { label: string; placeholder?: string } {
  if (body === "NMC") return { label: "State Medical Council that issued your registration", placeholder: "Maharashtra Medical Council" };
  if (body === "RCI") return { label: "Council", placeholder: "Rehabilitation Council of India" };
  if (body === "OTHER") return { label: "Name of the registration body", placeholder: "Name of the council or association" };
  return { label: "Registration council" };
}

// A small heading for one group of fields inside a section; it spans the full row.
function SubHeading({ children }: { children: ReactNode }) {
  return <h3 className="mt-2 border-t border-border pt-4 text-sm font-semibold text-foreground first:mt-0 first:border-t-0 first:pt-0 sm:col-span-2">{children}</h3>;
}

// stateOptions comes from the backend, so the dropdown and the backend check use the same spellings.
function toForm(p: ProProfile, stateOptions: string[]): FormValues {
  const text = (v: string | null) => v ?? "";
  const num = (v: number | null) => (v === null || v === undefined ? "" : String(v));
  return {
    fullName: p.fullName,
    dateOfBirth: text(p.dateOfBirth),
    credentialType: p.credentialType,
    qualifications: text(p.qualifications),
    registrationBody: p.registrationBody ?? "",
    registrationNumber: text(p.registrationNumber),
    registrationCouncil: text(p.registrationCouncil),
    registrationYear: num(p.registrationYear),
    registrationExpiry: text(p.registrationExpiry),
    bio: text(p.bio),
    ...splitSpecialties(p.specialties),
    languages: [...p.languages],
    city: text(p.city),
    // A saved value that is not in the list shows as unselected, so the person chooses one.
    state: p.state && stateOptions.includes(p.state) ? p.state : "",
    consultationMode: p.consultationMode,
    consultationFee: num(p.consultationFee),
    yearsExperience: num(p.yearsExperience),
    availabilityNote: text(p.availabilityNote),
    photoUrl: text(p.photoUrl),
    contactPhone: text(p.contactPhone),
    socialLinks: orderedLinks(p.socialLinks ?? {}),
  };
}

// Locked fields are only sent while they may still be changed; the backend would refuse them later.
function toUpdate(v: FormValues, includeLocked: boolean): ProUpdate {
  const text = (s: string) => (s.trim() === "" ? null : s.trim());
  const num = (s: string) => (s.trim() === "" ? null : Number(s));
  const update: ProUpdate = {
    bio: text(v.bio),
    specialties: specialtiesOf(v),
    languages: v.languages,
    city: text(v.city),
    state: text(v.state),
    consultationMode: v.consultationMode as ProConsultationMode,
    consultationFee: num(v.consultationFee),
    yearsExperience: num(v.yearsExperience),
    availabilityNote: text(v.availabilityNote),
    photoUrl: text(v.photoUrl),
    contactPhone: text(v.contactPhone),
    // Only ticked platforms are sent, so unticked ones are removed from the saved links.
    socialLinks: Object.fromEntries(Object.entries(v.socialLinks).map(([platform, link]) => [platform, (link ?? "").trim()])),
  };
  if (includeLocked) {
    Object.assign(update, {
      fullName: v.fullName.trim(),
      dateOfBirth: text(v.dateOfBirth),
      credentialType: v.credentialType as ProCredentialType,
      qualifications: text(v.qualifications),
      registrationBody: v.registrationBody === "" ? null : v.registrationBody,
      registrationNumber: text(v.registrationNumber),
      registrationCouncil: text(v.registrationCouncil),
      registrationYear: num(v.registrationYear),
      registrationExpiry: text(v.registrationExpiry),
    });
  }
  return update;
}

function validate(v: FormValues, includeLocked: boolean): FieldErrors {
  const errors: FieldErrors = {};
  const currentYear = new Date().getFullYear();
  const maxLength = (field: FieldName, label: string, max: number) => {
    const value = v[field];
    if (typeof value === "string" && value.trim().length > max) errors[field] = `${label} must be at most ${max} characters.`;
  };
  // Numeric fields only accept digits as they are typed; the range is checked here, on save.
  const inRange = (field: FieldName, label: string, min: number, max: number) => {
    const value = (v[field] as string).trim();
    if (value === "") return;
    const n = Number(value);
    if (!/^[0-9]+$/.test(value) || n < min || n > max) {
      errors[field] = `${label} must be a whole number from ${min} to ${max}.`;
    }
  };
  const list = (field: "languages", label: string) => {
    if (v[field].length > MAX_LIST_ITEMS) errors[field] = `You can add at most ${MAX_LIST_ITEMS} ${label}.`;
    else if (v[field].some((item) => item.length > MAX_LIST_ITEM)) {
      errors[field] = `Each entry must be at most ${MAX_LIST_ITEM} characters.`;
    }
  };

  if (includeLocked) {
    if (!v.fullName.trim()) errors.fullName = "Full name is required.";
    maxLength("fullName", "Full name", MAX_TEXT);
    if (v.dateOfBirth) {
      const today = todayInIndia();
      if (!/^\d{4}-\d{2}-\d{2}$/.test(v.dateOfBirth)) errors.dateOfBirth = "Please enter a valid date.";
      else if (v.dateOfBirth > today || ageOn(v.dateOfBirth, today) < MIN_AGE || ageOn(v.dateOfBirth, today) > MAX_AGE) {
        errors.dateOfBirth = `Your age must be between ${MIN_AGE} and ${MAX_AGE} years.`;
      }
    }
    if (!v.credentialType) errors.credentialType = CHOOSE_OPTION;
    maxLength("qualifications", "Qualifications", MAX_TEXT);
    maxLength("registrationNumber", "Registration number", MAX_TEXT);
    maxLength("registrationCouncil", "Registration council", MAX_TEXT);
    if (v.registrationBody && !allowedRegistrationBodies(v.credentialType).includes(v.registrationBody)) {
      errors.registrationBody = "Please choose one of the registration bodies listed for your credential type.";
    }
    if (!v.registrationCouncil.trim()) {
      if (v.registrationBody === "OTHER") errors.registrationCouncil = "Please enter the name of the registration body.";
      else if (v.registrationBody && v.credentialType === "COUNSELOR") errors.registrationCouncil = FILL_IN;
    }
    inRange("registrationYear", "Registration year", MIN_REGISTRATION_YEAR, currentYear);
  }
  if (!v.consultationMode) errors.consultationMode = CHOOSE_OPTION;
  maxLength("bio", "Bio", MAX_LONG_TEXT);
  maxLength("city", "City", MAX_TEXT);
  maxLength("state", "State", MAX_TEXT);
  const note = v.availabilityNote.trim();
  if (note && (note.length < MIN_AVAILABILITY_NOTE || note.length > MAX_TEXT)) {
    errors.availabilityNote = `Availability note must be between ${MIN_AVAILABILITY_NOTE} and ${MAX_TEXT} characters.`;
  }
  if (v.otherSpecializationTicked) {
    const other = v.otherSpecialization.trim();
    if (!other) errors.otherSpecialization = "Please describe your role, or untick Other.";
    else if (other.length < MIN_SPECIALTY || other.length > MAX_LIST_ITEM) {
      errors.otherSpecialization = `Your role must be between ${MIN_SPECIALTY} and ${MAX_LIST_ITEM} characters.`;
    }
  }
  if (specialtiesOf(v).length > MAX_SPECIALTIES) {
    errors.specialties = `You can choose at most ${MAX_SPECIALTIES} specializations.`;
  }
  list("languages", "languages");
  inRange("consultationFee", "Consultation fee", 0, MAX_CONSULTATION_FEE);
  inRange("yearsExperience", "Years of experience", 0, MAX_YEARS_EXPERIENCE);
  if (v.photoUrl.trim() && !/^https:\/\/\S+$/i.test(v.photoUrl.trim())) {
    errors.photoUrl = "Photo URL must start with https://.";
  }
  if (v.contactPhone.trim() && !normalizeIndianMobile(v.contactPhone.trim())) {
    errors.contactPhone = "Please enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9. You may add +91 or a leading 0.";
  }
  for (const platform of PRO_SOCIAL_PLATFORMS) {
    const link = v.socialLinks[platform];
    if (link === undefined) continue;
    const problem = socialLinkProblem(platform, link);
    if (problem) errors[`socialLinks.${platform}`] = problem;
  }
  return errors;
}

// Backend validation messages start with the field name ("consultationFee must be ..."), so they can be shown
// next to the matching field.
function fieldOf(message: string): ErrorKey | null {
  const first = message.split(/[\s:]/, 1)[0];
  if (ERROR_KEYS.includes(first)) return first as ErrorKey;
  // "Each entry in specialties must be ..." names the field later in the sentence.
  if (/\bspecialties\b/.test(message)) return "specialties";
  return null;
}

function Group({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <fieldset className="flex flex-col gap-4">
      <legend className="mb-1 flex flex-col gap-1">
        <span className="font-heading text-xl font-semibold text-foreground">{title}</span>
        {description ? <span className="text-sm text-muted-foreground">{description}</span> : null}
      </legend>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">{children}</div>
    </fieldset>
  );
}

function MissingChecklist({ items, title }: { items: ProMissingItem[]; title: string }) {
  return (
    <div className="flex flex-col gap-2 rounded-xl bg-muted/60 px-4 py-3">
      <p className="text-sm font-semibold text-foreground">{title}</p>
      <ul className="flex flex-col gap-1.5">
        {items.map((item) => (
          <li key={item.item} className="flex items-start gap-2 text-sm text-foreground">
            <Circle className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            {item.message}
          </li>
        ))}
      </ul>
    </div>
  );
}

type LoadState = { status: "loading" } | { status: "error"; message: string } | { status: "ready" };

function ProProfilePage() {
  const navigate = useNavigate();
  const { isLoaded, isSignedIn } = useAuth();
  const { api, disabled } = useProApi();
  const [load, setLoad] = useState<LoadState>({ status: "loading" });
  const [retry, setRetry] = useState(0);
  const [me, setMe] = useState<ProMe | null>(null);
  const [values, setValues] = useState<FormValues | null>(null);
  const [saved, setSaved] = useState<FormValues | null>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveSuccess, setSaveSuccess] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<{ message: string; missing: ProMissingItem[] } | null>(null);
  // The declaration is accepted again on every submission, so it always starts unticked.
  const [declarationAccepted, setDeclarationAccepted] = useState(false);

  // Replaces both the server state and the form (after loading, saving or submitting).
  const adopt = useCallback((next: ProMe) => {
    const form = toForm(next.profile, next.stateOptions);
    setMe(next);
    setValues(form);
    setSaved(form);
  }, []);

  // Nothing is requested until Clerk has finished loading and reports a signed-in user (RequireAuth
  // handles signed-out visitors). The form is always filled from the saved data on each load, and
  // any failure shows an error with Retry rather than an empty form or a redirect.
  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    api
      .me()
      .then((result) => {
        if (cancelled) return;
        adopt(result);
        setLoad({ status: "ready" });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ProApiError && e.kind === "not-registered") {
          navigate("/pro/join", { replace: true });
        } else if (!(e instanceof ProApiError) || (e.kind !== "unauthorized" && e.kind !== "disabled")) {
          setLoad({
            status: "error",
            message: e instanceof Error && e.message ? e.message : "Your profile could not be loaded. Please try again.",
          });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [ready, api, adopt, navigate, retry]);

  const dirty = values !== null && saved !== null && JSON.stringify(values) !== JSON.stringify(saved);

  // Warn before losing unsaved changes: the browser's own prompt on refresh or close (browsers show
  // their standard wording there), and this page's message when following a link inside the app.
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = UNSAVED_MESSAGE;
      return UNSAVED_MESSAGE;
    };
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const anchor = (event.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
      if (!anchor || anchor.target === "_blank" || anchor.hasAttribute("download")) return;
      const url = new URL(anchor.href, window.location.href);
      if (url.origin !== window.location.origin || (url.pathname === window.location.pathname && url.hash)) return;
      if (!window.confirm(UNSAVED_MESSAGE)) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    document.addEventListener("click", onClick, true);
    return () => {
      window.removeEventListener("beforeunload", onBeforeUnload);
      document.removeEventListener("click", onClick, true);
    };
  }, [dirty]);

  // After a document change only the server state is refreshed, so unsaved form edits are kept.
  const refreshAfterDocuments = useCallback(async () => {
    try {
      setMe(await api.me());
    } catch {
      // The document action itself succeeded; the next page load will show the latest state.
    }
  }, [api]);

  if (disabled) {
    return (
      <PageFrame>
        <DisabledAccountNotice />
      </PageFrame>
    );
  }
  if (!isLoaded || load.status === "loading" || !me || !values || !saved) {
    return (
      <PageFrame>
        {isLoaded && load.status === "error" ? (
          <ErrorNotice
            message={load.message}
            onRetry={() => {
              setLoad({ status: "loading" });
              setRetry((n) => n + 1);
            }}
          />
        ) : (
          <LoadingBlock label="Loading your professional profile." />
        )}
      </PageFrame>
    );
  }

  const canEditLocked = me.canEditCredentials;
  const status = me.profile.verificationStatus;
  const canAttemptSubmit = status === "DRAFT" || status === "NEEDS_INFO";
  const isCounselor = values.credentialType === "COUNSELOR";
  const allowedBodies = allowedRegistrationBodies(values.credentialType);
  const council = councilField(values.registrationBody);
  const unlistedSavedState =
    !values.state && me.profile.state && !me.stateOptions.includes(me.profile.state) ? me.profile.state : null;

  const clearErrors = (...keys: ErrorKey[]) =>
    setErrors((current) => {
      if (!keys.some((key) => current[key])) return current;
      const next = { ...current };
      for (const key of keys) delete next[key];
      return next;
    });

  const set = <K extends FieldName>(field: K, value: FormValues[K]) => {
    setSaveSuccess(null);
    setValues((current) => (current ? { ...current, [field]: value } : current));
    clearErrors(field);
  };

  const toggleSpecialization = (label: string, ticked: boolean) => {
    const chosen = new Set(values.specializations);
    if (ticked) chosen.add(label);
    else chosen.delete(label);
    set("specializations", FIXED_SPECIALIZATIONS.filter((l) => chosen.has(l)));
    clearErrors("specialties");
  };

  const setSocialLink = (platform: ProSocialPlatform, link: string | undefined) => {
    const next = { ...values.socialLinks };
    if (link === undefined) delete next[platform];
    else next[platform] = link;
    set("socialLinks", orderedLinks(next));
    clearErrors(`socialLinks.${platform}`);
  };

  // The latest date of birth the picker offers (today in India minus 21 years), and the earliest (age 90).
  const todayIndia = todayInIndia();
  const maxDateOfBirth = yearsBefore(todayIndia, MIN_AGE);
  const minDateOfBirth = (() => {
    const [y, m, d] = yearsBefore(todayIndia, MAX_AGE + 1).split("-").map(Number);
    return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
  })();

  const lockedProps = (field: FieldName) => ({
    disabled: !canEditLocked || saving,
    error: errors[field],
    helper: !canEditLocked ? LOCKED_HELPER : undefined,
  });

  const save = async (event?: FormEvent) => {
    event?.preventDefault();
    setSaveError(null);
    setSaveSuccess(null);
    const clientErrors = validate(values, canEditLocked);
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0) {
      setSaveError("Please correct the highlighted fields and save again.");
      return;
    }
    setSaving(true);
    try {
      adopt(await api.update(toUpdate(values, canEditLocked)));
      setSaveSuccess("Your changes have been saved.");
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      const message = e instanceof Error ? e.message : "Your changes could not be saved. Please try again.";
      setSaveError(message);
      const field = e instanceof ProApiError && e.status === 400 ? fieldOf(message) : null;
      if (field) setErrors({ [field]: message });
    } finally {
      setSaving(false);
    }
  };

  const submit = async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      adopt(await api.submit());
      setDeclarationAccepted(false);
      setConfirmOpen(false);
      setSaveSuccess("Your profile has been submitted for review.");
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (e) {
      setConfirmOpen(false);
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      setSubmitError({
        message: e instanceof Error ? e.message : "Your profile could not be submitted. Please try again.",
        missing: e instanceof ProApiError ? e.missing : [],
      });
    } finally {
      setSubmitting(false);
    }
  };

  // Fields needed before submitting. Registration details are only required for psychologists and
  // psychiatrists.
  const needsRegistration = !isCounselor;

  const startSubmit = () => {
    setSubmitError(null);
    const selectErrors: FieldErrors = {};
    if (!values.credentialType) selectErrors.credentialType = CHOOSE_OPTION;
    if (!values.consultationMode) selectErrors.consultationMode = CHOOSE_OPTION;
    if (!values.state) selectErrors.state = CHOOSE_OPTION;
    if (needsRegistration && !values.registrationBody) selectErrors.registrationBody = CHOOSE_OPTION;
    const fillErrors: FieldErrors = {};
    for (const field of ["dateOfBirth", "yearsExperience", "consultationFee", "availabilityNote", "contactPhone"] as const) {
      if (!values[field].trim()) fillErrors[field] = FILL_IN;
    }
    if (specialtiesOf(values).length === 0) fillErrors.specialties = "Please choose at least one specialization.";
    if (Object.keys(selectErrors).length > 0 || Object.keys(fillErrors).length > 0) {
      setErrors((current) => ({ ...current, ...selectErrors, ...fillErrors }));
      setSubmitError({
        message: "Please fill in or choose an option in each highlighted field, save your changes and then submit.",
        missing: [],
      });
      return;
    }
    setConfirmOpen(true);
  };

  return (
    <PageFrame>
      <ProStatusBanner me={me} />

      <form onSubmit={save} noValidate className="flex flex-col gap-8 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8">
        <p className="text-sm text-muted-foreground">
          Fields marked with an asterisk (<span className="text-destructive">*</span>) are required before you can
          submit your profile. The grey text inside a field is only an example.
        </p>
        <Group
          title="Identity and registration"
          description={
            !values.credentialType
              ? "Choose your credential type first. The registration details you need depend on it."
              : isCounselor
                ? "Registration details are optional for counselors. If you choose a registration body, add its council too."
                : values.credentialType === "PSYCHIATRIST"
                  ? "Psychiatrists must add their NMC registration, or the name of another registration body."
                  : "Psychologists must add their RCI registration, or the name of another registration body."
          }
        >
          <TextField label="Full name" required placeholder="Dr. Asha Rao" value={values.fullName} onChange={(v) => set("fullName", v)} {...lockedProps("fullName")} />
          <SelectField
            label="Credential type"
            required
            value={values.credentialType}
            onChange={(v) => {
              const next = v as ProCredentialType | "";
              set("credentialType", next);
              // A body that is not allowed for the new credential type is cleared.
              if (values.registrationBody && !allowedRegistrationBodies(next).includes(values.registrationBody)) {
                set("registrationBody", "");
              }
            }}
            options={PRO_CREDENTIAL_TYPES.map((t) => ({ value: t, label: PRO_CREDENTIAL_TYPE_LABELS[t] }))}
            {...lockedProps("credentialType")}
          />
          <TextField
            label="Qualifications"
            required
            value={values.qualifications}
            onChange={(v) => set("qualifications", v)}
            placeholder="M.A. Clinical Psychology"
            {...lockedProps("qualifications")}
            className="sm:col-span-2"
          />
          <SelectField
            label="Registration body"
            required={needsRegistration}
            value={values.registrationBody}
            onChange={(v) => set("registrationBody", v as ProRegistrationBody | "")}
            options={allowedBodies.map((b) => ({ value: b, label: PRO_REGISTRATION_BODY_LABELS[b] }))}
            {...lockedProps("registrationBody")}
            {...(canEditLocked && !values.credentialType ? { disabled: true, helper: CHOOSE_CREDENTIAL_FIRST } : {})}
          />
          <TextField
            label="Registration number"
            required={needsRegistration}
            placeholder="A12345"
            value={values.registrationNumber}
            onChange={(v) => set("registrationNumber", v)}
            {...lockedProps("registrationNumber")}
          />
          <TextField
            label={council.label}
            required={needsRegistration || Boolean(values.registrationBody)}
            value={values.registrationCouncil}
            onChange={(v) => set("registrationCouncil", v)}
            placeholder={council.placeholder}
            {...lockedProps("registrationCouncil")}
          />
          <TextField
            label="Registration year"
            required={needsRegistration}
            digits={4}
            placeholder="2015"
            value={values.registrationYear}
            onChange={(v) => set("registrationYear", v)}
            {...lockedProps("registrationYear")}
          />
          <TextField
            label="Registration expiry"
            required={needsRegistration}
            type="date"
            value={values.registrationExpiry}
            onChange={(v) => set("registrationExpiry", v)}
            {...lockedProps("registrationExpiry")}
          />
        </Group>

        <Group
          title="Practice details"
          description="You can update these details at any time, except your date of birth, which only the admin can change after you submit."
        >
          <SubHeading>About you</SubHeading>
          <TextAreaField
            label="Bio"
            required
            placeholder="I work with adults on anxiety, stress and low mood."
            value={values.bio}
            onChange={(v) => set("bio", v)}
            error={errors.bio}
            helper={`Describe how you work and who you help. Use at least 50 characters. You have written ${values.bio.trim().length} characters.`}
            disabled={saving}
            className="sm:col-span-2"
          />
          <TextField
            label="Date of birth"
            required
            type="date"
            min={minDateOfBirth}
            max={maxDateOfBirth}
            value={values.dateOfBirth}
            onChange={(v) => set("dateOfBirth", v)}
            {...lockedProps("dateOfBirth")}
            helper={
              canEditLocked
                ? `You must be between ${MIN_AGE} and ${MAX_AGE} years old. Your date of birth is never shown in the public directory.`
                : LOCKED_HELPER
            }
          />
          <div className="flex flex-col gap-2 sm:col-span-2" role="group" aria-labelledby="specializations-label">
            <span id="specializations-label" className="text-sm font-medium text-foreground">
              Specializations
              <span aria-hidden="true" className="text-destructive">
                {" "}*
              </span>
              <span className="sr-only"> (required)</span>
            </span>
            <p className="text-xs text-muted-foreground">{SPECIALIZATIONS_HELPER}</p>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              {FIXED_SPECIALIZATIONS.map((label) => (
                <CheckboxField
                  key={label}
                  label={label}
                  checked={values.specializations.includes(label)}
                  onChange={(ticked) => toggleSpecialization(label, ticked)}
                  disabled={saving}
                />
              ))}
              <CheckboxField
                label={PRO_SPECIALIZATION_OTHER}
                checked={values.otherSpecializationTicked}
                onChange={(ticked) => {
                  set("otherSpecializationTicked", ticked);
                  if (!ticked) clearErrors("otherSpecialization");
                  clearErrors("specialties");
                }}
                disabled={saving}
              />
            </div>
            {values.otherSpecializationTicked ? (
              <TextField
                label="Your role"
                required
                placeholder="Please describe your role"
                value={values.otherSpecialization}
                onChange={(v) => set("otherSpecialization", v)}
                error={errors.otherSpecialization}
                helper={`Between ${MIN_SPECIALTY} and ${MAX_LIST_ITEM} characters.`}
                disabled={saving}
                className="sm:max-w-md"
              />
            ) : null}
            {errors.specialties ? <p className="text-xs text-destructive">{errors.specialties}</p> : null}
          </div>
          <SubHeading>Contact</SubHeading>
          <TextField
            label="Contact email"
            type="email"
            value={me.profile.contactEmail}
            onChange={() => {}}
            disabled
            helper="This is the email on your account. Booking requests are sent here."
          />
          <TextField label="Contact phone" required type="tel" inputMode="tel" placeholder="98765 43210" value={values.contactPhone} onChange={(v) => set("contactPhone", v)} error={errors.contactPhone} disabled={saving}
            helper="A 10-digit Indian mobile number. It is not shown in the public directory." />

          <SubHeading>Location</SubHeading>
          <SelectField
            label="State"
            required
            emptyLabel="Select state"
            value={values.state}
            onChange={(v) => set("state", v)}
            options={me.stateOptions.map((name) => ({ value: name, label: name }))}
            error={errors.state}
            helper={
              unlistedSavedState
                ? `Your saved state, "${unlistedSavedState}", is not in the list. Please choose your state or union territory.`
                : undefined
            }
            disabled={saving}
          />
          <TextField label="City" required placeholder="Pune" value={values.city} onChange={(v) => set("city", v)} error={errors.city} disabled={saving} />

          <SubHeading>Consultation</SubHeading>
          <SelectField
            label="Consultation mode"
            required
            value={values.consultationMode}
            onChange={(v) => set("consultationMode", v as ProConsultationMode)}
            options={PRO_CONSULTATION_MODES.map((m) => ({ value: m, label: PRO_CONSULTATION_MODE_LABELS[m] }))}
            error={errors.consultationMode}
            disabled={saving}
          />
          <TextField label="Consultation fee (₹ per session)" required digits={6} placeholder="1000" value={values.consultationFee} onChange={(v) => set("consultationFee", v)} error={errors.consultationFee} disabled={saving}
            helper="Enter 0 if you do not charge a fee." />
          <TextField
            label="Availability note"
            required
            value={values.availabilityNote}
            onChange={(v) => set("availabilityNote", v)}
            placeholder="Weekdays 6 pm to 9 pm, Saturdays 10 am to 1 pm"
            error={errors.availabilityNote}
            helper={AVAILABILITY_HELPER}
            disabled={saving}
            className="sm:col-span-2"
          />

          <SubHeading>Experience</SubHeading>
          <TextField label="Years of experience" required digits={2} placeholder="5" value={values.yearsExperience} onChange={(v) => set("yearsExperience", v)} error={errors.yearsExperience} disabled={saving}
            helper={`A whole number from 0 to ${MAX_YEARS_EXPERIENCE}.`} />

          <SubHeading>Languages</SubHeading>
          <ChipInputField label="Languages" required values={values.languages} onChange={(v) => set("languages", v)} error={errors.languages} disabled={saving} placeholder="Hindi" />

          <SubHeading>Photo</SubHeading>
          <TextField
            label="Photo URL (optional)"
            type="url"
            inputMode="url"
            placeholder="https://example.com/photo.jpg"
            value={values.photoUrl}
            onChange={(v) => set("photoUrl", v)}
            error={errors.photoUrl}
            helper="A link to a professional photo of you that starts with https://."
            disabled={saving}
            className="sm:col-span-2"
          />
        </Group>

        <Group title="Social media profiles (optional)" description={SOCIAL_HELPER}>
          <div className="flex flex-col gap-3 sm:col-span-2">
            {PRO_SOCIAL_PLATFORMS.map((platform) => {
              const info = PRO_SOCIAL_PLATFORM_INFO[platform];
              const ticked = values.socialLinks[platform] !== undefined;
              return (
                <div key={platform} className="flex flex-col gap-2">
                  <CheckboxField
                    label={info.label}
                    checked={ticked}
                    onChange={(next) => setSocialLink(platform, next ? "" : undefined)}
                    disabled={saving}
                  />
                  {ticked ? (
                    <TextField
                      label={`${info.label} link`}
                      type="url"
                      inputMode="url"
                      placeholder={info.placeholder}
                      value={values.socialLinks[platform] ?? ""}
                      onChange={(v) => setSocialLink(platform, v)}
                      error={errors[`socialLinks.${platform}`]}
                      helper="The link must start with https://."
                      disabled={saving}
                      className="pl-6.5 sm:max-w-lg"
                    />
                  ) : null}
                </div>
              );
            })}
          </div>
        </Group>

        <div className="flex flex-col gap-3 border-t border-border pt-6">
          {saveError ? <ErrorNotice message={saveError} /> : null}
          {saveSuccess ? (
            <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-100">
              {saveSuccess}
            </p>
          ) : null}
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" className={primaryButtonClass} disabled={saving || !dirty}>
              {saving ? "Saving" : "Save changes"}
            </button>
            {dirty ? (
              <button type="button" className={secondaryButtonClass} disabled={saving} onClick={() => { setValues(saved); setErrors({}); setSaveError(null); }}>
                Discard changes
              </button>
            ) : null}
            {dirty ? (
              <span className="rounded-full border border-amber-300 bg-amber-50 px-2.5 py-0.5 text-xs font-semibold text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100">
                Unsaved changes
              </span>
            ) : (
              <span className="text-sm text-muted-foreground">All changes are saved.</span>
            )}
          </div>
        </div>
      </form>

      <ProDocuments me={me} api={api} onChanged={refreshAfterDocuments} credentialType={values.credentialType} />

      {canAttemptSubmit ? (
        <section className="flex flex-col gap-4 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8" aria-labelledby="submit-heading">
          <div className="flex flex-col gap-1">
            <h2 id="submit-heading" className="font-heading text-xl font-semibold text-foreground">
              Submit for review
            </h2>
            <p className="text-sm text-muted-foreground">
              When everything is ready, submit your profile so the NeuroNest team can review it.
            </p>
          </div>

          {submitError ? (
            submitError.missing.length > 0 ? (
              <MissingChecklist title={submitError.message} items={submitError.missing} />
            ) : (
              <ErrorNotice message={submitError.message} />
            )
          ) : me.missingForSubmit.length > 0 ? (
            <MissingChecklist title="Before you submit, please complete the following items:" items={me.missingForSubmit} />
          ) : (
            <p className="flex items-center gap-2 text-sm font-medium text-emerald-700 dark:text-emerald-300">
              <CheckCircle2 className="size-4" aria-hidden="true" />
              Everything required is complete.
            </p>
          )}
          {dirty ? <p className="text-sm text-muted-foreground">Save your changes before you submit.</p> : null}

          {/* No Privacy Policy or Terms page exists yet, so those names are plain text. */}
          <CheckboxField
            checked={declarationAccepted}
            onChange={setDeclarationAccepted}
            disabled={submitting}
            label={
              <>
                {DECLARATION_START} Privacy Policy and Terms. {DECLARATION_END}
              </>
            }
          />

          <button
            type="button"
            className={cn(primaryButtonClass, "self-start")}
            disabled={dirty || saving || submitting || !declarationAccepted}
            onClick={startSubmit}
          >
            <Send className="size-4" aria-hidden="true" />
            Submit for review
          </button>
        </section>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title="Submit your profile for review?"
        description={SUBMIT_CONFIRMATION}
        confirmLabel={submitting ? "Submitting" : "Yes, submit my profile"}
        cancelLabel="No, go back"
        busy={submitting}
        onConfirm={() => void submit()}
        onOpenChange={(open) => !open && !submitting && setConfirmOpen(false)}
      />
    </PageFrame>
  );
}

function PageFrame({ children }: { children: ReactNode }) {
  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-4xl flex-col gap-6">
        <header className="flex flex-col gap-2">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Your professional profile
          </h1>
          <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
            Keep your details up to date. Nothing is shown to the public until the NeuroNest team has approved your
            profile.
          </p>
        </header>
        {children}
      </Container>
    </section>
  );
}

export { ProProfilePage };
