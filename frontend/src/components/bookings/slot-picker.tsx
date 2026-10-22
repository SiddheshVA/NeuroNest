import { useState } from "react";

import { addDays, formatIstDay, formatIstTime } from "@/lib/ist-time";
import { getProfessionalAvailability } from "@/lib/professionals-client";
import { cn } from "@/lib/utils";

/** Days with their free slot start times (ISO with +05:30); only days that have slots. */
export type SlotDays = { date: string; slots: string[] }[];

// The availability endpoint's longest range, in days.
const MAX_RANGE = 31;

/** Loads [from, to] (YYYY-MM-DD, both included) in pieces the public availability endpoint accepts. */
export async function loadSlotDays(professionalId: string, from: string, to: string, signal?: AbortSignal): Promise<SlotDays> {
  const days: SlotDays = [];
  let start = from;
  while (start <= to) {
    const end = addDays(start, MAX_RANGE - 1) < to ? addDays(start, MAX_RANGE - 1) : to;
    const result = await getProfessionalAvailability(professionalId, start, end, signal);
    days.push(...result.days.filter((day) => day.slots.length > 0));
    start = addDays(end, 1);
  }
  return days;
}

/** Day chips, then 12-hour time chips for the chosen day. Nothing is chosen at first. */
export function SlotPicker({
  days,
  value,
  onChange,
  label,
  helper,
  error,
  disabled,
}: {
  days: SlotDays;
  value: string;
  onChange: (slot: string) => void;
  label: string;
  helper?: string;
  error?: string;
  disabled?: boolean;
}) {
  const [day, setDay] = useState(() => days.find((d) => d.slots.includes(value))?.date ?? "");
  const chosenDay = days.find((d) => d.date === day);

  return (
    <fieldset className="flex flex-col gap-3" disabled={disabled}>
      <legend className="text-sm font-medium text-foreground">{label}</legend>
      {helper ? <p className="-mt-1 text-xs text-muted-foreground">{helper}</p> : null}
      <div className="flex flex-wrap gap-2" role="group" aria-label={`${label}: day`}>
        {days.map((d) => (
          <button
            key={d.date}
            type="button"
            aria-pressed={d.date === day}
            onClick={() => {
              setDay(d.date);
              if (!d.slots.includes(value)) onChange("");
            }}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm ring-1 transition-colors disabled:cursor-not-allowed disabled:opacity-40",
              d.date === day ? "bg-primary text-primary-foreground ring-primary" : "bg-card text-foreground ring-border hover:bg-muted",
            )}
          >
            {formatIstDay(d.date)}
          </button>
        ))}
      </div>
      {chosenDay ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap gap-2" role="group" aria-label={`${label}: time`}>
            {chosenDay.slots.map((slot) => (
              <button
                key={slot}
                type="button"
                aria-pressed={slot === value}
                onClick={() => onChange(slot === value ? "" : slot)}
                className={cn(
                  "rounded-full px-3 py-1.5 text-sm tabular-nums ring-1 transition-colors disabled:cursor-not-allowed disabled:opacity-40",
                  slot === value ? "bg-primary text-primary-foreground ring-primary" : "bg-card text-foreground ring-border hover:bg-muted",
                )}
              >
                {formatIstTime(slot)}
              </button>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">Times are in Indian Standard Time.</p>
        </div>
      ) : null}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </fieldset>
  );
}
