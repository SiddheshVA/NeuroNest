import { createTheme } from "@mui/material/styles";

// MUI theme for the admin area only, using the NeuroNest palette from globals.css (converted from
// oklch to hex, which MUI's colour helpers need). The public app does not use this theme.
export const adminTheme = createTheme({
  palette: {
    mode: "light",
    primary: { main: "#2a5938", contrastText: "#fbf9f0" },
    secondary: { main: "#55a181", contrastText: "#ffffff" },
    error: { main: "#ce5342" },
    warning: { main: "#ac713e" },
    background: { default: "#fbf9f0", paper: "#ffffff" },
    text: { primary: "#232c23", secondary: "#646457" },
    divider: "#e2ded3",
  },
  shape: { borderRadius: 10 },
  typography: {
    fontFamily: "var(--font-sans), Roboto, system-ui, sans-serif",
    button: { textTransform: "none", fontWeight: 600 },
  },
  components: {
    MuiAppBar: { defaultProps: { elevation: 0 } },
    MuiPaper: { defaultProps: { variant: "outlined" } },
  },
});
