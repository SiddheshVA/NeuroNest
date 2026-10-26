import type { ReactNode } from "react";
import { SignIn, SignUp, useAuth } from "@clerk/clerk-react";
import { Link, Navigate, useLocation, useSearchParams } from "react-router-dom";
import { Loader2, Stethoscope } from "lucide-react";
import { safeRedirectPath } from "@/lib/safe-redirect";

// Width of the sign-in card and the professional card under it.
const AUTH_CARD_WIDTH = "26.5rem";
const GREEN = "#233E33";
const GREEN_HOVER = "#192E26";
const FOCUS_RING = "0 0 0 3px rgb(35 62 51 / 0.22)";

// Look of the Clerk sign-in and sign-up cards, on these two pages only (the app-wide Clerk settings
// are in ClerkProviders.tsx). Style objects rather than class names, so they win over Clerk's own styles.
const authAppearance = {
  variables: {
    colorPrimary: GREEN,
    colorForeground: "#1C352D",
    colorMutedForeground: "#4A5750",
    colorInput: "#F7F8F3",
    colorInputForeground: "#1C352D",
    colorBorder: "#D5DBCF",
    colorRing: GREEN,
    borderRadius: "0.75rem",
  },
  elements: {
    rootBox: { width: "100%", maxWidth: AUTH_CARD_WIDTH },
    cardBox: {
      width: "100%",
      maxWidth: "100%",
      borderRadius: "1.5rem",
      border: "1px solid #E5E8E1",
      boxShadow: "0 18px 48px -20px rgb(28 53 45 / 0.25)",
    },
    card: { padding: "2rem 1.5rem", boxShadow: "none", "@media (min-width: 640px)": { padding: "2.5rem 2.5rem 2rem" } },
    headerTitle: { fontSize: "1.25rem", fontWeight: 600, color: "#1C352D" },
    headerSubtitle: { color: "#4A5750" },
    socialButtonsBlockButton: {
      minHeight: "2.75rem",
      borderRadius: "9999px",
      border: "1px solid #D5DBCF !important",
      boxShadow: "none !important",
      transition: "background-color 150ms",
      "&:hover": { backgroundColor: "#F4F6F0" },
      "&:focus-visible": { boxShadow: `${FOCUS_RING} !important` },
    },
    dividerLine: { backgroundColor: "#E2E6DD" },
    dividerText: { color: "#4A5750" },
    formFieldLabel: { color: "#1C352D" },
    formFieldInput: {
      minHeight: "2.75rem",
      borderRadius: "0.75rem",
      // Clerk draws its own input border as a shadow, which these replace.
      border: "1px solid #D5DBCF !important",
      boxShadow: "none !important",
      "&:hover": { borderColor: "#BFC8B8 !important" },
      "&:focus": { borderColor: `${GREEN} !important`, boxShadow: `${FOCUS_RING} !important` },
    },
    formButtonPrimary: {
      minHeight: "2.75rem",
      borderRadius: "9999px",
      backgroundColor: GREEN,
      backgroundImage: "none",
      boxShadow: "none",
      textTransform: "none",
      fontSize: "0.875rem",
      transition: "background-color 150ms",
      "&:hover": { backgroundColor: GREEN_HOVER },
      "&:focus-visible": { boxShadow: FOCUS_RING },
    },
    footerActionText: { color: "#4A5750" },
    footerActionLink: { color: GREEN, fontWeight: 600 },
  },
};

// The sign-in / sign-up page: the valley artwork behind the Clerk card, the professional card under it.
function AuthShell({ children }: { children: ReactNode }) {
  return (
    <section className="auth-backdrop flex min-h-[calc(100svh-4rem)] flex-col items-center gap-6 px-4 py-12 sm:py-16">
      {children}
      <ProfessionalAccent />
    </section>
  );
}

// Shown under the user sign-in and sign-up. Professionals have their own sign-in on /pro/join, which
// always continues to /pro/dashboard, so a professional never lands on the user home.
// A frosted card, as wide as the Clerk card above it, so the artwork shows through: the icon in a soft
// circle, a heading, one supporting line and a button. Stacks on narrow screens with a full-width button.
function ProfessionalAccent() {
  return (
    <aside
      aria-labelledby="pro-callout-heading"
      style={{ maxWidth: AUTH_CARD_WIDTH }}
      className="flex w-full flex-col items-center gap-4 rounded-3xl border border-primary/20 bg-[#F4F6EF]/75 p-6 text-center shadow-xs backdrop-blur-md sm:flex-row sm:items-start sm:gap-5 sm:p-7 sm:text-left"
    >
      <span className="flex size-14 shrink-0 items-center justify-center rounded-full bg-primary/12 text-primary">
        <Stethoscope className="size-7" aria-hidden="true" />
      </span>
      <div className="flex w-full flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <h2 id="pro-callout-heading" className="font-heading text-lg leading-snug font-semibold text-foreground">
            Are you a licensed mental health professional?
          </h2>
          <p className="text-sm leading-relaxed text-primary dark:text-muted-foreground">
            Join NeuroNest to reach people who are looking for support. Your profile is reviewed before it goes live.
          </p>
        </div>
        <Link
          to="/pro/join"
          className="inline-flex min-h-11 w-full items-center justify-center rounded-full bg-[#233E33] px-5 text-sm font-medium text-white outline-none transition-colors duration-150 hover:bg-[#192E26] focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:ring-offset-2 focus-visible:ring-offset-background sm:w-auto sm:self-start"
        >
          Sign in or apply to be listed
        </Link>
      </div>
    </aside>
  );
}

// Where to go after signing in or signing up: redirect_url when it is a relative path on this site,
// otherwise the dashboard. It is passed to Clerk as the forced redirect, so Clerk never follows an
// unchecked redirect_url from the address bar. Clerk keeps the query string through its own steps.
function useAfterAuthPath() {
  const [params] = useSearchParams();
  const raw = params.get("redirect_url");
  const target = safeRedirectPath(raw);
  return { target: target ?? "/dashboard", query: target ? `?redirect_url=${encodeURIComponent(target)}` : "" };
}

// The user sign-in. After signing in a user goes where they were heading (redirect_url) or to the
// dashboard.
export function LoginPage() {
  const { target, query } = useAfterAuthPath();
  return (
    <AuthShell>
      <SignIn
        path="/login"
        routing="path"
        signUpUrl={`/sign-up${query}`}
        forceRedirectUrl={target}
        signUpForceRedirectUrl={target}
        appearance={authAppearance}
      />
    </AuthShell>
  );
}

export function SignUpPage() {
  const { target, query } = useAfterAuthPath();
  return (
    <AuthShell>
      <SignUp
        path="/sign-up"
        routing="path"
        signInUrl={`/login${query}`}
        forceRedirectUrl={target}
        signInForceRedirectUrl={target}
        appearance={authAppearance}
      />
    </AuthShell>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { isLoaded, isSignedIn } = useAuth();
  const location = useLocation();

  if (!isLoaded) {
    return (
      <div className="flex justify-center py-24 text-muted-foreground">
        <Loader2 className="size-6 animate-spin" aria-label="Loading" />
      </div>
    );
  }

  if (!isSignedIn) {
    // Clerk reads redirect_url off the URL itself and carries it through its
    // own internal step navigations, unlike React Router state.
    const target = `${location.pathname}${location.search}`;
    return <Navigate to={`/login?redirect_url=${encodeURIComponent(target)}`} replace />;
  }

  return <>{children}</>;
}
