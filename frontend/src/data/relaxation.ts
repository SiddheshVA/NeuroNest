import {
  BookOpen,
  Brain,
  ClipboardCheck,
  Flower2,
  HandHeart,
  Home,
  Leaf,
  LayoutDashboard,
  MessageCircle,
  Moon,
  NotebookPen,
  Sun,
  Trees,
  Wind,
  type LucideIcon,
  Music2,
} from "lucide-react";

import { breathingExercises } from "@/data/breathing-exercises";
import { meditations } from "@/data/meditations";
import { mindfulResetActivities } from "@/data/mindful-reset";
import { sleepExperiences } from "@/data/sleep-support";

export type RelaxationNeed = {
  id: RelaxationTag;
  label: string;
  icon: LucideIcon;
};

export type SidebarNavLink = {
  label: string;
  href: string;
  icon: LucideIcon;
};

export type RelaxationCategory = {
  slug: "breathing" | "meditation" | "sleep" | "sounds" | "music" | "mindful-reset";
  title: string;
  description: string;
  href: string;
  image: string;
  imageAlt: string;
  icon: LucideIcon;
  /**
   * Whether this category is shown in the Relaxation Hub UI (cards, nav).
   * Set to false to hide a category without deleting its data/assets.
   */
  visibleInHub: boolean;
};

/** Mood chip ids double as the tag vocabulary for every hub item. */
export type RelaxationTag =
  | "relieve-stress"
  | "better-sleep"
  | "calm-down"
  | "clear-mind"
  | "feel-positive";

export type RelaxationItemKind = "breathing" | "meditation" | "mindful-reset" | "sleep";

/**
 * One real, openable item from any Relaxation section. Title, category,
 * duration and visuals are read from that section's own data so hub cards
 * and search results can never drift from the item they open.
 */
export type RelaxationItem = {
  /** Unique across kinds, e.g. "breathing:box-breathing". */
  key: string;
  kind: RelaxationItemKind;
  title: string;
  category: string;
  duration: string;
  href: string;
  tags: RelaxationTag[];
  /** Breathing and meditation items have a photo… */
  image?: string;
  imageAlt?: string;
  /** …Mindful Reset and sleep items use their own icon on a gradient instead. */
  icon?: LucideIcon;
  gradientClassName?: string;
  /** Whether the gradient is dark (white icon) or light (primary icon). */
  gradientTone?: "dark" | "light";
};

/**
 * Temporary, local frontend data. Every dataset here is shaped so a future
 * API/database layer (activity history, favorites, recommendations) can
 * replace it without changing the components that consume it.
 */

export const relaxationSidebarLinks: SidebarNavLink[] = [
  { label: "Home", href: "/", icon: Home },
  { label: "Talk to AI", href: "/ai-companion", icon: MessageCircle },
  { label: "Assessment", href: "/assessment", icon: ClipboardCheck },
  { label: "Relaxation Hub", href: "/relaxation", icon: Leaf },
  { label: "Wellness Resources", href: "/resources", icon: BookOpen },
  { label: "Journal", href: "/journal", icon: NotebookPen },
  { label: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
];

export const relaxationNeeds: RelaxationNeed[] = [
  { id: "relieve-stress", label: "Relieve Stress", icon: Leaf },
  { id: "better-sleep", label: "Better Sleep", icon: Moon },
  { id: "calm-down", label: "Calm Down", icon: Flower2 },
  { id: "clear-mind", label: "Clear My Mind", icon: Brain },
  { id: "feel-positive", label: "Feel Positive", icon: Sun },
];

export const relaxationCategories: RelaxationCategory[] = [
  {
    slug: "breathing",
    title: "Breathing Exercises",
    description: "Simple breathing techniques to help calm your mind.",
    href: "/relaxation/breathing",
    image: "/images/relaxation-hub/breathing.png",
    imageAlt: "Soft watercolor illustration of green leaves and branches",
    icon: Wind,
    visibleInHub: true,
  },
  {
    slug: "meditation",
    title: "Meditation",
    description: "Guided practices for stress, anxiety, sleep, and gratitude.",
    href: "/relaxation/meditation",
    image: "/images/relaxation-hub/meditation.png",
    imageAlt: "Balanced stones beside a calm lake at sunset",
    icon: Flower2,
    visibleInHub: true,
  },
  {
    slug: "sleep",
    title: "Sleep Support",
    description: "Peaceful sounds and practices to help you wind down.",
    href: "/relaxation/sleep",
    image: "/images/relaxation-hub/sleep-support.png",
    imageAlt: "A crescent moon over a quiet lake at night",
    icon: Moon,
    visibleInHub: true,
  },
  {
    slug: "sounds",
    title: "Relaxing Sounds",
    description: "Nature sounds to create a peaceful environment.",
    href: "/relaxation/sounds",
    image: "/images/relaxation-hub/relaxation-sounds.png",
    imageAlt: "A gentle waterfall flowing through a sunlit forest",
    icon: Trees,
    visibleInHub: false,
  },
  {
    slug: "music",
    title: "Instrumental Music",
    description: "Calming instrumental music for relaxation and focus.",
    href: "/relaxation/music",
    image: "/images/relaxation-hub/instrumental.png",
    imageAlt: "An acoustic guitar resting on a porch at sunrise",
    icon: Music2,
    visibleInHub: false,
  },
  {
    slug: "mindful-reset",
    title: "Mindful Reset",
    description:
      "Guided body-awareness and grounding practices to release tension.",
    href: "/relaxation/mindful-reset",
    image: "/images/mind%20reset/header.png",
    imageAlt:
      "A person sitting cross-legged and at ease on a wooden deck overlooking a calm lake and green mountains",
    icon: HandHeart,
    visibleInHub: true,
  },
];

/**
 * Tags per item key. Kept here (not in each section's data file) so the hub's
 * mood filter and search can evolve without touching the section engines.
 */
const relaxationItemTags: Record<string, RelaxationTag[]> = {
  "breathing:box-breathing": ["relieve-stress", "calm-down"],
  "breathing:4-7-8-breathing": ["better-sleep", "calm-down"],
  "breathing:belly-breathing": ["relieve-stress", "calm-down"],
  "breathing:extended-exhale": ["calm-down", "better-sleep"],
  "breathing:alternate-nostril-breathing": ["clear-mind", "calm-down"],
  "breathing:triangular-breathing": ["relieve-stress", "clear-mind"],
  "breathing:five-finger-breathing": ["calm-down", "clear-mind"],
  "breathing:cyclic-sighing": ["calm-down", "relieve-stress"],

  "meditation:evening-calm": ["better-sleep", "calm-down"],
  "meditation:mindful-breathing": ["relieve-stress", "clear-mind"],
  "meditation:body-scan": ["relieve-stress", "better-sleep"],
  "meditation:open-awareness": ["clear-mind"],
  "meditation:loving-kindness": ["feel-positive"],
  "meditation:peaceful-visualization": ["feel-positive", "calm-down"],
  "meditation:mantra-meditation": ["clear-mind", "calm-down"],

  "mindful-reset:progressive-muscle-relaxation": ["relieve-stress", "better-sleep"],
  "mindful-reset:body-scan": ["clear-mind", "relieve-stress"],
  "mindful-reset:somatic-grounding": ["calm-down", "clear-mind"],
  "mindful-reset:tension-and-release": ["relieve-stress"],
  "mindful-reset:mindful-body-awareness": ["clear-mind", "feel-positive"],
  "mindful-reset:5-4-3-2-1-grounding": ["calm-down", "clear-mind"],

  "sleep:sleep-meditation": ["better-sleep", "calm-down"],
  "sleep:gentle-rain": ["better-sleep", "relieve-stress"],
  "sleep:ocean-waves": ["better-sleep", "calm-down"],
  "sleep:night-forest": ["better-sleep", "feel-positive"],
  "sleep:brown-noise": ["better-sleep", "clear-mind"],
  "sleep:pink-noise": ["better-sleep", "clear-mind"],
  "sleep:soft-white-noise": ["better-sleep", "clear-mind"],
  "sleep:gentle-wind": ["better-sleep", "calm-down"],
  "sleep:rain-and-thunder": ["better-sleep"],
  "sleep:night-rain-forest": ["better-sleep", "relieve-stress"],
  "sleep:quiet-night-ambience": ["better-sleep", "calm-down"],
  "sleep:peaceful-water-stream": ["better-sleep", "feel-positive"],
};

function formatMinuteRange(options: number[]) {
  const min = Math.min(...options);
  const max = Math.max(...options);
  return min === max ? `${min} min` : `${min}–${max} min`;
}

/** Every real item across Breathing, Meditation, Mindful Reset and Sleep. */
export const relaxationCatalog: RelaxationItem[] = [
  ...breathingExercises.map((exercise): RelaxationItem => {
    const key = `breathing:${exercise.id}`;
    return {
      key,
      kind: "breathing",
      title: exercise.title,
      category: "Breathing",
      duration: formatMinuteRange(exercise.durationOptions),
      href: `/relaxation/breathing/${exercise.id}`,
      tags: relaxationItemTags[key] ?? [],
      image: exercise.image,
      imageAlt: exercise.imageAlt,
    };
  }),
  ...meditations.map((meditation): RelaxationItem => {
    const key = `meditation:${meditation.slug}`;
    return {
      key,
      kind: "meditation",
      title: meditation.title,
      category: "Meditation",
      duration: `${meditation.durationMinutes} min`,
      href: `/relaxation/meditation/${meditation.slug}`,
      tags: relaxationItemTags[key] ?? [],
      image: meditation.image,
      imageAlt: meditation.imageAlt,
    };
  }),
  ...mindfulResetActivities.map((activity): RelaxationItem => {
    const key = `mindful-reset:${activity.id}`;
    return {
      key,
      kind: "mindful-reset",
      title: activity.title,
      category: "Mindful Reset",
      duration: `${activity.durationMinutes} min`,
      href: activity.href,
      tags: relaxationItemTags[key] ?? [],
      icon: activity.icon,
      gradientClassName: activity.gradientClassName,
      gradientTone: "light",
    };
  }),
  ...sleepExperiences.map((experience): RelaxationItem => {
    const key = `sleep:${experience.id}`;
    return {
      key,
      kind: "sleep",
      title: experience.title,
      category: "Sleep Support",
      duration: "Looping",
      href: `/relaxation/sleep?sound=${encodeURIComponent(experience.id)}`,
      tags: relaxationItemTags[key] ?? [],
      icon: experience.icon,
      gradientClassName: experience.gradientClassName,
      gradientTone: "dark",
    };
  }),
];

function pickItems(keys: string[]): RelaxationItem[] {
  return keys.map((key) => {
    const item = relaxationCatalog.find((candidate) => candidate.key === key);
    if (!item) throw new Error(`Unknown relaxation item: ${key}`);
    return item;
  });
}

export const quickResetItems: RelaxationItem[] = pickItems([
  "breathing:box-breathing",
  "mindful-reset:5-4-3-2-1-grounding",
  "mindful-reset:progressive-muscle-relaxation",
  "sleep:gentle-rain",
]);

export const popularPickItems: RelaxationItem[] = pickItems([
  "sleep:night-rain-forest",
  "meditation:loving-kindness",
  "breathing:4-7-8-breathing",
  "mindful-reset:body-scan",
]);

/** Case-insensitive match on title, category and tags (tag ids and chip labels). */
export function searchRelaxationItems(query: string, limit = 8): RelaxationItem[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return [];

  return relaxationCatalog
    .filter((item) => {
      const tagText = item.tags
        .map((tag) => `${tag} ${relaxationNeeds.find((need) => need.id === tag)?.label ?? ""}`)
        .join(" ");
      const haystack = `${item.title} ${item.category} ${tagText}`.toLowerCase();
      return terms.every((term) => haystack.includes(term));
    })
    .slice(0, limit);
}
