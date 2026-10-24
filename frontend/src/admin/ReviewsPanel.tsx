import { useEffect, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import Divider from "@mui/material/Divider";
import Paper from "@mui/material/Paper";
import Rating from "@mui/material/Rating";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { createTheme, ThemeProvider } from "@mui/material/styles";

import { adminTheme } from "./admin-theme";
import { AdminApiError, listProfessionalReviews, moderateReview } from "./admin-api";
import { formatDateTime } from "./format";
import { NoteActionDialog } from "./NoteActionDialog";
import type { AdminReview } from "./types";

const PAGE_SIZE = 20;
const MIN_REASON = 3;
const MAX_REASON = 500;

// Reviews UI follows the quick-colour-only rule: no ripples, fades or slides, here and in its dialogs.
// The rest of the admin area keeps MUI's defaults.
const stillTheme = createTheme(adminTheme, {
  transitions: { create: () => "none" },
  components: {
    MuiButtonBase: { defaultProps: { disableRipple: true } },
    MuiDialog: { defaultProps: { transitionDuration: 0 } },
    MuiBackdrop: { defaultProps: { transitionDuration: 0 } },
    MuiSnackbar: { defaultProps: { transitionDuration: 0 } },
  },
});

type Load = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; items: AdminReview[]; totalElements: number; totalPages: number; page: number };

/**
 * Every review of this professional, hidden ones included, newest first, with Hide and Unhide (a reason is
 * required for both). Reviews are never deleted here, and who wrote them is never shown.
 */
export function ReviewsPanel({ professionalId }: { professionalId: string }) {
  const [load, setLoad] = useState<Load>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);
  const [dialog, setDialog] = useState<{ review: AdminReview; action: "hide" | "unhide" } | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ severity: "success" | "error"; message: string } | null>(null);
  const [page, setPage] = useState(0);

  useEffect(() => {
    let cancelled = false;
    listProfessionalReviews(professionalId, page, PAGE_SIZE)
      .then((data) => {
        if (!cancelled) {
          setLoad({ status: "ready", items: data.items, totalElements: data.totalElements, totalPages: data.totalPages, page });
        }
      })
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        setLoad({ status: "error", message: e instanceof Error ? e.message : "Reviews could not be loaded." });
      });
    return () => {
      cancelled = true;
    };
  }, [professionalId, page, reloadCount]);

  const run = async (reason: string) => {
    if (!dialog) return;
    setBusy(true);
    setDialogError(null);
    try {
      const updated = await moderateReview(dialog.review.id, dialog.action, reason);
      setLoad((current) =>
        current.status === "ready"
          ? { ...current, items: current.items.map((r) => (r.id === updated.id ? updated : r)) }
          : current,
      );
      setNotice({
        severity: "success",
        message: dialog.action === "hide" ? "Review hidden. It no longer counts in the rating." : "Review visible again.",
      });
      setDialog(null);
    } catch (e) {
      if (e instanceof AdminApiError && e.status === 401) return;
      const message = e instanceof Error ? e.message : "The change could not be saved. Please try again.";
      if (e instanceof AdminApiError && (e.status === 400 || e.status === 409)) {
        setDialogError(message);
        // 409: someone else changed it meanwhile; show the current state.
        if (e.status === 409) setReloadCount((n) => n + 1);
      } else {
        setDialog(null);
        setNotice({ severity: "error", message });
      }
    } finally {
      setBusy(false);
    }
  };

  const hiddenCount = load.status === "ready" ? load.items.filter((r) => r.status === "HIDDEN").length : 0;

  return (
    <ThemeProvider theme={stillTheme}>
      <Paper sx={{ p: { xs: 2, sm: 3 } }} component="section" aria-labelledby="admin-reviews-heading">
        <Stack spacing={2}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "baseline", flexWrap: "wrap" }}>
            <Typography id="admin-reviews-heading" variant="h6" component="h2" sx={{ fontWeight: 700 }}>
              Reviews
            </Typography>
            {load.status === "ready" && (
              <Typography variant="body2" color="text.secondary">
                {load.totalElements} in total{hiddenCount > 0 ? `, ${hiddenCount} hidden on this page` : ""}
              </Typography>
            )}
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Reviews go live straight away. Hide one that breaks the rules (for example personal or health details,
            or contact details that got through); it then disappears from the profile and the rating. Nothing is
            deleted, and you can show it again.
          </Typography>

          {load.status === "loading" ? (
            <Typography variant="body2" color="text.secondary">
              Loading reviews.
            </Typography>
          ) : load.status === "error" ? (
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => setReloadCount((n) => n + 1)}>
                  Try again
                </Button>
              }
            >
              {load.message}
            </Alert>
          ) : load.items.length === 0 ? (
            <Typography variant="body2">No reviews yet.</Typography>
          ) : (
            <Stack divider={<Divider flexItem />} spacing={2}>
              {load.items.map((review) => (
                <Box key={review.id} component="article" aria-label={`Review from ${formatDateTime(review.createdAt)}`}>
                  <Stack direction={{ xs: "column", sm: "row" }} spacing={2} sx={{ justifyContent: "space-between" }}>
                    <Stack spacing={0.75} sx={{ minWidth: 0 }}>
                      <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
                        <Rating value={review.rating} readOnly size="small" aria-label={`${review.rating} out of 5`} />
                        <Chip
                          size="small"
                          label={review.status === "HIDDEN" ? "Hidden" : "Visible"}
                          color={review.status === "HIDDEN" ? "warning" : "default"}
                          variant={review.status === "HIDDEN" ? "filled" : "outlined"}
                        />
                        <Typography variant="caption" color="text.secondary">
                          {formatDateTime(review.createdAt)}
                          {review.updatedAt !== review.createdAt ? `, edited ${formatDateTime(review.updatedAt)}` : ""}
                        </Typography>
                      </Stack>
                      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                        {review.comment ?? <em>No comment.</em>}
                      </Typography>
                      {review.moderationReason && (
                        <Typography variant="caption" color="text.secondary">
                          {review.status === "HIDDEN" ? "Hidden" : "Shown again"} by {review.moderatedBy ?? "an admin"}
                          {review.moderatedAt ? ` on ${formatDateTime(review.moderatedAt)}` : ""}: {review.moderationReason}
                        </Typography>
                      )}
                      <Typography variant="caption" color="text.secondary">
                        Booking {review.bookingId}
                      </Typography>
                    </Stack>
                    <Box sx={{ flexShrink: 0 }}>
                      {review.status === "VISIBLE" ? (
                        <Button
                          variant="outlined"
                          color="error"
                          size="small"
                          onClick={() => {
                            setDialogError(null);
                            setDialog({ review, action: "hide" });
                          }}
                        >
                          Hide
                        </Button>
                      ) : (
                        <Button
                          variant="outlined"
                          size="small"
                          onClick={() => {
                            setDialogError(null);
                            setDialog({ review, action: "unhide" });
                          }}
                        >
                          Unhide
                        </Button>
                      )}
                    </Box>
                  </Stack>
                </Box>
              ))}
            </Stack>
          )}

          {load.status === "ready" && load.totalPages > 1 && (
            <Stack direction="row" spacing={2} sx={{ alignItems: "center", justifyContent: "space-between" }}>
              <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Newer
              </Button>
              <Typography variant="body2" color="text.secondary">
                Page {page + 1} of {load.totalPages}
              </Typography>
              <Button disabled={page + 1 >= load.totalPages} onClick={() => setPage((p) => p + 1)}>
                Older
              </Button>
            </Stack>
          )}
        </Stack>
      </Paper>

      {dialog?.action === "hide" && (
        <NoteActionDialog
          title="Hide this review"
          explanation="It disappears from the profile straight away and no longer counts in the average or the number of reviews. The client cannot edit or delete it while it is hidden."
          noteLabel="Reason"
          helper="Kept with the review for other admins. The client and the professional do not see it."
          placeholder="Mentions another client by name."
          min={MIN_REASON}
          max={MAX_REASON}
          confirmQuestion="Hide this review?"
          confirmLabel="Hide review"
          danger
          busy={busy}
          error={dialogError}
          onClose={() => setDialog(null)}
          onSubmit={(reason) => void run(reason)}
        />
      )}
      {dialog?.action === "unhide" && (
        <NoteActionDialog
          title="Show this review again"
          explanation="It returns to the profile and counts in the rating again."
          noteLabel="Reason"
          helper="Kept with the review for other admins. The client and the professional do not see it."
          placeholder="Checked again: no personal details."
          min={MIN_REASON}
          max={MAX_REASON}
          confirmQuestion="Show this review again?"
          confirmLabel="Unhide review"
          busy={busy}
          error={dialogError}
          onClose={() => setDialog(null)}
          onSubmit={(reason) => void run(reason)}
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
    </ThemeProvider>
  );
}
