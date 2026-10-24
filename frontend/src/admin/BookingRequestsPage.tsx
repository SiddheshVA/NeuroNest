import { useCallback, useEffect, useState } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
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
import TextField from "@mui/material/TextField";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import StickyNote2Outlined from "@mui/icons-material/StickyNote2Outlined";
import { AdminApiError, closeBooking, listBookings, nudgeBooking } from "./admin-api";
import { BookingStatusChip } from "./BookingStatusChip";
import { displayStatus, formatIstDateTime, hoursAsText, isUnpaidExpiry } from "./format";
import { NoteActionDialog } from "./NoteActionDialog";
import {
  BOOKING_STATUS_LABELS,
  BOOKING_STATUSES,
  RESPONDED_BY_LABELS,
  type AdminBookingItem,
  type BookingStatus,
  type PageResponse,
} from "./types";

const PAGE_SIZES = [10, 20, 50];
const DEFAULT_PAGE_SIZE = 20;
const WAITING_OPTIONS = [24, 48, 72];
const MIN_CLOSE_NOTE = 10;
const MAX_CLOSE_NOTE = 500;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Notice = { severity: "success" | "error"; message: string };
type Loaded = {
  key: string | null;
  data: PageResponse<AdminBookingItem> | null;
  error: string | null;
  /** When the page was loaded: the "expired" display rule is checked against this. */
  loadedAt: number;
};
type Action = { kind: "nudge" | "close"; row: AdminBookingItem };

// Filters and paging live in the URL, so links from the dashboard and the responsiveness table open
// the right view, and a reload keeps it.
function useBookingParams() {
  const [params, setParams] = useSearchParams();
  const statusParam = params.get("status") ?? "";
  const waitingParam = Number(params.get("unansweredOver"));
  const professionalParam = params.get("professionalId") ?? "";
  const pageParam = Number(params.get("page"));
  const sizeParam = Number(params.get("size"));

  const values = {
    status: (BOOKING_STATUSES as readonly string[]).includes(statusParam) ? (statusParam as BookingStatus) : "",
    unansweredOver: WAITING_OPTIONS.includes(waitingParam) ? waitingParam : null,
    professionalId: UUID_PATTERN.test(professionalParam) ? professionalParam : "",
    page: Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0,
    size: PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE,
  } as const;

  // Changing a filter returns to the first page; changing the page keeps the filters.
  const update = useCallback(
    (changes: Partial<Record<"status" | "unansweredOver" | "professionalId" | "page" | "size", string>>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          if (!("page" in changes)) next.delete("page");
          for (const [key, value] of Object.entries(changes)) {
            if (value) next.set(key, value);
            else next.delete(key);
          }
          if (next.get("page") === "0") next.delete("page");
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  return { values, update };
}

/** /admin/bookings: every booking request, without any of the person's private details. */
export function BookingRequestsPage() {
  const { values, update } = useBookingParams();
  const { status, unansweredOver, professionalId, page, size } = values;
  const [reloadCount, setReloadCount] = useState(0);
  const [loaded, setLoaded] = useState<Loaded>({ key: null, data: null, error: null, loadedAt: 0 });
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  // The professional's name for the filter chip, taken from the rows once they arrive.
  const [professionalName, setProfessionalName] = useState<string | null>(null);

  const queryKey = JSON.stringify([status, unansweredOver, professionalId, page, size, reloadCount]);
  const loading = loaded.key !== queryKey;
  const data = loaded.data;
  const loadError = loading ? null : loaded.error;

  useEffect(() => {
    let cancelled = false;
    listBookings({ status, unansweredOver, professionalId, page, size })
      .then((result) => {
        if (cancelled) return;
        setLoaded({ key: queryKey, data: result, error: null, loadedAt: Date.now() });
        if (professionalId && result.items.length > 0) setProfessionalName(result.items[0].professionalName);
      })
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        const message = e instanceof Error ? e.message : "The booking requests could not be loaded. Please try again.";
        setLoaded((current) => ({ ...current, key: queryKey, error: message }));
      });
    return () => {
      cancelled = true;
    };
  }, [queryKey, status, unansweredOver, professionalId, page, size]);

  const reload = () => setReloadCount((n) => n + 1);

  const openAction = (kind: Action["kind"], row: AdminBookingItem) => {
    setDialogError(null);
    setAction({ kind, row });
  };

  const run = async (current: Action, note?: string) => {
    setBusy(true);
    setDialogError(null);
    try {
      if (current.kind === "nudge") {
        await nudgeBooking(current.row.id);
        setNotice({ severity: "success", message: "Reminder sent." });
      } else {
        await closeBooking(current.row.id, note ?? "");
        setNotice({ severity: "success", message: "Request closed." });
      }
      setAction(null);
      reload();
    } catch (e) {
      // A 401 has already sent the admin to the login page with the session-expired message.
      if (e instanceof AdminApiError && e.status === 401) return;
      if (e instanceof AdminApiError && e.status === 400) {
        setDialogError(e.message);
        return;
      }
      setAction(null);
      setNotice({
        severity: "error",
        message: e instanceof Error ? e.message : "The action could not be completed. Please try again.",
      });
      // A 409 means the row changed meanwhile: show it as it is now.
      if (e instanceof AdminApiError && e.status === 409) reload();
    } finally {
      setBusy(false);
    }
  };

  const filtersActive = Boolean(status || unansweredOver || professionalId);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Booking requests
        </Typography>
        <Typography color="text.secondary">
          All times are in Indian Standard Time. The person&apos;s contact details, notes and meeting details are never
          shown here.
        </Typography>
      </Box>

      <Paper sx={{ p: 2 }}>
        <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ alignItems: { sm: "center" }, flexWrap: "wrap" }}>
          <TextField
            select
            label="Status"
            value={status}
            onChange={(e) => update({ status: e.target.value })}
            sx={{ minWidth: 220 }}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
          >
            <MenuItem value="">All</MenuItem>
            {BOOKING_STATUSES.map((value) => (
              <MenuItem key={value} value={value}>
                {BOOKING_STATUS_LABELS[value]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Waiting over"
            value={unansweredOver === null ? "" : String(unansweredOver)}
            onChange={(e) => update({ unansweredOver: e.target.value })}
            sx={{ minWidth: 180 }}
            helperText={unansweredOver !== null ? "Only pending requests that have not expired." : undefined}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
          >
            <MenuItem value="">Any</MenuItem>
            {WAITING_OPTIONS.map((hours) => (
              <MenuItem key={hours} value={String(hours)}>
                {hours} hours
              </MenuItem>
            ))}
          </TextField>
          {professionalId && (
            <Chip
              label={`Professional: ${professionalName ?? "the selected professional"}`}
              onDelete={() => {
                setProfessionalName(null);
                update({ professionalId: "" });
              }}
            />
          )}
        </Stack>
      </Paper>

      {loadError && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={reload}>
              Retry
            </Button>
          }
        >
          {loadError}
        </Alert>
      )}

      <Paper>
        {loading && <LinearProgress aria-label="Loading the booking requests" />}
        <TableContainer>
          <Table size="small" aria-label="Booking requests">
            <TableHead>
              <TableRow>
                <TableCell>Person</TableCell>
                <TableCell>Professional</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Requested time</TableCell>
                <TableCell>Waiting time</TableCell>
                <TableCell>Expires</TableCell>
                <TableCell>Responded</TableCell>
                <TableCell>Last reminder sent</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(data?.items ?? []).map((row) => {
                const shown = displayStatus(row, loaded.loadedAt);
                const canNudge = shown === "PENDING";
                const canClose = shown === "PENDING" || shown === "ALTERNATE_PROPOSED";
                return (
                  <TableRow key={row.id} hover>
                    <TableCell sx={{ fontWeight: 600 }}>{row.contactName}</TableCell>
                    <TableCell>
                      <Link component={RouterLink} to={`/admin/professionals/${row.professionalId}/edit`}>
                        {row.professionalName}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
                        <BookingStatusChip status={shown} />
                        {isUnpaidExpiry(row, shown) && (
                          <Typography variant="body2" color="text.secondary">
                            Expired: not paid
                          </Typography>
                        )}
                        {row.adminNote && (
                          <Tooltip title={`Admin note: ${row.adminNote}`}>
                            <IconButton size="small" aria-label={`Admin note: ${row.adminNote}`}>
                              <StickyNote2Outlined fontSize="small" />
                            </IconButton>
                          </Tooltip>
                        )}
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{formatIstDateTime(row.scheduledOrRequestedTime) ?? <Muted>Not set</Muted>}</Typography>
                      {row.status === "ALTERNATE_PROPOSED" && row.proposedDatetime && (
                        <Typography variant="body2" color="text.secondary">
                          Suggested: {formatIstDateTime(row.proposedDatetime)}
                        </Typography>
                      )}
                      {shown === "AWAITING_PAYMENT" && row.paymentDueAt && (
                        <Typography variant="body2" color="text.secondary">
                          Pay by: {formatIstDateTime(row.paymentDueAt)}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>{hoursAsText(row.hoursWaiting)}</TableCell>
                    <TableCell>{formatIstDateTime(row.expiresAt) ?? <Muted>Not set</Muted>}</TableCell>
                    <TableCell>
                      {row.respondedBy ? (
                        <>
                          <Typography variant="body2">{RESPONDED_BY_LABELS[row.respondedBy] ?? row.respondedBy}</Typography>
                          <Typography variant="body2" color="text.secondary">
                            {formatIstDateTime(row.respondedAt)}
                          </Typography>
                        </>
                      ) : (
                        <Muted>No response yet</Muted>
                      )}
                    </TableCell>
                    <TableCell>{formatIstDateTime(row.adminNudgedAt) ?? <Muted>Never</Muted>}</TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                        {canNudge && (
                          <Button size="small" variant="outlined" disabled={busy} onClick={() => openAction("nudge", row)}>
                            Send reminder
                          </Button>
                        )}
                        {canClose && (
                          <Button
                            size="small"
                            variant="outlined"
                            color="error"
                            disabled={busy}
                            onClick={() => openAction("close", row)}
                          >
                            Close request
                          </Button>
                        )}
                      </Stack>
                    </TableCell>
                  </TableRow>
                );
              })}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={9} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">
                      {filtersActive ? "No booking requests match these filters." : "There are no booking requests yet."}
                    </Typography>
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
          onPageChange={(_, nextPage) => update({ page: String(nextPage) })}
          onRowsPerPageChange={(e) => update({ size: e.target.value })}
          labelRowsPerPage="Rows per page"
        />
      </Paper>

      {action?.kind === "nudge" && (
        <Dialog open onClose={() => !busy && setAction(null)} fullWidth maxWidth="sm">
          <DialogTitle>Send a reminder to {action.row.professionalName}?</DialogTitle>
          <DialogContent>
            <Typography variant="body2">
              This sends a reminder email to the professional. You can remind each professional once every 12 hours.
            </Typography>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setAction(null)} disabled={busy}>
              Cancel
            </Button>
            <Button
              variant="contained"
              onClick={() => void run(action)}
              disabled={busy}
              startIcon={busy ? <CircularProgress size={16} color="inherit" aria-hidden="true" /> : undefined}
            >
              {busy ? "Sending" : "Send reminder"}
            </Button>
          </DialogActions>
        </Dialog>
      )}

      {action?.kind === "close" && (
        <NoteActionDialog
          title={`Close the request from ${action.row.contactName}`}
          explanation="The person will be told the request expired and will be shown other professionals and support information. The professional will be told you closed it."
          noteLabel="Note for your records"
          helper="This note is kept for your records only. It is not shown to anyone else."
          placeholder="The professional is on leave until next month."
          min={MIN_CLOSE_NOTE}
          max={MAX_CLOSE_NOTE}
          confirmQuestion="Close this request?"
          confirmLabel="Close request"
          danger
          busy={busy}
          error={dialogError}
          onClose={() => setAction(null)}
          onSubmit={(note) => void run(action, note)}
        />
      )}

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
