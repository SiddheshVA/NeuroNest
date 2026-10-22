import { useMemo } from "react";
import { Link as RouterLink, useLocation } from "react-router-dom";
import Button from "@mui/material/Button";
import ArrowBackRounded from "@mui/icons-material/ArrowBackRounded";
import { backTargetFor, useBackNavigation } from "@/lib/back-navigation";

/**
 * "Back to <label>" for the admin area, in Material UI, with the parent taken from the central map
 * (lib/back-navigation). Rendered once by AdminLayout above every page; nothing on pages without a parent.
 */
export function AdminBackLink() {
  const { pathname } = useLocation();
  const target = useMemo(() => backTargetFor(pathname), [pathname]);
  const onClick = useBackNavigation(target);
  if (!target) return null;
  return (
    <Button
      component={RouterLink}
      to={target.parentPath}
      onClick={onClick}
      startIcon={<ArrowBackRounded />}
      sx={{
        alignSelf: "flex-start",
        minHeight: 44,
        mb: 2,
        ml: -1,
        px: 1.5,
        // A clear ring for keyboard focus, on top of Material UI's ripple.
        "&.Mui-focusVisible": { outline: "2px solid", outlineColor: "primary.main", outlineOffset: 2 },
      }}
    >
      Back to {target.label}
    </Button>
  );
}
