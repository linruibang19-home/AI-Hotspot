import Link from "next/link";
import { notFound } from "next/navigation";
import { getPublicContent } from "@/lib/public-content";
import { FavoriteButton } from "@/components/favorite-button";
import { Icon } from "@/components/icons";

export const dynamic = "force-dynamic";

export default async function ContentDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const item = await getPublicContent(id);
  if (!item) notFound();
  const sourceTime = item.sourcePublishedAt
    ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "long", timeStyle: "short", timeZone: "Asia/Shanghai" }).format(new Date(item.sourcePublishedAt))
    : "原信源未提供时间";
  return (
    <div className="page-shell content-detail-page">
      <header className="detail-toolbar">
        <div>
          <strong>内容详情</strong>
          <p>公开准入内容 · 保留原文链接与来源追踪</p>
        </div>
        <Link className="button" href="/all">返回全部动态</Link>
      </header>
      <article className="detail-article">
        <div className="content-meta">
          <span className="source-avatar">{item.sourceName.slice(0, 2).toUpperCase()}</span>
          <strong>{item.sourceName}</strong>
          <span>· {sourceTypeLabel(item.sourceType)}</span>
          <span className="badge official-badge">{item.sourceOfficialLevel === "OFFICIAL" ? "官方" : item.sourceOfficialLevel === "FIRST_PARTY" ? "一手" : "第三方"}</span>
          {item.featured ? <span className="badge">✦ 精选</span> : null}
          {item.finalScore !== null ? <span className="score">● {Math.round(item.finalScore)}</span> : null}
          <FavoriteButton id={item.id} />
        </div>
        <h1>{item.title}</h1>
        {item.title !== item.originalTitle ? <p className="original-title">原文标题：{item.originalTitle}</p> : null}
        <p className="detail-summary">{item.summary ?? "该条目已通过公开准入，摘要正在补充。"}</p>
        {item.recommendationReason ? <div className="reason detail-reason"><strong>推荐理由：</strong>{item.recommendationReason}</div> : null}
        <dl className="content-facts">
          <div><dt>原文时间</dt><dd>{sourceTime}</dd></div>
          <div><dt>内容类型</dt><dd>{contentTypeLabel(item.contentType)}</dd></div>
          <div><dt>事实状态</dt><dd>{item.factStatus === "CONFIRMED" ? "已确认" : "未证实"}</dd></div>
          <div><dt>公开状态</dt><dd>PUBLISHED · PUBLIC</dd></div>
        </dl>
        {item.originalUrl ? <a className="button primary original-link" href={item.originalUrl} target="_blank" rel="noreferrer">阅读原文 <Icon name="external" /></a> : null}
      </article>
    </div>
  );
}

function sourceTypeLabel(value: string) {
  return ({ COMPANY: "官网", RESEARCH: "研究", MEDIA: "媒体", COMMUNITY: "社区", PAPER: "论文", GITHUB: "GitHub" } as Record<string, string>)[value] ?? value;
}

function contentTypeLabel(value: string) {
  return ({ ARTICLE: "资讯", RESEARCH: "论文/研究", RELEASE: "模型发布", PRODUCT: "产品更新", TUTORIAL: "教程实践", OPINION: "观点" } as Record<string, string>)[value] ?? value;
}
