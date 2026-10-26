import { useId, useState } from "react";
import IconButton from "@mui/material/IconButton";
import Popover from "@mui/material/Popover";
import Tooltip from "@mui/material/Tooltip";
import PlayArrowRounded from "@mui/icons-material/PlayArrowRounded";
import RecordVoiceOverRounded from "@mui/icons-material/RecordVoiceOverRounded";

import { toggleSx } from "@/components/nuro/nuro-panel-controls";
import { NEURO_VOICES, type NeuroVoice } from "@/lib/voice/voice-prefs";

/** A voice option with a preview button; shared by the settings popover and the first-time dialog. */
export function NuroVoiceOptions({
  selected,
  onSelect,
  onPreview,
  labelledBy,
}: {
  selected: NeuroVoice;
  onSelect: (voice: NeuroVoice) => void;
  onPreview: (voice: NeuroVoice) => void;
  labelledBy: string;
}) {
  const groupName = useId();
  return (
    <div role="radiogroup" aria-labelledby={labelledBy} className="flex flex-col gap-0.5">
      {NEURO_VOICES.map((option) => (
        <div
          key={option.value}
          className="flex items-center gap-1 rounded-lg pr-1 hover:bg-muted"
        >
          <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2.5 px-2 py-1.5">
            <input
              type="radio"
              name={groupName}
              value={option.value}
              checked={selected === option.value}
              onChange={() => onSelect(option.value)}
              className="size-4 shrink-0 accent-[var(--primary)]"
            />
            <span className="min-w-0">
              <span className="block truncate text-sm text-foreground">{option.label}</span>
              <span className="block truncate text-xs text-muted-foreground">{option.detail}</span>
            </span>
          </label>
          <Tooltip title="Preview">
            <IconButton
              aria-label={`Preview ${option.label}`}
              onClick={() => onPreview(option.value)}
              size="small"
              sx={toggleSx}
            >
              <PlayArrowRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        </div>
      ))}
    </div>
  );
}

/** "Voice" setting for spoken replies: Aarti or Anand, each with a preview. */
export function NuroVoicePicker({
  selected,
  onSelect,
  onPreview,
}: {
  selected: NeuroVoice;
  onSelect: (voice: NeuroVoice) => void;
  onPreview: (voice: NeuroVoice) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const headingId = useId();

  return (
    <>
      <Tooltip title="Voice for replies">
        <IconButton
          aria-label="Choose Neuro's voice"
          aria-haspopup="dialog"
          aria-expanded={anchor ? "true" : undefined}
          onClick={(event) => setAnchor(event.currentTarget)}
          size="small"
          sx={toggleSx}
        >
          <RecordVoiceOverRounded fontSize="small" />
        </IconButton>
      </Tooltip>
      <Popover
        open={Boolean(anchor)}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        slotProps={{
          paper: {
            role: "dialog",
            "aria-labelledby": headingId,
            sx: {
              backgroundColor: "var(--popover, var(--background))",
              color: "var(--foreground)",
              borderRadius: "16px",
            },
          },
        }}
      >
        <div className="w-72 max-w-[calc(100vw-32px)] p-3">
          <p id={headingId} className="px-2 pb-2 text-sm font-semibold text-foreground">
            Voice
          </p>
          <NuroVoiceOptions
            selected={selected}
            onSelect={onSelect}
            onPreview={onPreview}
            labelledBy={headingId}
          />
        </div>
      </Popover>
    </>
  );
}
