import type { AssessmentType } from "./types";

export interface AssessmentMeta {
  type: AssessmentType;
  label: string;
  fullName: string;
  /** Route to the questionnaire itself. */
  route: string;
  /** Small uppercase eyebrow shown above the result heading, e.g. "WELLBEING CHECK-IN". */
  resultEyebrow: string;
  snapshotTitle: string;
  /** Whether a higher raw score means a better outcome (WHO-5) or a worse one (everything else). */
  higherIsBetter: boolean;
  /** One-line summary shown on the hub's check-in cards. */
  shortDescription: string;
  questionCount: number;
  /** Rough time to complete, in minutes. */
  minutes: number;
}

export const ASSESSMENTS: Record<AssessmentType, AssessmentMeta> = {
  who5: {
    type: "who5",
    label: "WHO-5",
    fullName: "WHO-5 Well-Being Index",
    route: "/assessment",
    resultEyebrow: "Wellbeing check-in",
    snapshotTitle: "Your wellbeing snapshot",
    higherIsBetter: true,
    shortDescription: "A quick snapshot of your overall wellbeing over the last two weeks.",
    questionCount: 5,
    minutes: 2,
  },
  gad7: {
    type: "gad7",
    label: "GAD-7",
    fullName: "GAD-7 Anxiety Screening",
    route: "/assessment/gad7",
    resultEyebrow: "Anxiety check-in",
    snapshotTitle: "Your anxiety screening snapshot",
    higherIsBetter: false,
    shortDescription: "A short check-in on worry, nervousness and feeling on edge.",
    questionCount: 7,
    minutes: 2,
  },
  phq9: {
    type: "phq9",
    label: "PHQ-9",
    fullName: "PHQ-9 Depression Screening",
    route: "/assessment/phq9",
    resultEyebrow: "Mood check-in",
    snapshotTitle: "Your mood screening snapshot",
    higherIsBetter: false,
    shortDescription: "A short check-in on low mood and loss of interest.",
    questionCount: 9,
    minutes: 3,
  },
  pss10: {
    type: "pss10",
    label: "PSS-10",
    fullName: "Perceived Stress Scale",
    route: "/assessment/pss10",
    resultEyebrow: "Stress check-in",
    snapshotTitle: "Your stress screening snapshot",
    higherIsBetter: false,
    shortDescription: "How stressed or overwhelmed you have felt over the last month.",
    questionCount: 10,
    minutes: 3,
  },
  k10: {
    type: "k10",
    label: "K10",
    fullName: "K10 General Distress Check",
    route: "/assessment/k10",
    resultEyebrow: "General distress check-in",
    snapshotTitle: "Your general distress snapshot",
    higherIsBetter: false,
    shortDescription: "A general check-in on distress, for when you are not sure what is going on.",
    questionCount: 10,
    minutes: 3,
  },
  sci: {
    type: "sci",
    label: "SCI",
    fullName: "Sleep Condition Indicator",
    route: "/assessment/sci",
    resultEyebrow: "Sleep check-in",
    snapshotTitle: "Your sleep snapshot",
    higherIsBetter: true,
    shortDescription: "A short check-in on how you have been sleeping.",
    questionCount: 8,
    minutes: 2,
  },
};

/** Order the hub's "Choose a check-in" cards appear in. */
export const ASSESSMENT_DISPLAY_ORDER: AssessmentType[] = ["who5", "gad7", "phq9", "pss10", "k10", "sci"];

export interface ExplorationOption {
  type: AssessmentType;
  label: string;
  description: string;
}

/**
 * Options shown after a low WHO-5 score for "What has been affecting you
 * lately?" — deliberately framed as areas to explore, not diagnoses to pick.
 */
export const EXPLORATION_OPTIONS: ExplorationOption[] = [
  {
    type: "gad7",
    label: "Anxiety / worry",
    description: "Racing thoughts, restlessness, or feeling on edge.",
  },
  {
    type: "phq9",
    label: "Feeling low / loss of interest",
    description: "Low mood, low energy, or losing interest in things you enjoy.",
  },
  {
    type: "pss10",
    label: "Stress / feeling overwhelmed",
    description: "Feeling like things are piling up or hard to keep up with.",
  },
  {
    type: "sci",
    label: "Sleep difficulties",
    description: "Trouble falling asleep, staying asleep, or waking up rested.",
  },
];

/** All targeted (non-WHO-5) screenings, in a stable display order. */
export const TARGETED_ASSESSMENT_TYPES: AssessmentType[] = ["gad7", "phq9", "pss10", "k10", "sci"];

/** Shown after a low WHO-5 score for anyone unsure which area fits — a general distress check-in. */
export const GENERAL_CHECKIN_OPTION: ExplorationOption = {
  type: "k10",
  label: "Not sure what you're feeling?",
  description: "Take a short general check-in.",
};
