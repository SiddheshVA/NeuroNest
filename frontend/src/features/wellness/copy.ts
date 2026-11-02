// Every user-facing string of the Wellness Snapshot (API name: wellness check-in), in one place. Plain, warm, non-clinical wording; results
// are estimates for self-reflection, never a diagnosis. Option *values* live in questionnaire.ts.

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
  title: "How are you really doing?",
  intro:
    "Four quick steps, about a minute. You'll get a gentle read on your mood strain and sleep, what's moving them, and a few next steps.",
  privacy: "Your answers are only sent to work out your result. Nothing is kept in this browser.",
  historyTitle: "Your wellness snapshots",
  historyIntro: "Every snapshot you've saved, newest first. Only you can see these.",
} as const;

export const STEPS = [
  { title: "Your day", subtitle: "A little about your routine" },
  { title: "Your sleep", subtitle: "The sleep you actually get" },
  { title: "Your load", subtitle: "What's on your plate lately" },
  { title: "This week", subtitle: "One last thing, then your result" },
] as const;

export const NAV = {
  back: "Back",
  next: "Next",
  seeResult: "See my result",
  checking: "Working it out…",
  progress: (step: number, total: number) => `Step ${step} of ${total}`,
  whyWeAsk: "Why we ask",
  hideWhy: "Hide",
  required: "Please choose an answer to continue.",
  skip: "Skip",
  preferNotToSay: "Prefer not to say",
  startAgain: "Take a new snapshot",
  editAnswers: "Change my answers",
  sliderPrompt: "Slide or use the arrow keys",
  sliderNotAnswered: "Not answered yet",
  sliderValue: (value: number, max: number) => `${value} out of ${max}`,
} as const;

// ---- Questions ----

export const Q = {
  segment: {
    label: "Right now, are you mostly…",
    why: "Study and work stress feel different, so we use a model trained on people in a similar situation.",
    options: { student: "Studying", working: "Working", both: "Both" },
  },
  age: {
    label: "How old are you?",
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
    hint: "Think of the week as a whole, not just today.",
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
  sleepExtrasTitle: "If you track your health (phone or watch)",
  sleepExtrasHint: "Adds detail to your sleep result.",
  heartRate: {
    label: "Resting heart rate",
    options: { lt60: "Under 60 bpm", b60to70: "60–70 bpm", b70to80: "70–80 bpm", gt80: "Over 80 bpm" },
  },
  dailySteps: {
    label: "Daily steps",
    options: { lt3k: "Under 3,000", s3to6k: "3,000–6,000", s6to9k: "6,000–9,000", gt9k: "9,000+" },
  },
} as const;

// ---- Results ----

export const RESULT = {
  heading: "Your Wellness Snapshot",
  moodTitle: "Mood strain",
  moodSubtitle: "How much strain your answers point to right now.",
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
  confidence: {
    label: (c: Confidence) => `${c[0].toUpperCase()}${c.slice(1)} confidence`,
    whyTitle: "Why this confidence?",
    fullAnswers: "Based on complete answers that look typical for the data the estimate learned from.",
  },
  driversTitle: "What's moving this",
  driversEmpty: "Nothing stood out strongly either way.",
  direction: {
    mood: { increases: "adds to your strain", decreases: "eases your strain" },
    sleep: { increases: "points toward this pattern", decreases: "points away from it" },
  },
  sleepTitle: "Sleep",
  sleepGuidanceTitle: "Sleep tips for you",
  sleepGuidanceIntro: "Here's what general sleep and activity guidelines say about your answers.",
  seeDoctor: "If this sounds familiar and it's been going on for a while, it's worth mentioning to a doctor.",
  actionsTitle: "Next steps you might like",
  disclaimerTitle: "Good to know",
  savedTitle: "Saved to your snapshots",
  savedLink: "See your history",
  anonTitle: "Sign in to save your snapshots and see your progress",
  anonText: "This result is shown once and isn't stored anywhere.",
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
  heart_rate: "Resting heart rate",
  daily_steps: "Daily steps",
};

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
  title: "What-if",
  badge: "Not saved",
  intro: "Move the sliders to see how changes might shift your mood strain. Just exploring: nothing here is saved.",
  disabled: "What-if is switched off right now.",
  result: "With these changes",
  sleep: "Sleep (hours)",
  stress: "Stress this week",
  movement: "Movement (minutes a day)",
  updating: "Updating…",
  failed: "Couldn't update the what-if right now.",
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
  emptyText: "Take a one-minute snapshot to start seeing how you're doing over time.",
  start: "Take a snapshot",
  newCheckin: "New snapshot",
  details: "Details",
  hideDetails: "Hide details",
  delete: "Delete",
  deleteAll: "Delete all",
  previous: "Newer",
  next: "Older",
  pageOf: (page: number, total: number) => `Page ${page} of ${total}`,
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
  deleting: "Deleting…",
} as const;

export const DASHBOARD = {
  title: "Wellness Snapshot",
  latest: "Latest mood strain",
  none: "No Wellness Snapshots yet",
  noneText: "A one-minute snapshot of your mood strain and sleep.",
  start: "Take a snapshot",
  history: "See history",
  again: "Take a new snapshot",
  failed: "Your latest snapshot couldn't be loaded right now.",
} as const;
