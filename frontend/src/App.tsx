import { lazy, Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { NuroApp } from "@/components/nuro/nuro-app";
import { PlaceholderPage } from "@/components/shared/placeholder-page";
import RootLayout from "@/routes/RootLayout";
import { NavigationTracker } from "@/components/navigation/back-link";
import HomePage from "@/routes/HomePage";
import DashboardPage from "@/routes/DashboardPage";
import MemoryPage from "@/routes/MemoryPage";
import AppointmentsPage from "@/routes/AppointmentsPage";
import AssessmentResultPage from "@/routes/AssessmentResultPage";
import { AssessmentPage, Gad7Page, K10Page, Phq9Page, Pss10Page, SciPage } from "@/routes/AssessmentPages";
import { ArticleRoute, GuideRoute, ResourcesPage, VideoRoute } from "@/routes/ResourcesPages";
import { LoginPage, RequireAuth, SignUpPage } from "@/routes/AuthPages";
import { FindProfessionalRedirect, ProfessionalProfileRoute, ProfessionalsPage } from "@/routes/ProfessionalsPages";
import {
  ProAppointmentsRoute,
  ProDashboardRoute,
  ProDaysOffRoute,
  ProJoinPage,
  ProProfileRoute,
  ProRequestRoute,
  ProRequestsRoute,
} from "@/routes/ProPages";
import {
  BreathingExerciseRoute,
  BreathingPage,
  MeditationPage,
  MeditationRoute,
  MindfulResetActivityRoute,
  MindfulResetPage,
  RelaxationLayout,
  RelaxationPage,
  SleepPage,
  FavoritesPage,
} from "@/routes/RelaxationPages";

// Admin area: its own layout and auth, loaded only when an /admin URL is opened, and not linked
// from anywhere in the public app.
const AdminRoutes = lazy(() => import("@/admin/AdminRoutes"));

export default function App() {
  return (
    <>
    <NavigationTracker />
    <Routes>
      <Route
        path="/admin/*"
        element={
          <Suspense fallback={null}>
            <AdminRoutes />
          </Suspense>
        }
      />
      <Route element={<RootLayout />}>
        <Route index element={<HomePage />} />
        <Route path="/login/*" element={<LoginPage />} />
        <Route path="/sign-up/*" element={<SignUpPage />} />

        {/* Guests can chat too; signing in adds saved history. One route with an
            optional id, so moving between chats never remounts the page. */}
        <Route path="/ai-companion/:conversationId?" element={<NuroApp />} />

        <Route path="/assessment" element={<AssessmentPage />} />
        <Route path="/assessment/gad7" element={<Gad7Page />} />
        <Route path="/assessment/phq9" element={<Phq9Page />} />
        <Route path="/assessment/pss10" element={<Pss10Page />} />
        <Route path="/assessment/k10" element={<K10Page />} />
        <Route path="/assessment/sci" element={<SciPage />} />
        <Route path="/assessment/result/:type" element={<AssessmentResultPage />} />

        <Route path="/relaxation" element={<RelaxationLayout />}>
          <Route index element={<RelaxationPage />} />
          <Route path="breathing" element={<BreathingPage />} />
          <Route path="breathing/:exerciseId" element={<BreathingExerciseRoute />} />
          <Route path="meditation" element={<MeditationPage />} />
          <Route path="meditation/:slug" element={<MeditationRoute />} />
          <Route path="mindful-reset" element={<MindfulResetPage />} />
          <Route path="mindful-reset/:activityId" element={<MindfulResetActivityRoute />} />
          <Route path="sleep" element={<SleepPage />} />
          <Route path="favorites" element={<FavoritesPage />} />
        </Route>

        <Route
          path="/dashboard"
          element={
            <RequireAuth>
              <DashboardPage />
            </RequireAuth>
          }
        />
        <Route
          path="/settings/memory"
          element={
            <RequireAuth>
              <MemoryPage />
            </RequireAuth>
          }
        />

        <Route
          path="/appointments"
          element={
            <RequireAuth>
              <AppointmentsPage />
            </RequireAuth>
          }
        />

        <Route path="/resources" element={<ResourcesPage />} />
        <Route path="/resources/articles/:slug" element={<ArticleRoute />} />
        <Route path="/resources/videos/:slug" element={<VideoRoute />} />
        <Route path="/resources/guides/:slug" element={<GuideRoute />} />

        <Route path="/professionals" element={<ProfessionalsPage />} />
        <Route path="/professionals/:id" element={<ProfessionalProfileRoute />} />

        {/* Professional self-service. Not in the navigation; linked from the directory page. */}
        <Route path="/pro/join" element={<ProJoinPage />} />
        <Route path="/pro/dashboard" element={<ProDashboardRoute />} />
        <Route path="/pro/profile" element={<ProProfileRoute />} />
        <Route path="/pro/appointments" element={<ProAppointmentsRoute />} />
        <Route path="/pro/days-off" element={<ProDaysOffRoute />} />
        <Route path="/pro/requests" element={<ProRequestsRoute />} />
        <Route path="/pro/requests/:id" element={<ProRequestRoute />} />

        {/* Old URLs from the previous frontend. */}
        <Route path="/home" element={<Navigate to="/" replace />} />
        <Route path="/companion" element={<Navigate to="/ai-companion" replace />} />
        <Route path="/assessments" element={<Navigate to="/assessment" replace />} />
        <Route path="/sign-in/*" element={<Navigate to="/login" replace />} />
        <Route path="/find-professional" element={<FindProfessionalRedirect />} />

        <Route
          path="*"
          element={
            <PlaceholderPage
              eyebrow="Coming soon"
              title="This page isn't ready yet"
              description="We're still building this part of NeuroNest. Head back home to explore what's available."
            />
          }
        />
      </Route>
    </Routes>
    </>
  );
}
