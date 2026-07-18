import { notFound } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { PublicContentCard } from "@/components/public-content-card";
import { getPublicTopic } from "@/lib/public-discovery";

export const dynamic = "force-dynamic";
export default async function TopicDetailPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  let data;
  try { data = await getPublicTopic(slug); } catch { notFound(); }
  return <div className="page-shell topic-detail-page"><PageHeader title={data.topic.name} description={data.topic.description} />
    <section className="topic-detail-summary panel"><div><strong>{data.topic.contentCount}</strong><span>近期公开内容</span></div><div><strong>{data.topic.featuredCount}</strong><span>精选内容</span></div><p>主题由规则、AI 标签与编辑确认共同维护；下方只展示满足公开准入规则的内容。</p></section>
    <div className="favorites-list">{data.items.map((item) => <PublicContentCard item={item} showReason={item.featured} compact key={item.id} />)}</div>
  </div>;
}
