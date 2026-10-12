import { useCallback, useState, type ReactNode } from "react";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";

export const toggleSx = {
  color: "var(--muted-foreground)",
  "&:hover": { color: "var(--foreground)", backgroundColor: "var(--muted)" },
  "&.Mui-focusVisible": { outline: "2px solid var(--ring)", outlineOffset: 2 },
} as const;

const pressedSx = {
  ...toggleSx,
  color: "var(--primary)",
  backgroundColor: "color-mix(in oklch, var(--primary) 10%, transparent)",
} as const;

/**
 * Icon-only header button: always has an aria-label and a tooltip. Pass
 * `pressed` for an on/off toggle (sets aria-pressed and the active colour).
 */
export function NuroPanelToggle({
  label,
  tooltip = label,
  pressed,
  onClick,
  children,
}: {
  label: string;
  tooltip?: string;
  pressed?: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <Tooltip title={tooltip}>
      <IconButton
        aria-label={label}
        aria-pressed={pressed}
        onClick={onClick}
        size="small"
        sx={pressed ? pressedSx : toggleSx}
      >
        {children}
      </IconButton>
    </Tooltip>
  );
}

function readStoredOpen(key: string): boolean {
  try {
    const stored = window.localStorage.getItem(key);
    return stored === null ? true : stored !== "closed";
  } catch {
    return true;
  }
}

/**
 * Desktop open/closed state of a side panel, remembered across reloads.
 * Defaults to open when storage is empty or unavailable.
 */
export function usePersistentPanelOpen(key: string) {
  const [open, setOpenState] = useState(() => readStoredOpen(key));

  const setOpen = useCallback(
    (next: boolean) => {
      setOpenState(next);
      try {
        window.localStorage.setItem(key, next ? "open" : "closed");
      } catch {
        // Storage blocked (private mode etc.): the panel still toggles for this visit.
      }
    },
    [key],
  );

  return [open, setOpen] as const;
}
