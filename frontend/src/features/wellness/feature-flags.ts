/**
 * What-if panel. Safe to keep on because what-if calls go through previewCheckin (api.ts), which always sends
 * POST /api/wellness/checkin?save=false: the backend then never stores anything, even for a signed-in user.
 * Switch off to hide the live panel (it then renders disabled).
 */
export const WHAT_IF_ENABLED = true;

/** Debounce for what-if re-runs. */
export const WHAT_IF_DEBOUNCE_MS = 400;
