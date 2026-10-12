import { Navigate, useLocation, useParams } from "react-router-dom";
import { ProfessionalsDirectory } from "@/components/professionals/professionals-directory";
import { ProfessionalProfile } from "@/components/professionals/professional-profile";

export function ProfessionalsPage() {
  return <ProfessionalsDirectory />;
}

/** Keyed by id so moving between profiles starts from a fresh loading state. */
export function ProfessionalProfileRoute() {
  const { id = "" } = useParams<{ id: string }>();
  return <ProfessionalProfile key={id} id={id} />;
}

/** The directory used to be a placeholder at /find-professional; old links keep their query string. */
export function FindProfessionalRedirect() {
  const { search } = useLocation();
  return <Navigate to={`/professionals${search}`} replace />;
}
