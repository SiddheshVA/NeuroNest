import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link as RouterLink, useSearchParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import LinearProgress from "@mui/material/LinearProgress";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Tab from "@mui/material/Tab";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableContainer from "@mui/material/TableContainer";
import TableHead from "@mui/material/TableHead";
import TablePagination from "@mui/material/TablePagination";
import TableRow from "@mui/material/TableRow";
import Tabs from "@mui/material/Tabs";
import Typography from "@mui/material/Typography";
import { alpha } from "@mui/material/styles";
import { formatAmount, REPORT_CURRENCY, type PaymentTotals } from "@/lib/money";
import { AdminApiError, getPaymentSummary, listPayments, listRefundsNeeded } from "./admin-api";
import { BookingStatusChip } from "./BookingStatusChip";
import { formatIstDateTime } from "./format";
import {
  BOOKING_STATUS_LABELS,
  type AdminPaymentItem,
  type AdminPaymentSummary,
  type BookingStatus,
  type PageResponse,
  type RefundNeededItem,
} from "./types";

const PAGE_SIZES = [10, 20, 50];
const DEFAULT_PAGE_SIZE = 20;

type TabValue = "payments" | "refunds";

// Amounts come from the server in paise and are only formatted here; nothing is worked out in the browser.
const money = (paise: number, currency: string = REPORT_CURRENCY) => formatAmount(paise, currency) ?? "-";

function StatusCell({ status }: { status: string }) {
  return status in BOOKING_STATUS_LABELS ? (
    <BookingStatusChip status={status as BookingStatus} />
  ) : (
    <Chip size="small" variant="outlined" label={status} />
  );
}

function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}

/** Loads one page of a list; skips 401 (the layout sends the admin to the login page). */
function usePagedList<T>(load: (page: number, size: number) => Promise<PageResponse<T>>, page: number, size: number) {
  const [reloadCount, setReloadCount] = useState(0);
  const [loaded, setLoaded] = useState<{ key: string | null; data: PageResponse<T> | null; error: string | null }>({
    key: null,
    data: null,
    error: null,
  });
  const key = JSON.stringify([page, size, reloadCount]);
  useEffect(() => {
    let cancelled = false;
    load(page, size)
      .then((data) => !cancelled && setLoaded({ key, data, error: null }))
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        setLoaded((current) => ({ ...current, key, error: errorText(e, "The list could not be loaded. Please try again.") }));
      });
    return () => {
      cancelled = true;
    };
  }, [load, key, page, size]);
  const loading = loaded.key !== key;
  return { data: loaded.data, error: loading ? null : loaded.error, loading, retry: () => setReloadCount((n) => n + 1) };
}

function SummaryCard({ title, totals }: { title: string; totals: PaymentTotals }) {
  const rows: [string, ReactNode][] = [
    ["Gross collected", money(totals.grossPaise)],
    ["Commission earned", money(totals.commissionPaise)],
    ["Professionals' share", money(totals.professionalSharePaise)],
    ["Paid bookings", totals.paidSessions],
  ];
  return (
    <Paper sx={{ p: 2.5, flex: 1, minWidth: 260 }}>
      <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 1.5 }}>
        {title}
      </Typography>
      <Box component="dl" sx={{ display: "grid", gridTemplateColumns: "1fr auto", rowGap: 1, columnGap: 2, m: 0 }}>
        {rows.map(([label, value]) => (
          <Box key={label} sx={{ display: "contents" }}>
            <Typography component="dt" color="text.secondary">
              {label}
            </Typography>
            <Typography component="dd" sx={{ m: 0, fontWeight: 600, textAlign: "right" }}>
              {value}
            </Typography>
          </Box>
        ))}
      </Box>
    </Paper>
  );
}

function ListError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <Alert
      severity="error"
      action={
        <Button color="inherit" size="small" onClick={onRetry}>
          Retry
        </Button>
      }
    >
      {message}
    </Alert>
  );
}

function PaymentsTable({ page, size, onPage, onSize }: PagingProps) {
  const { data, error, loading, retry } = usePagedList<AdminPaymentItem>(listPayments, page, size);
  return (
    <>
      {error && <ListError message={error} onRetry={retry} />}
      <Paper>
        {loading && <LinearProgress aria-label="Loading payments" />}
        <TableContainer>
          <Table size="small" aria-label="Payments">
            <TableHead>
              <TableRow>
                <TableCell>Professional</TableCell>
                <TableCell>Session time</TableCell>
                <TableCell>Paid on</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell align="right">Commission</TableCell>
                <TableCell align="right">Professional's share</TableCell>
                <TableCell>Booking status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(data?.items ?? []).map((row) => (
                <TableRow key={row.paymentId} hover sx={row.counted ? undefined : { bgcolor: (theme) => alpha(theme.palette.warning.main, 0.08) }}>
                  <TableCell sx={{ fontWeight: 600 }}>
                    <Link component={RouterLink} to={`/admin/professionals/${row.professionalId}/edit`}>
                      {row.professionalName}
                    </Link>
                  </TableCell>
                  <TableCell>{formatIstDateTime(row.sessionTime) ?? "-"}</TableCell>
                  <TableCell>{formatIstDateTime(row.paidAt) ?? "-"}</TableCell>
                  <TableCell align="right">{money(row.amountPaise, row.currency)}</TableCell>
                  <TableCell align="right">{money(row.commissionPaise, row.currency)}</TableCell>
                  <TableCell align="right">{money(row.professionalSharePaise, row.currency)}</TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap", rowGap: 0.5 }}>
                      <StatusCell status={row.bookingStatus} />
                      {!row.counted && <Chip size="small" color="warning" label="Not counted: refund needed" />}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">No payments yet.</Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <Pagination data={data} page={page} size={size} onPage={onPage} onSize={onSize} />
      </Paper>
    </>
  );
}

function RefundsTable({ page, size, onPage, onSize }: PagingProps) {
  const { data, error, loading, retry } = usePagedList<RefundNeededItem>(listRefundsNeeded, page, size);
  return (
    <>
      <Typography color="text.secondary">
        Money was taken, but the booking is not confirmed (it expired or was cancelled while the person paid). Refund
        each one by hand in the Razorpay dashboard.
      </Typography>
      {error && <ListError message={error} onRetry={retry} />}
      <Paper>
        {loading && <LinearProgress aria-label="Loading refunds needed" />}
        <TableContainer>
          <Table size="small" aria-label="Refunds needed">
            <TableHead>
              <TableRow>
                <TableCell>Paid on</TableCell>
                <TableCell>Professional</TableCell>
                <TableCell>Person</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell>Booking status</TableCell>
                <TableCell>Razorpay payment</TableCell>
                <TableCell>Razorpay order</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(data?.items ?? []).map((row) => (
                <TableRow key={row.paymentId} hover>
                  <TableCell>{formatIstDateTime(row.paidAt) ?? "-"}</TableCell>
                  <TableCell sx={{ fontWeight: 600 }}>{row.professionalName}</TableCell>
                  <TableCell>{row.contactName}</TableCell>
                  <TableCell align="right">{money(row.amountPaise, row.currency)}</TableCell>
                  <TableCell>
                    <StatusCell status={row.bookingStatus} />
                  </TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 13 }}>{row.razorpayPaymentId ?? "-"}</TableCell>
                  <TableCell sx={{ fontFamily: "monospace", fontSize: 13 }}>{row.razorpayOrderId}</TableCell>
                </TableRow>
              ))}
              {data && data.items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} sx={{ py: 6, textAlign: "center" }}>
                    <Typography color="text.secondary">No refunds are needed.</Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
        <Pagination data={data} page={page} size={size} onPage={onPage} onSize={onSize} />
      </Paper>
    </>
  );
}

type PagingProps = { page: number; size: number; onPage: (page: number) => void; onSize: (size: number) => void };

function Pagination<T>({ data, page, size, onPage, onSize }: PagingProps & { data: PageResponse<T> | null }) {
  return (
    <TablePagination
      component="div"
      count={data?.totalElements ?? 0}
      page={data && page >= Math.max(1, data.totalPages) ? 0 : page}
      rowsPerPage={size}
      rowsPerPageOptions={PAGE_SIZES}
      onPageChange={(_, next) => onPage(next)}
      onRowsPerPageChange={(e) => onSize(Number(e.target.value))}
      labelRowsPerPage="Rows per page"
    />
  );
}

/** /admin/payments: commission totals, every paid payment, and the refunds that are needed. */
export function PaymentsPage() {
  const [params, setParams] = useSearchParams();
  const tab: TabValue = params.get("tab") === "refunds" ? "refunds" : "payments";
  const pageParam = Number(params.get("page"));
  const sizeParam = Number(params.get("size"));
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0;
  const size = PAGE_SIZES.includes(sizeParam) ? sizeParam : DEFAULT_PAGE_SIZE;

  const update = useCallback(
    (changes: Partial<Record<"tab" | "page" | "size", string>>) => {
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

  const [summaryReload, setSummaryReload] = useState(0);
  const [summary, setSummary] = useState<{ data: AdminPaymentSummary | null; error: string | null }>({
    data: null,
    error: null,
  });
  useEffect(() => {
    let cancelled = false;
    getPaymentSummary()
      .then((data) => !cancelled && setSummary({ data, error: null }))
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        setSummary({ data: null, error: errorText(e, "The totals could not be loaded. Please try again.") });
      });
    return () => {
      cancelled = true;
    };
  }, [summaryReload]);

  const paging: PagingProps = {
    page,
    size,
    onPage: (next) => update({ page: String(next) }),
    onSize: (next) => update({ size: String(next) }),
  };

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          Payments
        </Typography>
        <Typography color="text.secondary">
          Paid bookings and NeuroNest's commission. Totals count only payments whose booking is confirmed. Months are
          in India time.
        </Typography>
      </Box>

      {summary.error ? (
        <ListError message={summary.error} onRetry={() => setSummaryReload((n) => n + 1)} />
      ) : !summary.data ? (
        <LinearProgress aria-label="Loading the totals" />
      ) : (
        <Stack direction={{ xs: "column", md: "row" }} spacing={2}>
          <SummaryCard title={summary.data.month ? `This month (${summary.data.month})` : "This month"} totals={summary.data.thisMonth} />
          <SummaryCard title="All time" totals={summary.data.allTime} />
        </Stack>
      )}

      <Box sx={{ borderBottom: 1, borderColor: "divider" }}>
        <Tabs value={tab} onChange={(_, next: TabValue) => update({ tab: next === "payments" ? "" : next })} aria-label="Payment lists">
          <Tab value="payments" label="Payments" />
          <Tab value="refunds" label="Refund needed" />
        </Tabs>
      </Box>

      {tab === "payments" ? <PaymentsTable key="payments" {...paging} /> : <RefundsTable key="refunds" {...paging} />}
    </Stack>
  );
}
