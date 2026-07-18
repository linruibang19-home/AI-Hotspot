import { PageHeader } from "@/components/page-header";

export default function AboutPage() {
  return (
    <main className="page-shell editorial-page">
      <PageHeader title="关于 AI Hotspot" description="把分散的 AI 信息，整理成可验证、可追踪、可复用的知识。" />
      <section className="editorial-hero product-panel">
        <span className="overline">ABOUT · AI HOTSPOT</span>
        <h2>少追热点，多看脉络</h2>
        <p>AI Hotspot 聚合公开、可信的 AI 信源，通过去重、事件关联、评分和编辑流程，提供精选动态、主题追踪与周期报告。我们保留原始来源和英文原文，不把模型生成内容伪装成事实。</p>
      </section>
      <section className="principle-grid">
        <article className="product-panel"><b>01</b><h3>来源透明</h3><p>每条内容保留来源、发布时间、原文入口和内容策略。</p></article>
        <article className="product-panel"><b>02</b><h3>事件优先</h3><p>允许跨信源、跨时间关联同一事件，减少重复阅读。</p></article>
        <article className="product-panel"><b>03</b><h3>权限先行</h3><p>公开内容与私有知识严格隔离，检索前完成权限过滤。</p></article>
      </section>
    </main>
  );
}
