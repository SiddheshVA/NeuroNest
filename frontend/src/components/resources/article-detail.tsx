import type { ReactNode } from "react";
import Link from "next/link";
import { CalendarCheck, ClipboardList, Clock, ExternalLink, HeartHandshake, TriangleAlert } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { CrisisResourcesBox } from "@/components/shared/crisis-resources-box";
import { ResourceBackLink } from "@/components/resources/resource-back-link";
import { ResourceDisclaimer } from "@/components/resources/resource-disclaimer";
import type { Article, RelatedAssessment } from "@/data/wellness-resources";

// Each href matches an assessment route in App.tsx. WHO-5 has no route of its
// own, so it opens the check-in modal on the Assessment hub instead.
const ASSESSMENT_LINKS: Record<RelatedAssessment, { label: string; href: string }> = {
  WHO5: { label: "Take the WHO-5 wellbeing check", href: "/assessment?start=who5" },
  GAD7: { label: "Take the GAD-7 check-in", href: "/assessment/gad7" },
  PHQ9: { label: "Take the PHQ-9 check-in", href: "/assessment/phq9" },
  PSS10: { label: "Take the PSS-10 stress check", href: "/assessment/pss10" },
  SCI: { label: "Take the SCI sleep check-in", href: "/assessment/sci" },
};

// Currently a PlaceholderPage route in App.tsx until the directory is built.
const FIND_PROFESSIONAL_HREF = "/find-professional";

// The old placeholder text; never shown as a reviewer's name.
const PENDING_REVIEW_LABEL = "Pending clinical review";

/** The reviewer's name when the article has really been reviewed; null while review is pending. */
function reviewerName(article: Article): string | null {
  if (article.reviewStatus !== "reviewed") return null;
  const name = article.reviewedBy?.trim();
  if (!name || name.toLowerCase() === PENDING_REVIEW_LABEL.toLowerCase()) return null;
  return name;
}

const primaryButton =
  "inline-flex w-full items-center justify-center gap-2 rounded-full bg-primary px-6 py-3 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 sm:w-auto";
const secondaryButton =
  "inline-flex w-full items-center justify-center gap-2 rounded-full border border-border bg-card px-6 py-3 text-sm font-medium text-foreground transition-colors hover:bg-muted sm:w-auto";

function formatDate(iso: string) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });
}

function ArticleSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h2 className="font-heading text-xl font-semibold text-foreground sm:text-2xl">{title}</h2>
      <div className="flex flex-col gap-4 text-base leading-relaxed text-foreground/85">{children}</div>
    </section>
  );
}

function BulletList({ items }: { items: ReactNode[] }) {
  return (
    <ul className="flex list-disc flex-col gap-1.5 pl-5 marker:text-primary">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  );
}

/** Bold lead-in followed by the rest of the sentence, e.g. "Work: long hours". */
function LeadIn({ title, text, separator = " " }: { title: string; text: string; separator?: string }) {
  return (
    <>
      <strong className="font-semibold text-foreground">{title}</strong>
      {separator}
      {text}
    </>
  );
}

function ArticleDetail({ article }: { article: Article }) {
  const { sections } = article;
  const isPendingReview = article.reviewStatus === "pending";
  const reviewer = reviewerName(article);
  const assessment = article.relatedAssessment ? ASSESSMENT_LINKS[article.relatedAssessment] : null;

  return (
    <article className="mx-auto flex w-full max-w-3xl flex-col gap-10">
      <ResourceBackLink />

      <header className="flex flex-col gap-4">
        <h1 className="font-heading text-3xl font-semibold tracking-tight text-balance text-foreground sm:text-4xl">
          {article.title}
        </h1>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-muted-foreground">
          <Badge variant="secondary">{article.category}</Badge>
          <span className="flex items-center gap-1">
            <Clock className="size-4" aria-hidden="true" />
            {article.readingTimeMinutes} min read
          </span>
          {!isPendingReview && article.lastReviewedDate ? (
            <span className="flex items-center gap-1">
              <CalendarCheck className="size-4" aria-hidden="true" />
              Last reviewed {formatDate(article.lastReviewedDate)}
            </span>
          ) : null}
        </div>
      </header>

      {article.showCrisisBoxAtTop ? <CrisisResourcesBox /> : null}

      <p className="text-lg leading-relaxed text-foreground/90">{sections.intro}</p>

      <ArticleSection title="What it is">
        {sections.whatItIs.map((paragraph, index) => (
          <p key={index}>{paragraph}</p>
        ))}
      </ArticleSection>

      <ArticleSection title="Common signs">
        <BulletList items={sections.signs.items} />
        <p>{sections.signs.note}</p>
      </ArticleSection>

      <ArticleSection title="Why it happens">
        {sections.whyItHappens.paragraphs.map((paragraph, index) => (
          <p key={index}>{paragraph}</p>
        ))}
        <BulletList
          items={sections.whyItHappens.causes.map((cause) => (
            <LeadIn title={`${cause.title}:`} text={cause.text} />
          ))}
        />
        <p>{sections.whyItHappens.closing}</p>
      </ArticleSection>

      <ArticleSection title="What you can do">
        <ol className="flex list-decimal flex-col gap-3 pl-5 marker:font-semibold marker:text-primary">
          {sections.whatYouCanDo.steps.map((step) => (
            <li key={step.title}>
              <LeadIn title={step.title} text={step.text} />
            </li>
          ))}
        </ol>
        <div className="flex items-start gap-3 rounded-2xl border border-[#EADFC8] bg-[#FBF5E8] p-4 text-sm text-[#6B5426]">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <p>{sections.whatYouCanDo.thingsToAvoid}</p>
        </div>
      </ArticleSection>

      <ArticleSection title="When to seek help">
        <p>{sections.whenToSeekHelp.intro}</p>
        <BulletList items={sections.whenToSeekHelp.points} />
        <p>{sections.whenToSeekHelp.closing}</p>
        <div className="mt-1 flex flex-col gap-3 sm:flex-row sm:flex-wrap">
          {assessment ? (
            <Link href={assessment.href} className={primaryButton}>
              <ClipboardList className="size-4" aria-hidden="true" />
              {assessment.label}
            </Link>
          ) : null}
          <Link href={FIND_PROFESSIONAL_HREF} className={assessment ? secondaryButton : primaryButton}>
            <HeartHandshake className="size-4" aria-hidden="true" />
            Find a licensed professional
          </Link>
        </div>
      </ArticleSection>

      <CrisisResourcesBox />

      <ResourceDisclaimer />

      <ArticleSection title="Sources">
        <ul className="flex flex-col gap-2 text-sm">
          {article.sources.map((source) => (
            <li key={source.url}>
              <a
                href={source.url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline"
              >
                {source.title}
                <ExternalLink className="size-3.5 shrink-0" aria-hidden="true" />
                <span className="sr-only">(opens in a new tab)</span>
              </a>
            </li>
          ))}
        </ul>
      </ArticleSection>

      {reviewer ? (
        <ArticleSection title="Reviewed by">
          <p className="text-sm text-muted-foreground">{reviewer}</p>
        </ArticleSection>
      ) : null}
    </article>
  );
}

export { ArticleDetail };
