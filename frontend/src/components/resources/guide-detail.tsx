import { Clock } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { ResourceBackLink } from "@/components/resources/resource-back-link";
import { ResourceDisclaimer } from "@/components/resources/resource-disclaimer";
import type { Guide } from "@/data/wellness-resources";

function GuideDetail({ guide }: { guide: Guide }) {
  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-8">
      <ResourceBackLink />

      <header className="flex flex-col gap-4">
        <h1 className="font-heading text-3xl font-semibold tracking-tight text-balance text-foreground sm:text-4xl">
          {guide.title}
        </h1>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
          <Badge variant="secondary">{guide.type}</Badge>
          <span className="flex items-center gap-1">
            <Clock className="size-4" aria-hidden="true" />
            {guide.durationMinutes} min
          </span>
        </div>
        <p className="text-lg leading-relaxed text-foreground/90">{guide.summary}</p>
      </header>

      <ol className="flex flex-col gap-3" aria-label="Steps">
        {guide.steps.map((step, index) => (
          <li key={step.title} className="flex gap-4 rounded-2xl bg-card p-5 ring-1 ring-foreground/10">
            <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-sm font-semibold text-primary">
              {index + 1}
            </span>
            <div className="flex flex-col gap-1">
              <h2 className="font-heading text-base font-semibold text-foreground">{step.title}</h2>
              <p className="text-sm leading-relaxed text-muted-foreground">{step.text}</p>
            </div>
          </li>
        ))}
      </ol>

      <ResourceDisclaimer />
    </div>
  );
}

export { GuideDetail };
