import { WEEK_DAY_LABELS, formatRange, orderedDays, type WeeklyHours } from "@/lib/weekly-hours";

/**
 * The days and times a professional takes appointments, one row per day with hours, in 12-hour Indian
 * Standard Time. The gap between sessions is never shown publicly.
 */
function WeeklyAvailabilityTable({ hours, sessionMinutes }: { hours: WeeklyHours | null | undefined; sessionMinutes: number | null }) {
  const days = orderedDays(hours);
  if (days.length === 0) {
    return <p className="text-sm text-muted-foreground">Weekly hours are not listed yet.</p>;
  }
  return (
    <div className="flex flex-col gap-2">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">Weekly availability in Indian Standard Time</caption>
        <thead className="sr-only">
          <tr>
            <th scope="col">Day</th>
            <th scope="col">Hours</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {days.map(({ day, ranges }) => (
            <tr key={day} className="align-top">
              <th scope="row" className="py-2 pr-4 font-medium text-foreground">
                {WEEK_DAY_LABELS[day]}
              </th>
              <td className="py-2 text-foreground">
                {ranges.map((range) => (
                  <span key={`${range.start}-${range.end}`} className="block">
                    {formatRange(range)}
                  </span>
                ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-muted-foreground">
        {sessionMinutes ? `Session length: ${sessionMinutes} minutes. ` : null}All times are Indian Standard Time.
      </p>
    </div>
  );
}

export { WeeklyAvailabilityTable };
