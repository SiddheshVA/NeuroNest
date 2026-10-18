import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Checkbox from "@mui/material/Checkbox";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import FormControl from "@mui/material/FormControl";
import FormControlLabel from "@mui/material/FormControlLabel";
import FormGroup from "@mui/material/FormGroup";
import FormHelperText from "@mui/material/FormHelperText";
import FormLabel from "@mui/material/FormLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { AdminApiError, rejectProfessional, requestMoreInformation, verifyProfessional } from "./admin-api";
import { tomorrowInIndia } from "./format";
import { VerificationStatusChip } from "./VerificationStatusChip";
import {
  DOCUMENT_TYPE_LABELS,
  VERIFICATION_SOURCE_LABELS,
  allowedSources,
  verificationChecklist,
  type AdminDocument,
  type ProfessionalDetail,
  type VerificationSource,
  type VerifyRequest,
} from "./types";

// Mirror AdminVerificationService.MIN_NOTE and MAX_NOTE.
const MIN_NOTE = 10;
const MAX_NOTE = 1000;
const STATUS_CHANGED_MESSAGE = "This professional's status has changed. The page will reload.";
const RELOAD_DELAY_MS = 2500;

type Action = "verify" | "needs-info" | "reject";
type Notice = { severity: "success"; message: string };
type PanelError =
  | { kind: "validation"; message: string; missing: string[] }
  | { kind: "conflict" }
  | { kind: "other"; message: string; action: Action };

const SUCCESS_MESSAGES: Record<Action, string> = {
  verify: "Verified.",
  "needs-info": "Sent back for more information.",
  reject: "Rejected.",
};

// Date inputs never show spinner arrows; the picker is still available.
const dateInputSx = {
  "& input::-webkit-inner-spin-button, & input::-webkit-outer-spin-button": { display: "none" },
} as const;

export function ReviewDecisionPanel({
  professional: p,
  documents,
  onReload,
}: {
  professional: ProfessionalDetail;
  documents: AdminDocument[];
  /** Loads the record again, after the status changed underneath this page. */
  onReload: () => void;
}) {
  const navigate = useNavigate();
  const checklistItems = verificationChecklist(p.credentialType);
  const sources = allowedSources(p.credentialType);
  const isCounselor = p.credentialType === "COUNSELOR";
  const tomorrow = tomorrowInIndia();

  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [source, setSource] = useState<VerificationSource | "">("");
  const [registrationExpiry, setRegistrationExpiry] = useState("");
  const [nextRecheckDate, setNextRecheckDate] = useState("");
  const [evidenceDocumentId, setEvidenceDocumentId] = useState("");
  const [notes, setNotes] = useState("");
  const [activate, setActivate] = useState(true);

  const [dialog, setDialog] = useState<Action | null>(null);
  const [dialogText, setDialogText] = useState("");
  // The note or reason sent last, kept for Retry after a failure.
  const [lastText, setLastText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<PanelError | null>(null);

  // After a 409 the record is loaded again once the admin has had a moment to read the message.
  useEffect(() => {
    if (error?.kind !== "conflict") return;
    const timer = window.setTimeout(onReload, RELOAD_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [error, onReload]);

  if (p.verificationStatus !== "SUBMITTED") {
    return (
      <Paper sx={{ p: 2.5 }}>
        <Stack spacing={1.5}>
          <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
            Decision
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography variant="body2" color="text.secondary">
              Current status:
            </Typography>
            <VerificationStatusChip status={p.verificationStatus} />
          </Stack>
          <Typography variant="body2">A decision has already been made for this professional.</Typography>
          {p.verificationNotes && (
            <Box>
              <Typography variant="caption" color="text.secondary">
                Verification notes
              </Typography>
              <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {p.verificationNotes}
              </Typography>
            </Box>
          )}
        </Stack>
      </Paper>
    );
  }

  const checklistComplete = checklistItems.every((item) => checked[item.key]);
  const expiryError = !isCounselor && registrationExpiry && registrationExpiry < tomorrow
    ? "The date must be tomorrow or later."
    : null;
  const recheckError = nextRecheckDate && nextRecheckDate < tomorrow ? "The date must be tomorrow or later." : null;
  const canVerify = checklistComplete && source !== "" && !expiryError && !recheckError;
  const locked = busy || error?.kind === "conflict";

  const evidenceLabel = (doc: AdminDocument) =>
    `${DOCUMENT_TYPE_LABELS[doc.docType] ?? doc.docType}: ${doc.originalFilename ?? "Unnamed file"}`;

  // The exact body the backend expects, with empty optional values left out.
  const verifyBody = (): VerifyRequest => {
    const body: VerifyRequest = {
      source: source as VerificationSource,
      activate,
      checklist: Object.fromEntries(checklistItems.map((item) => [item.key, Boolean(checked[item.key])])),
    };
    if (!isCounselor && registrationExpiry) body.registrationExpiry = registrationExpiry;
    if (nextRecheckDate) body.nextRecheckDate = nextRecheckDate;
    if (notes.trim()) body.notes = notes.trim();
    if (evidenceDocumentId) body.evidenceDocumentId = evidenceDocumentId;
    return body;
  };

  const openDialog = (action: Action) => {
    setError(null);
    setDialogText("");
    setDialog(action);
  };

  const closeDialog = () => {
    if (!busy) setDialog(null);
  };

  const run = async (action: Action, text: string) => {
    setBusy(true);
    setError(null);
    try {
      if (action === "verify") await verifyProfessional(p.id, verifyBody());
      else if (action === "needs-info") await requestMoreInformation(p.id, text.trim());
      else await rejectProfessional(p.id, text.trim());
      const notice: Notice = { severity: "success", message: SUCCESS_MESSAGES[action] };
      navigate("/admin/review", { state: { notice } });
    } catch (e) {
      setDialog(null);
      // A 401 has already sent the admin to the login page with the session-expired message.
      if (e instanceof AdminApiError && e.status === 401) return;
      if (e instanceof AdminApiError && e.status === 409) {
        setError({ kind: "conflict" });
      } else if (e instanceof AdminApiError && e.status === 400) {
        setError({ kind: "validation", message: e.message, missing: e.missing });
      } else {
        setError({
          kind: "other",
          message: e instanceof Error ? e.message : "The decision could not be saved. Please try again.",
          action,
        });
      }
    } finally {
      setBusy(false);
    }
  };

  const confirm = () => {
    if (!dialog) return;
    setLastText(dialogText);
    void run(dialog, dialogText);
  };

  const missingLabel = (key: string) => checklistItems.find((item) => item.key === key)?.label ?? key;
  const textLength = dialogText.trim().length;
  const textValid = textLength >= MIN_NOTE && textLength <= MAX_NOTE;

  return (
    <Paper sx={{ p: 2.5 }}>
      <Stack spacing={2.5}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
          Decision
        </Typography>

        {error?.kind === "conflict" && <Alert severity="warning">{STATUS_CHANGED_MESSAGE}</Alert>}
        {error?.kind === "validation" && (
          <Alert severity="error">
            {error.message}
            {error.missing.length > 0 && (
              <Box component="ul" sx={{ m: 0, mt: 1, pl: 2.5 }}>
                {error.missing.map((key) => (
                  <li key={key}>{missingLabel(key)}</li>
                ))}
              </Box>
            )}
          </Alert>
        )}
        {error?.kind === "other" && (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" disabled={busy} onClick={() => void run(error.action, lastText)}>
                Retry
              </Button>
            }
          >
            {error.message}
          </Alert>
        )}

        <FormControl component="fieldset" disabled={locked}>
          <FormLabel component="legend" sx={{ fontWeight: 600, color: "text.primary" }}>
            Verification checklist
          </FormLabel>
          <FormHelperText sx={{ mx: 0, mb: 1 }}>Tick each item after you have checked it.</FormHelperText>
          <FormGroup>
            {checklistItems.map((item) => (
              <FormControlLabel
                key={item.key}
                control={
                  <Checkbox
                    checked={Boolean(checked[item.key])}
                    onChange={(e) => setChecked((current) => ({ ...current, [item.key]: e.target.checked }))}
                  />
                }
                label={item.label}
                sx={{ alignItems: "flex-start", "& .MuiCheckbox-root": { pt: 0.5 }, mb: 0.5 }}
              />
            ))}
          </FormGroup>
        </FormControl>

        <TextField
          select
          required
          label="Verification source"
          value={source}
          onChange={(e) => setSource(e.target.value as VerificationSource | "")}
          disabled={locked}
          size="small"
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
        >
          <MenuItem value="">
            <Typography component="span" color="text.secondary">
              Select source
            </Typography>
          </MenuItem>
          {sources.map((s) => (
            <MenuItem key={s} value={s}>
              {VERIFICATION_SOURCE_LABELS[s]}
            </MenuItem>
          ))}
        </TextField>

        {!isCounselor && (
          <TextField
            type="date"
            label="Registration expiry confirmed on the register"
            value={registrationExpiry}
            onChange={(e) => setRegistrationExpiry(e.target.value)}
            disabled={locked}
            size="small"
            error={Boolean(expiryError)}
            helperText={expiryError ?? "Leave empty to keep the date the person entered."}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: tomorrow } }}
            sx={dateInputSx}
          />
        )}

        <TextField
          type="date"
          label="Next re-check date"
          value={nextRecheckDate}
          onChange={(e) => setNextRecheckDate(e.target.value)}
          disabled={locked}
          size="small"
          error={Boolean(recheckError)}
          helperText={recheckError ?? "Leave empty to use the default: the earlier of the expiry date and 12 months from today."}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: tomorrow } }}
          sx={dateInputSx}
        />

        <TextField
          select
          label="Evidence document"
          value={evidenceDocumentId}
          onChange={(e) => setEvidenceDocumentId(e.target.value)}
          disabled={locked}
          size="small"
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
          helperText={documents.length === 0 ? "This professional has not uploaded any documents." : undefined}
        >
          <MenuItem value="">None</MenuItem>
          {documents.map((doc) => (
            <MenuItem key={doc.id} value={doc.id}>
              {evidenceLabel(doc)}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          label="Notes"
          multiline
          minRows={3}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          disabled={locked}
          placeholder="Registration number found on the register, active until 2030."
          helperText={`${notes.length} of ${MAX_NOTE} characters.`}
          slotProps={{ htmlInput: { maxLength: MAX_NOTE }, inputLabel: { shrink: true } }}
        />

        <FormControlLabel
          control={<Switch checked={activate} onChange={(e) => setActivate(e.target.checked)} disabled={locked} />}
          label="Show this listing in the public directory after approval"
        />

        {!canVerify && !locked && (
          <Typography variant="body2" color="text.secondary">
            Tick every checklist item and choose a verification source to enable Verify.
          </Typography>
        )}

        <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap", rowGap: 1.5 }}>
          <Button variant="contained" disabled={!canVerify || locked} onClick={() => openDialog("verify")}>
            Verify
          </Button>
          <Button variant="outlined" disabled={locked} onClick={() => openDialog("needs-info")}>
            Ask for more information
          </Button>
          <Button variant="outlined" color="error" disabled={locked} onClick={() => openDialog("reject")}>
            Reject
          </Button>
        </Stack>
      </Stack>

      <Dialog open={dialog !== null} onClose={closeDialog} fullWidth maxWidth="sm">
        {dialog === "verify" && (
          <>
            <DialogTitle>Verify this professional?</DialogTitle>
            <DialogContent>
              <Stack spacing={1}>
                <SummaryLine label="Name" value={p.fullName} />
                <SummaryLine
                  label="Verification source"
                  value={source ? VERIFICATION_SOURCE_LABELS[source as VerificationSource] : "Not chosen"}
                />
                <SummaryLine label="Show in the public directory" value={activate ? "Yes" : "No"} />
                <Typography variant="body2" sx={{ pt: 1 }}>
                  After this the professional can be listed in the public directory.
                </Typography>
              </Stack>
            </DialogContent>
          </>
        )}
        {(dialog === "needs-info" || dialog === "reject") && (
          <>
            <DialogTitle>
              {dialog === "needs-info" ? "Ask for more information?" : "Reject this professional?"}
            </DialogTitle>
            <DialogContent>
              <Stack spacing={2} sx={{ pt: 1 }}>
                <SummaryLine label="Name" value={p.fullName} />
                <TextField
                  autoFocus
                  required
                  multiline
                  minRows={4}
                  label={dialog === "needs-info" ? "Note" : "Reason"}
                  value={dialogText}
                  onChange={(e) => setDialogText(e.target.value)}
                  disabled={busy}
                  placeholder={
                    dialog === "needs-info"
                      ? "Please upload a clearer copy of your degree certificate."
                      : "The registration number could not be found on the official register."
                  }
                  error={dialogText.length > 0 && !textValid}
                  helperText={
                    <>
                      {dialog === "needs-info"
                        ? "The professional will see this note on their profile page."
                        : "The professional will see this reason on their profile page."}{" "}
                      Use {MIN_NOTE} to {MAX_NOTE} characters. You have written {textLength}.
                    </>
                  }
                  slotProps={{ htmlInput: { maxLength: MAX_NOTE }, inputLabel: { shrink: true } }}
                />
                {dialog === "reject" && (
                  <Typography variant="body2" color="error">
                    This cannot be undone from this screen.
                  </Typography>
                )}
              </Stack>
            </DialogContent>
          </>
        )}
        <DialogActions>
          <Button onClick={closeDialog} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="contained"
            color={dialog === "reject" ? "error" : "primary"}
            onClick={confirm}
            disabled={busy || (dialog !== "verify" && !textValid)}
            startIcon={busy ? <CircularProgress size={16} color="inherit" aria-hidden="true" /> : undefined}
          >
            {busy ? "Sending" : "Confirm"}
          </Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}

function SummaryLine({ label, value }: { label: string; value: string }) {
  return (
    <Typography variant="body2">
      <Typography component="span" variant="body2" color="text.secondary">
        {label}:{" "}
      </Typography>
      {value}
    </Typography>
  );
}
