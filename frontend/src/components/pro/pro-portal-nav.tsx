import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useLocation } from "react-router-dom";
import { useAuth } from "@clerk/clerk-react";

import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { ProApiError, useProApi } from "@/lib/pro-client";
import { cn } from "@/lib/utils";

type Tab = { href: string; label: string; badge?: number };

type NavState =
  | { status: "loading" }
  // No professional account yet (or the account is disabled): no portal tabs.
  | { status: "hidden" }
  | { status: "ready"; verified: boolean; pending: number; hasRequests: boolean };

/**
 * The professional portal's tabs: Dashboard, Profile, Requests, Appointments, Days off and Earnings. A professional
 * who is not verified sees Dashboard and Profile, plus Requests once they have any request. The Requests
 * tab shows how many requests wait for a reply. The counts are loaded again on every page change and
 * when the window gets focus.
 */
export function ProPortalNav() {
  const { isLoaded, isSignedIn } = useAuth();
  const { api } = useProApi();
  const { pathname } = useLocation();
  const [state, setState] = useState<NavState>({ status: "loading" });
  const [focusCount, setFocusCount] = useState(0);

  useEffect(() => {
    const onFocus = () => setFocusCount((n) => n + 1);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  const ready = isLoaded && isSignedIn;
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    (async () => {
      try {
        const summary = await api.dashboard();
        const verified = summary.verificationStatus === "VERIFIED";
        let hasRequests = verified || summary.pendingRequests > 0 || summary.awaitingUser > 0 || summary.upcomingConfirmed > 0;
        if (!hasRequests) hasRequests = (await api.bookings("", 0, 1)).totalElements > 0;
        if (!cancelled) setState({ status: "ready", verified, pending: summary.pendingRequests, hasRequests });
      } catch (e) {
        if (cancelled) return;
        // 401: signed in, but no professional account yet. 403: disabled (the page says so).
        if (e instanceof ProApiError && (e.status === 401 || e.status === 403)) setState({ status: "hidden" });
        // Anything else: keep the last tabs (or none yet); the page itself reports its own errors.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [ready, api, pathname, focusCount]);

  if (state.status === "hidden") return null;

  const tabs: Tab[] = [
    { href: "/pro/dashboard", label: "Dashboard" },
    { href: "/pro/profile", label: "Profile" },
  ];
  if (state.status === "ready" && (state.verified || state.hasRequests)) {
    tabs.push({ href: "/pro/requests", label: "Requests", badge: state.pending });
  }
  if (state.status === "ready" && state.verified) {
    tabs.push(
      { href: "/pro/appointments", label: "Appointments" },
      { href: "/pro/days-off", label: "Days off" },
      { href: "/pro/earnings", label: "Earnings" },
      { href: "/pro/reviews", label: "Reviews" },
    );
  }

  return (
    <nav aria-label="Professional portal" className="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0">
      <ul className="flex min-w-max gap-1 rounded-full bg-muted/60 p-1">
        {tabs.map((tab) => {
          const current = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
          return (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-medium whitespace-nowrap transition-colors",
                  current ? "bg-card text-foreground shadow-sm ring-1 ring-foreground/10" : "text-muted-foreground hover:text-foreground",
                )}
              >
                {tab.label}
                {tab.badge ? (
                  <span className="inline-flex min-w-5 items-center justify-center rounded-full bg-primary px-1.5 py-0.5 text-xs font-semibold text-primary-foreground">
                    {tab.badge}
                    <span className="sr-only"> {tab.badge === 1 ? "request is" : "requests are"} waiting for your reply</span>
                  </span>
                ) : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** The frame of the new portal pages: the tabs, then the title and a short introduction. */
export function ProPortalFrame({ title, intro, children }: { title: string; intro?: string; children: ReactNode }) {
  return (
    <section className="py-10 sm:py-14">
      <Container className="flex max-w-4xl flex-col gap-6">
        <ProPortalNav />
        <header className="flex flex-col gap-2">
          <BackLink />
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">{title}</h1>
          {intro ? <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">{intro}</p> : null}
        </header>
        {children}
      </Container>
    </section>
  );
}
