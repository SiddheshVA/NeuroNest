import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ContentCopyRounded from "@mui/icons-material/ContentCopyRounded";
import OpenInNewRounded from "@mui/icons-material/OpenInNewRounded";
import type { CredentialType } from "./types";

// Each register's home page only: no deep links, scraping or embedding.
const REGISTERS: Partial<Record<CredentialType, { label: string; href: string }>> = {
  PSYCHIATRIST: { label: "Open NMC Indian Medical Register", href: "https://www.nmc.org.in" },
  PSYCHOLOGIST: { label: "Open RCI register", href: "https://www.rehabcouncil.nic.in" },
};

export function RegisterShortcutsCard({
  credentialType,
  registrationNumber,
  onCopied,
  onCopyFailed,
}: {
  credentialType: CredentialType;
  registrationNumber: string | null;
  onCopied: () => void;
  onCopyFailed: () => void;
}) {
  const register = REGISTERS[credentialType];

  const copy = async () => {
    if (!registrationNumber) return;
    try {
      await navigator.clipboard.writeText(registrationNumber);
      onCopied();
    } catch {
      onCopyFailed();
    }
  };

  return (
    <Paper sx={{ p: 2.5 }}>
      <Stack spacing={2}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
          Check the official register
        </Typography>
        {register ? (
          <>
            <Typography variant="body2" color="text.secondary">
              Search the registration number on the official site, then tick the checklist in the next step.
            </Typography>
            <Button
              variant="outlined"
              endIcon={<OpenInNewRounded />}
              href={register.href}
              target="_blank"
              rel="noopener noreferrer"
              sx={{ alignSelf: "flex-start" }}
            >
              {register.label}
            </Button>
          </>
        ) : (
          <Typography variant="body2" color="text.secondary">
            Counselors are verified by their degree certificate. There is no national register.
          </Typography>
        )}
        <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", flexWrap: "wrap", rowGap: 1 }}>
          <Typography variant="body2">
            <Typography component="span" variant="body2" color="text.secondary">
              Registration number:{" "}
            </Typography>
            {registrationNumber ?? "Not provided"}
          </Typography>
          {registrationNumber && (
            <Button size="small" startIcon={<ContentCopyRounded />} onClick={() => void copy()}>
              Copy registration number
            </Button>
          )}
        </Stack>
      </Stack>
    </Paper>
  );
}
