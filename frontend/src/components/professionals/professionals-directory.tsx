import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { ChevronLeft, ChevronRight, RefreshCw, Search, SearchX } from "lucide-react";

import { Container } from "@/components/layout/container";
import { BackLink } from "@/components/navigation/back-link";
import { Input } from "@/components/ui/input";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { ResourceEmptyState } from "@/components/resources/resource-empty-state";
import { ProfessionalCard } from "@/components/professionals/professional-card";
import { DirectoryDisclaimer } from "@/components/professionals/professional-bits";
import { cn } from "@/lib/utils";
import {
  CONSULTATION_MODE_LABELS,
  CREDENTIAL_TYPE_LABELS,
  CREDENTIAL_TYPES,
  DIRECTORY_PAGE_SIZE,
  DIRECTORY_SORT_LABELS,
  DIRECTORY_SORTS,
  getProfessionalMeta,
  ProfessionalsApiError,
  SEARCH_MODES,
  searchProfessionals,
  type CredentialType,
  type DirectorySort,
  type ProfessionalMeta,
  type ProfessionalPage,
  type SearchMode,
} from "@/lib/professionals-client";

const EMPTY_MESSAGE = "No professionals match these filters. Try removing a filter.";

type FilterKey = "q" | "type" | "city" | "language" | "mode" | "sort" | "page";

const selectClassName =
  "h-10 w-full min-w-0 rounded-full border border-input bg-card px-3.5 text-sm text-foreground outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/** Filters and the page number live in the URL so results can be shared, reloaded and revisited. */
function useDirectoryParams() {
  const [params, setParams] = useSearchParams();
  const typeParam = params.get("type") ?? "";
  const modeParam = params.get("mode") ?? "";
  const sortParam = params.get("sort") ?? "";
  const pageParam = Number(params.get("page"));

  const values = {
    q: params.get("q") ?? "",
    credentialType: (CREDENTIAL_TYPES as readonly string[]).includes(typeParam) ? (typeParam as CredentialType) : "",
    city: params.get("city") ?? "",
    language: params.get("language") ?? "",
    mode: (SEARCH_MODES as readonly string[]).includes(modeParam) ? (modeParam as SearchMode) : "",
    sort: (DIRECTORY_SORTS as readonly string[]).includes(sortParam) ? (sortParam as DirectorySort) : "",
    // URL pages count from 1 for people; the API counts from 0.
    page: Number.isInteger(pageParam) && pageParam > 1 ? pageParam - 1 : 0,
    urgent: params.get("urgent") === "1",
  } as const;

  // Any filter or sort change returns to the first page. Other parameters (such as urgent) are kept.
  function update(changes: Partial<Record<FilterKey, string>>) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (!("page" in changes)) next.delete("page");
        for (const [key, value] of Object.entries(changes)) {
          if (value) next.set(key, value);
          else next.delete(key);
        }
        if (next.get("page") === "1") next.delete("page");
        return next;
      },
      { replace: true },
    );
  }

  function clearFilters() {
    setParams(
      (current) => {
        const next = new URLSearchParams();
        if (current.get("urgent") === "1") next.set("urgent", "1");
        // The sort is an order, not a filter: clearing the filters keeps it.
        const sort = current.get("sort");
        if (sort) next.set("sort", sort);
        return next;
      },
      { replace: true },
    );
  }

  return { values, update, clearFilters };
}

type Loaded = { key: string | null; data: ProfessionalPage | null; error: string | null };

function ProfessionalsDirectory() {
  const { values, update, clearFilters } = useDirectoryParams();
  const { q, credentialType, city, language, mode, sort, page, urgent } = values;

  const [searchText, setSearchText] = useState(q);
  const [meta, setMeta] = useState<ProfessionalMeta>({ cities: [], languages: [] });
  const [retryCount, setRetryCount] = useState(0);
  const [loaded, setLoaded] = useState<Loaded>({ key: null, data: null, error: null });

  const queryKey = JSON.stringify([q, credentialType, city, language, mode, sort, page, retryCount]);
  const loading = loaded.key !== queryKey;
  const data = loaded.data;
  const error = loading ? null : loaded.error;
  const hasFilters = Boolean(q || credentialType || city || language || mode);

  // Search runs after a short pause in typing.
  useEffect(() => {
    if (searchText.trim() === q.trim()) return;
    const timer = window.setTimeout(() => update({ q: searchText.trim() }), 350);
    return () => window.clearTimeout(timer);
    // update is recreated each render but only reads the latest URL, so it is safe to omit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchText, q]);

  useEffect(() => {
    const controller = new AbortController();
    getProfessionalMeta(controller.signal)
      .then(setMeta)
      .catch(() => {
        // Without meta the city and language lists only offer "All"; searching still works.
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    searchProfessionals(
      { q, credentialType, city, language, mode, sort, page, size: DIRECTORY_PAGE_SIZE },
      controller.signal,
    )
      .then((result) => setLoaded({ key: queryKey, data: result, error: null }))
      .catch((e) => {
        if (controller.signal.aborted) return;
        const message =
          e instanceof ProfessionalsApiError && e.status !== 0
            ? "The directory could not be loaded. Please try again in a moment."
            : e instanceof Error
              ? e.message
              : "The directory could not be loaded. Please try again in a moment.";
        setLoaded((current) => ({ key: queryKey, data: current.data, error: message }));
      });
    return () => controller.abort();
  }, [queryKey, q, credentialType, city, language, mode, sort, page]);

  // A city or language from a shared link that is no longer offered still shows as selected.
  const cityOptions = city && !meta.cities.includes(city) ? [city, ...meta.cities] : meta.cities;
  const languageOptions =
    language && !meta.languages.includes(language) ? [language, ...meta.languages] : meta.languages;

  const totalPages = data?.totalPages ?? 0;

  function goToPage(nextPage: number) {
    update({ page: String(nextPage + 1) });
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function resetAll() {
    setSearchText("");
    clearFilters();
  }

  return (
    <section className="py-10 sm:py-14">
      <Container className="flex flex-col gap-6">
        <BackLink className="-mb-4" />
        {urgent ? (
          <CrisisResourcesBox
            intro={
              <>
                If you are in crisis or thinking about harming yourself, please call one of these free helplines
                now. You do not need to wait for an appointment.
              </>
            }
          />
        ) : null}

        <header className="flex flex-col gap-2">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
            Find a Professional
          </h1>
          <p className="max-w-2xl text-base leading-relaxed text-muted-foreground">
            Browse counselors, psychologists and psychiatrists, and filter by city, language and how they consult.
          </p>
        </header>

        <DirectoryDisclaimer />

        <p className="-mt-3 text-sm text-muted-foreground">
          <Link to="/pro/join" className="font-medium text-primary underline-offset-4 hover:underline">
            Are you a licensed professional? List your practice on NeuroNest.
          </Link>
        </p>

        <div
          role="search"
          aria-label="Filter professionals"
          className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-[2fr_repeat(4,1fr)]"
        >
          <div className="relative sm:col-span-2 lg:col-span-1">
            <Search
              className="pointer-events-none absolute top-1/2 left-3.5 size-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              type="search"
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="Search by name or specialty"
              aria-label="Search by name or specialty"
              className="h-10 rounded-full bg-card pl-9"
            />
          </div>
          <select
            aria-label="Type of professional"
            value={credentialType}
            onChange={(event) => update({ type: event.target.value })}
            className={selectClassName}
          >
            <option value="">All types</option>
            {CREDENTIAL_TYPES.map((type) => (
              <option key={type} value={type}>
                {CREDENTIAL_TYPE_LABELS[type]}
              </option>
            ))}
          </select>
          <select
            aria-label="City"
            value={city}
            onChange={(event) => update({ city: event.target.value })}
            className={selectClassName}
          >
            <option value="">All cities</option>
            {cityOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
          <select
            aria-label="Language"
            value={language}
            onChange={(event) => update({ language: event.target.value })}
            className={selectClassName}
          >
            <option value="">All languages</option>
            {languageOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
          <select
            aria-label="Consultation mode"
            value={mode}
            onChange={(event) => update({ mode: event.target.value })}
            className={selectClassName}
          >
            <option value="">Online or in person</option>
            {SEARCH_MODES.map((option) => (
              <option key={option} value={option}>
                {CONSULTATION_MODE_LABELS[option]}
              </option>
            ))}
          </select>
        </div>

        <div className="flex min-h-5 flex-wrap items-center justify-between gap-2 text-sm text-muted-foreground">
          <span aria-live="polite">
            {loading && !data
              ? "Loading professionals."
              : data
                ? `${data.totalElements} ${data.totalElements === 1 ? "professional" : "professionals"} found.`
                : null}
          </span>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            {hasFilters ? (
              <button
                type="button"
                onClick={resetAll}
                className="rounded-sm font-medium text-primary underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                Clear all filters
              </button>
            ) : null}
            <label className="flex items-center gap-2">
              <span className="text-foreground">Sort by</span>
              <select
                value={sort}
                onChange={(event) => update({ sort: event.target.value })}
                className={cn(selectClassName, "w-auto")}
              >
                <option value="">Name (A to Z)</option>
                {DIRECTORY_SORTS.map((option) => (
                  <option key={option} value={option}>
                    {DIRECTORY_SORT_LABELS[option]}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </div>

        {error ? (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive">
            <span>{error}</span>
            <button
              type="button"
              onClick={() => setRetryCount((n) => n + 1)}
              className="inline-flex items-center gap-1.5 font-medium underline-offset-4 hover:underline"
            >
              <RefreshCw className="size-4" aria-hidden="true" />
              Try again
            </button>
          </div>
        ) : null}

        {!data && loading ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 sm:gap-5" aria-hidden="true">
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="h-72 animate-pulse rounded-2xl bg-muted" />
            ))}
          </div>
        ) : data && data.items.length === 0 ? (
          <ResourceEmptyState
            icon={SearchX}
            title={hasFilters ? EMPTY_MESSAGE : "There are no professionals listed yet. Please check back soon."}
            action={
              hasFilters ? (
                <button
                  type="button"
                  onClick={resetAll}
                  className="text-sm font-medium text-primary underline-offset-4 hover:underline"
                >
                  Clear all filters
                </button>
              ) : undefined
            }
          />
        ) : data ? (
          <div
            className={cn(
              "grid grid-cols-1 gap-4 transition-opacity sm:grid-cols-2 lg:grid-cols-3 sm:gap-5",
              loading && "opacity-60",
            )}
            aria-busy={loading}
          >
            {data.items.map((professional) => (
              <ProfessionalCard key={professional.id} professional={professional} />
            ))}
          </div>
        ) : null}

        {data && totalPages > 1 ? (
          <nav aria-label="Pages" className="flex items-center justify-center gap-3">
            <button
              type="button"
              onClick={() => goToPage(page - 1)}
              disabled={page === 0 || loading}
              className="inline-flex h-9 items-center gap-1 rounded-full border border-border bg-card px-3.5 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50"
            >
              <ChevronLeft className="size-4" aria-hidden="true" />
              Previous
            </button>
            <span className="text-sm text-muted-foreground">
              Page {Math.min(page + 1, totalPages)} of {totalPages}
            </span>
            <button
              type="button"
              onClick={() => goToPage(page + 1)}
              disabled={page + 1 >= totalPages || loading}
              className="inline-flex h-9 items-center gap-1 rounded-full border border-border bg-card px-3.5 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:pointer-events-none disabled:opacity-50"
            >
              Next
              <ChevronRight className="size-4" aria-hidden="true" />
            </button>
          </nav>
        ) : null}
      </Container>
    </section>
  );
}

export { ProfessionalsDirectory };
