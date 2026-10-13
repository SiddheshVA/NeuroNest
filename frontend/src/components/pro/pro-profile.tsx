import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { CheckCircle2, Circle, Send } from "lucide-react";

import { Container } from "@/components/layout/container";
import { ProDocuments } from "@/components/pro/pro-documents";
import { ProStatusBanner } from "@/components/pro/pro-status-banner";
import {
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
  PRO_REGISTRATION_BODIES,
  PRO_REGISTRATION_BODY_LABELS,
  ProApiError,
  expectedRegistrationBody,
  useProApi,
  type ProConsultationMode,
  type ProCredentialType,
  type ProMe,
  type ProMissingItem,
  type ProProfile,
  type ProRegistrationBody,
  type ProUpdate,
} from "@/lib/pro-client";

const LOCKED_HELPER = "These details can only be changed by the admin after submission.";
const UNSAVED_MESSAGE = "You have changes that are not saved. If you leave now, you will lose them.";
const CHOOSE_OPTION = "Please choose an option.";
const SUBMIT_CONFIRMATION =
  "After you submit, you can't change your name or registration details until the admin has reviewed your profile. Do you want to continue?";

// Limits mirror ProProfileService so most problems are caught before saving.
const MAX_TEXT = 200;
const MAX_LONG_TEXT = 4000;
const MAX_LIST_ITEMS = 20;
const MAX_LIST_ITEM = 60;
const MIN_REGISTRATION_YEAR = 1950;
const MAX_CONSULTATION_FEE = 100000;
const MAX_YEARS_EXPERIENCE = 70;
const PHONE_PATTERN = /^[0-9+()\- ]{6,30}$/;

interface FormValues {
  fullName: string;
  credentialType: ProCredentialType | "";
  qualifications: string;
  registrationBody: ProRegistrationBody | "";
  registrationNumber: string;
  registrationCouncil: string;
  registrationYear: string;
  registrationExpiry: string;
  bio: string;
  specialties: string[];
  languages: string[];
  city: string;
  state: string;
  consultationMode: ProConsultationMode | "";
  consultationFee: string;
  yearsExperience: string;
  availabilityNote: string;
  photoUrl: string;
  contactPhone: string;
}
type FieldName = keyof FormValues;
type FieldErrors = Partial<Record<FieldName, string>>;

const LOCKED_FIELDS: FieldName[] = [
  "fullName",
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
  "specialties",
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

function toForm(p: ProProfile): FormValues {
  const text = (v: string | null) => v ?? "";
  const num = (v: number | null) => (v === null || v === undefined ? "" : String(v));
  return {
    fullName: p.fullName,
    credentialType: p.credentialType,
    qualifications: text(p.qualifications),
    registrationBody: p.registrationBody ?? "",
    registrationNumber: text(p.registrationNumber),
    registrationCouncil: text(p.registrationCouncil),
    registrationYear: num(p.registrationYear),
    registrationExpiry: text(p.registrationExpiry),
    bio: text(p.bio),
    specialties: [...p.specialties],
    languages: [...p.languages],
    city: text(p.city),
    state: text(p.state),
    consultationMode: p.consultationMode,
    consultationFee: num(p.consultationFee),
    yearsExperience: num(p.yearsExperience),
    availabilityNote: text(p.availabilityNote),
    photoUrl: text(p.photoUrl),
    contactPhone: text(p.contactPhone),
  };
}

// Locked fields are only sent while they may still be changed; the backend would refuse them later.
function toUpdate(v: FormValues, includeLocked: boolean): ProUpdate {
  const text = (s: string) => (s.trim() === "" ? null : s.trim());
  const num = (s: string) => (s.trim() === "" ? null : Number(s));
  const update: ProUpdate = {
    bio: text(v.bio),
    specialties: v.specialties,
    languages: v.languages,
    city: text(v.city),
    state: text(v.state),
    consultationMode: v.consultationMode as ProConsultationMode,
    consultationFee: num(v.consultationFee),
    yearsExperience: num(v.yearsExperience),
    availabilityNote: text(v.availabilityNote),
    photoUrl: text(v.photoUrl),
    contactPhone: text(v.contactPhone),
  };
  if (includeLocked) {
    Object.assign(update, {
      fullName: v.fullName.trim(),
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
  const list = (field: "specialties" | "languages", label: string) => {
    if (v[field].length > MAX_LIST_ITEMS) errors[field] = `You can add at most ${MAX_LIST_ITEMS} ${label}.`;
    else if (v[field].some((item) => item.length > MAX_LIST_ITEM)) {
      errors[field] = `Each entry must be at most ${MAX_LIST_ITEM} characters.`;
    }
  };

  if (includeLocked) {
    if (!v.fullName.trim()) errors.fullName = "Full name is required.";
    maxLength("fullName", "Full name", MAX_TEXT);
    if (!v.credentialType) errors.credentialType = CHOOSE_OPTION;
    maxLength("qualifications", "Qualifications", MAX_TEXT);
    maxLength("registrationNumber", "Registration number", MAX_TEXT);
    maxLength("registrationCouncil", "Registration council", MAX_TEXT);
    const expected = expectedRegistrationBody(v.credentialType);
    if (expected && v.registrationBody && v.registrationBody !== expected) {
      errors.registrationBody = `The registration body must be ${expected} for a ${PRO_CREDENTIAL_TYPE_LABELS[v.credentialType as ProCredentialType].toLowerCase()}.`;
    }
    inRange("registrationYear", "Registration year", MIN_REGISTRATION_YEAR, currentYear);
  }
  if (!v.consultationMode) errors.consultationMode = CHOOSE_OPTION;
  maxLength("bio", "Bio", MAX_LONG_TEXT);
  maxLength("city", "City", MAX_TEXT);
  maxLength("state", "State", MAX_TEXT);
  maxLength("availabilityNote", "Availability note", MAX_TEXT);
  list("specialties", "specialties");
  list("languages", "languages");
  inRange("consultationFee", "Consultation fee", 0, MAX_CONSULTATION_FEE);
  inRange("yearsExperience", "Years of experience", 0, MAX_YEARS_EXPERIENCE);
  if (v.photoUrl.trim() && !/^https?:\/\/\S+$/i.test(v.photoUrl.trim())) {
    errors.photoUrl = "Photo URL must start with http:// or https://.";
  }
  if (v.contactPhone.trim() && !PHONE_PATTERN.test(v.contactPhone.trim())) {
    errors.contactPhone = "Contact phone must be 6 to 30 characters of digits, spaces, +, -, ( or ).";
  }
  return errors;
}

// Backend validation messages start with the field name ("consultationFee must be ..."), so they can be shown
// next to the matching field.
function fieldOf(message: string): FieldName | null {
  const first = message.split(/[\s:]/, 1)[0];
  return (ALL_FIELDS as string[]).includes(first) ? (first as FieldName) : null;
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

  // Replaces both the server state and the form (after loading, saving or submitting).
  const adopt = useCallback((next: ProMe) => {
    const form = toForm(next.profile);
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
  const expectedBody = expectedRegistrationBody(values.credentialType);

  const set = <K extends FieldName>(field: K, value: FormValues[K]) => {
    setSaveSuccess(null);
    setValues((current) => (current ? { ...current, [field]: value } : current));
    setErrors((current) => {
      if (!current[field]) return current;
      const next = { ...current };
      delete next[field];
      return next;
    });
  };

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
    if (needsRegistration && !values.registrationBody) selectErrors.registrationBody = CHOOSE_OPTION;
    if (Object.keys(selectErrors).length > 0) {
      setErrors((current) => ({ ...current, ...selectErrors }));
      setSubmitError({ message: "Please choose an option in each highlighted field before you submit.", missing: [] });
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
            isCounselor
              ? "Registration details are optional for counselors. Add them if you have them."
              : `${values.credentialType === "PSYCHIATRIST" ? "Psychiatrists" : "Psychologists"} must register with the ${expectedBody}.`
          }
        >
          <TextField label="Full name" required placeholder="Dr. Asha Rao" value={values.fullName} onChange={(v) => set("fullName", v)} {...lockedProps("fullName")} />
          <SelectField
            label="Credential type"
            required
            value={values.credentialType}
            onChange={(v) => set("credentialType", v as ProCredentialType)}
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
            options={(expectedBody ? [expectedBody] : [...PRO_REGISTRATION_BODIES]).map((b) => ({
              value: b,
              label: PRO_REGISTRATION_BODY_LABELS[b],
            }))}
            {...lockedProps("registrationBody")}
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
            label="Registration council"
            required={needsRegistration}
            value={values.registrationCouncil}
            onChange={(v) => set("registrationCouncil", v)}
            placeholder="Maharashtra Medical Council"
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

        <Group title="Practice details" description="You can update these details at any time.">
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
          <ChipInputField label="Specialties" required values={values.specialties} onChange={(v) => set("specialties", v)} error={errors.specialties} disabled={saving} placeholder="Anxiety" />
          <ChipInputField label="Languages" required values={values.languages} onChange={(v) => set("languages", v)} error={errors.languages} disabled={saving} placeholder="Hindi" />
          <TextField label="City" required placeholder="Pune" value={values.city} onChange={(v) => set("city", v)} error={errors.city} disabled={saving} />
          <TextField label="State" placeholder="Maharashtra" value={values.state} onChange={(v) => set("state", v)} error={errors.state} disabled={saving} />
          <SelectField
            label="Consultation mode"
            required
            value={values.consultationMode}
            onChange={(v) => set("consultationMode", v as ProConsultationMode)}
            options={PRO_CONSULTATION_MODES.map((m) => ({ value: m, label: PRO_CONSULTATION_MODE_LABELS[m] }))}
            error={errors.consultationMode}
            disabled={saving}
          />
          <TextField label="Years of experience" digits={2} placeholder="5" value={values.yearsExperience} onChange={(v) => set("yearsExperience", v)} error={errors.yearsExperience} disabled={saving} />
          <TextField label="Consultation fee (₹ per session)" digits={6} placeholder="1000" value={values.consultationFee} onChange={(v) => set("consultationFee", v)} error={errors.consultationFee} disabled={saving} />
          <TextField
            label="Availability note"
            value={values.availabilityNote}
            onChange={(v) => set("availabilityNote", v)}
            placeholder="Weekday evenings and Saturday mornings"
            error={errors.availabilityNote}
            disabled={saving}
          />
          <TextField label="Contact phone" type="tel" inputMode="tel" placeholder="+91 98765 43210" value={values.contactPhone} onChange={(v) => set("contactPhone", v)} error={errors.contactPhone} disabled={saving}
            helper="This number is not shown in the public directory." />
          <TextField
            label="Photo URL"
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
          <div className="flex flex-col gap-1.5 sm:col-span-2">
            <span className="text-sm font-medium text-foreground">Contact email</span>
            <p className="text-sm text-muted-foreground">
              {me.profile.contactEmail}. This comes from your sign-in account and is used for notifications.
            </p>
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

      <ProDocuments me={me} api={api} onChanged={refreshAfterDocuments} />

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

          <button
            type="button"
            className={cn(primaryButtonClass, "self-start")}
            disabled={dirty || saving || submitting}
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
