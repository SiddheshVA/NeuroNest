import type { ReactNode } from "react";
import { SignIn, SignUp, useAuth } from "@clerk/clerk-react";
import { Navigate, useLocation, useSearchParams } from "react-router-dom";
import { Loader2, Stethoscope } from "lucide-react";
import { AccentLink } from "@/components/shared/accent-link";

// Shown under the user sign-in and sign-up. Professionals have their own sign-in on /pro/join, which
// always continues to /pro/profile, so a professional never lands on the user home.
function ProfessionalAccent() {
  return (
    <AccentLink to="/pro/join" icon={Stethoscope}>
      Are you a licensed professional? Sign in or apply to be listed here.
    </AccentLink>
  );
}

// The user sign-in. After signing in a user goes where they were heading (redirect_url) or to the
// dashboard, exactly as before.
export function LoginPage() {
  const [params] = useSearchParams();
  return (
    <section className="flex min-h-[70vh] flex-col items-center justify-center gap-5 bg-muted px-4 py-16">
      <SignIn
        path="/login"
        routing="path"
        signUpUrl="/sign-up"
        fallbackRedirectUrl={params.get("redirect_url") || "/dashboard"}
      />
      <ProfessionalAccent />
    </section>
  );
}

export function SignUpPage() {
  return (
    <section className="flex min-h-[70vh] flex-col items-center justify-center gap-5 bg-muted px-4 py-16">
      <SignUp path="/sign-up" routing="path" signInUrl="/login" fallbackRedirectUrl="/dashboard" />
      <ProfessionalAccent />
    </section>
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
