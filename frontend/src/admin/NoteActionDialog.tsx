import { useState, type ReactNode } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

/**
 * An admin action that needs a written note: first the note (with a character counter), then a confirm
 * step that shows it again before anything is sent. Render it only while it is open, so every opening
 * starts empty. A 400 from the server is shown here (`error`); the caller handles everything else.
 */
export function NoteActionDialog({
  title,
  explanation,
  noteLabel,
  helper,
  placeholder,
  min,
  max,
  confirmQuestion,
  confirmLabel,
  danger,
  busy,
  error,
  onClose,
  onSubmit,
}: {
  title: string;
  explanation: ReactNode;
  noteLabel: string;
  helper: string;
  placeholder: string;
  min: number;
  max: number;
  confirmQuestion: string;
  confirmLabel: string;
  danger?: boolean;
  busy: boolean;
  error: string | null;
  onClose: () => void;
  onSubmit: (note: string) => void;
}) {
  const [step, setStep] = useState<"write" | "confirm">("write");
  const [note, setNote] = useState("");
  const length = note.trim().length;
  const valid = length >= min && length <= max;
  const close = () => {
    if (!busy) onClose();
  };

  return (
    <Dialog open onClose={close} fullWidth maxWidth="sm">
      <DialogTitle>{step === "write" ? title : confirmQuestion}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          {step === "write" ? (
            <>
              <Typography variant="body2">{explanation}</Typography>
              <TextField
                autoFocus
                required
                multiline
                minRows={4}
                label={noteLabel}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder={placeholder}
                error={note.length > 0 && !valid}
                helperText={
                  <>
                    {helper} Use {min} to {max} characters. You have written {length}.
                  </>
                }
                slotProps={{ htmlInput: { maxLength: max }, inputLabel: { shrink: true } }}
              />
            </>
          ) : (
            <>
              <Typography variant="body2">{explanation}</Typography>
              <Typography variant="caption" color="text.secondary" component="div">
                {noteLabel}
              </Typography>
              <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere", mt: -1.5 }}>
                {note.trim()}
              </Typography>
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        {step === "write" ? (
          <>
            <Button onClick={close}>Cancel</Button>
            <Button variant="contained" disabled={!valid} onClick={() => setStep("confirm")}>
              Continue
            </Button>
          </>
        ) : (
          <>
            <Button onClick={() => setStep("write")} disabled={busy}>
              Back
            </Button>
            <Button
              variant="contained"
              color={danger ? "error" : "primary"}
              onClick={() => onSubmit(note.trim())}
              disabled={busy}
              startIcon={busy ? <CircularProgress size={16} color="inherit" aria-hidden="true" /> : undefined}
            >
              {busy ? "Sending" : confirmLabel}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}
