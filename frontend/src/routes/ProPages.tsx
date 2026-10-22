import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";
import { Loader2 } from "lucide-react";
import { ProJoin } from "@/components/pro/pro-join";
import { ProProfilePage } from "@/components/pro/pro-profile";
import { ProRequestPage, ProRequestsPage } from "@/components/pro/pro-requests";

/** Open to everyone: explains the process and shows sign-in; a signed-in visitor goes to /pro/profile. */
export function ProJoinPage() {
  return <ProJoin />;
}

/**
 * Signed-in only. While Clerk is loading nothing is decided (no redirect); once it has loaded, a
 * signed-out visitor goes to the professional sign-in on /pro/join, which brings them back here.
 */
export function ProProfileRoute() {
  const { isLoaded, isSignedIn } = useAuth();
  if (!isLoaded) {
    return (
      <div className="flex justify-center py-24 text-muted-foreground">
        <Loader2 className="size-6 animate-spin" aria-label="Loading" />
      </div>
    );
  }
  if (!isSignedIn) return <Navigate to="/pro/join" replace />;
  return <ProProfilePage />;
}

/** The same guard as /pro/profile: wait for Clerk, then send a signed-out visitor to /pro/join. */
function SignedInPro({ children }: { children: ReactNode }) {
  const { isLoaded, isSignedIn } = useAuth();
  if (!isLoaded) {
    return (
      <div className="flex justify-center py-24 text-muted-foreground">
        <Loader2 className="size-6 animate-spin" aria-label="Loading" />
      </div>
    );
  }
  if (!isSignedIn) return <Navigate to="/pro/join" replace />;
  return <>{children}</>;
}

export function ProRequestsRoute() {
  return (
    <SignedInPro>
      <ProRequestsPage />
    </SignedInPro>
  );
}

export function ProRequestRoute() {
  return (
    <SignedInPro>
      <ProRequestPage />
    </SignedInPro>
  );
}
