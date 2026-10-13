import { useId, useState, type ReactNode } from "react";
import { Ban, X } from "lucide-react";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { DISABLED_ACCOUNT_MESSAGE } from "@/lib/pro-client";

/** Small form building blocks for the professional pages, styled like the rest of the public site. */

const controlClass =
  "w-full min-w-0 rounded-xl border border-input bg-card px-3 py-2 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20";

const primaryButtonClass =
  "inline-flex h-10 items-center justify-center gap-2 rounded-full bg-primary px-5 text-sm font-medium text-primary-foreground outline-none transition-colors hover:bg-primary/85 focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50";
const secondaryButtonClass =
  "inline-flex h-10 items-center justify-center gap-2 rounded-full border border-border bg-card px-5 text-sm font-medium text-foreground outline-none transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50";

export { controlClass, primaryButtonClass, secondaryButtonClass };

export function FieldShell({
  id,
  label,
  required,
  helper,
  error,
  className,
  children,
}: {
  id: string;
  label: string;
  required?: boolean;
  helper?: ReactNode;
  error?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <label htmlFor={id} className="text-sm font-medium text-foreground">
        {label}
        {required ? (
          <>
            <span aria-hidden="true" className="text-destructive">
              {" "}*
            </span>
            <span className="sr-only"> (required)</span>
          </>
        ) : null}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-destructive">
          {error}
        </p>
      ) : helper ? (
        <p id={`${id}-helper`} className="text-xs text-muted-foreground">
          {helper}
        </p>
      ) : null}
    </div>
  );
}

type CommonProps = {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  disabled?: boolean;
  helper?: ReactNode;
  error?: string;
  className?: string;
};

/**
 * A text input. With `digits` set it accepts only digits (up to that many): it is a plain text field
 * with a numeric keyboard, so there is no spinner and the mouse wheel cannot change it. Placeholders
 * are only examples and are never sent as values. Browser autofill is off.
 */
export function TextField({
  type = "text",
  placeholder,
  inputMode,
  digits,
  ...props
}: CommonProps & {
  type?: string;
  placeholder?: string;
  inputMode?: "numeric" | "tel" | "url" | "email";
  digits?: number;
}) {
  const id = useId();
  return (
    <FieldShell id={id} {...props}>
      <input
        id={id}
        type={digits ? "text" : type}
        inputMode={digits ? "numeric" : inputMode}
        pattern={digits ? "[0-9]*" : undefined}
        maxLength={digits}
        autoComplete="off"
        value={props.value}
        placeholder={placeholder}
        disabled={props.disabled}
        onChange={(e) => props.onChange(digits ? e.target.value.replace(/[^0-9]/g, "").slice(0, digits) : e.target.value)}
        aria-invalid={Boolean(props.error)}
        aria-describedby={props.error ? `${id}-error` : props.helper ? `${id}-helper` : undefined}
        className={controlClass}
      />
    </FieldShell>
  );
}

export function TextAreaField({ rows = 5, placeholder, ...props }: CommonProps & { rows?: number; placeholder?: string }) {
  const id = useId();
  return (
    <FieldShell id={id} {...props}>
      <textarea
        id={id}
        rows={rows}
        placeholder={placeholder}
        autoComplete="off"
        value={props.value}
        disabled={props.disabled}
        onChange={(e) => props.onChange(e.target.value)}
        aria-invalid={Boolean(props.error)}
        aria-describedby={props.error ? `${id}-error` : props.helper ? `${id}-helper` : undefined}
        className={cn(controlClass, "resize-y leading-relaxed")}
      />
    </FieldShell>
  );
}

/** A dropdown that always offers an empty "Select an option" choice, so nothing is chosen by default. */
export function SelectField({
  options,
  emptyLabel = "Select an option",
  ...props
}: CommonProps & { options: { value: string; label: string }[]; emptyLabel?: string }) {
  const id = useId();
  return (
    <FieldShell id={id} {...props}>
      <select
        id={id}
        value={props.value}
        disabled={props.disabled}
        onChange={(e) => props.onChange(e.target.value)}
        aria-invalid={Boolean(props.error)}
        aria-describedby={props.error ? `${id}-error` : props.helper ? `${id}-helper` : undefined}
        className={cn(controlClass, "h-10")}
      >
        <option value="">{emptyLabel}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </FieldShell>
  );
}

/** Free-text chips: type a value and press Enter or a comma to add it. */
export function ChipInputField({
  label,
  values,
  onChange,
  disabled,
  helper,
  error,
  placeholder,
  required,
}: {
  required?: boolean;
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
  disabled?: boolean;
  helper?: ReactNode;
  error?: string;
  placeholder?: string;
}) {
  const id = useId();
  const [draft, setDraft] = useState("");

  const add = (raw: string) => {
    const value = raw.trim();
    setDraft("");
    if (!value || values.some((existing) => existing.toLowerCase() === value.toLowerCase())) return;
    onChange([...values, value]);
  };

  return (
    <FieldShell id={id} label={label} required={required} helper={helper ?? "Type a value and press Enter to add it."} error={error}>
      <div
        className={cn(
          controlClass,
          "flex min-h-10 flex-wrap items-center gap-1.5 py-1.5",
          disabled && "cursor-not-allowed bg-muted",
          error && "border-destructive",
        )}
      >
        {values.map((value) => (
          <span
            key={value}
            className="inline-flex items-center gap-1 rounded-full bg-secondary py-0.5 pr-1 pl-2.5 text-xs font-medium text-secondary-foreground"
          >
            {value}
            {!disabled ? (
              <button
                type="button"
                onClick={() => onChange(values.filter((v) => v !== value))}
                className="flex size-4 items-center justify-center rounded-full hover:bg-foreground/10"
                aria-label={`Remove ${value}`}
              >
                <X className="size-3" aria-hidden="true" />
              </button>
            ) : null}
          </span>
        ))}
        <input
          id={id}
          value={draft}
          disabled={disabled}
          placeholder={values.length === 0 ? placeholder : undefined}
          onChange={(e) => {
            const next = e.target.value;
            if (next.endsWith(",")) add(next.slice(0, -1));
            else setDraft(next);
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              add(draft);
            } else if (e.key === "Backspace" && draft === "" && values.length > 0) {
              onChange(values.slice(0, -1));
            }
          }}
          onBlur={() => add(draft)}
          autoComplete="off"
          aria-invalid={Boolean(error)}
          className="min-w-[8rem] flex-1 bg-transparent py-0.5 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed"
        />
      </div>
    </FieldShell>
  );
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = "Cancel",
  busy,
  onConfirm,
  onOpenChange,
}: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <button type="button" className={secondaryButtonClass} onClick={() => onOpenChange(false)} disabled={busy}>
            {cancelLabel}
          </button>
          <button type="button" className={primaryButtonClass} onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Shown instead of everything else when the professional account is disabled. */
export function DisabledAccountNotice() {
  return (
    <div className="mx-auto flex max-w-xl items-start gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 p-6 text-destructive">
      <Ban className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <p className="text-sm font-medium sm:text-base">{DISABLED_ACCOUNT_MESSAGE}</p>
    </div>
  );
}

export function ErrorNotice({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
      <span>{message}</span>
      {onRetry ? (
        <button type="button" onClick={onRetry} className="font-medium underline-offset-4 hover:underline">
          Try again
        </button>
      ) : null}
    </div>
  );
}

export function LoadingBlock({ label }: { label: string }) {
  return (
    <div className="flex flex-col gap-4" aria-busy="true">
      <span className="sr-only">{label}</span>
      <div className="h-24 animate-pulse rounded-2xl bg-muted" aria-hidden="true" />
      <div className="h-72 animate-pulse rounded-2xl bg-muted" aria-hidden="true" />
    </div>
  );
}
