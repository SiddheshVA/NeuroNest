import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import AppBar from "@mui/material/AppBar";
import Badge from "@mui/material/Badge";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import LogoutRounded from "@mui/icons-material/LogoutRounded";
import {
  AdminApiError,
  adminSessionRemainingMs,
  clearAdminSession,
  EXPIRED_LOGIN_PATH,
  getDashboardSummary,
  getReviewQueue,
  setAdminUnauthorizedHandler,
} from "./admin-api";
import { AdminBackLink } from "./AdminBackLink";

// Admin chrome: a top bar with the title, the navigation (with the number of professionals waiting for
// review and of requests with no reply after 48 hours) and a log out button. Also sends the admin back to the
// login page on any 401 from the API, and when the token's expiry time is reached.
export function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  useEffect(() => {
    const goToExpiredLogin = () => {
      clearAdminSession();
      navigate(EXPIRED_LOGIN_PATH, { replace: true, state: { from: location.pathname + location.search } });
    };
    setAdminUnauthorizedHandler(goToExpiredLogin);

    const remaining = adminSessionRemainingMs();
    // setTimeout's maximum delay is about 24.8 days; admin tokens last 2 hours.
    const timer = remaining === null ? undefined : window.setTimeout(goToExpiredLogin, Math.max(0, remaining));

    return () => {
      setAdminUnauthorizedHandler(null);
      window.clearTimeout(timer);
    };
  }, [navigate, location.pathname, location.search]);

  // The waiting count is refreshed on every page change, so it is current after each decision.
  const [waitingCount, setWaitingCount] = useState<number | null>(null);
  useEffect(() => {
    let cancelled = false;
    getReviewQueue(0, 1)
      .then((page) => !cancelled && setWaitingCount(page.totalElements))
      .catch((e) => {
        // A 401 is already handled by the API helper; any other failure just hides the badge.
        if (!cancelled && !(e instanceof AdminApiError && e.status === 401)) setWaitingCount(null);
      });
    return () => {
      cancelled = true;
    };
  }, [location.pathname]);

  // Requests with no reply after 48 hours, refreshed the same way.
  const [unansweredCount, setUnansweredCount] = useState<number | null>(null);
  useEffect(() => {
    let cancelled = false;
    getDashboardSummary()
      .then((summary) => !cancelled && setUnansweredCount(summary.unansweredOver48h))
      .catch((e) => {
        if (!cancelled && !(e instanceof AdminApiError && e.status === 401)) setUnansweredCount(null);
      });
    return () => {
      cancelled = true;
    };
  }, [location.pathname]);

  const logOut = () => {
    clearAdminSession();
    navigate("/admin/login", { replace: true });
  };

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default", color: "text.primary" }}>
      <AppBar position="sticky" color="primary">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="span" sx={{ fontWeight: 700, mr: 2 }}>
            NeuroNest Admin
          </Typography>
          <Box component="nav" aria-label="Admin" sx={{ display: "flex", gap: 1, flexGrow: 1, flexWrap: "wrap" }}>
            {/* "end": only the dashboard itself, not every /admin page, marks this entry as current. */}
            <Button color="inherit" component={NavLink} to="/admin" end sx={navLinkSx}>
              Dashboard
            </Button>
            <Button color="inherit" component={NavLink} to="/admin/professionals" sx={navLinkSx}>
              Professionals
            </Button>
            <Button
              color="inherit"
              component={NavLink}
              to="/admin/review"
              // Extra room on the right so the count badge does not overlap the button edge.
              sx={{ ...navLinkSx, pr: waitingCount ? 3.5 : 1.5 }}
              aria-label={
                waitingCount === null
                  ? "Review queue"
                  : `Review queue, ${waitingCount} ${waitingCount === 1 ? "professional" : "professionals"} waiting`
              }
            >
              <Badge
                color="secondary"
                badgeContent={waitingCount ?? 0}
                max={99}
                invisible={!waitingCount}
                sx={{ "& .MuiBadge-badge": { right: -12 } }}
              >
                Review queue
              </Badge>
            </Button>
            <Button
              color="inherit"
              component={NavLink}
              to="/admin/bookings"
              sx={{ ...navLinkSx, pr: unansweredCount ? 3.5 : 1.5 }}
              aria-label={
                unansweredCount
                  ? `Booking requests, ${unansweredCount} with no reply after 48 hours`
                  : "Booking requests"
              }
            >
              <Badge
                color="warning"
                badgeContent={unansweredCount ?? 0}
                max={99}
                invisible={!unansweredCount}
                sx={{ "& .MuiBadge-badge": { right: -12 } }}
              >
                Booking requests
              </Badge>
            </Button>
            <Button color="inherit" component={NavLink} to="/admin/responsiveness" sx={navLinkSx}>
              Professional responsiveness
            </Button>
          </Box>
          <Button color="inherit" variant="outlined" startIcon={<LogoutRounded />} onClick={logOut}>
            Log out
          </Button>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ maxWidth: 1200, mx: "auto", px: { xs: 2, md: 3 }, py: { xs: 3, md: 4 } }}>
        <AdminBackLink />
        <Outlet />
      </Box>
    </Box>
  );
}

// The current section is underlined (NavLink adds the "active" class).
const navLinkSx = {
  borderRadius: 2,
  px: 1.5,
  "&.active": { textDecoration: "underline", textUnderlineOffset: "6px", textDecorationThickness: "2px" },
} as const;
