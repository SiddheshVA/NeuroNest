import { useEffect, useRef, useState } from "react";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Chip from "@mui/material/Chip";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import LockRounded from "@mui/icons-material/LockRounded";
import VisibilityRounded from "@mui/icons-material/VisibilityRounded";
import WarningAmberRounded from "@mui/icons-material/WarningAmberRounded";
import { AdminApiError, getDocumentFile } from "./admin-api";
import { formatDateTime, formatFileSize } from "./format";
import { DOCUMENT_TYPE_LABELS, type AdminDocument, type DocumentType } from "./types";

type Viewer =
  | { status: "closed" }
  | { status: "loading"; doc: AdminDocument }
  | { status: "ready"; doc: AdminDocument; url: string; kind: "pdf" | "image" }
  | { status: "error"; doc: AdminDocument; message: string };

// The file is fetched with the admin token in a header and shown from a short-lived object URL. There is
// no download button, no "open in new tab" and no link to copy; the URL is revoked when the dialog
// closes or the page is left.
export function ReviewDocumentsPanel({
  professionalId,
  documents,
  requiredDocuments,
}: {
  professionalId: string;
  documents: AdminDocument[];
  requiredDocuments: DocumentType[];
}) {
  const [viewer, setViewer] = useState<Viewer>({ status: "closed" });
  // The object URL in use, and a counter so a slow response for a dialog that was already closed (or
  // replaced by another document) is thrown away.
  const urlRef = useRef<string | null>(null);
  const requestRef = useRef(0);

  const revoke = () => {
    if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    urlRef.current = null;
  };

  useEffect(
    () => () => {
      requestRef.current += 1;
      if (urlRef.current) URL.revokeObjectURL(urlRef.current);
      urlRef.current = null;
    },
    [],
  );

  const open = async (doc: AdminDocument) => {
    revoke();
    const request = ++requestRef.current;
    setViewer({ status: "loading", doc });
    try {
      const blob = await getDocumentFile(professionalId, doc.id);
      if (request !== requestRef.current) return;
      const type = (blob.type || doc.contentType || "").toLowerCase();
      const kind = type === "application/pdf" ? "pdf" : type === "image/jpeg" || type === "image/png" ? "image" : null;
      if (!kind) {
        setViewer({ status: "error", doc, message: "This file type cannot be shown here. Only PDF, JPG and PNG files can be viewed." });
        return;
      }
      const url = URL.createObjectURL(blob);
      urlRef.current = url;
      setViewer({ status: "ready", doc, url, kind });
    } catch (e) {
      if (request !== requestRef.current) return;
      if (e instanceof AdminApiError && e.status === 401) return;
      setViewer({
        status: "error",
        doc,
        message:
          e instanceof AdminApiError && e.status === 404
            ? "This document could not be found. It may have been deleted."
            : e instanceof Error
              ? e.message
              : "The document could not be loaded. Please try again.",
      });
    }
  };

  const close = () => {
    requestRef.current += 1;
    revoke();
    setViewer({ status: "closed" });
  };

  const uploaded = new Set(documents.map((d) => d.docType));
  const missing = requiredDocuments.filter((type) => !uploaded.has(type));

  return (
    <Paper sx={{ p: 2.5 }}>
      <Stack spacing={2}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
          Documents
        </Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: "flex-start", color: "text.secondary" }}>
          <LockRounded fontSize="small" sx={{ mt: 0.25 }} aria-hidden="true" />
          <Typography variant="body2">These documents are private. Use them only for verification.</Typography>
        </Stack>

        {missing.length > 0 && (
          <Stack direction="row" sx={{ flexWrap: "wrap", gap: 1 }}>
            {missing.map((type) => (
              <Chip
                key={type}
                size="small"
                color="warning"
                icon={<WarningAmberRounded />}
                label={`Missing: ${DOCUMENT_TYPE_LABELS[type]}`}
              />
            ))}
          </Stack>
        )}

        {documents.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            No documents have been uploaded.
          </Typography>
        ) : (
          <Stack component="ul" spacing={1.5} sx={{ listStyle: "none", m: 0, p: 0 }}>
            {documents.map((doc) => (
              <Box
                component="li"
                key={doc.id}
                sx={{
                  display: "flex",
                  gap: 1.5,
                  alignItems: "center",
                  justifyContent: "space-between",
                  border: 1,
                  borderColor: "divider",
                  borderRadius: 2,
                  px: 1.5,
                  py: 1,
                }}
              >
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {DOCUMENT_TYPE_LABELS[doc.docType] ?? doc.docType}
                  </Typography>
                  <Typography variant="body2" noWrap title={doc.originalFilename ?? undefined}>
                    {doc.originalFilename ?? "Unnamed file"}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {formatFileSize(doc.sizeBytes)}, uploaded on {formatDateTime(doc.uploadedAt)}
                  </Typography>
                </Box>
                <Button
                  size="small"
                  variant="outlined"
                  startIcon={<VisibilityRounded />}
                  onClick={() => void open(doc)}
                  aria-label={`View ${DOCUMENT_TYPE_LABELS[doc.docType] ?? "document"}`}
                  sx={{ flexShrink: 0 }}
                >
                  View
                </Button>
              </Box>
            ))}
          </Stack>
        )}
      </Stack>

      <Dialog open={viewer.status !== "closed"} onClose={close} fullWidth maxWidth="lg">
        {viewer.status !== "closed" && (
          <>
            <DialogTitle>
              {DOCUMENT_TYPE_LABELS[viewer.doc.docType] ?? "Document"}
              <Typography variant="body2" color="text.secondary" component="span" sx={{ display: "block" }}>
                {viewer.doc.originalFilename ?? "Unnamed file"}. These documents are private. Use them only for
                verification.
              </Typography>
            </DialogTitle>
            <DialogContent dividers sx={{ p: 0, bgcolor: "action.hover" }}>
              {viewer.status === "loading" && (
                <Box sx={{ display: "grid", placeItems: "center", minHeight: 320 }}>
                  <CircularProgress aria-label="Loading the document" />
                </Box>
              )}
              {viewer.status === "error" && (
                <Box sx={{ p: 2 }}>
                  <Alert severity="error">{viewer.message}</Alert>
                </Box>
              )}
              {viewer.status === "ready" && viewer.kind === "pdf" && (
                // The browser's PDF toolbar (with its download button) is hidden where the viewer allows it.
                <Box
                  component="iframe"
                  title={DOCUMENT_TYPE_LABELS[viewer.doc.docType] ?? "Document"}
                  src={`${viewer.url}#toolbar=0&navpanes=0`}
                  sx={{ display: "block", width: "100%", height: "75vh", border: 0 }}
                />
              )}
              {viewer.status === "ready" && viewer.kind === "image" && (
                <Box sx={{ display: "grid", placeItems: "center", p: 2 }}>
                  <Box
                    component="img"
                    src={viewer.url}
                    alt={DOCUMENT_TYPE_LABELS[viewer.doc.docType] ?? "Document"}
                    draggable={false}
                    onContextMenu={(e) => e.preventDefault()}
                    sx={{ maxWidth: "100%", maxHeight: "75vh", objectFit: "contain" }}
                  />
                </Box>
              )}
            </DialogContent>
            <DialogActions>
              <Button onClick={close}>Close</Button>
            </DialogActions>
          </>
        )}
      </Dialog>
    </Paper>
  );
}
