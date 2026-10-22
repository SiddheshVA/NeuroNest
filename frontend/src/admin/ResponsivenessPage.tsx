import { useCallback, useEffect, useState } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { AdminApiError, getResponsiveness } from "./admin-api";
import { formatIstDate, medianHoursText } from "./format";
import type { PageResponse, ResponsivenessItem } from "./types";

const PERIODS = [30, 60, 90];
const PAGE_SIZES = [10, 20, 50];
const DEFAULT_PAGE_SIZE = 20;

type Loaded = { key: string | null; data: PageResponse<ResponsivenessItem> | null; error: string | null };

/** /admin/responsiveness: per professional, how they answer requests. The server sorts worst first. */
export function ResponsivenessPage() {
  const [params, setParams] = useSearchParams();
  const daysParam = Number(params.get("days"));
  const pageParam = Number(params.get("page"));
  const sizeParam = Number(params.get("size"));
  const days = PERIODS.includes(daysParam) ? daysParam : 30;
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0;
  const size = PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE;

  const update = useCallback(
    (changes: Partial<Record<"days" | "page" | "size", string>>) => {
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

  const [reloadCount, setReloadCount] = useState(0);
  const [loaded, setLoaded] = useState<Loaded>({ key: null, data: null, error: null });
  const queryKey = JSON.stringify([days, page, size, reloadCount]);
  const loading = loaded.key !== queryKey;
  const data = loaded.data;
  const loadError = loading ? null : loaded.error;

  useEffect(() => {
    let cancelled = false;
    getResponsiveness(days, page, size)
      .then((result) => !cancelled && setLoaded({ key: queryKey, data: result, error: null }))
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        const message = e instanceof Error ? e.message : "The table could not be loaded. Please try again.";
        setLoaded((current) => ({ ...current, key: queryKey, error: message }));
      });
    return () => {
      cancelled = true;
    };
  }, [queryKey, days, page, size]);

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Professional responsiveness
        </Typography>
        <Typography color="text.secondary">
          Professionals who do not answer leave people waiting. Use this table to decide whom to remind or review.
        </Typography>
      </Box>

      <Paper sx={{ p: 2 }}>
        <TextField
          select
          label="Period"
          value={String(days)}
          onChange={(e) => update({ days: e.target.value === "30" ? "" : e.target.value })}
          sx={{ minWidth: 200 }}
          slotProps={{ inputLabel: { shrink: true } }}
        >
          {PERIODS.map((period) => (
            <MenuItem key={period} value={String(period)}>
              Last {period} days
            </MenuItem>
          ))}
        </TextField>
      </Paper>

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

      <Paper>
        {loading && <LinearProgress aria-label="Loading the responsiveness table" />}
        <TableContainer>
          <Table size="small" aria-label="Professional responsiveness">
            <TableHead>
              <TableRow>
                <TableCell>Professional</TableCell>
                <TableCell align="right">Requests received</TableCell>
                <TableCell align="right">Answered</TableCell>
                <TableCell align="right">Expired</TableCell>
                <TableCell align="right">Waiting now</TableCell>
                <TableCell>Median time to first reply</TableCell>
                <TableCell>Last answered</TableCell>
                <TableCell align="right">Requests</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data?.items.map((row) => (
                <TableRow key={row.professionalId} hover>
                  <TableCell sx={{ fontWeight: 600 }}>
                    <Link component={RouterLink} to={`/admin/professionals/${row.professionalId}/edit`}>
                      {row.fullName}
                    </Link>
                  </TableCell>
                  <TableCell align="right">{row.received}</TableCell>
                  <TableCell align="right">{row.answered}</TableCell>
                  <TableCell align="right">{row.expired}</TableCell>
                  <TableCell align="right">{row.pendingNow}</TableCell>
                  <TableCell>{medianHoursText(row.medianHoursToFirstResponse)}</TableCell>
                  <TableCell>{formatIstDate(row.lastAnsweredAt) ?? "Never"}</TableCell>
                  <TableCell align="right">
                    <Link component={RouterLink} to={`/admin/bookings?professionalId=${encodeURIComponent(row.professionalId)}`}>
                      View requests
                    </Link>
                  </TableCell>
                </TableRow>
              ))}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={8} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">No requests were received in this period.</Typography>
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
    </Stack>
  );
}
