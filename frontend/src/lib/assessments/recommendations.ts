import type { AssessmentType } from "./types";

/**
 * Centralized wellness recommendation + result-action service for the
 * Assessment module. UI components never branch on assessmentType/level
 * themselves — they call getRecommendations()/getResultActions() and render
 * whatever comes back.
 *
 * AVAILABILITY: only entries whose destination exists carry an `action`
 * (Neuro, the Relaxation Hub pages, Wellness Resources, and the Tele-MANAS helpline). Journal
 * isn't built yet. A suggestion with no shipped route still appears as a plain
 * card — it just never renders as a clickable dead link. Wiring a feature up
 * later is a one-line `action` addition here, nowhere else.
 *
 * Never recommends medication or makes treatment claims — only short,
 * gentle wellness/support suggestions.
 */

export type RecommendationKind =
  | "breathing"
  | "grounding"
  | "meditation"
  | "mindfulness"
  | "journaling"
  | "gentle-routines"
  | "relaxation-hub"
  | "sleep-sounds"
  | "sleep-meditation"
  | "relaxation-before-sleep"
  | "wellness-resources"
  | "stress-management-resources"
  | "sleep-routine-resources"
  | "talk-to-nuro"
  | "professional-support";

export interface RecommendationInput {
  assessmentType: AssessmentType;
  /** The result's raw score — kept in the signature for future score-sensitive tuning. */
  score: number;
  /** The result's levelKey (e.g. "moderate", "poor"). */
  level: string;
  safetyFlag: boolean;
  suggestProfessionalSupport?: boolean;
}

export interface RecommendationItem {
  kind: RecommendationKind;
  /** Short, 2-4 word card title, e.g. "Write it down". */
  title: string;
  /** One plain-language sentence — no medical claims. */
  description: string;
  /** Where the card leads, when that page exists (the card is plain text otherwise). */
  href?: string;
}

export interface ResultAction {
  kind: RecommendationKind;
  label: string;
  href: string;
}

interface RecommendationDefinition {
  title: string;
  description: string;
  /** Present only when the destination route actually exists today. */
  action?: { label: string; href: string };
}

const DEFINITIONS: Record<RecommendationKind, RecommendationDefinition> = {
  breathing: {
    title: "Try a breathing exercise",
    description: "Slow breathing can help you settle your mind.",
    action: { label: "Start breathing", href: "/relaxation/breathing" },
  },
  grounding: {
    title: "Try a grounding exercise",
    description: "Noticing what's around you can help when worry feels like too much.",
    action: { label: "Try a grounding reset", href: "/relaxation/mindful-reset" },
  },
  meditation: {
    title: "Try a short meditation",
    description: "A few quiet minutes can help you reset.",
    action: { label: "Browse meditations", href: "/relaxation/meditation" },
  },
  mindfulness: {
    title: "Take a mindful pause",
    description: "A few slow minutes can help you notice stress before it builds up.",
    action: { label: "Open Mindful Reset", href: "/relaxation/mindful-reset" },
  },
  journaling: {
    title: "Write it down",
    description: "Putting your thoughts on paper instead of keeping them in your head can help.",
  },
  "gentle-routines": {
    title: "Keep small routines going",
    description: "Simple daily habits can help, even on harder days.",
  },
  "relaxation-hub": {
    title: "Explore relaxation tools",
    description: "Guided breathing, calming sounds, and music are in the Relaxation Hub.",
    action: { label: "Open Relaxation Hub", href: "/relaxation" },
  },
  "sleep-sounds": {
    title: "Try calming sleep sounds",
    description: "Soft background sounds may help you wind down at night.",
    action: { label: "Play sleep sounds", href: "/relaxation/sleep" },
  },
  "sleep-meditation": {
    title: "Try a sleep meditation",
    description: "A short, guided meditation can help ease you into rest.",
    action: { label: "Browse meditations", href: "/relaxation/meditation" },
  },
  "relaxation-before-sleep": {
    title: "Wind down before bed",
    description: "A calm bedtime routine may help you rest better.",
    action: { label: "Open Sleep Support", href: "/relaxation/sleep" },
  },
  "wellness-resources": {
    title: "Browse wellness resources",
    description: "Simple articles with ideas that might help.",
    action: { label: "Browse resources", href: "/resources" },
  },
  "stress-management-resources": {
    title: "Explore stress-easing ideas",
    description: "Practical tips for easing everyday stress.",
    action: { label: "Browse resources", href: "/resources" },
  },
  "sleep-routine-resources": {
    title: "Build a steady sleep routine",
    description: "Small changes to your routine can support better sleep.",
    action: { label: "Browse resources", href: "/resources" },
  },
  "talk-to-nuro": {
    title: "Talk to someone",
    description: "Sharing how you've been feeling with Neuro can help.",
    action: { label: "Talk with Neuro", href: "/ai-companion" },
  },
  "professional-support": {
    title: "Talk to a professional",
    description: "If things have felt hard to manage, a mental health professional can help.",
    action: { label: "Call Tele-MANAS (14416)", href: "tel:14416" },
  },
};

function who5Kinds(level: string): RecommendationKind[] {
  switch (level) {
    case "high":
      return ["relaxation-hub", "journaling", "talk-to-nuro"];
    case "moderate":
      return ["breathing", "journaling", "talk-to-nuro"];
    case "low":
    default:
      return ["breathing", "meditation", "journaling", "talk-to-nuro", "wellness-resources"];
  }
}

function gad7Kinds(level: string): RecommendationKind[] {
  switch (level) {
    case "minimal":
      return ["breathing", "wellness-resources"];
    case "mild":
      return ["breathing", "grounding", "talk-to-nuro"];
    case "moderate":
      return ["breathing", "grounding", "relaxation-hub", "talk-to-nuro"];
    case "severe":
    default:
      return ["grounding", "relaxation-hub", "talk-to-nuro", "wellness-resources"];
  }
}

function phq9Kinds(level: string): RecommendationKind[] {
  switch (level) {
    case "minimal":
      return ["journaling", "wellness-resources"];
    case "mild":
      return ["journaling", "gentle-routines", "talk-to-nuro"];
    case "moderate":
      return ["journaling", "gentle-routines", "relaxation-hub", "talk-to-nuro"];
    case "moderately-severe":
    case "severe":
    default:
      return ["gentle-routines", "talk-to-nuro", "wellness-resources"];
  }
}

function pss10Kinds(level: string): RecommendationKind[] {
  switch (level) {
    case "low":
      return ["breathing", "mindfulness"];
    case "moderate":
      return ["breathing", "mindfulness", "relaxation-hub", "journaling"];
    case "high":
    default:
      return ["breathing", "relaxation-hub", "journaling", "stress-management-resources"];
  }
}

function k10Kinds(level: string): RecommendationKind[] {
  switch (level) {
    case "likely-well":
      return ["mindfulness", "wellness-resources"];
    case "mild":
      return ["breathing", "journaling", "talk-to-nuro"];
    case "moderate":
      return ["breathing", "grounding", "relaxation-hub", "talk-to-nuro"];
    case "severe":
    default:
      return ["grounding", "relaxation-hub", "talk-to-nuro", "wellness-resources"];
  }
}

function sciKinds(level: string): RecommendationKind[] {
  switch (level) {
    case "steady":
      return ["sleep-sounds", "relaxation-before-sleep"];
    case "difficulty":
    default:
      return ["sleep-sounds", "sleep-meditation", "relaxation-before-sleep", "sleep-routine-resources"];
  }
}

function getKinds(input: RecommendationInput): RecommendationKind[] {
  const base = (() => {
    switch (input.assessmentType) {
      case "who5":
        return who5Kinds(input.level);
      case "gad7":
        return gad7Kinds(input.level);
      case "phq9":
        return phq9Kinds(input.level);
      case "pss10":
        return pss10Kinds(input.level);
      case "k10":
        return k10Kinds(input.level);
      case "sci":
        return sciKinds(input.level);
      default:
        return [];
    }
  })();

  // WHO-5 never recommends professional support directly — a low score is a
  // reason to explore further, not a verdict (see suggestFurtherExploration).
  const shouldAddProfessionalSupport =
    input.assessmentType !== "who5" && (input.safetyFlag || input.suggestProfessionalSupport);

  if (shouldAddProfessionalSupport && !base.includes("professional-support")) {
    return [...base, "professional-support"];
  }
  return base;
}

/**
 * Short, gentle wellness suggestion cards for a result — never medication,
 * never a treatment claim. Excludes "professional-support": that gets its
 * own single, distinct section on the result page (see
 * AssessmentResultCard) instead of also appearing here, so the page never
 * says the same thing twice.
 */
export function getRecommendations(input: RecommendationInput): RecommendationItem[] {
  return getKinds(input)
    .filter((kind) => kind !== "professional-support")
    .map((kind) => ({
      kind,
      title: DEFINITIONS[kind].title,
      description: DEFINITIONS[kind].description,
      href: DEFINITIONS[kind].action?.href,
    }));
}

/**
 * Clickable result actions. Always leads with "Talk with Neuro"; every other
 * action is deduplicated by destination and only included when its route is
 * actually live (see the AVAILABILITY note above) — this never returns a
 * dead link.
 */
export function getResultActions(input: RecommendationInput): ResultAction[] {
  const actions: ResultAction[] = [];
  const seenHrefs = new Set<string>();

  const talkToNuro = DEFINITIONS["talk-to-nuro"].action;
  if (talkToNuro) {
    actions.push({ kind: "talk-to-nuro", ...talkToNuro });
    seenHrefs.add(talkToNuro.href);
  }

  for (const kind of getKinds(input)) {
    const action = DEFINITIONS[kind].action;
    if (!action || seenHrefs.has(action.href)) continue;
    seenHrefs.add(action.href);
    actions.push({ kind, label: action.label, href: action.href });
  }

  return actions;
}
