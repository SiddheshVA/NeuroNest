/**
 * What-if panel. OFF until the backend has a way to score a check-in WITHOUT saving it.
 *
 * POST /api/wellness/checkin always stores the check-in for a signed-in user and has no "don't save" option
 * (backend/docs/wellness-checkin.md, WellnessCheckinService), so every slider move would create a saved
 * check-in. Turn this on only once such an option exists and api.ts uses it for what-if calls.
 */
export const WHAT_IF_ENABLED = false;

/** Debounce for what-if re-runs once enabled. */
export const WHAT_IF_DEBOUNCE_MS = 400;
