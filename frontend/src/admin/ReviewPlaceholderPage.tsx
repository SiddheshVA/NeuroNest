import { Link as RouterLink } from "react-router-dom";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import ArrowBackRounded from "@mui/icons-material/ArrowBackRounded";

// Temporary page for /admin/review/:id, so the queue's Review button has somewhere to go. It is
// replaced by the real review page in the next step.
export function ReviewPlaceholderPage() {
  return (
    <Stack spacing={3}>
      <Button component={RouterLink} to="/admin/review" startIcon={<ArrowBackRounded />} sx={{ alignSelf: "flex-start" }}>
        Back to the review queue
      </Button>
      <Paper sx={{ p: 3 }}>
        <Typography>This review page is built in the next step.</Typography>
      </Paper>
    </Stack>
  );
}
