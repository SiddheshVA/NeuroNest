import { ReplayRounded, VolumeOffRounded, VolumeUpRounded } from "@mui/icons-material";
import { Button, IconButton, Stack, Tooltip } from "@mui/material";

import type { Narration } from "@/lib/relaxation/use-narration";

/** Mute toggle and "replay step" for step narration. Renders nothing if speech isn't supported. */
function MindfulResetNarrationControls({ narration }: { narration: Narration }) {
  if (!narration.supported) {
    return null;
  }

  const muteLabel = narration.muted ? "Turn narration on" : "Mute narration";

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center", justifyContent: "center" }}>
      <Tooltip title={muteLabel}>
        <IconButton
          aria-label={muteLabel}
          aria-pressed={narration.muted}
          onClick={narration.toggleMute}
          size="small"
        >
          {narration.muted ? <VolumeOffRounded fontSize="small" /> : <VolumeUpRounded fontSize="small" />}
        </IconButton>
      </Tooltip>
      <Button
        onClick={narration.replay}
        disabled={!narration.canReplay}
        size="small"
        startIcon={<ReplayRounded fontSize="small" />}
        sx={{ textTransform: "none", borderRadius: 999 }}
      >
        Replay step
      </Button>
    </Stack>
  );
}

export { MindfulResetNarrationControls };
