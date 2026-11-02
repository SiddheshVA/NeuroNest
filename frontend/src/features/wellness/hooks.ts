import { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import { loginHref } from "@/lib/safe-redirect";
import { latestCheckin, listCheckins } from "./api.ts";
import { errorKind } from "./errors.ts";
import type { CheckinItem, CheckinPage } from "./types.ts";

/** The app's existing re-login behaviour on a 401: back to sign-in, returning to this page afterwards. */
export function useRelogin() {
  const navigate = useNavigate();
  const location = useLocation();
  return useCallback(
    () => navigate(loginHref(`${location.pathname}${location.search}`), { replace: true }),
    [navigate, location.pathname, location.search],
  );
}

type Load<T> = { status: "loading" } | { status: "error"; error: unknown } | { status: "ready"; data: T };

/** The latest stored check-in (null when there is none yet). */
export function useLatestCheckin() {
  const relogin = useRelogin();
  const [state, setState] = useState<Load<CheckinItem | null>>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    latestCheckin()
      .then((data) => !cancelled && setState({ status: "ready", data }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (errorKind(error) === "unauthorized") relogin();
        else setState({ status: "error", error });
      });
    return () => {
      cancelled = true;
    };
  }, [attempt, relogin]);

  const retry = useCallback(() => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  }, []);
  return { state, retry };
}

/** One page of the user's history; reload() refetches it in place (no full page reload). */
export function useCheckinHistory(page: number, size: number) {
  const relogin = useRelogin();
  const [state, setState] = useState<Load<CheckinPage>>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    listCheckins(page, size)
      .then((data) => !cancelled && setState({ status: "ready", data }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (errorKind(error) === "unauthorized") relogin();
        else setState({ status: "error", error });
      });
    return () => {
      cancelled = true;
    };
  }, [page, size, attempt, relogin]);

  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const retry = useCallback(() => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  }, []);
  return { state, setState, reload, retry };
}
