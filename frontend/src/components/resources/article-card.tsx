import Link from "next/link";
import { ArrowRight, Clock } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { Article } from "@/data/wellness-resources";

function ArticleCard({ article }: { article: Article }) {
  return (
    <Link
      href={`/resources/articles/${article.slug}`}
      className="group flex flex-col gap-3 rounded-2xl bg-card p-5 outline-none ring-1 ring-foreground/10 transition-all duration-300 hover:-translate-y-1 hover:shadow-lg focus-visible:ring-3 focus-visible:ring-ring/50"
    >
      <Badge variant="secondary">{article.category}</Badge>
      <h3 className="font-heading text-lg font-semibold text-foreground">{article.title}</h3>
      <p className="line-clamp-3 text-sm text-muted-foreground">{article.summary}</p>
      <div className="mt-auto flex items-center justify-between pt-2">
        <span className="flex items-center gap-1 text-xs text-muted-foreground">
          <Clock className="size-3.5" aria-hidden="true" />
          {article.readingTimeMinutes} min read
        </span>
        <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary transition-transform group-hover:translate-x-0.5">
          <ArrowRight className="size-3.5" aria-hidden="true" />
        </span>
      </div>
    </Link>
  );
}

export { ArticleCard };
