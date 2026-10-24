import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "@clerk/nextjs";
import { CalendarClock, CalendarX, CheckCircle2, Loader2, LogIn } from "lucide-react";

import {
  CheckboxField,
  ErrorNotice,
  primaryButtonClass,
  secondaryButtonClass,
  SelectField,
  TextAreaField,
  TextField,
} from "@/components/pro/pro-ui";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import {
  BookingApiError,
  createBooking,
  getLatestAssessment,
  INSTRUMENT_LABELS,
  isIndianMobile,
  isSlotTaken,
  SLOT_TAKEN_MESSAGE,
  type LatestAssessment,
  type PreferredMethod,
  type PreferredMode,
} from "@/lib/bookings-client";
import { addDays, formatIstDate, formatIstDay, formatIstTime, todayInIndia } from "@/lib/ist-time";
import { getProfessionalAvailability, type PublicProfessional } from "@/lib/professionals-client";
import { loginHref } from "@/lib/safe-redirect";
import { cn } from "@/lib/utils";

const FIRST_DAYS = 14;
const MORE_DAYS = 14;
// The booking window and the availability endpoint's longest range (in days).
const MAX_DAYS_AHEAD = 60;
const MAX_RANGE = 31;
const MIN_NAME = 2;
const MAX_NAME = 80;
const MAX_NOTE = 500;

const SHARE_EXPLANATION =
  "The professional will see the name of the test, your score, the result band and the date. They will not see your individual answers or your AI chat.";

// The request form is open while the profile URL has ?book=1. Signing in returns to that URL, so the form
// opens by itself afterwards.
const BOOK_PARAM = "book";
const FORM_ID = "request-appointment";
const TOGGLE_ID = "request-appointment-toggle";

function bookingPath(professionalId: string) {
  return `/professionals/${professionalId}?${BOOK_PARAM}=1`;
}

/** Whether the request form is open, and a setter that keeps the rest of the URL (and adds no history). */
function useBookingFormOpen(): [boolean, (open: boolean) => void] {
  const [params, setParams] = useSearchParams();
  const open = params.get(BOOK_PARAM) === "1";
  const setOpen = useCallback(
    (next: boolean) =>
      setParams(
        (current) => {
          const updated = new URLSearchParams(current);
          if (next) updated.set(BOOK_PARAM, "1");
          else updated.delete(BOOK_PARAM);
          return updated;
        },
        { replace: true },
      ),
    [setParams],
  );
  return [open, setOpen];
}

function Card({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("flex flex-col gap-3 rounded-2xl bg-card p-6 ring-1 ring-foreground/10", className)}>{children}</div>
  );
}

/**
 * The side panel of a profile: why booking is not possible, a sign-in prompt for signed-out visitors,
 * or the button that opens the request form for signed-in ones.
 */
export function BookingStatusCard({ professional: p }: { professional: PublicProfessional }) {
  const { isLoaded, isSignedIn } = useAuth();
  const [open, setOpen] = useBookingFormOpen();

  if (!p.bookable) {
    return (
      <Card>
        <p className="flex items-start gap-2 text-sm text-muted-foreground">
          <CalendarX className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          {p.isSampleData
            ? "Sample listing. Booking is not available."
            : "This professional is not taking requests right now."}
        </p>
      </Card>
    );
  }
  if (!isLoaded) return null;
  if (!isSignedIn) {
    return (
      <Card>
        <p className="text-sm text-foreground">Sign in to see available times and request an appointment.</p>
        <Link href={loginHref(bookingPath(p.id))} className={primaryButtonClass}>
          <LogIn className="size-4" aria-hidden="true" />
          Sign in
        </Link>
      </Card>
    );
  }
  return (
    <Card>
      <p className="text-sm text-foreground">Choose a time that suits you and send a request.</p>
      <button
        type="button"
        id={TOGGLE_ID}
        aria-expanded={open}
        aria-controls={open ? FORM_ID : undefined}
        onClick={() => setOpen(!open)}
        className={cn(secondaryButtonClass, "cursor-pointer")}
      >
        <CalendarClock className="size-4" aria-hidden="true" />
        Request appointment
      </button>
    </Card>
  );
}

/**
 * The request form, only for a bookable listing and a signed-in visitor, and only once it has been opened
 * (the "Request appointment" button, or ?book=1 after signing in). Nothing is loaded until then.
 */
export function BookingSection({ professional }: { professional: PublicProfessional }) {
  const { isLoaded, isSignedIn } = useAuth();
  const [open, setOpen] = useBookingFormOpen();
  if (!professional.bookable || !isLoaded || !isSignedIn || !open) return null;
  return (
    <OpenBookingSection
      professional={professional}
      onClose={() => {
        setOpen(false);
        // Back to the button that opened it.
        document.getElementById(TOGGLE_ID)?.focus();
      }}
    />
  );
}

function OpenBookingSection({ professional, onClose }: { professional: PublicProfessional; onClose: () => void }) {
  const sectionRef = useRef<HTMLElement>(null);

  // When it opens: an ordinary (instant) scroll to the form, and focus on it, so keyboard users land there.
  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;
    section.scrollIntoView({ block: "start" });
    section.focus({ preventScroll: true });
  }, []);

  return (
    <section
      ref={sectionRef}
      id={FORM_ID}
      tabIndex={-1}
      aria-label="Request an appointment"
      className="relative scroll-mt-24 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 outline-none focus-visible:ring-3 focus-visible:ring-ring/50 sm:p-8"
    >
      <button
        type="button"
        onClick={onClose}
        className="absolute top-6 right-6 cursor-pointer rounded-sm text-sm font-medium text-primary underline-offset-4 outline-none transition-colors duration-150 hover:underline focus-visible:ring-3 focus-visible:ring-ring/50 sm:top-8 sm:right-8"
      >
        Close<span className="sr-only"> the appointment request form</span>
      </button>
      <BookingForm professional={professional} />
    </section>
  );
}

// ---- Availability ----

type Days = { date: string; slots: string[] }[];

/** Loads [from, to] in pieces the availability endpoint accepts. */
async function loadRange(id: string, from: string, to: string, signal?: AbortSignal): Promise<Days> {
  const days: Days = [];
  let start = from;
  while (start <= to) {
    const end = addDays(start, MAX_RANGE - 1) < to ? addDays(start, MAX_RANGE - 1) : to;
    const result = await getProfessionalAvailability(id, start, end, signal);
    days.push(...result.days.filter((day) => day.slots.length > 0));
    start = addDays(end, 1);
  }
  return days;
}

function SlotPicker({
  days,
  value,
  onChange,
  exclude,
  label,
  helper,
}: {
  days: Days;
  value: string;
  onChange: (slot: string) => void;
  exclude?: string;
  label: string;
  helper: string;
}) {
  const [day, setDay] = useState(() => days.find((d) => d.slots.includes(value))?.date ?? "");
  const available = days.map((d) => ({ ...d, slots: d.slots.filter((slot) => slot !== exclude) }));
  const chosenDay = available.find((d) => d.date === day);

  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="text-sm font-medium text-foreground">{label}</legend>
      <p className="-mt-1 text-xs text-muted-foreground">{helper}</p>
      <div className="flex flex-wrap gap-2" role="group" aria-label={`${label}: day`}>
        {available.map((d) => (
          <button
            key={d.date}
            type="button"
            disabled={d.slots.length === 0}
            aria-pressed={d.date === day}
            onClick={() => {
              setDay(d.date);
              if (!d.slots.includes(value)) onChange("");
            }}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm ring-1 transition-colors disabled:cursor-not-allowed disabled:opacity-40",
              d.date === day
                ? "bg-primary text-primary-foreground ring-primary"
                : "bg-card text-foreground ring-border hover:bg-muted",
            )}
          >
            {formatIstDay(d.date)}
          </button>
        ))}
      </div>
      {chosenDay ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap gap-2" role="group" aria-label={`${label}: time`}>
            {chosenDay.slots.map((slot) => (
              <button
                key={slot}
                type="button"
                aria-pressed={slot === value}
                onClick={() => onChange(slot === value ? "" : slot)}
                className={cn(
                  "rounded-full px-3 py-1.5 text-sm tabular-nums ring-1 transition-colors",
                  slot === value
                    ? "bg-primary text-primary-foreground ring-primary"
                    : "bg-card text-foreground ring-border hover:bg-muted",
                )}
              >
                {formatIstTime(slot)}
              </button>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">Times are in Indian Standard Time.</p>
        </div>
      ) : null}
    </fieldset>
  );
}

// ---- The form ----

type Availability =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; days: Days; loadedTo: string; loadingMore: boolean };

type ShareState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; result: LatestAssessment }
  | { status: "none" }
  | { status: "error" };

function describeAssessment(result: LatestAssessment) {
  const name = INSTRUMENT_LABELS[result.instrument] ?? result.instrument;
  return `${name}, score ${result.score}, ${result.severityBand}, taken on ${formatIstDate(result.createdAt)}`;
}

function BookingForm({ professional: p }: { professional: PublicProfessional }) {
  const navigate = useNavigate();
  const [today] = useState(todayInIndia);
  const lastDate = addDays(today, MAX_DAYS_AHEAD);

  const [availability, setAvailability] = useState<Availability>({ status: "loading" });
  const [availabilityTry, setAvailabilityTry] = useState(0);

  const [mode, setMode] = useState<PreferredMode | "">("");
  const [method, setMethod] = useState<PreferredMethod | "">("");
  const [slot, setSlot] = useState("");
  const [alternate, setAlternate] = useState("");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [note, setNote] = useState("");
  const [share, setShare] = useState(false);
  const [shareState, setShareState] = useState<ShareState>({ status: "idle" });
  const [consent, setConsent] = useState(false);

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<{ message: string; retry: boolean } | null>(null);
  const [sent, setSent] = useState(false);
  // Bumped after the slots are reloaded so the pickers start again from the fresh list.
  const [pickerKey, setPickerKey] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    const to = addDays(today, FIRST_DAYS - 1);
    loadRange(p.id, today, to, controller.signal)
      .then((days) => setAvailability({ status: "ready", days, loadedTo: to, loadingMore: false }))
      .catch(() => {
        if (controller.signal.aborted) return;
        setAvailability({ status: "error", message: "The available times could not be loaded. Please try again." });
      });
    return () => controller.abort();
  }, [p.id, today, availabilityTry]);

  const showLaterDates = () => {
    if (availability.status !== "ready") return;
    const from = addDays(availability.loadedTo, 1);
    const candidate = addDays(availability.loadedTo, MORE_DAYS);
    const to = candidate < lastDate ? candidate : lastDate;
    setAvailability({ ...availability, loadingMore: true });
    loadRange(p.id, from, to)
      .then((more) =>
        setAvailability({ status: "ready", days: [...availability.days, ...more], loadedTo: to, loadingMore: false }),
      )
      .catch(() => {
        setAvailability({ ...availability, loadingMore: false });
        setFormError({ message: "The later dates could not be loaded. Please try again.", retry: false });
      });
  };

  // After a 409: the same dates again, without the time that was just taken. Every other value stays.
  const reloadSlots = useCallback(async () => {
    if (availability.status !== "ready") return;
    const loadedTo = availability.loadedTo;
    try {
      const days = await loadRange(p.id, today, loadedTo);
      const all = new Set(days.flatMap((d) => d.slots));
      setAvailability({ status: "ready", days, loadedTo, loadingMore: false });
      if (!all.has(slot)) setSlot("");
      if (!all.has(alternate)) setAlternate("");
      setPickerKey((k) => k + 1);
    } catch {
      setAvailability({ status: "error", message: "The available times could not be loaded. Please try again." });
    }
  }, [availability, p.id, today, slot, alternate]);

  const toggleShare = async (checked: boolean) => {
    setShare(checked);
    if (!checked) return;
    setShareState({ status: "loading" });
    try {
      const result = await getLatestAssessment();
      if (result) {
        setShareState({ status: "ready", result });
      } else {
        setShare(false);
        setShareState({ status: "none" });
      }
    } catch (error) {
      setShare(false);
      if (error instanceof BookingApiError && error.status === 401) {
        navigate(loginHref(bookingPath(p.id)));
        return;
      }
      setShareState({ status: "error" });
    }
  };

  const modeOptions = (
    p.consultationMode === "BOTH" ? (["ONLINE", "IN_PERSON"] as const) : ([p.consultationMode] as const)
  ).map((value) => ({ value, label: value === "ONLINE" ? "Online" : "In person" }));

  const trimmedName = name.trim();
  const nameValid = trimmedName.length >= MIN_NAME && trimmedName.length <= MAX_NAME;
  const phoneValid = isIndianMobile(phone.trim());
  const valid =
    mode !== "" &&
    (mode !== "ONLINE" || method !== "") &&
    slot !== "" &&
    alternate !== slot &&
    nameValid &&
    phoneValid &&
    note.length <= MAX_NOTE &&
    consent &&
    shareState.status !== "loading";

  const submit = async () => {
    if (!valid || submitting) return;
    setSubmitting(true);
    setFormError(null);
    const sharing = share && shareState.status === "ready";
    try {
      await createBooking({
        professionalId: p.id,
        preferredMode: mode,
        ...(mode === "ONLINE" && method ? { preferredMethod: method } : {}),
        contactName: trimmedName,
        contactPhone: phone.trim(),
        slotStart: slot,
        ...(alternate ? { alternateSlotStart: alternate } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
        consentGiven: true,
        shareLatestAssessment: sharing,
      });
      setSent(true);
    } catch (error) {
      if (error instanceof BookingApiError && error.status === 401) {
        navigate(loginHref(bookingPath(p.id)));
        return;
      }
      if (isSlotTaken(error)) {
        await reloadSlots();
        setFormError({ message: SLOT_TAKEN_MESSAGE, retry: false });
      } else if (error instanceof BookingApiError && [400, 409, 429].includes(error.status)) {
        setFormError({ message: error.message, retry: false });
      } else {
        setFormError({
          message: error instanceof Error ? error.message : "Something went wrong. Please try again.",
          retry: true,
        });
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (sent) {
    return (
      <div className="flex flex-col items-start gap-3" role="status">
        <CheckCircle2 className="size-8 text-primary" aria-hidden="true" />
        <p className="text-base text-foreground">
          Your request has been sent. The professional has 72 hours to respond. You can follow it under{" "}
          <Link href="/appointments" className="font-medium text-primary underline underline-offset-4">
            My appointment requests
          </Link>
          .
        </p>
      </div>
    );
  }

  return (
    <form
      className="flex flex-col gap-6"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      {/* Room on the right for the Close link. */}
      <h2 className="pr-16 font-heading text-lg font-semibold text-foreground">Request an appointment</h2>

      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
        <SelectField
          label="Consultation mode"
          required
          value={mode}
          options={modeOptions}
          onChange={(value) => {
            setMode(value as PreferredMode | "");
            if (value !== "ONLINE") setMethod("");
          }}
        />
        {mode === "ONLINE" ? (
          <SelectField
            label="How would you like to meet?"
            required
            value={method}
            options={[
              { value: "PHONE", label: "Phone call" },
              { value: "VIDEO", label: "Video call" },
            ]}
            onChange={(value) => setMethod(value as PreferredMethod | "")}
            helper="Your choice is a request. The professional makes the final decision when confirming."
          />
        ) : null}
      </div>

      <div className="flex flex-col gap-5">
        <h3 className="text-sm font-semibold text-foreground">
          Date and time
          <span aria-hidden="true" className="text-destructive">
            {" "}*
          </span>
          <span className="sr-only"> (required)</span>
        </h3>
        {availability.status === "loading" ? (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            Loading the available times.
          </p>
        ) : availability.status === "error" ? (
          <ErrorNotice
            message={availability.message}
            onRetry={() => {
              setAvailability({ status: "loading" });
              setAvailabilityTry((n) => n + 1);
            }}
          />
        ) : (
          <>
            {availability.days.length === 0 ? (
              <p className="text-sm text-muted-foreground">No times are available right now.</p>
            ) : (
              <>
                <SlotPicker
                  key={`first-${pickerKey}`}
                  days={availability.days}
                  value={slot}
                  onChange={(value) => {
                    setSlot(value);
                    if (value && value === alternate) setAlternate("");
                  }}
                  label="First choice"
                  helper="Choose a day, then a time."
                />
                <SlotPicker
                  key={`second-${pickerKey}`}
                  days={availability.days}
                  value={alternate}
                  onChange={setAlternate}
                  exclude={slot}
                  label="Second choice"
                  helper="Optional. Choose a different time as a second choice."
                />
              </>
            )}
            {availability.loadedTo < lastDate ? (
              <button
                type="button"
                className={cn(secondaryButtonClass, "w-fit")}
                onClick={showLaterDates}
                disabled={availability.loadingMore}
              >
                {availability.loadingMore ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
                Show later dates
              </button>
            ) : null}
          </>
        )}
      </div>

      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
        <TextField
          label="Your name"
          required
          value={name}
          onChange={setName}
          placeholder="Priya Sharma"
          error={
            name !== "" && !nameValid ? `Your name must be between ${MIN_NAME} and ${MAX_NAME} characters.` : undefined
          }
        />
        <TextField
          label="Phone number"
          required
          inputMode="tel"
          value={phone}
          onChange={(value) => setPhone(value.replace(/[^0-9+\s()-]/g, "").slice(0, 20))}
          placeholder="9876543210"
          helper="Your phone number is shared with the professional only after they confirm your appointment."
          error={
            phone.trim() !== "" && !phoneValid
              ? "Enter a 10-digit Indian mobile number starting with 6, 7, 8 or 9."
              : undefined
          }
        />
      </div>

      <TextAreaField
        label="Note"
        rows={4}
        value={note}
        onChange={(value) => setNote(value.slice(0, MAX_NOTE))}
        placeholder="I would like help with exam stress and trouble sleeping."
        helper={
          <>
            Tell the professional briefly what you would like help with. This is optional. Share only what you are
            comfortable sharing.{" "}
            <span className="tabular-nums">
              {note.length} of {MAX_NOTE} characters used.
            </span>
          </>
        }
      />

      <div className="flex flex-col gap-2 rounded-xl bg-muted/50 p-4">
        <CheckboxField
          label="Share my latest assessment result with this professional."
          checked={share}
          disabled={shareState.status === "none" || shareState.status === "loading"}
          onChange={(checked) => void toggleShare(checked)}
        />
        <div className="flex flex-col gap-1 pl-6.5 text-xs text-muted-foreground">
          {shareState.status === "loading" ? (
            <p className="flex items-center gap-1.5">
              <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
              Loading your latest result.
            </p>
          ) : shareState.status === "ready" && share ? (
            <p className="font-medium text-foreground">{describeAssessment(shareState.result)}</p>
          ) : shareState.status === "none" ? (
            <p className="font-medium text-foreground">You have no saved assessment result to share.</p>
          ) : shareState.status === "error" ? (
            <p className="text-destructive">Your latest result could not be loaded. Please try again.</p>
          ) : null}
          <p>{SHARE_EXPLANATION}</p>
        </div>
      </div>

      <CheckboxField
        label="I agree that NeuroNest shares my name and note with this professional so they can respond to my request, and my email address and phone number once they confirm my appointment. I understand this is not an emergency service."
        checked={consent}
        onChange={setConsent}
      />

      <CrisisResourcesBox intro={<>This is not an emergency service.</>} className="rounded-2xl p-5 sm:p-6" />

      {formError ? (
        <div role="alert">
          <ErrorNotice message={formError.message} onRetry={formError.retry ? () => void submit() : undefined} />
        </div>
      ) : null}

      <button type="submit" className={cn(primaryButtonClass, "w-fit")} disabled={!valid || submitting}>
        {submitting ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
        Send request
      </button>
    </form>
  );
}
