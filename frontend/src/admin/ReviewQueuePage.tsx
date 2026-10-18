import { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import LinearProgress from "@mui/material/LinearProgress";
import Paper from "@mui/material/Paper";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import RateReviewRounded from "@mui/icons-material/RateReviewRounded";
import { AdminApiError, getReviewQueue } from "./admin-api";
import { formatDate, formatDateTime, hoursSince, isPastDate, waitingTime } from "./format";
import { CREDENTIAL_TYPE_LABELS, type PageResponse, type ReviewQueueItem } from "./types";

const PAGE_SIZES = [10, 20, 50];
const DEFAULT_PAGE_SIZE = 20;
const LONG_WAIT_HOURS = 48;

type Notice = { severity: "success" | "error"; message: string };
type LoadedPage = {
  key: string | null;
  data: PageResponse<ReviewQueueItem> | null;
  error: string | null;
  // When the page was loaded, so waiting times are measured from one moment.
  loadedAt: number;
};

// Paging lives in the URL, so it survives a visit to a review page and a reload.
function usePageParams() {
  const [params, setParams] = useSearchParams();
  const sizeParam = Number(params.get("size"));
  const pageParam = Number(params.get("page"));
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0;
  const size = PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE;

  const update = useCallback(
    (changes: { page?: number; size?: number }) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          // A new page size starts again from the first page.
          const nextPage = changes.size !== undefined ? 0 : (changes.page ?? page);
          if (nextPage > 0) next.set("page", String(nextPage));
          else next.delete("page");
          if (changes.size !== undefined) {
            if (changes.size === DEFAULT_PAGE_SIZE) next.delete("size");
            else next.set("size", String(changes.size));
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams, page],
  );

  return { page, size, update };
}

// Professionals whose profile is SUBMITTED, oldest submission first (the backend sorts the queue).
export function ReviewQueuePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { page, size, update } = usePageParams();
  const [reloadCount, setReloadCount] = useState(0);
  const [loaded, setLoaded] = useState<LoadedPage>({ key: null, data: null, error: null, loadedAt: 0 });
  // A decision on the review page comes back here with its message in the navigation state.
  const [notice, setNotice] = useState<Notice | null>(
    (location.state as { notice?: Notice } | null)?.notice ?? null,
  );

  // Drop the message from the navigation state so a reload does not show it again.
  useEffect(() => {
    if ((location.state as { notice?: Notice } | null)?.notice) {
      navigate(location.pathname + location.search, { replace: true, state: null });
    }
  }, [location.state, location.pathname, location.search, navigate]);

  const queryKey = JSON.stringify([page, size, reloadCount]);
  const loading = loaded.key !== queryKey;
  const data = loaded.data;
  const loadError = loading ? null : loaded.error;

  useEffect(() => {
    let cancelled = false;
    getReviewQueue(page, size)
      .then((result) => {
        if (!cancelled) setLoaded({ key: queryKey, data: result, error: null, loadedAt: Date.now() });
      })
      .catch((e) => {
        // A 401 has already sent the admin to the login page with the session-expired message.
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        const message = e instanceof Error ? e.message : "The review queue could not be loaded. Please try again.";
        setLoaded((current) => ({ ...current, key: queryKey, error: message }));
      });
    return () => {
      cancelled = true;
    };
  }, [queryKey, page, size]);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Review queue
        </Typography>
        <Typography color="text.secondary">
          Professionals who have submitted their profile for verification, with the oldest submission first.
        </Typography>
      </Box>

      {loadError && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => setReloadCount((n) => n + 1)}>
              Retry
            </Button>
          }
        >
          {loadError}
        </Alert>
      )}

      <Paper sx={{ overflow: "hidden" }}>
        {loading && <LinearProgress aria-label="Loading the review queue" />}
        <TableContainer>
          <Table size="small" sx={{ minWidth: 1000 }}>
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>City</TableCell>
                <TableCell>Submitted</TableCell>
                <TableCell>Waiting</TableCell>
                <TableCell align="right">Documents</TableCell>
                <TableCell>Registration</TableCell>
                <TableCell>Registration expiry</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data?.items.map((row) => {
                const waited = hoursSince(row.submittedAt, loaded.loadedAt);
                return (
                  <TableRow key={row.id} hover>
                    <TableCell sx={{ fontWeight: 600 }}>{row.fullName}</TableCell>
                    <TableCell>{CREDENTIAL_TYPE_LABELS[row.credentialType] ?? row.credentialType}</TableCell>
                    <TableCell>{row.city ?? <Muted>Not set</Muted>}</TableCell>
                    <TableCell>{formatDateTime(row.submittedAt) ?? <Muted>Not recorded</Muted>}</TableCell>
                    <TableCell>
                      <Stack spacing={0.5} sx={{ alignItems: "flex-start" }}>
                        <Typography variant="body2">
                          {waitingTime(row.submittedAt, loaded.loadedAt) ?? <Muted>Unknown</Muted>}
                        </Typography>
                        {waited !== null && waited > LONG_WAIT_HOURS && (
                          <Chip size="small" color="warning" label="Waiting over 48 hours" />
                        )}
                      </Stack>
                    </TableCell>
                    <TableCell align="right">{row.documentCount}</TableCell>
                    <TableCell>
                      {row.registrationBody || row.registrationNumber ? (
                        <Typography variant="body2">
                          {row.registrationBody ?? "No body"}
                          {row.registrationNumber ? `, ${row.registrationNumber}` : ""}
                        </Typography>
                      ) : (
                        <Muted>Not provided</Muted>
                      )}
                    </TableCell>
                    <TableCell>
                      {row.registrationExpiry ? (
                        <Typography
                          variant="body2"
                          color={isPastDate(row.registrationExpiry) ? "error" : "text.primary"}
                        >
                          {formatDate(row.registrationExpiry)}
                          {isPastDate(row.registrationExpiry) ? " (expired)" : ""}
                        </Typography>
                      ) : (
                        <Muted>Not set</Muted>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        variant="contained"
                        startIcon={<RateReviewRounded />}
                        onClick={() => navigate(`/admin/review/${row.id}`)}
                        aria-label={`Review ${row.fullName}`}
                      >
                        Review
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">No professionals are waiting for review.</Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination
          component="div"
          count={data?.totalElements ?? 0}
          page={data && page >= Math.max(1, data.totalPages) ? 0 : page}
          rowsPerPage={size}
          rowsPerPageOptions={PAGE_SIZES}
          onPageChange={(_, nextPage) => update({ page: nextPage })}
          onRowsPerPageChange={(e) => update({ size: Number(e.target.value) })}
          labelRowsPerPage="Rows per page"
        />
      </Paper>

      <Snackbar
        open={notice !== null}
        autoHideDuration={notice?.severity === "error" ? 8000 : 4000}
        onClose={(_, reason) => reason !== "clickaway" && setNotice(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        {notice ? (
          <Alert severity={notice.severity} variant="filled" onClose={() => setNotice(null)} sx={{ width: "100%" }}>
            {notice.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Stack>
  );
}

function Muted({ children }: { children: string }) {
  return (
    <Typography variant="body2" color="text.secondary" component="span">
      {children}
    </Typography>
  );
}
