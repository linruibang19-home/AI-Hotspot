import Link from "next/link";
import { notFound } from "next/navigation";
import { PublicContentCard } from "@/components/public-content-card";
import { getPublicEvent } from "@/lib/public-discovery";

export const dynamic = "force-dynamic";
export default async function EventDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  let data;
  try { data = await getPublicEvent(id); } catch { notFound(); }
  return <article className="page-shell event-detail-page"><Link className="detail-back" href="/">← 返回精选</Link>
    <header className="event-detail-header"><div><span className="badge">热点事件</span>{data.event.factStatus === "UNCONFIRMED" ? <span className="badge warning-badge">未证实</span> : <span className="badge official-badge">已证实</span>}<span>{data.event.sourceCount} 个独立信源</span></div><h1>{data.event.title}</h1><p>{data.event.summary ?? "该事件由多条公开内容聚合，摘要正在补充。"}</p></header>
    <section className="event-metrics panel"><div><strong>{data.event.contentCount}</strong><span>关联内容</span></div><div><strong>{data.event.sourceCount}</strong><span>独立信源</span></div><div><strong>{Math.round(data.event.heatScore)}</strong><span>当前热度</span></div></section>
    <h2 className="event-section-title">来源时间线</h2><div className="favorites-list">{data.items.map((item) => <PublicContentCard item={item} showReason={item.featured} compact key={item.id} />)}</div>
  </article>;
}
