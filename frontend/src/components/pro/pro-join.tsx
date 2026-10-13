import { useEffect, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { SignIn, SignUp, useAuth, useUser } from "@clerk/clerk-react";
import { ClipboardCheck, FileUp, Mail, ShieldCheck, UserPlus, UserRound, type LucideIcon } from "lucide-react";
import { AccentLink } from "@/components/shared/accent-link";

import { Container } from "@/components/layout/container";
import {
  DisabledAccountNotice,
  ErrorNotice,
  LoadingBlock,
  SelectField,
  TextField,
  primaryButtonClass,
} from "@/components/pro/pro-ui";
import {
  PRO_CONSULTATION_MODE_LABELS,
  PRO_CONSULTATION_MODES,
  PRO_CREDENTIAL_TYPE_LABELS,
  PRO_CREDENTIAL_TYPES,
  ProApiError,
  useProApi,
  type ProConsultationMode,
  type ProCredentialType,
} from "@/lib/pro-client";
import { cn } from "@/lib/utils";

const JOIN_PATH = "/pro/join";
const PROFILE_PATH = "/pro/profile";
const CHOOSE_OPTION = "Please choose an option.";

const STEPS: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: UserPlus, title: "Create an account", text: "You sign in to NeuroNest and create your professional account." },
  { icon: ClipboardCheck, title: "Add your details", text: "You fill in your qualifications, registration and practice details." },
  { icon: FileUp, title: "Upload your proof", text: "You upload proof of your registration and identity, which is stored privately." },
  { icon: ShieldCheck, title: "Review and approval", text: "The NeuroNest team reviews your details, and you are listed only after approval." },
];

function ProcessExplainer() {
  return (
    <div className="flex flex-col gap-5">
      <ol className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {STEPS.map((step, index) => (
          <li key={step.title} className="flex items-start gap-3 rounded-2xl bg-card p-4 ring-1 ring-foreground/10">
            <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
              <step.icon className="size-4" aria-hidden="true" />
            </span>
            <div className="flex flex-col gap-0.5">
              <span className="text-sm font-semibold text-foreground">
                Step {index + 1}: {step.title}
              </span>
              <span className="text-sm text-muted-foreground">{step.text}</span>
            </div>
          </li>
        ))}
      </ol>
      <p className="rounded-2xl border border-border bg-muted/60 px-4 py-3 text-sm font-medium text-foreground">
        Nothing about you is shown to the public until the NeuroNest team has approved your profile.
      </p>
    </div>
  );
}

/**
 * Clerk's own sign-in and sign-up, kept on this page. Afterwards Clerk always sends the person to
 * /pro/profile (never the user home); someone without a professional account yet is sent from there
 * to the registration form on this page. Switching between sign-in and sign-up stays on this page.
 */
function SignInOrUp() {
  const [params, setParams] = useSearchParams();
  const mode = params.get("mode") === "sign-up" ? "sign-up" : "sign-in";
  const setMode = (next: "sign-in" | "sign-up") => setParams(next === "sign-up" ? { mode: "sign-up" } : {}, { replace: true });
  return (
    <div className="flex flex-col items-center gap-4">
      <div className="flex rounded-full border border-border bg-card p-1" role="group" aria-label="Choose how to continue">
        {(["sign-in", "sign-up"] as const).map((option) => (
          <button
            key={option}
            type="button"
            aria-pressed={mode === option}
            onClick={() => setMode(option)}
            className={cn(
              "rounded-full px-4 py-1.5 text-sm font-medium transition-colors",
              mode === option ? "bg-primary text-primary-foreground" : "text-foreground hover:bg-muted",
            )}
          >
            {option === "sign-in" ? "I have an account" : "Create an account"}
          </button>
        ))}
      </div>
      {mode === "sign-in" ? (
        <SignIn
          routing="hash"
          signUpUrl={`${JOIN_PATH}?mode=sign-up`}
          forceRedirectUrl={PROFILE_PATH}
          signUpForceRedirectUrl={PROFILE_PATH}
        />
      ) : (
        <SignUp
          routing="hash"
          signInUrl={JOIN_PATH}
          forceRedirectUrl={PROFILE_PATH}
          signInForceRedirectUrl={PROFILE_PATH}
        />
      )}
    </div>
  );
}

function RegisterForm({ onRegistered }: { onRegistered: () => void }) {
  const { api, disabled } = useProApi();
  const { user } = useUser();
  const [fullName, setFullName] = useState("");
  const [credentialType, setCredentialType] = useState<ProCredentialType | "">("");
  const [consultationMode, setConsultationMode] = useState<ProConsultationMode | "">("");
  const [errors, setErrors] = useState<Partial<Record<"fullName" | "credentialType" | "consultationMode", string>>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (disabled) return <DisabledAccountNotice />;

  const email = user?.primaryEmailAddress?.emailAddress;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const next: typeof errors = {};
    if (!fullName.trim()) next.fullName = "Please enter your full name.";
    if (!credentialType) next.credentialType = CHOOSE_OPTION;
    if (!consultationMode) next.consultationMode = CHOOSE_OPTION;
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length > 0 || !credentialType || !consultationMode) return;

    setSubmitting(true);
    try {
      await api.register({ fullName: fullName.trim(), credentialType, consultationMode });
      onRegistered();
    } catch (e) {
      if (!(e instanceof ProApiError)) {
        setServerError("Registration failed. Please try again.");
      } else if (e.status === 409 && e.message.startsWith("A professional account already exists for this sign-in")) {
        // Already registered (for example in another tab): continue to the profile.
        onRegistered();
      } else if (e.kind !== "unauthorized" && e.kind !== "disabled") {
        setServerError(e.message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8">
      <div className="flex flex-col gap-1">
        <h2 className="font-heading text-xl font-semibold text-foreground">Create your professional account</h2>
        <p className="text-sm text-muted-foreground">You can add the rest of your details on the next page.</p>
      </div>

      <div className="flex items-start gap-2.5 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-100">
        <Mail className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <p>
          NeuroNest will send notifications about your listing to the email address on your sign-in account
          {email ? (
            <>
              , which is <span className="font-semibold">{email}</span>
            </>
          ) : null}
          . To use a different address, change it in your account settings before you continue. This email address
          must be verified.
        </p>
      </div>

      {serverError ? <ErrorNotice message={serverError} /> : null}

      <p className="text-sm text-muted-foreground">
        Fields marked with an asterisk (<span className="text-destructive">*</span>) are required. The grey text
        inside a field is only an example.
      </p>
      <TextField label="Full name" required placeholder="Dr. Asha Rao" value={fullName} onChange={setFullName} error={errors.fullName}
        helper="Use the name that appears on your registration certificate." />
      <SelectField
        label="Credential type"
        required
        value={credentialType}
        onChange={(v) => setCredentialType(v as ProCredentialType | "")}
        options={PRO_CREDENTIAL_TYPES.map((t) => ({ value: t, label: PRO_CREDENTIAL_TYPE_LABELS[t] }))}
        error={errors.credentialType}
      />
      <SelectField
        label="Consultation mode"
        required
        value={consultationMode}
        onChange={(v) => setConsultationMode(v as ProConsultationMode | "")}
        options={PRO_CONSULTATION_MODES.map((m) => ({ value: m, label: PRO_CONSULTATION_MODE_LABELS[m] }))}
        error={errors.consultationMode}
      />
      <button type="submit" className={cn(primaryButtonClass, "self-start")} disabled={submitting}>
        {submitting ? "Creating your account" : "Create professional account"}
      </button>
    </form>
  );
}

type JoinState = { status: "checking" } | { status: "register" } | { status: "error"; message: string };

function SignedInJoin() {
  const navigate = useNavigate();
  const { api, disabled } = useProApi();
  const [state, setState] = useState<JoinState>({ status: "checking" });
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .me()
      .then(() => {
        if (!cancelled) navigate(PROFILE_PATH, { replace: true });
      })
      .catch((e) => {
        if (cancelled || !(e instanceof ProApiError)) return;
        if (e.kind === "not-registered") setState({ status: "register" });
        else if (e.kind !== "unauthorized" && e.kind !== "disabled") setState({ status: "error", message: e.message });
      });
    return () => {
      cancelled = true;
    };
  }, [api, navigate, retry]);

  if (disabled) return <DisabledAccountNotice />;
  if (state.status === "checking") return <LoadingBlock label="Checking your professional account." />;
  if (state.status === "error") {
    return (
      <ErrorNotice
        message={state.message}
        onRetry={() => {
          setState({ status: "checking" });
          setRetry((n) => n + 1);
        }}
      />
    );
  }
  return <RegisterForm onRegistered={() => navigate(PROFILE_PATH, { replace: true })} />;
}

function ProJoin() {
  const { isLoaded, isSignedIn } = useAuth();

  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-4xl flex-col gap-8">
        <header className="flex flex-col gap-2">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
            List your practice on NeuroNest
          </h1>
          <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
            Licensed counselors, psychologists and psychiatrists can apply to appear in the NeuroNest directory.
          </p>
        </header>

        <ProcessExplainer />

        {!isLoaded ? (
          <LoadingBlock label="Loading your sign-in status." />
        ) : isSignedIn ? (
          <SignedInJoin />
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-center text-sm text-muted-foreground">
              Sign in or create an account to begin. You will go to your professional profile afterwards.
            </p>
            <SignInOrUp />
            <div className="flex justify-center">
              <AccentLink to="/login" icon={UserRound}>
                Not a professional? Go back to user login.
              </AccentLink>
            </div>
          </div>
        )}
      </Container>
    </section>
  );
}

export { ProJoin };
