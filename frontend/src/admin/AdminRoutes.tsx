import { useEffect } from "react";
import { Navigate, Route, Routes, useParams } from "react-router-dom";
import { ThemeProvider } from "@mui/material/styles";
import { adminTheme } from "./admin-theme";
import { AdminGuard } from "./AdminGuard";
import { AdminLayout } from "./AdminLayout";
import { AdminLoginPage } from "./AdminLoginPage";
import { ProfessionalsListPage } from "./ProfessionalsListPage";
import { ProfessionalFormPage } from "./ProfessionalFormPage";
import { ReviewQueuePage } from "./ReviewQueuePage";
import { ReviewPage } from "./ReviewPage";
import { DashboardPage } from "./DashboardPage";
import { BookingRequestsPage } from "./BookingRequestsPage";
import { ResponsivenessPage } from "./ResponsivenessPage";
import { PaymentsPage } from "./PaymentsPage";

// Everything under /admin. Loaded lazily from App.tsx, so none of this code ships in the public
// bundle, and rendered outside RootLayout, so the public navbar and footer never appear here.
export default function AdminRoutes() {
  useAdminDocumentHead();

  return (
    <ThemeProvider theme={adminTheme}>
      <Routes>
        <Route path="login" element={<AdminLoginPage />} />
        <Route
          element={
            <AdminGuard>
              <AdminLayout />
            </AdminGuard>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="professionals" element={<ProfessionalsListPage />} />
          <Route path="professionals/new" element={<ProfessionalFormPage key="new" />} />
          <Route path="professionals/:id/edit" element={<EditProfessionalRoute />} />
          <Route path="review" element={<ReviewQueuePage />} />
          <Route path="review/:id" element={<ReviewRoute />} />
          <Route path="bookings" element={<BookingRequestsPage />} />
          <Route path="responsiveness" element={<ResponsivenessPage />} />
          <Route path="payments" element={<PaymentsPage />} />
          <Route path="*" element={<Navigate to="/admin" replace />} />
        </Route>
      </Routes>
    </ThemeProvider>
  );
}

// A fresh form per record, so nothing carries over between "add" and "edit" or between two records.
function EditProfessionalRoute() {
  const { id } = useParams<{ id: string }>();
  return <ProfessionalFormPage key={id} />;
}

// A fresh review page per record, so nothing carries over from the previous professional.
function ReviewRoute() {
  const { id } = useParams<{ id: string }>();
  return <ReviewPage key={id} />;
}

// Sets the tab title and asks search engines not to index admin pages; restores both on the way out.
function useAdminDocumentHead() {
  useEffect(() => {
    const previousTitle = document.title;
    document.title = "NeuroNest Admin";
    const robots = document.createElement("meta");
    robots.name = "robots";
    robots.content = "noindex, nofollow";
    document.head.appendChild(robots);
    return () => {
      document.title = previousTitle;
      robots.remove();
    };
  }, []);
}
