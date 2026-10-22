import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Loader2 } from "lucide-react";

import { loadSlotDays, SlotPicker, type SlotDays } from "@/components/bookings/slot-picker";
import {
  ErrorNotice,
  primaryButtonClass,
  secondaryButtonClass,
  SelectField,
  TextAreaField,
  TextField,
} from "@/components/pro/pro-ui";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { METHOD_LABELS, type MeetingMethod } from "@/lib/bookings-client";
import { addDays, formatIstDateTime, todayInIndia } from "@/lib/ist-time";
import { ProApiError, type useProApi } from "@/lib/pro-client";
import {
  MAX_MEETING_LINK,
  MAX_REPLY_NOTE,
  meetingAddressProblem,
  meetingLinkProblem,
  type MeetingDetails,
  type ProBooking,
} from "@/lib/pro-bookings";
import { ProfessionalsApiError } from "@/lib/professionals-client";
import { cn } from "@/lib/utils";

type ProApi = ReturnType<typeof useProApi>["api"];
export type ReplyKind = "confirm" | "propose" | "decline";

const FIRST_DAYS = 14;
const MORE_DAYS = 14;
const MAX_DAYS_AHEAD = 60;

const NOTE_HELPER = "Do not add phone numbers, email addresses or website links here. The server rejects them.";
const PROPOSE_EXPLANATION = "The person will be asked to accept this time or cancel. They have 72 hours.";
const DECLINE_EXPLANATION =
  "The person will be told that you could not take this appointment. They will be shown other professionals and crisis support information.";

// ---- Sending ----

/**
 * Sends a reply once (a second click while it runs does nothing). A 400 goes back to the form with the
 * server's message; a 409 is handed to the page, which shows it and reloads the request; a 401 or a
 * disabled account is handled by useProApi and the page; anything else is shown with a retry.
 */
function useReply(onSuccess: (booking: ProBooking) => void, onConflict: (message: string) => void) {
  const running = useRef(false);
  const [busy, setBusy] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const run = async (send: () => Promise<ProBooking>, backToForm: () => void) => {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setServerError(null);
    setFailure(null);
    try {
      onSuccess(await send());
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "disabled" || e.kind === "unauthorized")) return;
      if (e instanceof ProApiError && e.status === 400) {
        setServerError(e.message);
        backToForm();
      } else if (e instanceof ProApiError && e.status === 409) {
        onConflict(e.message);
      } else {
        setFailure(e instanceof Error && e.message ? e.message : "Your reply could not be sent. Please try again.");
      }
    } finally {
      running.current = false;
      setBusy(false);
    }
  };

  return { busy, serverError, failure, run };
}

// ---- The dialog frame: a form step, then a check-before-sending step ----

function ReplyDialogFrame({
  title,
  description,
  step,
  busy,
  serverError,
  failure,
  sendLabel,
  onContinue,
  onBack,
  onSend,
  onClose,
  form,
  review,
}: {
  title: string;
  description: string;
  step: "form" | "review";
  busy: boolean;
  serverError: string | null;
  failure: string | null;
  sendLabel: string;
  onContinue: () => void;
  onBack: () => void;
  onSend: () => void;
  onClose: () => void;
  form: ReactNode;
  review: ReactNode;
}) {
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      <DialogContent className="max-h-[calc(100dvh-2rem)] max-w-xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{step === "form" ? description : "Please check your reply before you send it."}</DialogDescription>
        </DialogHeader>

        {step === "form" ? (
          <div className="flex flex-col gap-5">
            {serverError ? <ErrorNotice message={serverError} /> : null}
            {form}
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {review}
            {failure ? <ErrorNotice message={failure} onRetry={busy ? undefined : onSend} /> : null}
          </div>
        )}

        <DialogFooter>
          {step === "form" ? (
            <>
              <button type="button" className={secondaryButtonClass} onClick={onClose}>
                Cancel
              </button>
              <button type="button" className={primaryButtonClass} onClick={onContinue}>
                Continue
              </button>
            </>
          ) : (
            <>
              <button type="button" className={secondaryButtonClass} onClick={onBack} disabled={busy}>
                Back
              </button>
              <button type="button" className={primaryButtonClass} onClick={onSend} disabled={busy}>
                {busy ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
                {busy ? "Sending your reply" : sendLabel}
              </button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ReviewLine({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm break-words whitespace-pre-line text-foreground">{value}</dd>
    </div>
  );
}

// ---- Meeting method, link or address, and note ----

interface MeetingValues {
  method: MeetingMethod | "";
  link: string;
  address: string;
  note: string;
}

type MeetingErrors = Partial<Record<keyof MeetingValues, string>>;

const EMPTY_MEETING: MeetingValues = { method: "", link: "", address: "", note: "" };

function methodOptions(b: ProBooking): MeetingMethod[] {
  if (b.preferredMode === "ONLINE") return ["PHONE", "VIDEO"];
  if (b.preferredMode === "IN_PERSON") return ["IN_PERSON"];
  return ["PHONE", "VIDEO", "IN_PERSON"];
}

function preferenceSentence(b: ProBooking): string | undefined {
  if (b.preferredMode === "IN_PERSON") return "The person asked to meet in person.";
  if (b.preferredMethod === "PHONE") return "The person asked for a phone call.";
  if (b.preferredMethod === "VIDEO") return "The person asked for a video call.";
  if (b.preferredMode === "ONLINE") return "The person asked for an online session.";
  return undefined;
}

function meetingErrors(v: MeetingValues): MeetingErrors {
  const errors: MeetingErrors = {};
  if (!v.method) errors.method = "Please choose how you will meet.";
  if (v.method === "VIDEO") {
    const problem = meetingLinkProblem(v.link);
    if (problem) errors.link = problem;
  }
  if (v.method === "IN_PERSON") {
    const problem = meetingAddressProblem(v.address);
    if (problem) errors.address = problem;
  }
  if (v.note.trim().length > MAX_REPLY_NOTE) errors.note = `The note must be at most ${MAX_REPLY_NOTE} characters.`;
  return errors;
}

/** Only the field that belongs to the chosen method is sent; an empty note is left out. */
function meetingBody(v: MeetingValues): MeetingDetails {
  const body: MeetingDetails = { meetingMethod: v.method as MeetingMethod };
  if (v.method === "VIDEO") body.meetingLink = v.link.trim();
  if (v.method === "IN_PERSON") body.meetingAddress = v.address.trim();
  if (v.note.trim()) body.note = v.note.trim();
  return body;
}

function NoteField({
  value,
  onChange,
  error,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  error?: string;
  label: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <TextAreaField
        label={label}
        rows={3}
        value={value}
        onChange={(next) => onChange(next.slice(0, MAX_REPLY_NOTE))}
        helper={NOTE_HELPER}
        error={error}
      />
      <p className="text-xs text-muted-foreground" aria-live="polite">
        {value.length} of {MAX_REPLY_NOTE} characters used.
      </p>
    </div>
  );
}

function MeetingFields({
  booking,
  value,
  onChange,
  errors,
}: {
  booking: ProBooking;
  value: MeetingValues;
  onChange: (value: MeetingValues) => void;
  errors: MeetingErrors;
}) {
  const set = <K extends keyof MeetingValues>(key: K, next: MeetingValues[K]) => onChange({ ...value, [key]: next });
  return (
    <>
      <SelectField
        label="How will you meet?"
        required
        value={value.method}
        onChange={(next) => set("method", next as MeetingMethod | "")}
        options={methodOptions(booking).map((method) => ({ value: method, label: METHOD_LABELS[method] }))}
        helper={preferenceSentence(booking)}
        error={errors.method}
      />
      {value.method === "PHONE" ? (
        <p className="rounded-xl bg-muted/60 px-4 py-3 text-sm text-foreground">
          You will call the person on their phone number. It is shown after you confirm.
        </p>
      ) : null}
      {value.method === "VIDEO" ? (
        <TextField
          label="Meeting link"
          required
          type="url"
          inputMode="url"
          placeholder="https://meet.example.com/your-room"
          value={value.link}
          onChange={(next) => set("link", next.slice(0, MAX_MEETING_LINK))}
          helper="Paste a link from the video service you use. Only this person will see it after you confirm."
          error={errors.link}
        />
      ) : null}
      {value.method === "IN_PERSON" ? (
        <TextField
          label="Clinic address"
          required
          placeholder="Clinic name, street, city"
          value={value.address}
          onChange={(next) => set("address", next)}
          error={errors.address}
        />
      ) : null}
      <NoteField label="Note for the person" value={value.note} onChange={(next) => set("note", next)} error={errors.note} />
    </>
  );
}

function MeetingReview({ value }: { value: MeetingValues }) {
  return (
    <>
      <ReviewLine label="How you will meet" value={value.method ? METHOD_LABELS[value.method] : ""} />
      {value.method === "VIDEO" ? <ReviewLine label="Meeting link" value={value.link.trim()} /> : null}
      {value.method === "IN_PERSON" ? <ReviewLine label="Clinic address" value={value.address.trim()} /> : null}
      <ReviewLine label="Your note" value={value.note.trim() || "You did not add a note."} />
    </>
  );
}

// ---- Confirm ----

type DialogProps = {
  booking: ProBooking;
  api: ProApi;
  onClose: () => void;
  onSuccess: (booking: ProBooking) => void;
  onConflict: (message: string) => void;
};

function ConfirmDialogContent({ booking: b, api, onClose, onSuccess, onConflict }: DialogProps) {
  const groupId = useId();
  const [now] = useState(() => Date.now());
  const [step, setStep] = useState<"form" | "review">("form");
  const [chosen, setChosen] = useState<"PREFERRED" | "ALTERNATE" | "">("");
  const [meeting, setMeeting] = useState<MeetingValues>(EMPTY_MEETING);
  const [showErrors, setShowErrors] = useState(false);
  const { busy, serverError, failure, run } = useReply(onSuccess, onConflict);

  const choices = [
    { value: "PREFERRED" as const, label: "First choice", time: b.preferredDatetime },
    ...(b.alternateDatetime ? [{ value: "ALTERNATE" as const, label: "Second choice", time: b.alternateDatetime }] : []),
  ];
  const chosenTime = choices.find((c) => c.value === chosen)?.time ?? null;
  const errors = meetingErrors(meeting);
  const timeError = chosen ? undefined : "Please choose a time.";
  const valid = !timeError && Object.keys(errors).length === 0;

  return (
    <ReplyDialogFrame
      title="Confirm the appointment"
      description="Choose the time and how you will meet. The person will see these details after you confirm."
      step={step}
      busy={busy}
      serverError={serverError}
      failure={failure}
      sendLabel="Confirm appointment"
      onClose={onClose}
      onContinue={() => {
        setShowErrors(true);
        if (valid) setStep("review");
      }}
      onBack={() => setStep("form")}
      onSend={() =>
        void run(
          () => api.confirmBooking(b.id, { chosenSlot: chosen as "PREFERRED" | "ALTERNATE", ...meetingBody(meeting) }),
          () => setStep("form"),
        )
      }
      form={
        <>
          <fieldset className="flex flex-col gap-2" aria-describedby={showErrors && timeError ? `${groupId}-error` : undefined}>
            <legend className="mb-1 text-sm font-medium text-foreground">
              Which time do you choose?
              <span aria-hidden="true" className="text-destructive">
                {" "}*
              </span>
            </legend>
            {choices.map((choice) => {
              const passed = !choice.time || Date.parse(choice.time) <= now;
              return (
                <label
                  key={choice.value}
                  className={cn(
                    "flex items-start gap-2.5 rounded-xl px-3 py-2.5 ring-1 ring-border",
                    passed ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted",
                  )}
                >
                  <input
                    type="radio"
                    name={groupId}
                    value={choice.value}
                    checked={chosen === choice.value}
                    disabled={passed}
                    onChange={() => setChosen(choice.value)}
                    className="mt-0.5 size-4 accent-primary"
                  />
                  <span className="flex flex-col text-sm">
                    <span className="font-medium text-foreground">{choice.label}</span>
                    <span className="text-muted-foreground">
                      {choice.time ? formatIstDateTime(choice.time) : "No time was given."}
                      {passed && choice.time ? " This time has already passed." : ""}
                    </span>
                  </span>
                </label>
              );
            })}
            {showErrors && timeError ? (
              <p id={`${groupId}-error`} className="text-xs text-destructive">
                {timeError}
              </p>
            ) : null}
          </fieldset>
          <MeetingFields booking={b} value={meeting} onChange={setMeeting} errors={showErrors ? errors : {}} />
        </>
      }
      review={
        <>
          <dl className="flex flex-col gap-3 rounded-xl bg-muted/60 p-4">
            <ReviewLine label="Appointment time" value={chosenTime ? formatIstDateTime(chosenTime) : ""} />
            <MeetingReview value={meeting} />
          </dl>
          <p className="text-sm text-foreground">
            After you confirm, the person will see these meeting details and you will see their contact details.
          </p>
        </>
      }
    />
  );
}

// ---- Suggest another time ----

type Availability =
  | { status: "loading" }
  | { status: "error"; message: string; canRetry: boolean }
  | { status: "ready"; professionalId: string; days: SlotDays; loadedTo: string; loadingMore: boolean; moreError: boolean };

function ProposeDialogContent({ booking: b, api, onClose, onSuccess, onConflict }: DialogProps) {
  const [today] = useState(todayInIndia);
  const lastDate = addDays(today, MAX_DAYS_AHEAD);
  const [step, setStep] = useState<"form" | "review">("form");
  const [availability, setAvailability] = useState<Availability>({ status: "loading" });
  const [availabilityTry, setAvailabilityTry] = useState(0);
  const [slot, setSlot] = useState("");
  const [meeting, setMeeting] = useState<MeetingValues>(EMPTY_MEETING);
  const [showErrors, setShowErrors] = useState(false);
  const { busy, serverError, failure, run } = useReply(onSuccess, onConflict);

  // Your own free times, from the same public availability the people booking you see (your id comes
  // from /pro/me, never from the address bar).
  useEffect(() => {
    const controller = new AbortController();
    const to = addDays(today, FIRST_DAYS - 1);
    api
      .me()
      .then(async (me) => {
        const days = await loadSlotDays(me.profile.id, today, to, controller.signal);
        if (!controller.signal.aborted) {
          setAvailability({ status: "ready", professionalId: me.profile.id, days, loadedTo: to, loadingMore: false, moreError: false });
        }
      })
      .catch((e) => {
        if (controller.signal.aborted) return;
        if (e instanceof ProApiError && (e.kind === "disabled" || e.kind === "unauthorized")) return;
        if (e instanceof ProfessionalsApiError && e.status === 404) {
          setAvailability({
            status: "error",
            message:
              "Your free times could not be loaded because your profile is not publicly listed right now. You can still confirm or decline this request.",
            canRetry: false,
          });
          return;
        }
        setAvailability({ status: "error", message: "Your free times could not be loaded. Please try again.", canRetry: true });
      });
    return () => controller.abort();
  }, [api, today, availabilityTry]);

  const showLaterDates = () => {
    if (availability.status !== "ready" || availability.loadingMore) return;
    const current = availability;
    const from = addDays(current.loadedTo, 1);
    const candidate = addDays(current.loadedTo, MORE_DAYS);
    const to = candidate < lastDate ? candidate : lastDate;
    setAvailability({ ...current, loadingMore: true, moreError: false });
    loadSlotDays(current.professionalId, from, to)
      .then((more) => setAvailability({ ...current, days: [...current.days, ...more], loadedTo: to, loadingMore: false }))
      .catch(() => setAvailability({ ...current, loadingMore: false, moreError: true }));
  };

  const errors = meetingErrors(meeting);
  const slotError = slot ? undefined : "Please choose a time.";
  const valid = availability.status === "ready" && !slotError && Object.keys(errors).length === 0;

  return (
    <ReplyDialogFrame
      title="Suggest another time"
      description={PROPOSE_EXPLANATION}
      step={step}
      busy={busy}
      serverError={serverError}
      failure={failure}
      sendLabel="Send suggestion"
      onClose={onClose}
      onContinue={() => {
        setShowErrors(true);
        if (valid) setStep("review");
      }}
      onBack={() => setStep("form")}
      onSend={() => void run(() => api.proposeBooking(b.id, { proposedSlot: slot, ...meetingBody(meeting) }), () => setStep("form"))}
      form={
        <>
          {availability.status === "loading" ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground" aria-busy="true">
              <Loader2 className="size-4 animate-spin" aria-hidden="true" />
              Loading your free times.
            </p>
          ) : availability.status === "error" ? (
            <ErrorNotice
              message={availability.message}
              onRetry={
                availability.canRetry
                  ? () => {
                      setAvailability({ status: "loading" });
                      setAvailabilityTry((n) => n + 1);
                    }
                  : undefined
              }
            />
          ) : (
            <div className="flex flex-col gap-3">
              {availability.days.length === 0 ? (
                <p className="text-sm text-foreground">You have no free times in the dates shown.</p>
              ) : (
                <SlotPicker
                  days={availability.days}
                  value={slot}
                  onChange={setSlot}
                  label="Which time do you suggest?"
                  helper="Only days with free times are shown."
                  error={showErrors ? slotError : undefined}
                />
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
              {availability.moreError ? <ErrorNotice message="The later dates could not be loaded. Please try again." /> : null}
            </div>
          )}
          <MeetingFields booking={b} value={meeting} onChange={setMeeting} errors={showErrors ? errors : {}} />
        </>
      }
      review={
        <>
          <dl className="flex flex-col gap-3 rounded-xl bg-muted/60 p-4">
            <ReviewLine label="Suggested time" value={slot ? formatIstDateTime(slot) : ""} />
            <MeetingReview value={meeting} />
          </dl>
          <p className="text-sm text-foreground">{PROPOSE_EXPLANATION}</p>
        </>
      }
    />
  );
}

// ---- Decline ----

function DeclineDialogContent({ booking: b, api, onClose, onSuccess, onConflict }: DialogProps) {
  const [step, setStep] = useState<"form" | "review">("form");
  const [note, setNote] = useState("");
  const { busy, serverError, failure, run } = useReply(onSuccess, onConflict);

  return (
    <ReplyDialogFrame
      title="Decline this request"
      description={DECLINE_EXPLANATION}
      step={step}
      busy={busy}
      serverError={serverError}
      failure={failure}
      sendLabel="Decline request"
      onClose={onClose}
      onContinue={() => setStep("review")}
      onBack={() => setStep("form")}
      onSend={() => void run(() => api.declineBooking(b.id, note.trim() ? { note: note.trim() } : {}), () => setStep("form"))}
      form={<NoteField label="Note for the person (optional)" value={note} onChange={setNote} />}
      review={
        <>
          <dl className="flex flex-col gap-3 rounded-xl bg-muted/60 p-4">
            <ReviewLine label="Your note" value={note.trim() || "You did not add a note."} />
          </dl>
          <p className="text-sm text-foreground">{DECLINE_EXPLANATION}</p>
        </>
      }
    />
  );
}

/** The dialog for one reply. Render it only while open, so every opening starts empty. */
export function ReplyDialog({ kind, ...props }: DialogProps & { kind: ReplyKind }) {
  if (kind === "confirm") return <ConfirmDialogContent {...props} />;
  if (kind === "propose") return <ProposeDialogContent {...props} />;
  return <DeclineDialogContent {...props} />;
}
