import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { adminSessionRemainingMs, clearAdminSession, EXPIRED_LOGIN_PATH, getValidAdminToken } from "./admin-api";

// Lets the admin pages render only with an unexpired admin token. The server still checks every
// request; this guard only avoids showing pages that would immediately fail.
export function AdminGuard({ children }: { children: ReactNode }) {
  const location = useLocation();

  if (getValidAdminToken()) {
    return <>{children}</>;
  }

  const hadSession = adminSessionRemainingMs() !== null;
  clearAdminSession();
  const from = location.pathname + location.search;
  return <Navigate to={hadSession ? EXPIRED_LOGIN_PATH : "/admin/login"} replace state={{ from }} />;
}
