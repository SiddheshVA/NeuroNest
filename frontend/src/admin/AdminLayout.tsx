import { useEffect } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import LogoutRounded from "@mui/icons-material/LogoutRounded";
import {
  adminSessionRemainingMs,
  clearAdminSession,
  EXPIRED_LOGIN_PATH,
  setAdminUnauthorizedHandler,
} from "./admin-api";

// Admin chrome: a top bar with the title and a log out button. Also sends the admin back to the
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

  const logOut = () => {
    clearAdminSession();
    navigate("/admin/login", { replace: true });
  };

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default", color: "text.primary" }}>
      <AppBar position="sticky" color="primary">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="span" sx={{ flexGrow: 1, fontWeight: 700 }}>
            NeuroNest Admin
          </Typography>
          <Button color="inherit" variant="outlined" startIcon={<LogoutRounded />} onClick={logOut}>
            Log out
          </Button>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ maxWidth: 1200, mx: "auto", px: { xs: 2, md: 3 }, py: { xs: 3, md: 4 } }}>
        <Outlet />
      </Box>
    </Box>
  );
}
