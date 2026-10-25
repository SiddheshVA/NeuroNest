import { useEffect, useRef, useState } from "react";
import { ImageUp, Loader2, Trash2, UserRound } from "lucide-react";
import { secondaryButtonClass } from "@/components/pro/pro-ui";
import { ProApiError } from "@/lib/pro-client";
import { cn } from "@/lib/utils";

// Mirrors ProPhotoService on the backend.
const ACCEPTED_TYPES = ["image/jpeg", "image/png", "image/webp"];
const MAX_BYTES = 2 * 1024 * 1024;

function photoProblem(file: File): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) return "Please choose a JPG, PNG or WebP image.";
  if (file.size > MAX_BYTES) return "The photo must be 2 MB or smaller. Please choose a smaller image.";
  return null;
}

/**
 * The profile photo: choose an image, see a preview, replace or remove it. The image is uploaded to
 * Cloudinary straight away and its URL goes into the form; it is stored when the form is saved.
 */
function PhotoUploadField({
  value,
  onChange,
  upload,
  disabled,
  blockedReason,
  error,
}: {
  value: string;
  onChange: (url: string) => void;
  upload: (file: File) => Promise<{ photoUrl: string }>;
  disabled?: boolean;
  /** When set, uploading is disabled and this sentence explains why (no professional account yet). */
  blockedReason?: string;
  error?: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [localPreview, setLocalPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => () => {
    if (localPreview) URL.revokeObjectURL(localPreview);
  }, [localPreview]);

  const onFileChosen = async (file: File | undefined) => {
    if (inputRef.current) inputRef.current.value = "";
    if (!file) return;
    const check = photoProblem(file);
    if (check) {
      setProblem(check);
      return;
    }
    setProblem(null);
    setLocalPreview(URL.createObjectURL(file));
    setBusy(true);
    try {
      const { photoUrl } = await upload(file);
      onChange(photoUrl);
    } catch (e) {
      if (e instanceof ProApiError && (e.kind === "unauthorized" || e.kind === "disabled")) return;
      setProblem(e instanceof Error ? e.message : "The photo could not be uploaded. Please try again.");
    } finally {
      setLocalPreview(null);
      setBusy(false);
    }
  };

  const preview = localPreview ?? (value || null);
  const locked = disabled || busy || Boolean(blockedReason);
  const shownError = problem ?? error;

  return (
    <div className="flex flex-col gap-3 sm:col-span-2">
      <span className="text-sm font-medium text-foreground">Profile photo (optional)</span>
      <div className="flex flex-wrap items-center gap-4">
        <div className="relative flex size-24 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted ring-1 ring-foreground/10">
          {preview ? (
            <img src={preview} alt="Your profile photo preview" className="size-full object-cover" />
          ) : (
            <UserRound className="size-10 text-muted-foreground" aria-hidden="true" />
          )}
          {busy ? (
            <span className="absolute inset-0 flex items-center justify-center bg-background/60">
              <Loader2 className="size-6 animate-spin" aria-label="Uploading" />
            </span>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" className={secondaryButtonClass} disabled={locked} onClick={() => inputRef.current?.click()}>
            <ImageUp className="size-4" aria-hidden="true" />
            {busy ? "Uploading" : value ? "Replace photo" : "Upload photo"}
          </button>
          {value && !busy ? (
            <button type="button" className={secondaryButtonClass} disabled={locked} onClick={() => onChange("")}>
              <Trash2 className="size-4" aria-hidden="true" />
              Remove
            </button>
          ) : null}
        </div>
        <input
          ref={inputRef}
          type="file"
          accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
          className="hidden"
          onChange={(e) => void onFileChosen(e.target.files?.[0])}
        />
      </div>
      <p className={cn("text-sm", shownError ? "text-destructive" : "text-muted-foreground")} role={shownError ? "alert" : undefined}>
        {shownError ??
          blockedReason ??
          "A clear, professional photo of you. JPG, PNG or WebP, up to 2 MB. Save your changes to keep the new photo."}
      </p>
    </div>
  );
}

export { PhotoUploadField };
