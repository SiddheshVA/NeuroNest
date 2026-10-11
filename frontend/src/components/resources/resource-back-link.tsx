import Link from "next/link";
import { ChevronLeft } from "lucide-react";

function ResourceBackLink({ tab }: { tab?: "articles" | "videos" | "guides" }) {
  return (
    <Link
      href={tab ? `/resources?tab=${tab}` : "/resources"}
      className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:text-foreground"
    >
      <ChevronLeft className="size-4" aria-hidden="true" />
      Back to Resources
    </Link>
  );
}

export { ResourceBackLink };
