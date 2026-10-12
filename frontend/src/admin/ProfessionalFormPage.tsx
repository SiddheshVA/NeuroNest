import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link as RouterLink, useLocation, useNavigate, useParams } from "react-router-dom";
import Alert from "@mui/material/Alert";
import Autocomplete from "@mui/material/Autocomplete";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Divider from "@mui/material/Divider";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Switch from "@mui/material/Switch";
import TextField, { type TextFieldProps } from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import ArrowBackRounded from "@mui/icons-material/ArrowBackRounded";
import { AdminApiError, createProfessional, getProfessional, updateProfessional } from "./admin-api";
import { formatDate, formatDateTime } from "./format";
import { SampleChip, VerificationStatusChip } from "./VerificationStatusChip";
import {
  CONSULTATION_MODE_LABELS,
  CONSULTATION_MODES,
  CREDENTIAL_TYPE_LABELS,
  CREDENTIAL_TYPES,
  REGISTRATION_BODIES,
  REGISTRATION_BODY_LABELS,
  type ConsultationMode,
  type CredentialType,
  type ProfessionalDetail,
  type ProfessionalRequest,
  type RegistrationBody,
} from "./types";

const RE_REVIEW_MESSAGE = "Because a verified detail changed, this listing now needs to be reviewed again.";
const MIN_REGISTRATION_YEAR = 1950;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Form values are kept as strings (numbers included) so fields can be empty or mid-edit.
interface FormValues {
  fullName: string;
  credentialType: CredentialType | "";
  qualifications: string;
  registrationBody: RegistrationBody | "";
  registrationNumber: string;
  registrationCouncil: string;
  registrationYear: string;
  registrationExpiry: string;
  bio: string;
  specialties: string[];
  languages: string[];
  city: string;
  state: string;
  consultationMode: ConsultationMode | "";
  feeMin: string;
  feeMax: string;
  yearsExperience: string;
  contactEmail: string;
  contactPhone: string;
  availabilityNote: string;
  photoUrl: string;
  isSampleData: boolean;
}

type FieldName = keyof FormValues;
type FieldErrors = Partial<Record<FieldName, string>>;

const EMPTY_FORM: FormValues = {
  fullName: "",
  credentialType: "",
  qualifications: "",
  registrationBody: "",
  registrationNumber: "",
  registrationCouncil: "",
  registrationYear: "",
  registrationExpiry: "",
  bio: "",
  specialties: [],
  languages: [],
  city: "",
  state: "",
  consultationMode: "",
  feeMin: "",
  feeMax: "",
  yearsExperience: "",
  contactEmail: "",
  contactPhone: "",
  availabilityNote: "",
  photoUrl: "",
  isSampleData: false,
};

const FIELD_NAMES = Object.keys(EMPTY_FORM) as FieldName[];

function toFormValues(p: ProfessionalDetail): FormValues {
  const text = (value: string | null) => value ?? "";
  const num = (value: number | null) => (value === null || value === undefined ? "" : String(value));
  return {
    fullName: p.fullName,
    credentialType: p.credentialType,
    qualifications: text(p.qualifications),
    registrationBody: p.registrationBody ?? "",
    registrationNumber: text(p.registrationNumber),
    registrationCouncil: text(p.registrationCouncil),
    registrationYear: num(p.registrationYear),
    registrationExpiry: text(p.registrationExpiry),
    bio: text(p.bio),
    specialties: [...p.specialties],
    languages: [...p.languages],
    city: text(p.city),
    state: text(p.state),
    consultationMode: p.consultationMode,
    feeMin: num(p.feeMin),
    feeMax: num(p.feeMax),
    yearsExperience: num(p.yearsExperience),
    contactEmail: p.contactEmail,
    contactPhone: text(p.contactPhone),
    availabilityNote: text(p.availabilityNote),
    photoUrl: text(p.photoUrl),
    isSampleData: p.isSampleData,
  };
}

function toRequest(v: FormValues): ProfessionalRequest {
  const text = (value: string) => (value.trim() === "" ? null : value.trim());
  const num = (value: string) => (value.trim() === "" ? null : Number(value));
  return {
    fullName: v.fullName.trim(),
    credentialType: v.credentialType,
    qualifications: text(v.qualifications),
    registrationBody: v.registrationBody === "" ? null : v.registrationBody,
    registrationNumber: text(v.registrationNumber),
    registrationCouncil: text(v.registrationCouncil),
    registrationYear: num(v.registrationYear),
    registrationExpiry: text(v.registrationExpiry),
    bio: text(v.bio),
    specialties: v.specialties,
    languages: v.languages,
    city: text(v.city),
    state: text(v.state),
    consultationMode: v.consultationMode,
    feeMin: num(v.feeMin),
    feeMax: num(v.feeMax),
    yearsExperience: num(v.yearsExperience),
    contactEmail: v.contactEmail.trim(),
    contactPhone: text(v.contactPhone),
    availabilityNote: text(v.availabilityNote),
    photoUrl: text(v.photoUrl),
    isSampleData: v.isSampleData,
  };
}

// Mirrors the backend's rules so most mistakes are caught before a round trip. The backend still
// validates everything; its 400 messages are shown as well.
function validate(v: FormValues): FieldErrors {
  const errors: FieldErrors = {};
  const currentYear = new Date().getFullYear();
  const wholeNumber = (field: FieldName, label: string, value: string) => {
    if (value.trim() === "") return;
    const n = Number(value);
    if (!Number.isInteger(n)) errors[field] = `${label} must be a whole number.`;
    else if (n < 0) errors[field] = `${label} cannot be negative.`;
  };

  if (!v.fullName.trim()) errors.fullName = "Full name is required.";
  if (!v.credentialType) errors.credentialType = "Credential type is required.";
  if (!v.consultationMode) errors.consultationMode = "Consultation mode is required.";
  if (!v.contactEmail.trim()) errors.contactEmail = "Contact email is required.";
  else if (!EMAIL_PATTERN.test(v.contactEmail.trim())) errors.contactEmail = "Enter a valid email address.";

  wholeNumber("feeMin", "Minimum fee", v.feeMin);
  wholeNumber("feeMax", "Maximum fee", v.feeMax);
  wholeNumber("yearsExperience", "Years of experience", v.yearsExperience);
  if (!errors.feeMin && !errors.feeMax && v.feeMin.trim() && v.feeMax.trim() && Number(v.feeMin) > Number(v.feeMax)) {
    errors.feeMin = "Minimum fee cannot be more than the maximum fee.";
  }

  if (v.registrationYear.trim()) {
    const year = Number(v.registrationYear);
    if (!Number.isInteger(year) || year < MIN_REGISTRATION_YEAR || year > currentYear) {
      errors.registrationYear = `Registration year must be between ${MIN_REGISTRATION_YEAR} and ${currentYear}.`;
    }
  }
  if (v.photoUrl.trim() && !/^https?:\/\/\S+$/i.test(v.photoUrl.trim())) {
    errors.photoUrl = "Photo URL must start with http:// or https://.";
  }
  return errors;
}

// Backend messages start with the field name ("feeMin must be ..."), which lets the message also be
// shown under the matching field.
function fieldFromServerMessage(message: string): FieldName | null {
  const first = message.split(/[\s:]/, 1)[0];
  return (FIELD_NAMES as string[]).includes(first) ? (first as FieldName) : null;
}

export function ProfessionalFormPage() {
  const { id } = useParams<{ id: string }>();
  const isEdit = Boolean(id);
  const navigate = useNavigate();
  const location = useLocation();

  const [values, setValues] = useState<FormValues>(EMPTY_FORM);
  const [record, setRecord] = useState<ProfessionalDetail | null>(null);
  const [loading, setLoading] = useState(isEdit);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(
    (location.state as { created?: boolean } | null)?.created
      ? "The professional has been added as an inactive draft."
      : null,
  );
  const [reReviewRequired, setReReviewRequired] = useState(false);
  const topRef = useRef<HTMLDivElement>(null);

  // The "added" message comes through navigation state; drop it so a reload doesn't show it again.
  useEffect(() => {
    if ((location.state as { created?: boolean } | null)?.created) {
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location.state, location.pathname, navigate]);

  // The page is keyed by id (AdminRoutes), so the initial state already fits this record.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    getProfessional(id)
      .then((detail) => {
        if (cancelled) return;
        setRecord(detail);
        setValues(toFormValues(detail));
      })
      .catch((e) => {
        if (cancelled || (e instanceof AdminApiError && e.status === 401)) return;
        setLoadError(
          e instanceof AdminApiError && e.status === 404
            ? "This professional could not be found. It may have been removed."
            : e instanceof Error
              ? e.message
              : "The professional could not be loaded.",
        );
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  const set = <K extends FieldName>(field: K, value: FormValues[K]) => {
    setValues((current) => ({ ...current, [field]: value }));
    setErrors((current) => {
      if (!current[field] && !(field === "feeMax" && current.feeMin)) return current;
      const next = { ...current };
      delete next[field];
      if (field === "feeMax") delete next.feeMin;
      return next;
    });
  };

  const scrollToTop = () => topRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setServerError(null);
    setSuccess(null);
    setReReviewRequired(false);

    const clientErrors = validate(values);
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length > 0) {
      setServerError("Please correct the highlighted fields and try again.");
      scrollToTop();
      return;
    }

    setSaving(true);
    try {
      if (id) {
        const result = await updateProfessional(id, toRequest(values));
        const { reReviewRequired: needsReview, ...detail } = result;
        setRecord(detail);
        setValues(toFormValues(detail));
        setReReviewRequired(needsReview);
        setSuccess("The changes have been saved.");
      } else {
        const created = await createProfessional(toRequest(values));
        navigate(`/admin/professionals/${created.id}/edit`, { replace: true, state: { created: true } });
        return;
      }
    } catch (e) {
      if (e instanceof AdminApiError && e.status === 401) return;
      const message = e instanceof Error ? e.message : "The professional could not be saved.";
      setServerError(message);
      const field = e instanceof AdminApiError && e.status === 400 ? fieldFromServerMessage(message) : null;
      if (field) setErrors({ [field]: message });
    } finally {
      setSaving(false);
    }
    scrollToTop();
  };

  if (loading) {
    return (
      <Box sx={{ display: "grid", placeItems: "center", py: 10 }}>
        <CircularProgress aria-label="Loading the professional" />
      </Box>
    );
  }

  if (loadError) {
    return (
      <Stack spacing={2}>
        <BackLink />
        <Alert severity="error">{loadError}</Alert>
      </Stack>
    );
  }

  const field = (name: FieldName, label: string, props: TextFieldProps = {}) => (
    <TextField
      fullWidth
      {...props}
      label={label}
      value={values[name] as string}
      onChange={(e) => set(name, e.target.value as never)}
      error={Boolean(errors[name])}
      helperText={errors[name] ?? props.helperText}
    />
  );

  return (
    <Stack spacing={3} ref={topRef}>
      <BackLink />
      <Stack direction="row" spacing={1.5} sx={{ alignItems: "center", flexWrap: "wrap" }}>
        <Typography variant="h4" component="h1" sx={{ fontWeight: 700 }}>
          {isEdit ? "Edit professional" : "Add professional"}
        </Typography>
        {record?.isSampleData && <SampleChip />}
      </Stack>

      {reReviewRequired && <Alert severity="warning">{RE_REVIEW_MESSAGE}</Alert>}
      {success && <Alert severity="success" onClose={() => setSuccess(null)}>{success}</Alert>}
      {serverError && <Alert severity="error">{serverError}</Alert>}

      <Box
        sx={{
          display: "grid",
          gap: 3,
          gridTemplateColumns: { xs: "1fr", md: "minmax(0, 1fr) 320px" },
          alignItems: "start",
        }}
      >
        <Paper component="form" onSubmit={submit} noValidate sx={{ p: { xs: 2, sm: 3 } }}>
          <Stack spacing={4}>
            <Section title="Basic details">
              {field("fullName", "Full name", { required: true })}
              {field("credentialType", "Credential type", {
                select: true,
                required: true,
                children: CREDENTIAL_TYPES.map((type) => (
                  <MenuItem key={type} value={type}>
                    {CREDENTIAL_TYPE_LABELS[type]}
                  </MenuItem>
                )),
              })}
              {field("consultationMode", "Consultation mode", {
                select: true,
                required: true,
                children: CONSULTATION_MODES.map((mode) => (
                  <MenuItem key={mode} value={mode}>
                    {CONSULTATION_MODE_LABELS[mode]}
                  </MenuItem>
                )),
              })}
              {field("qualifications", "Qualifications", { helperText: "For example: M.Phil in Clinical Psychology." })}
              {field("yearsExperience", "Years of experience", { type: "number", slotProps: { htmlInput: { min: 0, step: 1 } } })}
            </Section>

            <Section title="Contact">
              {field("contactEmail", "Contact email", { required: true, type: "email", autoComplete: "off" })}
              {field("contactPhone", "Contact phone", { type: "tel" })}
            </Section>

            <Section title="Registration">
              {field("registrationBody", "Registration body", {
                select: true,
                children: [
                  <MenuItem key="none" value="">
                    Not specified
                  </MenuItem>,
                  ...REGISTRATION_BODIES.map((body) => (
                    <MenuItem key={body} value={body}>
                      {REGISTRATION_BODY_LABELS[body]}
                    </MenuItem>
                  )),
                ],
              })}
              {field("registrationNumber", "Registration number")}
              {field("registrationCouncil", "Registration council", {
                helperText: "For example: Maharashtra Medical Council.",
              })}
              {field("registrationYear", "Registration year", {
                type: "number",
                slotProps: { htmlInput: { min: MIN_REGISTRATION_YEAR, max: new Date().getFullYear(), step: 1 } },
              })}
              {field("registrationExpiry", "Registration expiry", {
                type: "date",
                slotProps: { inputLabel: { shrink: true } },
              })}
            </Section>

            <Section title="Practice">
              <ChipListField
                label="Specialties"
                values={values.specialties}
                onChange={(list) => set("specialties", list)}
              />
              <ChipListField
                label="Languages"
                values={values.languages}
                onChange={(list) => set("languages", list)}
              />
              {field("city", "City")}
              {field("state", "State")}
              {field("feeMin", "Minimum fee (₹)", { type: "number", slotProps: { htmlInput: { min: 0, step: 1 } } })}
              {field("feeMax", "Maximum fee (₹)", { type: "number", slotProps: { htmlInput: { min: 0, step: 1 } } })}
              {field("availabilityNote", "Availability note", {
                helperText: "For example: Weekday evenings and Saturday mornings.",
              })}
            </Section>

            <Section title="Profile" columns={1}>
              {field("bio", "Bio", { multiline: true, minRows: 4 })}
              {field("photoUrl", "Photo URL", { type: "url", helperText: "A link that starts with https://." })}
              <FormControlLabel
                control={
                  <Switch
                    checked={values.isSampleData}
                    onChange={(e) => set("isSampleData", e.target.checked)}
                  />
                }
                label="This is sample data, not a real professional."
              />
            </Section>

            <Divider />
            <Stack direction="row" spacing={2} sx={{ justifyContent: "flex-end" }}>
              <Button component={RouterLink} to="/admin/professionals" disabled={saving}>
                Cancel
              </Button>
              <Button type="submit" variant="contained" disabled={saving}>
                {saving ? "Saving" : isEdit ? "Save changes" : "Add professional"}
              </Button>
            </Stack>
          </Stack>
        </Paper>

        <VerificationPanel record={record} />
      </Box>
    </Stack>
  );
}

function BackLink() {
  return (
    <Box>
      <Button component={RouterLink} to="/admin/professionals" startIcon={<ArrowBackRounded />} size="small">
        Back to professionals
      </Button>
    </Box>
  );
}

function Section({ title, children, columns = 2 }: { title: string; children: ReactNode; columns?: 1 | 2 }) {
  return (
    <Stack spacing={2}>
      <Typography variant="h6" component="h2" sx={{ fontWeight: 600 }}>
        {title}
      </Typography>
      <Box
        sx={{
          display: "grid",
          gap: 2,
          gridTemplateColumns: { xs: "1fr", sm: columns === 2 ? "1fr 1fr" : "1fr" },
        }}
      >
        {children}
      </Box>
    </Stack>
  );
}

// Free-text chip input: type a value and press Enter (or leave the field) to add it.
function ChipListField({
  label,
  values,
  onChange,
}: {
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <Autocomplete
      multiple
      freeSolo
      autoSelect
      options={[] as string[]}
      value={values}
      onChange={(_, next) => {
        const cleaned: string[] = [];
        for (const item of next) {
          const trimmed = String(item).trim();
          if (trimmed && !cleaned.some((existing) => existing.toLowerCase() === trimmed.toLowerCase())) {
            cleaned.push(trimmed);
          }
        }
        onChange(cleaned);
      }}
      renderInput={(params) => (
        <TextField {...params} label={label} helperText="Type a value and press Enter to add it." />
      )}
    />
  );
}

function VerificationPanel({ record }: { record: ProfessionalDetail | null }) {
  return (
    <Paper sx={{ p: 3, position: { md: "sticky" }, top: { md: 88 } }}>
      <Stack spacing={2}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 600 }}>
          Verification
        </Typography>
        <InfoRow label="Verification status">
          <VerificationStatusChip status={record?.verificationStatus ?? "DRAFT"} />
        </InfoRow>
        <InfoRow label="Active">
          <Typography variant="body2">
            {record?.active ? "Yes, this listing is active." : "No, this listing is inactive."}
          </Typography>
        </InfoRow>
        <InfoRow label="Verified by">
          <ValueOrMissing value={record?.verifiedBy} missing="Not verified yet." />
        </InfoRow>
        <InfoRow label="Verified at">
          <ValueOrMissing value={formatDateTime(record?.verifiedAt)} missing="Not verified yet." />
        </InfoRow>
        <InfoRow label="Verification notes">
          <ValueOrMissing value={record?.verificationNotes} missing="There are no notes." />
        </InfoRow>
        {record?.nextRecheckDate && (
          <InfoRow label="Next recheck date">
            <Typography variant="body2">{formatDate(record.nextRecheckDate)}</Typography>
          </InfoRow>
        )}
        {!record && (
          <Typography variant="body2" color="text.secondary">
            New listings start as inactive drafts.
          </Typography>
        )}
        <Alert severity="info" variant="outlined">
          Verification actions will be added in a later step.
        </Alert>
      </Stack>
    </Paper>
  );
}

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 0.5 }}>
        {label}
      </Typography>
      {children}
    </Box>
  );
}

function ValueOrMissing({ value, missing }: { value: string | null | undefined; missing: string }) {
  return value ? (
    <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
      {value}
    </Typography>
  ) : (
    <Typography variant="body2" color="text.secondary">
      {missing}
    </Typography>
  );
}
