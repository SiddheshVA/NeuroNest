// Class names shared by the wellness screens, matching the Dashboard's look (cards, buttons, focus ring).
// Motion is only added with motion-safe:, so reduced-motion users get none.

export const focusRing = "outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
export const card = "rounded-[28px] border border-[#E5E8E1] bg-white p-6 shadow-xs sm:p-8";
export const primaryButton = `inline-flex cursor-pointer items-center justify-center gap-2 rounded-full bg-[#233E33] px-5 py-2.5 text-sm font-medium text-white motion-safe:transition-colors motion-safe:duration-150 hover:bg-[#192E26] disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`;
export const secondaryButton = `inline-flex cursor-pointer items-center justify-center gap-2 rounded-full border border-[#233E33]/20 bg-white px-5 py-2.5 text-sm font-medium text-[#233E33] motion-safe:transition-colors motion-safe:duration-150 hover:bg-[#233E33]/5 disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`;
export const dangerButton = `inline-flex cursor-pointer items-center justify-center gap-2 rounded-full bg-[#8A3324] px-5 py-2.5 text-sm font-medium text-white motion-safe:transition-colors motion-safe:duration-150 hover:bg-[#702718] disabled:cursor-not-allowed disabled:opacity-50 ${focusRing}`;
export const textLink = `rounded-sm font-medium text-[#233E33] underline underline-offset-4 hover:text-[#192E26] ${focusRing}`;
export const muted = "text-[#4A5750]";

/** Calm band colours (no alarming red); the band is always also written out, never colour alone. */
export const bandTone: Record<string, string> = {
  Low: "bg-[#DCE8DA] text-[#1F3A2C]",
  Moderate: "bg-[#EFE3C6] text-[#4A3A12]",
  High: "bg-[#EBD5CB] text-[#5A2A1C]",
};
