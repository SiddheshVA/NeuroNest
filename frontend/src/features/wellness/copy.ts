// Every user-facing string of the Wellness Snapshot (API name: wellness check-in), in one place. Plain, warm, non-clinical wording; results
// are estimates for self-reflection, never a diagnosis. Option *values* live in questionnaire.ts. The only
// figures written here are the two documented guidelines (adults: 7+ hours of sleep; WHO: 150+ minutes of
// activity a week); everything else shown comes from the user's own answers or the API response.

import type { Confidence, RiskBand, Segment } from "./types.ts";

/** Picks the wording for the chosen segment; before one is chosen, the neutral "both" wording is used. */
export function bySegment<T>(segment: Segment | null, text: { student: T; working: T; both: T }): T {
  return text[segment ?? "both"];
}

export const COMMON = {
  loading: "Loading",
} as const;

export const PAGE = {
  eyebrow: "Wellness Snapshot",
  promise: "About 2 minutes. No account needed, and nothing is saved unless you are signed in.",
  stepsLabel: "Snapshot steps",
  privacy: "Your answers are only sent to work out your result. Nothing is kept in this browser.",
  historyTitle: "Your wellness snapshots",
  historyPrivacy: "Only you can see these. Delete any entry, or all of them, whenever you like.",
} as const;

/** `title` names the step in the step list; `heading` is the step's page heading. */
export const STEPS = [
  { title: "Your day", heading: "Let's start with your day" },
  { title: "Your sleep", heading: "Let's talk about your sleep" },
  { title: "Your load", heading: "What's been on your plate" },
  { title: "This week", heading: "Last one: how this week felt" },
] as const;

export const NAV = {
  back: "Back",
  next: "Continue",
  seeResult: "See my result",
  checking: "Working it out…",
  progress: (step: number, total: number) => `Step ${step} of ${total}`,
  stepDone: "done",
  hint: "You can go back and change any answer.",
  whyWeAsk: "Why we ask",
  required: "Please choose an answer to continue.",
  skip: "Skip",
  preferNotToSay: "Prefer not to say",
  scaleValue: (value: number, max: number) => `${value} out of ${max}`,
} as const;

// ---- Questions ----

export const Q = {
  segment: {
    label: "Right now, are you mostly…",
    hint: "Pick the one that takes up most of your week.",
    why: "Study and work stress feel different, so we use a model trained on people in a similar situation.",
    options: { student: "Studying", working: "Working", both: "Both" },
  },
  age: {
    label: "How old are you?",
    hint: "Use the buttons or type it in.",
    why: "How stress shows up shifts with age, so it helps the estimate fit you better.",
    decrease: "One year younger",
    increase: "One year older",
    unit: "years",
  },
  movement: {
    label: "How much do you move on a normal day?",
    hint: "Walks, gym, sports, even the long walk to the bus or the metro.",
    why: "Moving your body is one of the strongest everyday buffers against stress and poor sleep.",
    options: {
      lt15: "Barely (under 15 min)",
      m15to30: "A bit (15–30 min)",
      m30to60: "Some (30–60 min)",
      gt60: "Plenty (1 hr+)",
    },
  },
  sleepHours: {
    label: "How long are you actually asleep on a normal night?",
    hint: "Not just in bed scrolling. Your best guess is fine.",
    why: "Sleep length is closely linked to mood, focus and how well you handle stress.",
    options: { lt5: "Under 5 h", h5to6: "5–6 h", h6to7: "6–7 h", h7to8: "7–8 h", gt8: "8 h+" },
  },
  rested: {
    label: "How rested do you feel when you wake up?",
    hint: "1 is running on empty, 10 is fully recharged.",
    why: "Feeling unrefreshed can matter as much as hours slept, and it helps spot sleep patterns.",
    low: "Exhausted",
    high: "Fully recharged",
  },
  pressure: {
    label: (s: Segment | null) =>
      bySegment(s, {
        student: "How heavy has your study load felt lately?",
        working: "How heavy has your workload felt lately?",
        both: "How heavy have study and work felt lately, all together?",
      }),
    hint: (s: Segment | null) =>
      bySegment(s, {
        student: "Deadlines, exams, assignments, that one group project.",
        working: "Deadlines, targets, late calls, shift changes, the commute on top.",
        both: "Classes plus shifts, deadlines on both sides, and getting between them.",
      }),
    why: "Ongoing pressure is one of the clearest signals linked to mood strain in the research we use.",
    options: ["Light", "Manageable", "Busy", "Heavy", "Crushing"],
  },
  satisfaction: {
    label: (s: Segment | null) =>
      bySegment(s, {
        student: "How do you feel about your studies these days?",
        working: "How do you feel about your work these days?",
        both: "How do you feel about what you're studying and your job these days?",
      }),
    hint: (s: Segment | null) =>
      bySegment(s, {
        student: "Is it interesting, worth it, going somewhere?",
        working: "Is it meaningful, secure, worth the effort? Remote or on site.",
        both: "Overall, is the juggling feeling worth it?",
      }),
    why: "Feeling that your effort is worth it protects against burnout, even when things are busy.",
    options: ["Really unhappy", "Not great", "Okay", "Pretty good", "Love it"],
  },
  money: {
    label: "How much have money worries been on your mind lately?",
    hint: (s: Segment | null) =>
      bySegment(s, {
        student: "Fees, rent, loans, the cost of just getting by.",
        working: "Rent, EMIs, bills, job security, supporting family.",
        both: "Fees, rent, EMIs, bills, making ends meet on both fronts.",
      }),
    why: "Money stress is common and strongly tied to mood. Naming it can make it easier to tackle.",
    options: ["Not at all", "A little", "Sometimes", "Often", "All the time"],
  },
  stress: {
    label: "Overall, how stressed have you felt this week?",
    hint: "Think of the week as a whole, not just today. 1 is calm, 10 is overwhelmed.",
    why: "Your own sense of stress connects how you're sleeping with how you're feeling.",
    low: "Calm",
    high: "Overwhelmed",
  },
} as const;

export const OPTIONAL = {
  title: "Sharpen your result",
  subtitle: "Optional. Skip any of these; your result works without them.",
  show: "Add a few optional details",
  added: (hint: string) => `Added: ${hint.charAt(0).toLowerCase()}${hint.slice(1)}`,
  workStudyHours: {
    label: (s: Segment | null) =>
      bySegment(s, {
        student: "Hours of study on a normal day, classes included",
        working: "Hours of work on a normal day, commute not included",
        both: "Hours of study and work on a normal day, combined",
      }),
    hint: "Adds accuracy to your mood estimate.",
    options: { lt4: "Under 4 h", h4to6: "4–6 h", h6to8: "6–8 h", h8to10: "8–10 h", gt10: "10 h+" },
  },
  dietaryHabits: {
    label: "Your eating pattern most days",
    hint: "Adds accuracy to your mood estimate.",
    options: {
      unhealthy: "Mostly skipped meals or junk",
      moderate: "A mix",
      healthy: "Mostly regular, balanced meals",
    },
  },
  familyHistory: {
    label: "Has anyone in your close family had a mental-health condition?",
    hint: "Adds accuracy to your mood estimate. Completely optional.",
    options: { yes: "Yes", no: "No" },
  },
} as const;

// ---- Live feedback while answering ----

export const INSIGHT = {
  /** Sleep feedback by the chosen sleep chip (its representative hours). */
  sleep: (hours: number) =>
    hours < 5
      ? "Well under the 7+ hours most adults are advised to get. Worth a closer look in your results."
      : hours < 6
        ? "Below the 7+ hours adults are generally advised to get."
        : hours < 7
          ? "Close, but a little under the 7+ hours adults are generally advised to get."
          : hours < 8
            ? "Right in the range adults are generally advised to aim for (7 or more hours)."
            : "Plenty of time asleep. If you still wake up tired, the rested rating matters more.",
  /** A word for the 1-10 rested score. */
  restedWord: (score: number) =>
    score <= 2 ? "exhausted" : score <= 4 ? "tired" : score <= 6 ? "so-so" : score <= 8 ? "fairly rested" : "fully recharged",
  sleepTitle: (chipLabel: string, restedWord: string | null) =>
    restedWord ? `${chipLabel}, feeling ${restedWord}` : chipLabel,
  sleepPrompt: "Pick your usual sleep to see how it compares with general guidance.",
  sleepPromptTitle: "How your sleep compares",
  movementTitle: "Your movement this week",
  movement: (weeklyMinutes: number) =>
    weeklyMinutes >= 150
      ? `Roughly ${weeklyMinutes} minutes a week, which meets the WHO's 150+ minutes a week for adults.`
      : `Roughly ${weeklyMinutes} minutes a week. The WHO suggests adults aim for 150 or more.`,
  movementPrompt: "Pick how much you move to see how it compares with the WHO's activity guideline.",
  loadTitle: "Why these three",
  load: "Load, how you feel about it and money worries together shape your mood estimate. Be honest; only you see this.",
  stressTitle: "Nearly there",
  stress: "Your result shows what's moving your mood, and you can try a what-if afterwards without saving anything.",
} as const;

// ---- Results ----

export const RESULT = {
  eyebrow: "Wellness Snapshot",
  heading: "Here is your snapshot",
  lede: "An estimate to guide your next step, never a diagnosis.",
  checkAgain: "Check in again",
  editAnswers: "Change my answers",
  moodTitle: "Mood strain",
  bands: {
    Low: { label: "Low", text: "Your answers point to a fairly steady place right now. Keep doing what's working." },
    Moderate: {
      label: "Moderate",
      text: "Some things are weighing on you. Small, regular resets can make a real difference.",
    },
    High: {
      label: "High",
      text: "A lot seems to be weighing on you. You don't have to carry it alone; support can help.",
    },
  } satisfies Record<RiskBand, { label: string; text: string }>,
  scaleLabel: (band: string) => `Mood strain: ${band}`,
  you: "You",
  confidence: {
    label: (c: Confidence) => `${c[0].toUpperCase()}${c.slice(1)} confidence`,
    whyTitle: (c: Confidence) => `Why ${c} confidence?`,
    fullAnswers: "Based on complete answers that look typical for the data the estimate learned from.",
    addNow: "Add it now",
  },
  driversTitle: "What is moving this",
  driversEmpty: "Nothing stood out strongly either way.",
  driverTag: { up: "Raises", down: "Eases" },
  direction: {
    mood: { increases: "adds to your strain", decreases: "eases your strain" },
    sleep: { increases: "points toward this pattern", decreases: "points away from it" },
  },
  sleepTitle: "Sleep",
  sleepGuidanceIntro: "Here's what general sleep and activity guidelines say about your answers.",
  sleepMeter: {
    label: "Sleep a night",
    min: "4 h",
    max: "10 h",
    zone: "Advised range: 7 h or more",
    value: (hours: number) => `${hours} h`,
    aria: (hours: number) =>
      `Sleep: about ${hours} hours a night. Adults are generally advised to get 7 or more hours.`,
  },
  movementMeter: {
    label: "Movement a week",
    min: "0",
    max: "300+ min",
    zone: "WHO guideline: 150 min or more",
    value: (minutes: number) => `${minutes} min`,
    aria: (minutes: number) =>
      `Movement: about ${minutes} minutes a week. The WHO suggests adults get at least 150 minutes a week.`,
  },
  restedNote: (word: string, meetsActivity: boolean) =>
    `You wake up feeling ${word}. ${
      meetsActivity
        ? "Your movement meets the weekly guideline, which helps sleep too."
        : "A little more movement could help you reach the weekly guideline and sleep better."
    }`,
  seeDoctor: "If this sounds familiar and it's been going on for a while, it's worth mentioning to a doctor.",
  actionsTitle: "Suggested next steps",
  actionGo: "Go",
  browseProfessionals: "Browse verified professionals",
  disclaimerTitle: "Good to know",
  crisisLink: "Need crisis support?",
  crisisHide: "Hide crisis support",
  savedPill: "Saved to your history",
  anonTitle: "Sign in to save your snapshots and see your progress",
  anonButton: "Sign in or create an account",
  crisisIntro:
    "If things feel like too much right now, or you're thinking about harming yourself, please reach out right away:",
} as const;

/** Friendly names for the drivers' snake_case feature names; unknown names fall back to the API's label. */
export const DRIVER_LABELS: Record<string, string> = {
  age: "Your age",
  pressure: "Study / work load",
  satisfaction: "How you feel about study / work",
  sleep_duration: "Sleep length",
  financial_stress: "Money worries",
  dietary_habits: "Eating pattern",
  work_study_hours: "Daily study / work hours",
  family_history: "Family history",
  quality_of_sleep: "How rested you wake up",
  stress_level: "Stress this week",
  physical_activity_minutes: "Daily movement",
};

/** The small line under a driver, from the value the API returned. Unknown features show no value line. */
export const DRIVER_VALUES: Record<string, (value: string) => string> = {
  pressure: (v) => `You rated it ${v} out of 5`,
  satisfaction: (v) => `You rated it ${v} out of 5`,
  financial_stress: (v) => `You rated it ${v} out of 5`,
  stress_level: (v) => `You rated it ${v} out of 10`,
  quality_of_sleep: (v) => `You rated it ${v} out of 10`,
  sleep_duration: (v) => `About ${v} h a night`,
  physical_activity_minutes: (v) => `About ${v} min a day`,
  work_study_hours: (v) => `About ${v} h a day`,
  age: (v) => `${v} years`,
  family_history: (v) => `You answered: ${v}`,
  dietary_habits: (v) => `You answered: ${v}`,
};
export const YES_NO = { yes: "Yes", no: "No" } as const;

/**
 * Plain-language versions of the API's confidence reasons, matched by their start. Unknown reasons are not
 * shown raw (they are technical); the generic line is used instead.
 */
export const CONFIDENCE_REASONS: { startsWith: string; text: string }[] = [
  { startsWith: "model quality limits confidence", text: "This estimate is still early, so we keep its confidence modest." },
  { startsWith: "optional input(s) not provided", text: "A few optional answers were skipped." },
  { startsWith: "input(s) outside the range seen in training", text: "Some answers are outside what the estimate learned from." },
  { startsWith: "input(s) not used by this model", text: "Some answers aren't used by this estimate (for example, age for working people)." },
  { startsWith: "segment 'both'", text: "For study and work together, we use the student estimate, so we're a bit more careful." },
  { startsWith: "booster inputs", text: "Optional sleep-tracking details weren't added." },
];
export const CONFIDENCE_REASON_FALLBACK = "Based on how complete and typical your answers are.";

export interface ActionCopy {
  label: string;
  description: string;
}

/** Suggested-action codes from the backend. Unknown codes are ignored. */
export const ACTIONS: Record<string, ActionCopy> = {
  ASSESSMENT_PHQ9: { label: "Take the mood check (PHQ-9)", description: "A short, widely used self-check for low mood." },
  ASSESSMENT_PSS10: { label: "Take the stress check (PSS-10)", description: "See how stress has felt over the past month." },
  FIND_PROFESSIONAL: { label: "Talk to a professional", description: "Verified psychologists and counsellors." },
  RELAXATION: { label: "Try a quick reset", description: "Breathing, meditation and calming exercises." },
  RELAXATION_SLEEP: { label: "Wind down for better sleep", description: "Sleep sounds and wind-down routines." },
  SEE_DOCTOR: {
    label: "Consider talking to your doctor",
    description: "A GP can check what's behind ongoing sleep problems.",
  },
  KEEP_TRACKING: { label: "Take another snapshot next week", description: "Spot changes over time in your history." },
};

export const WHAT_IF = {
  title: "What if things shifted a little?",
  intro: "Move a slider to see how your mood strain might change. Just exploring: nothing here is saved.",
  badge: "Illustrative / not saved",
  disabled: "What-if is switched off right now.",
  result: "With these changes",
  now: "Your snapshot now",
  sleep: "Sleep",
  stress: "Stress",
  movement: "Movement",
  sleepValue: (v: number) => `${v} h`,
  stressValue: (v: number) => `${v}/10`,
  movementValue: (v: number) => `${v} min/day`,
  start: "Move a slider to see how your result might shift.",
  same: "Same band as your snapshot.",
  lower: "Lower than your snapshot.",
  higher: "Higher than your snapshot.",
  updating: "Updating…",
  failed: "Couldn't update the what-if right now.",
  openAll: "Open all sliders",
} as const;

// ---- Errors ----

export const ERRORS = {
  unavailable: "The snapshot service is resting for a moment, please try again shortly. Your answers are still here.",
  network: "We couldn't reach NeuroNest. Please check your connection and try again.",
  invalid: "A few answers need another look.",
  generic: "Something went wrong. Please try again.",
  retry: "Try again",
  loadHistory: "We couldn't load your snapshots.",
  deleteFailed: "We couldn't delete that just now. Please try again.",
} as const;

// ---- History and dashboard ----

export const HISTORY = {
  empty: "No snapshots yet",
  emptyText: "Take a two-minute snapshot to start seeing how you're doing over time.",
  start: "Take your first snapshot",
  newCheckin: "Check in again",
  view: "View",
  hide: "Hide",
  delete: "Delete",
  deleteAll: "Delete all history",
  previous: "Newer",
  next: "Older",
  pageOf: (page: number, total: number) => `Page ${page} of ${total}`,
  relative: (days: number) =>
    days <= 0 ? "Today" : days === 1 ? "Yesterday" : days < 14 ? `${days} days ago` : `${Math.floor(days / 7)} weeks ago`,
  sleepConcern: "Sleep worth a look",
  sleepOk: "Sleep okay",
  sleepGuidance: "Sleep tips only",
  confirmOneTitle: "Delete this snapshot?",
  confirmOneText: "It will be removed from your history for good.",
  confirmAllTitle: "Delete all your snapshots?",
  confirmAllText: (n: number) =>
    `This permanently removes all ${n} snapshot${n === 1 ? "" : "s"} from your history. This can't be undone.`,
  confirmAllType: "To confirm, type DELETE",
  confirmWord: "DELETE",
  cancel: "Keep it",
  cancelAll: "Keep them",
  confirmDelete: "Delete",
  confirmDeleteAll: "Delete all",
  deleting: "Deleting…",
} as const;

export const DASHBOARD = {
  title: "Wellness Snapshot",
  latest: "Latest",
  none: "No Wellness Snapshots yet",
  noneText: "A two-minute snapshot of your mood strain and sleep.",
  start: "Take your first snapshot",
  history: "See full history",
  again: "Check in again",
  failed: "Your latest snapshot couldn't be loaded right now.",
} as const;

export const TREND = {
  summary: (bands: string[]) =>
    bands.length === 1
      ? `Your latest snapshot: ${bands[0]}.`
      : `Your last ${bands.length} snapshots, oldest to newest: ${bands.join(", ")}.`,
} as const;
