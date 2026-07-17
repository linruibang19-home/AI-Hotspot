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
        action={<button className="button" type="button" disabled title="该业务将在对应后续里程碑接入真实数据与操作">{actionLabel} · 尚未接通</button>}
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
            <span className="status-line">{card.status ?? "方案占位 · 尚未接通业务"}</span>
          </article>
        ))}
      </section>
    </div>
  );
}
