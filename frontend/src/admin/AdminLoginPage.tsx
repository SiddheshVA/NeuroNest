import { useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { adminLogin, AdminApiError, getValidAdminToken, SESSION_EXPIRED_MESSAGE } from "./admin-api";

export function AdminLoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const from = safeAdminPath((location.state as { from?: string } | null)?.from);
  const expired = searchParams.get("expired") === "1";

  if (getValidAdminToken()) {
    return <Navigate to={from} replace />;
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError("Please enter both the username and the password.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await adminLogin(username.trim(), password);
      navigate(from, { replace: true });
    } catch (e) {
      setError(e instanceof AdminApiError ? e.message : "Login failed. Please try again.");
      setPassword("");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Box
      sx={{
        minHeight: "100vh",
        display: "grid",
        placeItems: "center",
        bgcolor: "background.default",
        color: "text.primary",
        px: 2,
      }}
    >
      <Paper component="form" onSubmit={submit} noValidate sx={{ width: "100%", maxWidth: 400, p: { xs: 3, sm: 4 } }}>
        <Stack spacing={2.5}>
          <Box>
            <Typography variant="h5" component="h1" sx={{ fontWeight: 700 }}>
              NeuroNest Admin
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Sign in with the admin account to manage professional listings.
            </Typography>
          </Box>

          {expired && !error && <Alert severity="info">{SESSION_EXPIRED_MESSAGE}</Alert>}
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            required
            fullWidth
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
            fullWidth
          />
          <Button type="submit" variant="contained" size="large" disabled={submitting} fullWidth>
            {submitting ? "Signing in" : "Sign in"}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

// Only return to admin pages after login, never to an arbitrary path from navigation state.
function safeAdminPath(path: string | undefined): string {
  return path && path.startsWith("/admin/") && !path.startsWith("/admin/login") ? path : "/admin";
}
