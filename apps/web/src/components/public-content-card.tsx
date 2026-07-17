import Link from "next/link";
import type { PublicContent } from "@/lib/public-content";

const officialLabels = {
  OFFICIAL: "官方",
  FIRST_PARTY: "一手",
  THIRD_PARTY: "第三方",
} as const;

export function PublicContentCard({ item, showReason }: { item: PublicContent; showReason: boolean }) {
  return (
    <article className="content-card public-content-card">
      <div className="content-meta">
        <span className="source-avatar">{item.sourceName.slice(0, 2).toUpperCase()}</span>
        <span className="source-name">{item.sourceName}</span>
        <span>· {item.sourceType}</span>
        {item.featured ? <span className="badge">✦ 精选</span> : null}
        <span className="badge official-badge">{officialLabels[item.sourceOfficialLevel]}</span>
        {item.factStatus === "UNCONFIRMED" ? <span className="badge warning-badge">未证实</span> : null}
        {item.finalScore !== null ? <span className="score">● {Math.round(item.finalScore)}</span> : null}
      </div>
      <h2><Link href={`/content/${item.id}`}>{item.title}</Link></h2>
      <p>{item.summary ?? "该条目已通过公开准入，摘要正在补充。"}</p>
      <div className="tag-list">
        <span># {item.contentType}</span>
        <span># {officialLabels[item.sourceOfficialLevel]}信源</span>
      </div>
      {showReason && item.recommendationReason ? (
        <div className="reason"><strong>推荐理由：</strong>{item.recommendationReason}</div>
      ) : null}
    </article>
  );
}

