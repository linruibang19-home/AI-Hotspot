import Link from "next/link";
import type { PublicContent } from "@/lib/public-content";
import { FavoriteButton } from "@/components/favorite-button";

const officialLabels = {
  OFFICIAL: "官方",
  FIRST_PARTY: "一手",
  THIRD_PARTY: "第三方",
} as const;

const sourceTypeLabels: Record<string, string> = {
  COMPANY: "官网",
  RESEARCH: "研究",
  MEDIA: "媒体",
  COMMUNITY: "社区",
  PAPER: "论文",
  GITHUB: "GitHub",
};

const contentTypeLabels: Record<string, string> = {
  ARTICLE: "资讯",
  RESEARCH: "论文/研究",
  RELEASE: "模型发布",
  PRODUCT: "产品更新",
  TUTORIAL: "教程/实践",
  OPINION: "观点",
};

export function PublicContentCard({ item, showReason, compact = false }: { item: PublicContent; showReason: boolean; compact?: boolean }) {
  return (
    <article className={`content-card public-content-card ${compact ? "compact" : ""}`}>
      <div className="content-meta">
        <span className="source-avatar">{item.sourceName.slice(0, 2).toUpperCase()}</span>
        <span className="source-name">{item.sourceName}</span>
        <span className="source-type">· {sourceTypeLabels[item.sourceType] ?? item.sourceType}</span>
        {item.featured ? <span className="badge">✦ 精选</span> : null}
        <span className="badge official-badge">{officialLabels[item.sourceOfficialLevel]}</span>
        {item.factStatus === "UNCONFIRMED" ? <span className="badge warning-badge">未证实</span> : null}
        {item.finalScore !== null ? <span className="score">● {Math.round(item.finalScore)}</span> : null}
        <FavoriteButton id={item.id} />
      </div>
      <h2><Link href={`/content/${item.id}`}>{item.title}</Link></h2>
      <p>{item.summary ?? "该条目已通过公开准入，摘要正在补充。"}</p>
      <div className="tag-list">
        <span># {contentTypeLabels[item.contentType] ?? item.contentType}</span>
        <span># {officialLabels[item.sourceOfficialLevel]}信源</span>
      </div>
      {showReason && item.recommendationReason ? (
        <div className="reason"><strong>推荐理由：</strong>{item.recommendationReason}</div>
      ) : null}
    </article>
  );
}
