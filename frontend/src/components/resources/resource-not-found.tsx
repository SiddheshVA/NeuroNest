import { SearchX } from "lucide-react";
import { ResourceBackLink } from "@/components/resources/resource-back-link";
import { ResourceEmptyState } from "@/components/resources/resource-empty-state";

function ResourceNotFound({ kind }: { kind: string }) {
  return (
    <div className="flex flex-col gap-8">
      <ResourceBackLink />
      <ResourceEmptyState
        icon={SearchX}
        title={`We couldn't find that ${kind}`}
        description={`It may have been moved or isn't published yet. Head back to Wellness Resources to see what's available.`}
      />
    </div>
  );
}

export { ResourceNotFound };
