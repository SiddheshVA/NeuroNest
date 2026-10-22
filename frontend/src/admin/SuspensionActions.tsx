import { useState } from "react";
import Alert from "@mui/material/Alert";
import Button from "@mui/material/Button";
import Snackbar from "@mui/material/Snackbar";
import { AdminApiError, reinstateProfessional, suspendProfessional } from "./admin-api";
import { NoteActionDialog } from "./NoteActionDialog";
import type { ProfessionalDetail } from "./types";

const MIN_NOTE = 10;
const MAX_NOTE = 1000;

type Notice = { severity: "success" | "error"; message: string };

/**
 * "Suspend listing" for a VERIFIED professional and "Reinstate listing" for a SUSPENDED one; nothing for
 * any other status. After success the page gets the updated record.
 */
export function SuspensionActions({
  record,
  onChanged,
}: {
  record: ProfessionalDetail;
  onChanged: (detail: ProfessionalDetail) => void;
}) {
  const [dialog, setDialog] = useState<"suspend" | "reinstate" | null>(null);
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  const status = record.verificationStatus;
  if (status !== "VERIFIED" && status !== "SUSPENDED" && !notice) return null;

  const open = (kind: "suspend" | "reinstate") => {
    setDialogError(null);
    setDialog(kind);
  };

  const run = async (kind: "suspend" | "reinstate", note: string) => {
    setBusy(true);
    setDialogError(null);
    try {
      const detail = kind === "suspend" ? await suspendProfessional(record.id, note) : await reinstateProfessional(record.id, note);
      setDialog(null);
      onChanged(detail);
      setNotice({ severity: "success", message: kind === "suspend" ? "Listing suspended." : "Listing reinstated." });
    } catch (e) {
      // A 401 has already sent the admin to the login page with the session-expired message.
      if (e instanceof AdminApiError && e.status === 401) return;
      const message = e instanceof Error ? e.message : "The change could not be saved. Please try again.";
      if (e instanceof AdminApiError && (e.status === 400 || e.status === 409)) {
        // Shown as the server wrote it, for example that the registration has expired.
        setDialogError(message);
      } else {
        setDialog(null);
        setNotice({ severity: "error", message });
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {status === "VERIFIED" && (
        <Button variant="outlined" color="error" onClick={() => open("suspend")}>
          Suspend listing
        </Button>
      )}
      {status === "SUSPENDED" && (
        <Button variant="contained" onClick={() => open("reinstate")}>
          Reinstate listing
        </Button>
      )}

      {dialog === "suspend" && (
        <NoteActionDialog
          title={`Suspend ${record.fullName}`}
          explanation="The listing disappears from the directory immediately, and the professional can no longer sign in to their portal. Open requests expire on their own."
          noteLabel="Note"
          helper="The professional will see this note on their profile page."
          placeholder="Your listing is paused while we look into a complaint. We will contact you by email."
          min={MIN_NOTE}
          max={MAX_NOTE}
          confirmQuestion="Suspend this listing?"
          confirmLabel="Suspend listing"
          danger
          busy={busy}
          error={dialogError}
          onClose={() => setDialog(null)}
          onSubmit={(note) => void run("suspend", note)}
        />
      )}
      {dialog === "reinstate" && (
        <NoteActionDialog
          title={`Reinstate ${record.fullName}`}
          explanation="The listing returns to the directory and the professional can sign in again."
          noteLabel="Note"
          helper="The professional will see this note on their profile page."
          placeholder="We have finished our review and your listing is visible again."
          min={MIN_NOTE}
          max={MAX_NOTE}
          confirmQuestion="Reinstate this listing?"
          confirmLabel="Reinstate listing"
          busy={busy}
          error={dialogError}
          onClose={() => setDialog(null)}
          onSubmit={(note) => void run("reinstate", note)}
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
    </>
  );
}
