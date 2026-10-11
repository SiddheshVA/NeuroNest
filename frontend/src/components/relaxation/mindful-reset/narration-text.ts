import type { MindfulResetStep } from "@/data/mindful-reset";

const ONES = [
  "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
  "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
  "eighteen", "nineteen",
];
const TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

function numberToWords(n: number): string {
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? `-${ONES[n % 10]}` : "");
  return String(n); // larger numbers don't occur in step text; leave them to the speech engine
}

/** Makes on-screen text speakable: digits as words ("5" → "five"), "—" as a comma pause. */
function toSpeakable(text: string): string {
  return text
    .replace(/\s*—\s*/g, ", ")
    .replace(/\d+/g, (digits) => numberToWords(Number(digits)))
    .trim();
}

function withFullStop(text: string): string {
  return /[.!?]$/.test(text) ? text : `${text}.`;
}

/**
 * What the narration says for a step: the instruction, then the detail if there is one.
 * The short "Gently tense your …" steps (6 seconds) speak only the instruction, so the
 * speech ends well inside the step.
 */
function narrationTextForStep(step: MindfulResetStep): string {
  const parts = [step.instruction];
  const isShortTensionStep = step.phase === "tension" && step.seconds <= 6;
  if (step.detail && !isShortTensionStep) {
    parts.push(step.detail);
  }
  return parts.map((part) => withFullStop(toSpeakable(part))).join(" ");
}

export { narrationTextForStep, toSpeakable };
