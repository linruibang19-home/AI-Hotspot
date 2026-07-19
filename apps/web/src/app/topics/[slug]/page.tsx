import { notFound } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { PublicContentCard } from "@/components/public-content-card";
import { getPublicTopic } from "@/lib/public-discovery";

export const dynamic = "force-dynamic";
export default async function TopicDetailPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  let data;
  try { data = await getPublicTopic(slug); } catch { notFound(); }
  const groups = groupByDate(data.items);
  return <div className="page-shell topic-detail-page"><PageHeader title={data.topic.name} description={data.topic.description} />
    <section className="topic-detail-summary panel"><div><strong>{data.topic.contentCount}</strong><span>近期公开内容</span></div><div><strong>{data.topic.featuredCount}</strong><span>精选内容</span></div><p>主题由规则、AI 标签与编辑确认共同维护；下方只展示满足公开准入规则的内容。</p></section>
    {groups.length === 0 ? <section className="panel empty-state"><h2>暂无通过真实模型审核的内容</h2><p>Mock、演示和未完成 LLM 评分的条目已从公开区隔离；配置真实模型并完成重新分析后会自动出现在这里。</p></section> : groups.map(([date, items]) =>
      <section className="date-section" key={date.key} aria-label={`${date.label}主题内容`}><div className="date-heading"><strong>{date.label}</strong><span>⌄</span><small>{date.weekday} · {items.length} 条</small></div><div className="timeline">{items.map((item) =>
        <div className="timeline-row" key={item.id}><time className="timeline-time" dateTime={item.publishedAt}>{formatTime(item.publishedAt)}</time><PublicContentCard item={item} showReason compact /></div>
      )}</div></section>
    )}
  </div>;
}

function groupByDate(items: Awaited<ReturnType<typeof getPublicTopic>>["items"]): Array<[{ key: string; label: string; weekday: string }, typeof items]> {
  const grouped = new Map<string, typeof items>();
  for (const item of items) {
    const date = new Date(item.sourcePublishedAt ?? item.publishedAt);
    const key = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
    grouped.set(key, [...(grouped.get(key) ?? []), item]);
  }
  return [...grouped.entries()].map(([key, dateItems]) => {
    const date = new Date(dateItems[0].sourcePublishedAt ?? dateItems[0].publishedAt);
    return [{ key, label: new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "long", day: "numeric" }).format(date), weekday: new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", weekday: "long" }).format(date) }, dateItems];
  });
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}
