import type { ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { ClerkProvider, useAuth } from "@clerk/clerk-react";
import { registerTokenGetter } from "@/lib/clerkAuthBridge";

const CLERK_PUBLISHABLE_KEY = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY;

if (!CLERK_PUBLISHABLE_KEY) {
  throw new Error(
    "Missing VITE_CLERK_PUBLISHABLE_KEY. Set it in frontend/.env.local (see Clerk dashboard > API Keys).",
  );
}

// Matches the NeuroNest palette (see globals.css).
const appearance = {
  variables: {
    colorPrimary: "#233E33",
    colorText: "#1C352D",
    borderRadius: "0.75rem",
    fontFamily: "var(--font-sans)",
  },
};

// Routes Clerk's internal navigation (post sign-in/up redirects, UserButton
// links) through React Router instead of a full page reload.
export function ClerkProviderWithRoutes({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  return (
    <ClerkProvider
      publishableKey={CLERK_PUBLISHABLE_KEY}
      appearance={appearance}
      routerPush={(to) => navigate(to)}
      routerReplace={(to) => navigate(to, { replace: true })}
      signInUrl="/login"
      signUpUrl="/sign-up"
    >
      {children}
    </ClerkProvider>
  );
}

// Wires the api.ts client to Clerk's getToken() so it can attach a fresh
// bearer token to every backend request without api.ts being a component.
export function ClerkTokenBridge({ children }: { children: ReactNode }) {
  const { getToken } = useAuth();
  registerTokenGetter(getToken);
  return <>{children}</>;
}
