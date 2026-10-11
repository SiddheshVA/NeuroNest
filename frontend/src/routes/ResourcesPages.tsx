import type { ReactNode } from "react";
import { useParams } from "react-router-dom";
import { Container } from "@/components/layout/container";
import { ArticleDetail } from "@/components/resources/article-detail";
import { VideoDetail } from "@/components/resources/video-detail";
import { GuideDetail } from "@/components/resources/guide-detail";
import { ResourceNotFound } from "@/components/resources/resource-not-found";
import { getArticleBySlug, getGuideBySlug, getVideoBySlug } from "@/data/wellness-resources";

export { ResourcesHub as ResourcesPage } from "@/components/resources/resources-hub";

function DetailLayout({ children }: { children: ReactNode }) {
  return (
    <section className="py-8 sm:py-12">
      <Container>{children}</Container>
    </section>
  );
}

export function ArticleRoute() {
  const { slug = "" } = useParams();
  const article = getArticleBySlug(slug);
  return (
    <DetailLayout>
      {article ? <ArticleDetail article={article} /> : <ResourceNotFound kind="article" tab="articles" />}
    </DetailLayout>
  );
}

export function VideoRoute() {
  const { slug = "" } = useParams();
  const video = getVideoBySlug(slug);
  return (
    <DetailLayout>
      {video ? <VideoDetail video={video} /> : <ResourceNotFound kind="video" tab="videos" />}
    </DetailLayout>
  );
}

export function GuideRoute() {
  const { slug = "" } = useParams();
  const guide = getGuideBySlug(slug);
  return (
    <DetailLayout>
      {guide ? <GuideDetail guide={guide} /> : <ResourceNotFound kind="guide" tab="guides" />}
    </DetailLayout>
  );
}
