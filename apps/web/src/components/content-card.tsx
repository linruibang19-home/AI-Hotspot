type ContentCardProps = {
  item: {
    source: string;
    sourceType: string;
    title: string;
    summary: string;
    reason: string;
    tags: string[];
    score: number;
  };
};

export function ContentCard({ item }: ContentCardProps) {
  return (
    <article className="content-card">
      <div className="content-meta">
        <span className="source-avatar">{item.source.slice(0, 2)}</span>
        <span>{item.source}</span>
        <span>· {item.sourceType}</span>
        <span className="badge">✦ 精选</span>
        <span className="badge">官方/一手</span>
        <span className="score">● {item.score}</span>
      </div>
      <h2>{item.title}</h2>
      <p>{item.summary}</p>
      <div className="tag-list">
        {item.tags.map((tag) => <span key={tag}># {tag}</span>)}
      </div>
      <div className="reason"><strong>推荐理由：</strong>{item.reason}</div>
    </article>
  );
}
