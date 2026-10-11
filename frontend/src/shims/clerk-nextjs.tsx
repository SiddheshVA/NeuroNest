import type { ReactNode } from "react";
import { useAuth } from "@clerk/clerk-react";

// Drop-in for the @clerk/nextjs client APIs the ported components use.
export { SignIn, SignUp, UserButton, useAuth, useClerk, useUser } from "@clerk/clerk-react";

// @clerk/nextjs v7's <Show when="signed-in" fallback={...}>.
export function Show({
  when,
  fallback = null,
  children,
}: {
  when: "signed-in" | "signed-out";
  fallback?: ReactNode;
  children?: ReactNode;
}) {
  const { isLoaded, isSignedIn } = useAuth();
  if (!isLoaded) return null;
  const matches = when === "signed-in" ? isSignedIn : !isSignedIn;
  return <>{matches ? children : fallback}</>;
}
