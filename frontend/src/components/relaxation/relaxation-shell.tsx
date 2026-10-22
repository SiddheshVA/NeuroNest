import type { ReactNode } from "react";

import { RelaxationSidebar } from "@/components/relaxation/relaxation-sidebar";
import { RelaxationMobileTopbar } from "@/components/relaxation/relaxation-mobile-topbar";
import { BackLink } from "@/components/navigation/back-link";

function RelaxationShell({ children }: { children: ReactNode }) {
  return (
    <div className="lg:flex lg:h-screen lg:overflow-hidden">
      <RelaxationSidebar />

      <div className="min-w-0 flex-1 lg:h-screen lg:overflow-y-auto">
        <RelaxationMobileTopbar />

        <div className="mx-auto w-full max-w-6xl px-4 pt-5 pb-10 sm:px-6 sm:pt-6 lg:px-10 lg:pt-8">
          {/* The back control for every relaxation page, above its own header (central map). */}
          <BackLink className="mb-3" />
          {children}
        </div>
      </div>
    </div>
  );
}

export { RelaxationShell };
