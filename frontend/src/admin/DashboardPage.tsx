import { useEffect, useState, type ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Skeleton from "@mui/material/Skeleton";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { AdminApiError, getDashboardSummary } from "./admin-api";
import { medianHoursText } from "./format";
import type { DashboardSummary } from "./types";

type Loaded = { data: DashboardSummary | null; error: string | null };

/** /admin: the numbers that need attention, loaded again whenever the window gets focus. */
export function DashboardPage() {
  const [reloadCount, setReloadCount] = useState(0);
  const [loaded, setLoaded] = useState<Loaded>({ data: null, error: null });

  useEffect(() => {
    let cancelled = false;
    getDashboardSummary()
      .then((data) => !cancelled && setLoaded({ data, error: null }))
      .catch((e) => {
        // A 401 has already sent the admin to the login page with the session-expired message.
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        const message = e instanceof Error ? e.message : "The dashboard could not be loaded. Please try again.";
        setLoaded((current) => ({ data: current.data, error: message }));
      });
    return () => {
      cancelled = true;
    };
  }, [reloadCount]);

  useEffect(() => {
    const refresh = () => setReloadCount((n) => n + 1);
    window.addEventListener("focus", refresh);
    return () => window.removeEventListener("focus", refresh);
  }, []);

  const s = loaded.data;

  return (
    <Stack spacing={4}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Dashboard
        </Typography>
        <Typography color="text.secondary">What needs your attention, and how bookings are going.</Typography>
      </Box>

      {loaded.error && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => setReloadCount((n) => n + 1)}>
              Retry
            </Button>
          }
        >
          {loaded.error}
        </Alert>
      )}

      {!s && !loaded.error && <DashboardSkeleton />}

      {s && (
        <>
          <Group title="Needs your attention">
            <StatCard
              value={s.reviewQueue}
              label="Professionals waiting for review"
              link={{ to: "/admin/review", label: "Open the review queue" }}
              note={
                s.reviewQueueOver48h > 0
                  ? `${s.reviewQueueOver48h} waiting over 48 hours`
                  : undefined
              }
            />
            <StatCard
              value={s.unansweredOver48h}
              label="Requests with no reply after 48 hours"
              tone={s.unansweredOver48h > 0 ? "error" : undefined}
              link={{ to: "/admin/bookings?unansweredOver=48", label: "See these requests" }}
            />
            <StatCard value={s.registrationsExpiringIn30Days} label="Registrations expiring in the next 30 days" />
          </Group>

          <Group title="Bookings now">
            <StatCard value={s.pendingTotal} label="Pending requests" link={{ to: "/admin/bookings?status=PENDING", label: "See pending requests" }} />
            <StatCard value={s.awaitingUserTotal} label="Waiting for the person" />
            <StatCard
              value={s.upcomingConfirmed}
              label="Upcoming confirmed appointments"
              link={{ to: "/admin/bookings?status=CONFIRMED", label: "See confirmed appointments" }}
            />
          </Group>

          <Group title="Professionals">
            <StatCard value={s.professionalsVerifiedActive} label="Verified and active" />
            <StatCard value={s.professionalsNeedsInfo} label="Needing more information" />
          </Group>

          <Group
            title="Last 30 days"
            footer="A low response rate means professionals are not answering. Check the responsiveness table."
            footerLink={{ to: "/admin/responsiveness", label: "Open professional responsiveness" }}
          >
            <StatCard value={s.last30Days.requestsCreated} label="Requests created" />
            <StatCard value={s.last30Days.answered} label="Answered" />
            <StatCard value={s.last30Days.expired} label="Expired" />
            <StatCard value={s.last30Days.cancelledByUser} label="Cancelled by the person" />
            <StatCard
              value={`${s.last30Days.responseRatePercent.toLocaleString("en-IN", { maximumFractionDigits: 1 })}%`}
              label="Response rate"
            />
            <StatCard
              value={medianHoursText(s.last30Days.medianHoursToFirstResponse)}
              label="Median time to first reply"
              small={s.last30Days.medianHoursToFirstResponse === null}
            />
          </Group>
        </>
      )}
    </Stack>
  );
}

function Group({
  title,
  children,
  footer,
  footerLink,
}: {
  title: string;
  children: ReactNode;
  footer?: string;
  footerLink?: { to: string; label: string };
}) {
  return (
    <Box component="section">
      <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 1.5 }}>
        {title}
      </Typography>
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)", md: "repeat(3, 1fr)" } }}>
        {children}
      </Box>
      {footer && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
          {footer}{" "}
          {footerLink && (
            <Link component={RouterLink} to={footerLink.to}>
              {footerLink.label}
            </Link>
          )}
        </Typography>
      )}
    </Box>
  );
}

function StatCard({
  value,
  label,
  link,
  note,
  tone,
  small,
}: {
  value: number | string;
  label: string;
  link?: { to: string; label: string };
  note?: string;
  tone?: "error";
  small?: boolean;
}) {
  return (
    <Paper
      sx={{
        p: 2.5,
        display: "flex",
        flexDirection: "column",
        gap: 0.5,
        borderLeft: 4,
        borderColor: tone === "error" ? "error.main" : "transparent",
      }}
    >
      <Typography
        component="p"
        variant={small ? "h6" : "h4"}
        sx={{ fontWeight: 700, color: tone === "error" ? "error.main" : "text.primary" }}
      >
        {value}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      {note && (
        <Typography variant="body2" sx={{ color: "warning.dark", fontWeight: 600 }}>
          {note}
        </Typography>
      )}
      {link && (
        <Link component={RouterLink} to={link.to} variant="body2" sx={{ mt: 0.5 }}>
          {link.label}
        </Link>
      )}
    </Paper>
  );
}

function DashboardSkeleton() {
  return (
    <Stack spacing={4} aria-busy="true" aria-label="Loading the dashboard">
      {[3, 3, 2, 6].map((count, group) => (
        <Box key={group}>
          <Skeleton variant="text" width={200} height={32} />
          <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)", md: "repeat(3, 1fr)" } }}>
            {Array.from({ length: count }, (_, i) => (
              <Skeleton key={i} variant="rounded" height={110} />
            ))}
          </Box>
        </Box>
      ))}
    </Stack>
  );
}
