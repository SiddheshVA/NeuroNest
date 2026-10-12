import { useCallback, useEffect, useState } from "react";
import { Link as RouterLink, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import LinearProgress from "@mui/material/LinearProgress";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import EditRounded from "@mui/icons-material/EditRounded";
import { AdminApiError, listProfessionals, setProfessionalActive } from "./admin-api";
import { formatDate, isPastDate } from "./format";
import { SampleChip, VerificationStatusChip } from "./VerificationStatusChip";
import {
  CREDENTIAL_TYPE_LABELS,
  CREDENTIAL_TYPES,
  VERIFICATION_STATUS_LABELS,
  VERIFICATION_STATUSES,
  type CredentialType,
  type PageResponse,
  type ProfessionalSummary,
  type VerificationStatus,
} from "./types";

const PAGE_SIZES = [10, 20, 50];
const DEFAULT_PAGE_SIZE = 20;

type Notice = { severity: "success" | "error"; message: string };
type LoadedPage = { key: string | null; data: PageResponse<ProfessionalSummary> | null; error: string | null };

// Filters and paging live in the URL, so they survive a visit to the edit page and a reload.
function useListParams() {
  const [params, setParams] = useSearchParams();
  const sizeParam = Number(params.get("size"));
  const pageParam = Number(params.get("page"));
  const typeParam = params.get("type") ?? "";
  const statusParam = params.get("status") ?? "";
  const activeParam = params.get("active") ?? "";

  const values = {
    q: params.get("q") ?? "",
    credentialType: (CREDENTIAL_TYPES as readonly string[]).includes(typeParam) ? (typeParam as CredentialType) : "",
    verificationStatus: (VERIFICATION_STATUSES as readonly string[]).includes(statusParam)
      ? (statusParam as VerificationStatus)
      : "",
    active: activeParam === "true" || activeParam === "false" ? activeParam : "",
    page: Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0,
    size: PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE,
  } as const;

  // Changing any filter returns to the first page; changing the page keeps the filters.
  const update = useCallback(
    (changes: Partial<Record<"q" | "type" | "status" | "active" | "page" | "size", string>>) => {
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

export function ProfessionalsListPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { values, update } = useListParams();
  const { q, credentialType, verificationStatus, active, page, size } = values;

  const [searchText, setSearchText] = useState(q);
  const [reloadCount, setReloadCount] = useState(0);
  // The last response, tagged with the query it answers; the table keeps showing it while the next
  // query loads.
  const [loaded, setLoaded] = useState<LoadedPage>({ key: null, data: null, error: null });
  const [pendingIds, setPendingIds] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(
    (location.state as { notice?: Notice } | null)?.notice ?? null,
  );

  // Search waits for a short pause in typing before it updates the URL and reloads.
  useEffect(() => {
    if (searchText.trim() === q.trim()) return;
    const timer = window.setTimeout(() => update({ q: searchText.trim() }), 350);
    return () => window.clearTimeout(timer);
  }, [searchText, q, update]);

  const queryKey = JSON.stringify([q, credentialType, verificationStatus, active, page, size, reloadCount]);
  const loading = loaded.key !== queryKey;
  const data = loaded.data;
  const loadError = loading ? null : loaded.error;

  useEffect(() => {
    let cancelled = false;
    listProfessionals({ q, credentialType, verificationStatus, active, page, size })
      .then((result) => {
        if (!cancelled) setLoaded({ key: queryKey, data: result, error: null });
      })
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        const message = e instanceof Error ? e.message : "The list could not be loaded. Please try again.";
        setLoaded((current) => ({ key: queryKey, data: current.data, error: message }));
      });
    return () => {
      cancelled = true;
    };
  }, [queryKey, q, credentialType, verificationStatus, active, page, size]);

  const toggleActive = async (row: ProfessionalSummary, nextActive: boolean) => {
    // Show the new position immediately; put it back if the server refuses.
    const setRowActive = (value: boolean) =>
      setLoaded((current) => ({
        ...current,
        data: current.data && {
          ...current.data,
          items: current.data.items.map((item) => (item.id === row.id ? { ...item, active: value } : item)),
        },
      }));
    setRowActive(nextActive);
    setPendingIds((ids) => new Set(ids).add(row.id));
    try {
      const updated = await setProfessionalActive(row.id, nextActive);
      setRowActive(updated.active);
      setNotice({
        severity: "success",
        message: updated.active
          ? `${row.fullName} is now active.`
          : `${row.fullName} is now inactive.`,
      });
    } catch (e) {
      setRowActive(row.active);
      if (!(e instanceof AdminApiError && e.status === 401)) {
        setNotice({ severity: "error", message: e instanceof Error ? e.message : "The change could not be saved." });
      }
    } finally {
      setPendingIds((ids) => {
        const next = new Set(ids);
        next.delete(row.id);
        return next;
      });
    }
  };

  const hasFilters = Boolean(q || credentialType || verificationStatus || active);

  return (
    <Stack spacing={3}>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ justifyContent: "space-between", alignItems: { sm: "center" } }}>
        <Box>
          <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
            Professionals
          </Typography>
          <Typography color="text.secondary">
            All listings, including inactive and unverified ones.
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddRounded />}
          component={RouterLink}
          to="/admin/professionals/new"
          sx={{ alignSelf: { xs: "flex-start", sm: "center" } }}
        >
          Add professional
        </Button>
      </Stack>

      <Paper sx={{ p: 2 }}>
        <Box
          sx={{
            display: "grid",
            gap: 2,
            gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", md: "2fr 1fr 1fr 1fr" },
          }}
        >
          <TextField
            label="Search by name or email"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            size="small"
            type="search"
          />
          <TextField
            select
            label="Type"
            value={credentialType}
            onChange={(e) => update({ type: e.target.value })}
            size="small"
          >
            <MenuItem value="">All types</MenuItem>
            {CREDENTIAL_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {CREDENTIAL_TYPE_LABELS[type]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Verification status"
            value={verificationStatus}
            onChange={(e) => update({ status: e.target.value })}
            size="small"
          >
            <MenuItem value="">All statuses</MenuItem>
            {VERIFICATION_STATUSES.map((status) => (
              <MenuItem key={status} value={status}>
                {VERIFICATION_STATUS_LABELS[status]}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Active"
            value={active}
            onChange={(e) => update({ active: e.target.value })}
            size="small"
          >
            <MenuItem value="">Active and inactive</MenuItem>
            <MenuItem value="true">Active only</MenuItem>
            <MenuItem value="false">Inactive only</MenuItem>
          </TextField>
        </Box>
      </Paper>

      {loadError && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => setReloadCount((n) => n + 1)}>
              Try again
            </Button>
          }
        >
          {loadError}
        </Alert>
      )}

      <Paper sx={{ overflow: "hidden" }}>
        {loading && <LinearProgress aria-label="Loading professionals" />}
        <TableContainer>
          <Table size="small" sx={{ minWidth: 900 }}>
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>City</TableCell>
                <TableCell>Verification status</TableCell>
                <TableCell>Active</TableCell>
                <TableCell>Sample</TableCell>
                <TableCell>Registration expiry</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data?.items.map((row) => (
                <TableRow key={row.id} hover>
                  <TableCell sx={{ fontWeight: 600 }}>{row.fullName}</TableCell>
                  <TableCell>{CREDENTIAL_TYPE_LABELS[row.credentialType] ?? row.credentialType}</TableCell>
                  <TableCell>{row.city ?? <Muted>Not set</Muted>}</TableCell>
                  <TableCell>
                    <VerificationStatusChip status={row.verificationStatus} />
                  </TableCell>
                  <TableCell>
                    <Switch
                      checked={row.active}
                      disabled={pendingIds.has(row.id)}
                      onChange={(e) => void toggleActive(row, e.target.checked)}
                      slotProps={{ input: { "aria-label": `Active status for ${row.fullName}` } }}
                    />
                  </TableCell>
                  <TableCell>{row.isSampleData ? <SampleChip /> : null}</TableCell>
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
                      variant="outlined"
                      startIcon={<EditRounded />}
                      onClick={() => navigate(`/admin/professionals/${row.id}/edit`)}
                    >
                      Edit
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">
                      {hasFilters
                        ? "No professionals match these filters."
                        : "There are no professionals yet. Use the Add professional button to create one."}
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
