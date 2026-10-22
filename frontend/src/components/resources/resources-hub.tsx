import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Tabs } from "@base-ui/react/tabs";
import { BookOpen, ListChecks, PlayCircle, Search, SearchX, type LucideIcon } from "lucide-react";

import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { Input } from "@/components/ui/input";
import { ArticleCard } from "@/components/resources/article-card";
import { VideoCard } from "@/components/resources/video-card";
import { GuideCard } from "@/components/resources/guide-card";
import { ResourceEmptyState } from "@/components/resources/resource-empty-state";
import { ResourceFilterChips } from "@/components/resources/resource-filter-chips";
import { ResourceDisclaimer } from "@/components/resources/resource-disclaimer";
import { articleCategories, articles, guideTypes, guides, videos } from "@/data/wellness-resources";

type TabId = "articles" | "videos" | "guides";

const TABS: { id: TabId; label: string; icon: LucideIcon; emptyTitle: string }[] = [
  { id: "articles", label: "Articles", icon: BookOpen, emptyTitle: "Articles are coming soon." },
  { id: "videos", label: "Videos", icon: PlayCircle, emptyTitle: "Videos are coming soon." },
  { id: "guides", label: "Practical Guides", icon: ListChecks, emptyTitle: "Practical guides are coming soon." },
];

function isTabId(value: string | null): value is TabId {
  return TABS.some((tab) => tab.id === value);
}

/** Case-insensitive match against title, summary/description and tags. */
function matchesQuery(query: string, fields: { title: string; text: string; tags: string[] }) {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  return [fields.title, fields.text, ...fields.tags].some((field) => field.toLowerCase().includes(q));
}

function uniqueCategories(items: { category: string }[]) {
  return [...new Set(items.map((item) => item.category))];
}

function ResourcesHub() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const activeTab: TabId = isTabId(tabParam) ? tabParam : "articles";
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<string | null>(null);

  function changeTab(tab: TabId) {
    setFilter(null);
    setSearchParams(tab === "articles" ? {} : { tab }, { replace: true });
  }

  const filterOptions: readonly string[] =
    activeTab === "articles" ? articleCategories : activeTab === "videos" ? uniqueCategories(videos) : guideTypes;

  const visibleArticles = useMemo(
    () =>
      articles.filter(
        (a) =>
          (activeTab !== "articles" || !filter || a.category === filter) &&
          matchesQuery(query, { title: a.title, text: a.summary, tags: a.tags }),
      ),
    [activeTab, filter, query],
  );
  const visibleVideos = useMemo(
    () =>
      videos.filter(
        (v) =>
          (activeTab !== "videos" || !filter || v.category === filter) &&
          matchesQuery(query, { title: v.title, text: v.description, tags: v.tags }),
      ),
    [activeTab, filter, query],
  );
  const visibleGuides = useMemo(
    () =>
      guides.filter(
        (g) =>
          (activeTab !== "guides" || !filter || g.type === filter) &&
          matchesQuery(query, { title: g.title, text: g.summary, tags: g.tags }),
      ),
    [activeTab, filter, query],
  );

  const totals: Record<TabId, number> = {
    articles: articles.length,
    videos: videos.length,
    guides: guides.length,
  };
  const visibleCounts: Record<TabId, number> = {
    articles: visibleArticles.length,
    videos: visibleVideos.length,
    guides: visibleGuides.length,
  };

  function clearSearch() {
    setQuery("");
    setFilter(null);
  }

  function renderPanel(tab: (typeof TABS)[number]) {
    if (totals[tab.id] === 0) {
      return (
        <ResourceEmptyState
          icon={tab.icon}
          title={tab.emptyTitle}
          description="We're carefully preparing this content. Check back soon."
        />
      );
    }
    if (visibleCounts[tab.id] === 0 && !query.trim()) {
      // Only a filter chip is narrowing things down: this category is just empty so far.
      return (
        <ResourceEmptyState
          icon={tab.icon}
          title={`${tab.label} for "${filter}" are coming soon.`}
          description="We're carefully preparing this content. Check back soon, or browse everything for now."
          action={
            <button
              type="button"
              onClick={clearSearch}
              className="text-sm font-medium text-primary underline-offset-4 hover:underline"
            >
              Show all {tab.label.toLowerCase()}
            </button>
          }
        />
      );
    }
    if (visibleCounts[tab.id] === 0) {
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
        {tab.id === "articles" && visibleArticles.map((a) => <ArticleCard key={a.id} article={a} />)}
        {tab.id === "videos" && visibleVideos.map((v) => <VideoCard key={v.id} video={v} />)}
        {tab.id === "guides" && visibleGuides.map((g) => <GuideCard key={g.id} guide={g} />)}
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
            Trusted articles, videos and practical guides to help you understand and care for your wellbeing.
          </p>
        </header>

        <Tabs.Root value={activeTab} onValueChange={(value) => changeTab(value as TabId)} className="flex flex-col gap-6">
          <Tabs.List className="flex gap-1 overflow-x-auto border-b border-border">
            {TABS.map((tab) => (
              <Tabs.Tab
                key={tab.id}
                value={tab.id}
                className="-mb-px flex shrink-0 items-center gap-2 border-b-2 border-transparent px-3 py-2.5 text-sm font-medium whitespace-nowrap text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-[active]:border-primary data-[active]:text-foreground"
              >
                <tab.icon className="hidden size-4 sm:block" aria-hidden="true" />
                {tab.label}
              </Tabs.Tab>
            ))}
          </Tabs.List>

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
                aria-label="Search wellness resources"
                className="h-10 rounded-full bg-card pl-9"
              />
            </div>
            <ResourceFilterChips
              options={filterOptions}
              selected={filter}
              onSelect={setFilter}
              label={activeTab === "guides" ? "Filter guides by type" : "Filter by category"}
            />
          </div>

          {TABS.map((tab) => (
            <Tabs.Panel key={tab.id} value={tab.id} className="outline-none">
              {renderPanel(tab)}
            </Tabs.Panel>
          ))}
        </Tabs.Root>

        <ResourceDisclaimer className="border-t border-border pt-6 text-center" />
      </Container>
    </section>
  );
}

export { ResourcesHub };
