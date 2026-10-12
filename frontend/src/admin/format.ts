// Display helpers for the admin pages.

// "2030-03-31" (a date with no time) shown in the admin's locale without a timezone shift.
export function formatDate(isoDate: string | null | undefined): string | null {
  if (!isoDate) return null;
  const [year, month, day] = isoDate.split("-").map(Number);
  if (!year || !month || !day) return isoDate;
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

export function formatDateTime(isoInstant: string | null | undefined): string | null {
  if (!isoInstant) return null;
  const date = new Date(isoInstant);
  return Number.isNaN(date.getTime())
    ? isoInstant
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function isPastDate(isoDate: string | null | undefined): boolean {
  if (!isoDate) return false;
  const today = new Date();
  const todayIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
  return isoDate < todayIso;
}
