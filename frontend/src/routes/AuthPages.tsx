import type { ReactNode } from "react";
import { SignIn, SignUp, useAuth } from "@clerk/clerk-react";
import { Navigate, useLocation, useSearchParams } from "react-router-dom";
import { Loader2 } from "lucide-react";

export function LoginPage() {
  const [params] = useSearchParams();
  return (
    <section className="flex min-h-[70vh] items-center justify-center bg-muted px-4 py-16">
      <SignIn
        path="/login"
        routing="path"
        signUpUrl="/sign-up"
        fallbackRedirectUrl={params.get("redirect_url") || "/dashboard"}
      />
    </section>
  );
}

export function SignUpPage() {
  return (
    <section className="flex min-h-[70vh] items-center justify-center bg-muted px-4 py-16">
      <SignUp path="/sign-up" routing="path" signInUrl="/login" fallbackRedirectUrl="/dashboard" />
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
