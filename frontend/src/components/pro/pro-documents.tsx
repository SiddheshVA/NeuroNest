import { useRef, useState } from "react";
import { CheckCircle2, Circle, FileText, Lock, Trash2, Upload } from "lucide-react";
import { ConfirmDialog, ErrorNotice, secondaryButtonClass } from "@/components/pro/pro-ui";
import { cn } from "@/lib/utils";
import {
  ACCEPTED_UPLOAD_TYPES,
  MAX_DOCUMENTS,
  PRO_DOCUMENT_TYPE_LABELS,
  ProApiError,
  checkUploadFile,
  formatFileSize,
  type ProCredentialType,
  type ProDocument,
  type ProDocumentType,
  type ProMe,
  type useProApi,
} from "@/lib/pro-client";

type ProApi = ReturnType<typeof useProApi>["api"];

// The three identity and qualification documents, always shown in this order, then "Additional document".
const DOCUMENT_CARDS: { type: ProDocumentType; hint: string }[] = [
  { type: "REGISTRATION_CERTIFICATE", hint: "RCI, NMC or State Medical Council registration" },
  { type: "DEGREE_CERTIFICATE", hint: "M.Phil Psychology, MD Psychiatry or equivalent" },
  { type: "ID_PROOF", hint: "Passport, PAN card, driving licence or Aadhaar with the first 8 digits masked" },
  { type: "OTHER", hint: "Any other supporting document, if you have one" },
];

function formatDate(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString(undefined, { dateStyle: "medium" });
}

/**
 * Required-document checklist, uploads and the list of uploaded files. Only file names and details
 * are shown: no link or address to any file is ever displayed or built.
 */
function ProDocuments({
  me,
  api,
  onChanged,
  credentialType,
}: {
  me: ProMe;
  api: ProApi;
  onChanged: () => Promise<void>;
  /** The credential type currently selected in the form, which may not be saved yet. */
  credentialType: ProCredentialType | "";
}) {
  const editable = me.canManageDocuments;
  const inputRef = useRef<HTMLInputElement>(null);
  const [pendingType, setPendingType] = useState<ProDocumentType | null>(null);
  const [busyType, setBusyType] = useState<ProDocumentType | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [toDelete, setToDelete] = useState<ProDocument | null>(null);
  const [deleting, setDeleting] = useState(false);

  const atLimit = me.documents.length >= MAX_DOCUMENTS;
  const uploadedTypes = new Set(me.documents.map((d) => d.docType));
  // Which documents are required comes only from the backend's rules (the registration certificate is
  // optional only for counselors). They follow the credential type selected in the form; until one is
  // chosen, every document that any credential type requires is shown as required.
  const rule = credentialType ? me.documentRules[credentialType] : undefined;
  const required = new Set<ProDocumentType>(
    rule ? rule.required : Object.values(me.documentRules).flatMap((r) => r.required),
  );
  const optional = new Set<ProDocumentType>(rule ? rule.optional : []);

  const chooseFile = (type: ProDocumentType) => {
    setError(null);
    setSuccess(null);
    setPendingType(type);
    inputRef.current?.click();
  };

  const onFileChosen = async (file: File | undefined) => {
    const type = pendingType;
    if (inputRef.current) inputRef.current.value = "";
    if (!file || !type) return;
    const problem = await checkUploadFile(file);
    if (problem) {
      setError(problem);
      return;
    }
    setBusyType(type);
    try {
      await api.uploadDocument(type, file);
      setSuccess(`${PRO_DOCUMENT_TYPE_LABELS[type]} has been uploaded.`);
      await onChanged();
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      setError(e instanceof Error ? e.message : "The upload failed. Please try again.");
    } finally {
      setBusyType(null);
    }
  };

  const confirmDelete = async () => {
    if (!toDelete) return;
    setDeleting(true);
    setError(null);
    setSuccess(null);
    try {
      await api.deleteDocument(toDelete.id);
      setSuccess("The document has been deleted.");
      setToDelete(null);
      await onChanged();
    } catch (e) {
      setToDelete(null);
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      setError(e instanceof Error ? e.message : "The document could not be deleted. Please try again.");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <section className="flex flex-col gap-5 rounded-2xl bg-card p-6 ring-1 ring-foreground/10 sm:p-8" aria-labelledby="documents-heading">
      <div className="flex flex-col gap-1">
        <h2 id="documents-heading" className="font-heading text-xl font-semibold text-foreground">
          Documents
        </h2>
        <p className="flex items-start gap-2 text-sm text-muted-foreground">
          <Lock className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          Your documents are stored privately and are only seen by the NeuroNest admin for verification.
        </p>
        <p className="text-sm text-muted-foreground">Accepted files are PDF, JPG and PNG, up to 5 MB each.</p>
      </div>

      {!editable ? (
        <p className="rounded-xl bg-muted/60 px-4 py-3 text-sm text-muted-foreground">
          Documents can only be changed while your profile is a draft or when the NeuroNest team asks for more
          information.
        </p>
      ) : null}
      {error ? <ErrorNotice message={error} /> : null}
      {success ? (
        <p className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-100" role="status">
          {success}
        </p>
      ) : null}

      <input
        ref={inputRef}
        type="file"
        accept={ACCEPTED_UPLOAD_TYPES}
        className="hidden"
        onChange={(e) => void onFileChosen(e.target.files?.[0])}
      />

      <ul className="flex flex-col divide-y divide-border rounded-xl ring-1 ring-foreground/10">
        {DOCUMENT_CARDS.map(({ type, hint }) => {
          const isRequired = required.has(type);
          const done = uploadedTypes.has(type);
          return (
            <li key={type} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <span className="flex items-center gap-2.5 text-sm">
                {isRequired ? (
                  done ? (
                    <CheckCircle2 className="size-5 text-emerald-600" aria-hidden="true" />
                  ) : (
                    <Circle className="size-5 text-muted-foreground" aria-hidden="true" />
                  )
                ) : (
                  <FileText className="size-5 text-muted-foreground" aria-hidden="true" />
                )}
                <span>
                  <span className="font-medium text-foreground">
                    {PRO_DOCUMENT_TYPE_LABELS[type]}
                    {isRequired ? (
                      <>
                        <span aria-hidden="true" className="text-destructive">
                          {" "}*
                        </span>
                        <span className="sr-only"> (required)</span>
                      </>
                    ) : optional.has(type) ? (
                      <span className="font-normal text-muted-foreground"> (optional)</span>
                    ) : null}
                  </span>
                  <span className="block text-xs text-muted-foreground">{hint}.</span>
                  {isRequired || done ? (
                    <span className="block text-xs text-muted-foreground">
                      {done ? "This document has been uploaded." : "This document has not been uploaded yet."}
                    </span>
                  ) : null}
                </span>
              </span>
              <button
                type="button"
                className={secondaryButtonClass}
                disabled={!editable || atLimit || busyType !== null}
                onClick={() => chooseFile(type)}
              >
                <Upload className="size-4" aria-hidden="true" />
                {busyType === type ? "Uploading" : done && type !== "OTHER" ? "Upload another" : "Upload"}
              </button>
            </li>
          );
        })}
      </ul>
      {atLimit && editable ? (
        <p className="text-sm text-muted-foreground">
          You have reached the limit of {MAX_DOCUMENTS} documents. Delete one before uploading another.
        </p>
      ) : null}

      <div className="flex flex-col gap-2">
        <h3 className="text-sm font-semibold text-foreground">Uploaded files</h3>
        {me.documents.length === 0 ? (
          <p className="text-sm text-muted-foreground">You have not uploaded any documents yet.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {me.documents.map((doc) => (
              <li key={doc.id} className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-muted/50 px-4 py-3">
                <span className="flex min-w-0 items-center gap-2.5">
                  <FileText className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-medium text-foreground">
                      {doc.originalFilename ?? "Unnamed file"}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {PRO_DOCUMENT_TYPE_LABELS[doc.docType]}, {formatFileSize(doc.sizeBytes)}, uploaded on{" "}
                      {formatDate(doc.uploadedAt)}
                    </span>
                  </span>
                </span>
                <button
                  type="button"
                  className={cn(secondaryButtonClass, "h-8 px-3 text-destructive")}
                  disabled={!editable}
                  onClick={() => setToDelete(doc)}
                  aria-label={`Delete ${doc.originalFilename ?? "this file"}`}
                >
                  <Trash2 className="size-4" aria-hidden="true" />
                  Delete
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <ConfirmDialog
        open={toDelete !== null}
        title="Delete this document?"
        description={`${toDelete?.originalFilename ?? "This file"} will be removed permanently.`}
        confirmLabel={deleting ? "Deleting" : "Delete document"}
        busy={deleting}
        onConfirm={() => void confirmDelete()}
        onOpenChange={(open) => !open && !deleting && setToDelete(null)}
      />
    </section>
  );
}

export { ProDocuments };
