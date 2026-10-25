import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { WEEK_DAY_LABELS, formatRange, orderedDays, type WeeklyHours } from "@/lib/weekly-hours";

// Read-only weekly hours for the admin pages: one row per day with hours (12-hour Indian Standard Time),
// then the session length and the gap between sessions. The professional edits these on their profile.
export function WeeklyHoursReadOnly({
  weeklyHours,
  sessionMinutes,
  bufferMinutes,
}: {
  weeklyHours: WeeklyHours | null | undefined;
  sessionMinutes: number | null | undefined;
  bufferMinutes: number | null | undefined;
}) {
  const days = orderedDays(weeklyHours);
  return (
    <Stack spacing={1}>
      {days.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          No weekly hours have been added.
        </Typography>
      ) : (
        <Table size="small" aria-label="Weekly availability in Indian Standard Time">
          <TableBody>
            {days.map(({ day, ranges }) => (
              <TableRow key={day}>
                <TableCell component="th" scope="row" sx={{ fontWeight: 600, width: 140, pl: 0, verticalAlign: "top" }}>
                  {WEEK_DAY_LABELS[day]}
                </TableCell>
                <TableCell sx={{ pr: 0 }}>
                  {ranges.map((range) => (
                    <Typography key={`${range.start}-${range.end}`} variant="body2" component="span" sx={{ display: "block" }}>
                      {formatRange(range)}
                    </Typography>
                  ))}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <Typography variant="body2">
        Session length: {sessionMinutes ? `${sessionMinutes} minutes` : "Not chosen"}.
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Break after each session: {bufferMinutes ?? 0} minutes (the same for every professional). All times are Indian Standard Time.
      </Typography>
    </Stack>
  );
}
