import { PageHeader } from "@/components/page-header";

export type WorkspaceCard = {
  title: string;
  description: string;
  status?: string;
};

export function WorkspacePage({
  title,
  description,
  actionLabel,
  metrics,
  cards,
}: {
  title: string;
  description: string;
  actionLabel: string;
  metrics: Array<[string, string]>;
  cards: WorkspaceCard[];
}) {
  return (
    <div className="page-shell">
      <PageHeader
        title={title}
        description={description}
        action={<button className="button primary" type="button">{actionLabel}</button>}
      />
      <section className="metric-grid" aria-label={`${title}概览`}>
        {metrics.map(([value, label]) => (
          <div className="metric-card" key={label}>
            <strong>{value}</strong>
            <span>{label}</span>
          </div>
        ))}
      </section>
      <section className="workspace-grid">
        {cards.map((card) => (
          <article className="workspace-card" key={card.title}>
            <h2>{card.title}</h2>
            <p>{card.description}</p>
            <span className="status-line">{card.status ?? "M1 Mock 数据"}</span>
          </article>
        ))}
      </section>
    </div>
  );
}
