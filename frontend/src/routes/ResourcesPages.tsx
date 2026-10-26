import type { ReactNode } from "react";
import { useParams } from "react-router-dom";
import { Container } from "@/components/layout/container";
import { ArticleDetail } from "@/components/resources/article-detail";
import { ResourceNotFound } from "@/components/resources/resource-not-found";
import { getArticleBySlug } from "@/data/wellness-resources";

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
      {article ? <ArticleDetail article={article} /> : <ResourceNotFound kind="article" />}
    </DetailLayout>
  );
}
