import { Navigate, useSearchParams } from "react-router-dom";
import { SignIn, SignUp, useAuth } from "@clerk/clerk-react";
import { ClipboardCheck, FileUp, ShieldCheck, UserPlus, UserRound, type LucideIcon } from "lucide-react";
import { AccentLink } from "@/components/shared/accent-link";

import { Container } from "@/components/layout/container";
import { LoadingBlock } from "@/components/pro/pro-ui";
import { cn } from "@/lib/utils";

const JOIN_PATH = "/pro/join";
// After sign-in or sign-up: an existing professional stays on the dashboard; someone without a
// professional account yet is sent on from there to /pro/profile, where the first save registers them.
const HOME_PATH = "/pro/dashboard";

const STEPS: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: UserPlus, title: "Create an account", text: "You sign in to NeuroNest and create your account." },
  { icon: ClipboardCheck, title: "Add your details", text: "You fill in your qualifications, registration / license number and practice details." },
  { icon: FileUp, title: "Upload your proof", text: "You upload proof of your registration and identity, which is stored privately." },
  { icon: ShieldCheck, title: "Review and approval", text: "Your profile shows as Pending verification while the NeuroNest team reviews it. You are listed only after approval." },
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
 * /pro/dashboard (never the user home); someone without a professional account yet goes on from there to
 * the empty profile, where their first save creates the account. Switching between sign-in and sign-up
 * stays on this page.
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
          forceRedirectUrl={HOME_PATH}
          signUpForceRedirectUrl={HOME_PATH}
        />
      ) : (
        <SignUp
          routing="hash"
          signInUrl={JOIN_PATH}
          forceRedirectUrl={HOME_PATH}
          signInForceRedirectUrl={HOME_PATH}
        />
      )}
    </div>
  );
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
            Licensed mental health professionals (psychologists, psychiatrists and registered counsellors) can apply
            to appear in the NeuroNest directory.
          </p>
          <p className="max-w-2xl text-sm font-medium text-foreground">
            Teachers, trainers and coaches are not eligible.
          </p>
        </header>

        <ProcessExplainer />

        {!isLoaded ? (
          <LoadingBlock label="Loading your sign-in status." />
        ) : isSignedIn ? (
          // Signed in already: the dashboard sends someone without a professional account on to the profile.
          <Navigate to={HOME_PATH} replace />
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-center text-sm text-muted-foreground">
              Sign in or create an account to begin. You will go to your professional dashboard afterwards.
            </p>
            <SignInOrUp />
            <div className="flex justify-center">
              <AccentLink to="/login" icon={UserRound}>
                Not a licensed mental health professional? Go back to user login.
              </AccentLink>
            </div>
          </div>
        )}
      </Container>
    </section>
  );
}

export { ProJoin };
