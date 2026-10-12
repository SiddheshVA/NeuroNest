import { useId, useMemo, useState } from "react";
import IconButton from "@mui/material/IconButton";
import Popover from "@mui/material/Popover";
import Tooltip from "@mui/material/Tooltip";
import PlayArrowRounded from "@mui/icons-material/PlayArrowRounded";
import RecordVoiceOverRounded from "@mui/icons-material/RecordVoiceOverRounded";

import { toggleSx } from "@/components/nuro/nuro-panel-controls";
import { sortVoicesForPicker } from "@/lib/voice/speech-text";

const AUTO_VALUE = "__auto__";

function VoiceRow({
  name,
  value,
  title,
  detail,
  checked,
  onSelect,
  onPreview,
  previewLabel,
}: {
  name: string;
  value: string;
  title: string;
  detail: string;
  checked: boolean;
  onSelect: () => void;
  onPreview: () => void;
  previewLabel: string;
}) {
  return (
    <div className="flex items-center gap-1 rounded-lg pr-1 hover:bg-muted">
      <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2.5 px-2 py-1.5">
        <input
          type="radio"
          name={name}
          value={value}
          checked={checked}
          onChange={onSelect}
          className="size-4 shrink-0 accent-[var(--primary)]"
        />
        <span className="min-w-0">
          <span className="block truncate text-sm text-foreground">{title}</span>
          <span className="block truncate text-xs text-muted-foreground">{detail}</span>
        </span>
      </label>
      <Tooltip title="Preview">
        <IconButton aria-label={previewLabel} onClick={onPreview} size="small" sx={toggleSx}>
          <PlayArrowRounded fontSize="small" />
        </IconButton>
      </Tooltip>
    </div>
  );
}

/**
 * "Voice" setting for spoken replies: Automatic (a female Indian voice where
 * available) or any voice this browser offers, each with a preview.
 */
export function NuroVoicePicker({
  voices,
  selectedName,
  autoVoice,
  onSelect,
  onPreview,
}: {
  voices: SpeechSynthesisVoice[];
  /** The chosen voice's name, or null for Automatic. */
  selectedName: string | null;
  autoVoice: SpeechSynthesisVoice | null;
  onSelect: (name: string | null) => void;
  onPreview: (name: string | null) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const headingId = useId();
  const groupName = useId();
  const sorted = useMemo(() => sortVoicesForPicker(voices), [voices]);
  // A saved voice this device doesn't have falls back to Automatic.
  const selectedAvailable = selectedName !== null && voices.some((v) => v.name === selectedName);

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
        <div className="w-80 max-w-[calc(100vw-32px)] p-3">
          <p id={headingId} className="px-2 pb-2 text-sm font-semibold text-foreground">
            Voice
          </p>
          <div
            role="radiogroup"
            aria-labelledby={headingId}
            className="flex max-h-80 flex-col gap-0.5 overflow-y-auto"
          >
            <VoiceRow
              name={groupName}
              value={AUTO_VALUE}
              title="Automatic (recommended)"
              detail={autoVoice ? `${autoVoice.name} · ${autoVoice.lang}` : "Browser default voice"}
              checked={!selectedAvailable}
              onSelect={() => onSelect(null)}
              onPreview={() => onPreview(null)}
              previewLabel="Preview automatic voice"
            />
            {sorted.map((voice, index) => (
              <VoiceRow
                key={`${voice.name}-${voice.lang}-${index}`}
                name={groupName}
                value={voice.name}
                title={voice.name}
                detail={voice.lang}
                checked={selectedAvailable && voice.name === selectedName}
                onSelect={() => onSelect(voice.name)}
                onPreview={() => onPreview(voice.name)}
                previewLabel={`Preview ${voice.name}`}
              />
            ))}
            {sorted.length === 0 ? (
              <p className="px-2 py-1.5 text-xs text-muted-foreground">
                This browser hasn&apos;t listed its voices yet. The default voice will be used.
              </p>
            ) : null}
          </div>
        </div>
      </Popover>
    </>
  );
}
