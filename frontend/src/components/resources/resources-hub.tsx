import { useMemo, useState } from "react";
import { BookOpen, Search, SearchX } from "lucide-react";

import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { Input } from "@/components/ui/input";
import { ArticleCard } from "@/components/resources/article-card";
import { ResourceEmptyState } from "@/components/resources/resource-empty-state";
import { ResourceFilterChips } from "@/components/resources/resource-filter-chips";
import { ResourceDisclaimer } from "@/components/resources/resource-disclaimer";
import { articleCategories, articles } from "@/data/wellness-resources";

/** Case-insensitive match against title, summary and tags. */
function matchesQuery(query: string, fields: { title: string; text: string; tags: string[] }) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [fields.title, fields.text, ...fields.tags].some((field) => field.toLowerCase().includes(q));
}

function ResourcesHub() {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<string | null>(null);

  const visibleArticles = useMemo(
    () =>
      articles.filter(
        (a) => (!filter || a.category === filter) && matchesQuery(query, { title: a.title, text: a.summary, tags: a.tags }),
      ),
    [filter, query],
  );

  function clearSearch() {
    setQuery("");
    setFilter(null);
  }

  function renderArticles() {
    if (articles.length === 0) {
      return (
        <ResourceEmptyState
          icon={BookOpen}
          title="Articles are coming soon."
          description="We're carefully preparing this content. Check back soon."
        />
      );
    }
    if (visibleArticles.length === 0 && !query.trim()) {
      // Only a filter chip is narrowing things down: this category is just empty so far.
      return (
        <ResourceEmptyState
          icon={BookOpen}
          title={`Articles for "${filter}" are coming soon.`}
          description="We're carefully preparing this content. Check back soon, or browse everything for now."
          action={
            <button
              type="button"
              onClick={clearSearch}
              className="text-sm font-medium text-primary underline-offset-4 hover:underline"
            >
              Show all articles
            </button>
          }
        />
      );
    }
    if (visibleArticles.length === 0) {
      return (
        <ResourceEmptyState
          icon={SearchX}
          title="No results found"
          description="Try a different search term or clear the filters."
          action={
            <button
              type="button"
              onClick={clearSearch}
              className="text-sm font-medium text-primary underline-offset-4 hover:underline"
            >
              Clear search and filters
            </button>
          }
        />
      );
    }

    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 sm:gap-5">
        {visibleArticles.map((a) => (
          <ArticleCard key={a.id} article={a} />
        ))}
      </div>
    );
  }

  return (
    <section className="py-10 sm:py-14">
      <Container className="flex flex-col gap-8">
        <BackLink className="-mb-6" />
        <header className="flex flex-col gap-2">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Wellness Resources
          </h1>
          <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
            Trusted articles to help you understand and care for your wellbeing.
          </p>
        </header>

        <div className="flex flex-col gap-6">
          <div className="flex flex-col gap-4">
            <div className="relative w-full sm:max-w-md">
              <Search
                className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Search by title, topic or tag..."
                aria-label="Search articles"
                className="h-10 rounded-full bg-card pl-9"
              />
            </div>
            <ResourceFilterChips
              options={articleCategories}
              selected={filter}
              onSelect={setFilter}
              label="Filter articles by category"
            />
          </div>

          {renderArticles()}
        </div>

        <ResourceDisclaimer className="border-t border-border pt-6 text-center" />
      </Container>
    </section>
  );
}

export { ResourcesHub };
