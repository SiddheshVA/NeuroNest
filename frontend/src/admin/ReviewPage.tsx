import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link as RouterLink, useParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import Link from "@mui/material/Link";
import Paper from "@mui/material/Paper";
import Snackbar from "@mui/material/Snackbar";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ArrowBackRounded from "@mui/icons-material/ArrowBackRounded";
import { AdminApiError, getDocuments, getProfessional, getVerificationLog } from "./admin-api";
import { ageFromDateOfBirth, formatDate, formatDateTime, isPastDate } from "./format";
import { RegisterShortcutsCard } from "./RegisterShortcutsCard";
import { ReviewDecisionPanel } from "./ReviewDecisionPanel";
import { ReviewDocumentsPanel } from "./ReviewDocumentsPanel";
import { SampleChip, VerificationStatusChip } from "./VerificationStatusChip";
import {
  CONSULTATION_MODE_LABELS,
  CREDENTIAL_TYPE_LABELS,
  DOCUMENT_TYPE_LABELS,
  LOG_ACTION_LABELS,
  REGISTRATION_BODY_LABELS,
  SOCIAL_PLATFORM_LABELS,
  SOCIAL_PLATFORMS,
  VERIFICATION_SOURCE_LABELS,
  VERIFICATION_STATUS_LABELS,
  requiredDocuments,
  type AdminDocument,
  type DocumentType,
  type LogEntry,
  type ProfessionalDetail,
  type VerificationSource,
  type VerificationStatus,
} from "./types";

const NOT_PROVIDED = "Not provided";

type Loaded = { professional: ProfessionalDetail; documents: AdminDocument[]; log: LogEntry[] };
type LoadState =
  | { status: "loading" }
  | { status: "ready"; data: Loaded }
  | { status: "not-found" }
  | { status: "error"; message: string };

// /admin/review/:id. Read-only details and history on the left; documents, the register shortcut and
// the decision panel on the right. Everything is loaded again on Retry.
export function ReviewPage() {
  const { id = "" } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [reloadCount, setReloadCount] = useState(0);
  const [snackbar, setSnackbar] = useState<{ severity: "success" | "error"; message: string } | null>(null);

  // Loads the record, documents and log again (Retry, and after the status changed elsewhere).
  const reload = useCallback(() => {
    setState({ status: "loading" });
    setReloadCount((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([getProfessional(id), getDocuments(id), getVerificationLog(id)])
      .then(([professional, documents, log]) => {
        if (!cancelled) setState({ status: "ready", data: { professional, documents, log } });
      })
      .catch((e) => {
        // A 401 has already sent the admin to the login page with the session-expired message.
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        if (e instanceof AdminApiError && (e.status === 404 || e.status === 400)) {
          // 400 is a malformed id in the address, which is not a professional either.
          setState({ status: "not-found" });
          return;
        }
        setState({
          status: "error",
          message: e instanceof Error ? e.message : "This professional could not be loaded. Please try again.",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [id, reloadCount]);

  const backLink = (
    <Button component={RouterLink} to="/admin/review" startIcon={<ArrowBackRounded />} sx={{ alignSelf: "flex-start" }}>
      Back to the review queue
    </Button>
  );

  if (state.status === "loading") {
    return (
      <Stack spacing={3}>
        {backLink}
        <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
          <CircularProgress aria-label="Loading the professional" />
        </Box>
      </Stack>
    );
  }
  if (state.status === "not-found") {
    return (
      <Stack spacing={3}>
        {backLink}
        <Alert severity="warning">This professional was not found.</Alert>
      </Stack>
    );
  }
  if (state.status === "error") {
    return (
      <Stack spacing={3}>
        {backLink}
        <Alert
          severity="error"
          action={
            <Button
              color="inherit"
              size="small"
              onClick={reload}
            >
              Retry
            </Button>
          }
        >
          {state.message}
        </Alert>
      </Stack>
    );
  }

  const { professional: p, documents, log } = state.data;
  const status = p.verificationStatus;

  return (
    <Stack spacing={3}>
      {backLink}
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", flexWrap: "wrap", rowGap: 1 }}>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          {p.fullName}
        </Typography>
        <VerificationStatusChip status={status} size="medium" />
        {p.isSampleData && <SampleChip />}
      </Stack>

      <Box
        sx={{
          display: "grid",
          gap: 3,
          gridTemplateColumns: { xs: "minmax(0, 1fr)", md: "minmax(0, 3fr) minmax(0, 2fr)" },
          alignItems: "start",
        }}
      >
        <Stack spacing={3}>
          <ProfessionalDetails p={p} />
          <VerificationLog log={log} documents={documents} />
        </Stack>

        <Stack spacing={3}>
          <ReviewDocumentsPanel
            professionalId={p.id}
            documents={documents}
            requiredDocuments={requiredDocumentsFor(p)}
          />
          <RegisterShortcutsCard
            credentialType={p.credentialType}
            registrationNumber={p.registrationNumber}
            onCopied={() => setSnackbar({ severity: "success", message: "Copied." })}
            onCopyFailed={() =>
              setSnackbar({ severity: "error", message: "The registration number could not be copied. Please copy it by hand." })
            }
          />
          <ReviewDecisionPanel professional={p} documents={documents} onReload={reload} />
        </Stack>
      </Box>

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={snackbar?.severity === "error" ? 8000 : 3000}
        onClose={(_, reason) => reason !== "clickaway" && setSnackbar(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "center" }}
      >
        {snackbar ? (
          <Alert severity={snackbar.severity} variant="filled" onClose={() => setSnackbar(null)} sx={{ width: "100%" }}>
            {snackbar.message}
          </Alert>
        ) : undefined}
      </Snackbar>
    </Stack>
  );
}

// The admin record has no requiredDocuments field today; use it if the backend ever adds one, and
// otherwise derive it from the credential type.
function requiredDocumentsFor(p: ProfessionalDetail): DocumentType[] {
  const fromBackend = (p as ProfessionalDetail & { requiredDocuments?: DocumentType[] }).requiredDocuments;
  return Array.isArray(fromBackend) ? fromBackend : requiredDocuments(p.credentialType);
}

// ---- Left column ----

function ProfessionalDetails({ p }: { p: ProfessionalDetail }) {
  const age = ageFromDateOfBirth(p.dateOfBirth);
  const expired = isPastDate(p.registrationExpiry);
  const links = SOCIAL_PLATFORMS.filter((platform) => p.socialLinks?.[platform]);

  return (
    <Paper sx={{ p: 2.5 }}>
      <Stack spacing={2.5} divider={<Divider flexItem />}>
        <DetailGroup title="Identity">
          <Detail label="Full name" value={p.fullName} />
          <Detail
            label="Date of birth"
            value={p.dateOfBirth ? `${formatDate(p.dateOfBirth)}${age !== null ? ` (age ${age})` : ""}` : null}
          />
          <Detail label="Credential type" value={CREDENTIAL_TYPE_LABELS[p.credentialType] ?? p.credentialType} />
          <Detail label="Qualifications" value={p.qualifications} />
        </DetailGroup>

        <DetailGroup title="Registration">
          <Detail
            label="Registration body"
            value={p.registrationBody ? (REGISTRATION_BODY_LABELS[p.registrationBody] ?? p.registrationBody) : null}
          />
          <Detail label="Council" value={p.registrationCouncil} />
          <Detail label="Registration number" value={p.registrationNumber} />
          <Detail label="Registration year" value={p.registrationYear === null ? null : String(p.registrationYear)} />
          <Detail
            label="Registration expiry"
            value={
              p.registrationExpiry ? (
                <Typography variant="body2" color={expired ? "error" : "text.primary"} component="span">
                  {formatDate(p.registrationExpiry)}
                  {expired ? " (expired)" : ""}
                </Typography>
              ) : null
            }
          />
        </DetailGroup>

        <DetailGroup title="Practice">
          <Detail label="State" value={p.state} />
          <Detail label="City" value={p.city} />
          <Detail label="Consultation mode" value={CONSULTATION_MODE_LABELS[p.consultationMode] ?? p.consultationMode} />
          <Detail
            label="Consultation fee"
            value={
              p.consultationFee === null
                ? null
                : p.consultationFee === 0
                  ? "No fee"
                  : `₹${p.consultationFee.toLocaleString("en-IN")} per session`
            }
          />
          <Detail label="Availability note" value={p.availabilityNote} />
          <Detail
            label="Years of experience"
            value={p.yearsExperience === null ? null : `${p.yearsExperience} ${p.yearsExperience === 1 ? "year" : "years"}`}
          />
          <Detail label="Languages" value={p.languages.length ? p.languages.join(", ") : null} />
          <Detail label="Specializations" value={p.specialties.length ? p.specialties.join(", ") : null} />
          <Detail label="Bio" value={p.bio} wide />
        </DetailGroup>

        <DetailGroup title="Contact">
          <Detail label="Email" value={p.contactEmail} />
          <Detail label="Phone" value={p.contactPhone} />
        </DetailGroup>

        <DetailGroup title="Social links">
          {links.length === 0 ? (
            <Detail label="Profiles" value="None provided" wide />
          ) : (
            links.map((platform) => {
              const url = p.socialLinks[platform] ?? "";
              return (
                <Detail
                  key={platform}
                  label={SOCIAL_PLATFORM_LABELS[platform]}
                  value={
                    // Only https links are ever saved; anything else is shown as text, not as a link.
                    /^https:\/\//i.test(url) ? (
                      <Link href={url} target="_blank" rel="noopener noreferrer" sx={{ wordBreak: "break-all" }}>
                        {url}
                      </Link>
                    ) : (
                      url
                    )
                  }
                  wide
                />
              );
            })
          )}
        </DetailGroup>

        <DetailGroup title="Declaration">
          <Detail
            label="Declaration"
            value={p.declarationAcceptedAt ? `Accepted on ${formatDateTime(p.declarationAcceptedAt)}` : "Not accepted"}
            wide
          />
        </DetailGroup>
      </Stack>
    </Paper>
  );
}

function DetailGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box component="section">
      <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 700, mb: 1.5 }}>
        {title}
      </Typography>
      <Box
        component="dl"
        sx={{ m: 0, display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr" } }}
      >
        {children}
      </Box>
    </Box>
  );
}

function Detail({ label, value, wide }: { label: string; value: ReactNode; wide?: boolean }) {
  const empty = value === null || value === undefined || (typeof value === "string" && value.trim() === "");
  return (
    <Box sx={{ gridColumn: wide ? "1 / -1" : undefined, minWidth: 0 }}>
      <Typography component="dt" variant="caption" color="text.secondary" sx={{ display: "block" }}>
        {label}
      </Typography>
      <Typography
        component="dd"
        variant="body2"
        color={empty ? "text.secondary" : "text.primary"}
        sx={{ m: 0, whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
      >
        {empty ? NOT_PROVIDED : value}
      </Typography>
    </Box>
  );
}

function VerificationLog({ log, documents }: { log: LogEntry[]; documents: AdminDocument[] }) {
  const statusLabel = (s: string | null) =>
    s ? (VERIFICATION_STATUS_LABELS[s as VerificationStatus] ?? s) : NOT_PROVIDED;
  const documentName = (docId: string) => {
    const doc = documents.find((d) => d.id === docId);
    return doc ? `${DOCUMENT_TYPE_LABELS[doc.docType] ?? doc.docType} (${doc.originalFilename ?? "unnamed file"})` : "A deleted document";
  };

  return (
    <Paper sx={{ p: 2.5 }}>
      <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 1.5 }}>
        Verification history
      </Typography>
      {log.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          There is no history yet.
        </Typography>
      ) : (
        // The backend returns the log newest first.
        <Stack component="ol" spacing={1.5} divider={<Divider flexItem />} sx={{ listStyle: "none", m: 0, p: 0 }}>
          {log.map((entry, index) => (
            <Box component="li" key={`${entry.createdAt}-${index}`}>
              <Stack direction="row" spacing={1} sx={{ justifyContent: "space-between", flexWrap: "wrap", rowGap: 0.5 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {LOG_ACTION_LABELS[entry.action] ?? entry.action}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {formatDateTime(entry.createdAt)}
                </Typography>
              </Stack>
              <Typography variant="body2" color="text.secondary">
                From {statusLabel(entry.fromStatus)} to {statusLabel(entry.toStatus)}, by{" "}
                {entry.actor === "ADMIN" ? "the admin" : entry.actor === "PROFESSIONAL" ? "the professional" : entry.actor}
                {entry.source
                  ? `, source: ${VERIFICATION_SOURCE_LABELS[entry.source as VerificationSource] ?? entry.source}`
                  : ""}
                .
              </Typography>
              {entry.evidenceDocumentId && (
                <Typography variant="body2" color="text.secondary">
                  Evidence: {documentName(entry.evidenceDocumentId)}.
                </Typography>
              )}
              {entry.note && (
                <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                  {entry.note}
                </Typography>
              )}
            </Box>
          ))}
        </Stack>
      )}
    </Paper>
  );
}
