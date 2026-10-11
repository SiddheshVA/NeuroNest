import { useState } from "react";
import { Navigate, Outlet, useParams } from "react-router-dom";
import { RelaxationShell } from "@/components/relaxation/relaxation-shell";
import { RelaxationTopbar } from "@/components/relaxation/relaxation-topbar";
import { RelaxationHero } from "@/components/relaxation/relaxation-hero";
import { RelaxationNeeds } from "@/components/relaxation/relaxation-needs";
import { YouWereHere } from "@/components/relaxation/you-were-here";
import { FavoriteRelaxation } from "@/components/relaxation/favorite-relaxation";
import { RelaxationCategories } from "@/components/relaxation/relaxation-categories";
import { QuickResets } from "@/components/relaxation/quick-resets";
import { RecommendedRelaxation } from "@/components/relaxation/recommended-relaxation";
import { BreathingExerciseDetail } from "@/components/relaxation/breathing/breathing-exercise-detail";
import { MeditationDetail } from "@/components/relaxation/meditation/meditation-detail";
import { MindfulResetActivityDetail } from "@/components/relaxation/mindful-reset/mindful-reset-activity-detail";
import { getBreathingExerciseById } from "@/data/breathing-exercises";
import { getMeditationBySlug } from "@/data/meditations";
import { getMindfulResetActivityById } from "@/data/mindful-reset";
import {
  quickResetItems,
  type RelaxationItem,
  type RelaxationTag,
} from "@/data/relaxation";
import { useRecommendations } from "@/lib/relaxation/use-relaxation-activity";

export { BreathingPage } from "@/components/relaxation/breathing/breathing-page";
export { MeditationPage } from "@/components/relaxation/meditation/meditation-page";
export { MindfulResetPage } from "@/components/relaxation/mindful-reset/mindful-reset-page";
export { SleepPage } from "@/components/relaxation/sleep/sleep-page";
export { FavoritesPage } from "@/components/relaxation/favorites-page";

export function RelaxationLayout() {
  return (
    <RelaxationShell>
      <Outlet />
    </RelaxationShell>
  );
}

function filterByTag(items: RelaxationItem[], tag: RelaxationTag | null) {
  return tag ? items.filter((item) => item.tags.includes(tag)) : items;
}

export function RelaxationPage() {
  const [selectedTag, setSelectedTag] = useState<RelaxationTag | null>(null);
  const recommendations = useRecommendations();
  const visibleRecommendations = selectedTag
    ? recommendations.entries.filter((entry) => entry.item.tags.includes(selectedTag))
    : recommendations.entries;

  return (
    <div className="flex flex-col gap-8 sm:gap-9">
      <RelaxationTopbar currentPage="Relaxation Hub" />
      {/* Hides itself until something has been opened. */}
      <YouWereHere />
      <RelaxationHero />
      <RelaxationNeeds selected={selectedTag} onSelect={setSelectedTag} />

      {/* Hides itself until something is saved. */}
      <FavoriteRelaxation />

      <RelaxationCategories />
      <QuickResets items={filterByTag(quickResetItems, selectedTag)} />
      <RecommendedRelaxation mode={recommendations.mode} entries={visibleRecommendations} />
    </div>
  );
}

export function BreathingExerciseRoute() {
  const { exerciseId = "" } = useParams();
  const exercise = getBreathingExerciseById(exerciseId);
  if (!exercise) return <Navigate to="/relaxation/breathing" replace />;
  return <BreathingExerciseDetail exercise={exercise} />;
}

export function MeditationRoute() {
  const { slug = "" } = useParams();
  const meditation = getMeditationBySlug(slug);
  if (!meditation) return <Navigate to="/relaxation/meditation" replace />;
  return <MeditationDetail meditation={meditation} />;
}

export function MindfulResetActivityRoute() {
  const { activityId = "" } = useParams();
  const activity = getMindfulResetActivityById(activityId);
  if (!activity) return <Navigate to="/relaxation/mindful-reset" replace />;
  const { icon: Icon, ...activityWithoutIcon } = activity;
  return (
    <MindfulResetActivityDetail
      activity={activityWithoutIcon}
      icon={<Icon className="size-6" aria-hidden="true" />}
    />
  );
}
