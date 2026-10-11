import { useMemo } from "react";
import { useLocation, useNavigate } from "react-router-dom";

// Drop-in for the parts of next/navigation the ported components use.
export function useRouter() {
  const navigate = useNavigate();
  return useMemo(
    () => ({
      push: (href: string) => navigate(href),
      replace: (href: string) => navigate(href, { replace: true }),
      back: () => navigate(-1),
      forward: () => navigate(1),
      refresh: () => {},
      prefetch: () => {},
    }),
    [navigate],
  );
}

export function usePathname() {
  return useLocation().pathname;
}
