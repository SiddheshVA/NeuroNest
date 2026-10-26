import { useId, useState } from "react";
import Dialog from "@mui/material/Dialog";

import { Button } from "@/components/ui/button";
import { NuroVoiceOptions } from "@/components/nuro/nuro-voice-picker";
import { DEFAULT_NEURO_VOICE, type NeuroVoice } from "@/lib/voice/voice-prefs";

/**
 * Asked once, the first time voice is used ("Read replies aloud" or the mic).
 * Closing it without choosing keeps the default voice (Aarti). The voice can be
 * changed later from the voice setting in the chat header.
 */
export function NuroVoiceChoiceDialog({
  open,
  onPreview,
  onDone,
}: {
  open: boolean;
  onPreview: (voice: NeuroVoice) => void;
  /**
   * Called with the chosen voice and `confirmed` true (Continue), or the default
   * voice and `confirmed` false when the dialog is dismissed. It only saves the voice.
   */
  onDone: (voice: NeuroVoice, confirmed: boolean) => void;
}) {
  const [selected, setSelected] = useState<NeuroVoice>(DEFAULT_NEURO_VOICE);
  const titleId = useId();
  const descriptionId = useId();

  return (
    <Dialog
      open={open}
      onClose={() => onDone(DEFAULT_NEURO_VOICE, false)}
      aria-labelledby={titleId}
      aria-describedby={descriptionId}
      slotProps={{
        paper: {
          sx: {
            width: 360,
            maxWidth: "calc(100vw - 32px)",
            m: 2,
            backgroundColor: "var(--popover, var(--background))",
            color: "var(--foreground)",
            borderRadius: "20px",
          },
        },
      }}
    >
      <div className="flex flex-col gap-3 p-5">
        <div>
          <h2 id={titleId} className="font-heading text-lg font-semibold text-foreground">
            Choose Neuro&apos;s voice
          </h2>
          <p id={descriptionId} className="mt-1 text-sm text-muted-foreground">
            Pick the voice you&apos;d like to hear. You can change it any time.
          </p>
        </div>
        <NuroVoiceOptions
          selected={selected}
          onSelect={setSelected}
          onPreview={onPreview}
          labelledBy={titleId}
        />
        <Button className="self-end" onClick={() => onDone(selected, true)}>
          Continue
        </Button>
      </div>
    </Dialog>
  );
}
