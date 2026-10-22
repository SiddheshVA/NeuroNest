import { Copy, Plus, X } from "lucide-react";
import { CheckboxField, SelectField, secondaryButtonClass } from "@/components/pro/pro-ui";
import { cn } from "@/lib/utils";
import {
  BUFFER_MINUTE_OPTIONS,
  END_TIME_OPTIONS,
  MAX_RANGES_PER_DAY,
  SESSION_MINUTE_OPTIONS,
  START_TIME_OPTIONS,
  WEEK_DAYS,
  WEEK_DAY_LABELS,
  formatTime,
  type WeekDay,
  type WeeklyDraft,
} from "@/lib/weekly-hours";

const START_OPTIONS = START_TIME_OPTIONS.map((t) => ({ value: t, label: formatTime(t) }));
const END_OPTIONS = END_TIME_OPTIONS.map((t) => ({ value: t, label: formatTime(t) }));
const WEEKDAYS_AFTER_MONDAY: WeekDay[] = ["TUE", "WED", "THU", "FRI"];

export interface WeeklyHoursErrors {
  /** A problem with the hours as a whole, such as no range at all. */
  general?: string;
  days: Partial<Record<WeekDay, string>>;
  sessionMinutes?: string;
  bufferMinutes?: string;
}

/**
 * The days and times a professional takes appointments, plus the session length and the gap between
 * sessions. Times are chosen from 15-minute steps shown in 12-hour form and kept as 24-hour "18:00".
 */
export function WeeklyHoursEditor({
  draft,
  onChange,
  sessionMinutes,
  onSessionMinutesChange,
  bufferMinutes,
  onBufferMinutesChange,
  errors,
  disabled,
}: {
  draft: WeeklyDraft;
  onChange: (next: WeeklyDraft) => void;
  sessionMinutes: string;
  onSessionMinutesChange: (value: string) => void;
  bufferMinutes: string;
  onBufferMinutesChange: (value: string) => void;
  errors: WeeklyHoursErrors;
  disabled?: boolean;
}) {
  const setDay = (day: WeekDay, next: WeeklyDraft[WeekDay]) => onChange({ ...draft, [day]: next });

  const toggleDay = (day: WeekDay, enabled: boolean) =>
    // Ticking a day offers one empty range; unticking clears its ranges.
    setDay(day, enabled ? { enabled: true, ranges: [{ start: "", end: "" }] } : { enabled: false, ranges: [] });

  const setRange = (day: WeekDay, index: number, field: "start" | "end", value: string) =>
    setDay(day, {
      ...draft[day],
      ranges: draft[day].ranges.map((r, i) => (i === index ? { ...r, [field]: value } : r)),
    });

  const addRange = (day: WeekDay) =>
    setDay(day, { ...draft[day], ranges: [...draft[day].ranges, { start: "", end: "" }] });

  const removeRange = (day: WeekDay, index: number) =>
    setDay(day, { ...draft[day], ranges: draft[day].ranges.filter((_, i) => i !== index) });

  const mondayHasRange = draft.MON.enabled && draft.MON.ranges.some((r) => r.start || r.end);
  const copyMonday = () => {
    const next = { ...draft };
    for (const day of WEEKDAYS_AFTER_MONDAY) {
      next[day] = { enabled: true, ranges: draft.MON.ranges.map((r) => ({ ...r })) };
    }
    onChange(next);
  };

  return (
    <div className="flex flex-col gap-4 sm:col-span-2" role="group" aria-labelledby="weekly-hours-label">
      <div className="flex flex-col gap-1">
        <span id="weekly-hours-label" className="text-sm font-medium text-foreground">
          Weekly availability
          <span aria-hidden="true" className="text-destructive">
            {" "}*
          </span>
          <span className="sr-only"> (required)</span>
        </span>
        <p className="text-xs text-muted-foreground">
          Choose the days and times when you take appointments. All times are Indian Standard Time.
        </p>
        {errors.general ? <p className="text-xs text-destructive">{errors.general}</p> : null}
      </div>

      <ul className="flex flex-col divide-y divide-border rounded-xl ring-1 ring-foreground/10">
        {WEEK_DAYS.map((day) => {
          const label = WEEK_DAY_LABELS[day];
          const { enabled, ranges } = draft[day];
          const error = errors.days[day];
          return (
            <li key={day} className="flex flex-col gap-3 px-4 py-3">
              <CheckboxField
                label={`Available on ${label}`}
                checked={enabled}
                onChange={(checked) => toggleDay(day, checked)}
                disabled={disabled}
              />
              {enabled ? (
                <div className="flex flex-col gap-3 pl-6.5">
                  {ranges.map((range, index) => (
                    <fieldset key={index} className="flex flex-col gap-3 sm:flex-row sm:items-end">
                      <legend className="sr-only">
                        {label}, time range {index + 1}
                      </legend>
                      <SelectField
                        label="From"
                        emptyLabel="Select time"
                        value={range.start}
                        onChange={(v) => setRange(day, index, "start", v)}
                        options={START_OPTIONS}
                        disabled={disabled}
                        className="sm:w-40"
                      />
                      <SelectField
                        label="To"
                        emptyLabel="Select time"
                        value={range.end}
                        onChange={(v) => setRange(day, index, "end", v)}
                        options={END_OPTIONS}
                        disabled={disabled}
                        className="sm:w-40"
                      />
                      <button
                        type="button"
                        className={cn(secondaryButtonClass, "h-10 self-start px-3 sm:self-auto")}
                        onClick={() => removeRange(day, index)}
                        disabled={disabled}
                        aria-label={`Remove ${label} time range ${index + 1}`}
                      >
                        <X className="size-4" aria-hidden="true" />
                        Remove
                      </button>
                    </fieldset>
                  ))}
                  {ranges.length < MAX_RANGES_PER_DAY ? (
                    <button
                      type="button"
                      className={cn(secondaryButtonClass, "h-9 self-start px-4")}
                      onClick={() => addRange(day)}
                      disabled={disabled}
                      aria-label={`Add another time range for ${label}`}
                    >
                      <Plus className="size-4" aria-hidden="true" />
                      Add another time range
                    </button>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      You can add up to {MAX_RANGES_PER_DAY} time ranges for each day.
                    </p>
                  )}
                </div>
              ) : null}
              {error ? <p className="text-xs text-destructive">{error}</p> : null}
              {day === "MON" ? (
                <button
                  type="button"
                  className={cn(secondaryButtonClass, "h-9 self-start px-4")}
                  onClick={copyMonday}
                  disabled={disabled || !mondayHasRange}
                >
                  <Copy className="size-4" aria-hidden="true" />
                  Copy Monday&apos;s hours to Tuesday through Friday
                </button>
              ) : null}
            </li>
          );
        })}
      </ul>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <SelectField
          label="Session length"
          required
          value={sessionMinutes}
          onChange={onSessionMinutesChange}
          options={SESSION_MINUTE_OPTIONS.map((m) => ({ value: String(m), label: `${m} minutes` }))}
          error={errors.sessionMinutes}
          helper="Every time range must be at least one session long."
          disabled={disabled}
        />
        <SelectField
          label="Gap between sessions"
          value={bufferMinutes}
          onChange={onBufferMinutesChange}
          options={BUFFER_MINUTE_OPTIONS.map((m) => ({ value: String(m), label: `${m} minutes` }))}
          error={errors.bufferMinutes}
          helper="Optional. Time to rest between two sessions. If you leave this empty, there is no gap."
          disabled={disabled}
        />
      </div>
    </div>
  );
}
