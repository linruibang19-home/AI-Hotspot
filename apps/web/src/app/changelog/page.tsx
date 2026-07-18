import { PageHeader } from "@/components/page-header";

const releases = [
  { version: "M5", title: "高保真产品界面", body: "完成精选、全部动态、日报/周报/月报、主题、收藏、订阅、知识库问答与 Agent 工作台界面。" },
  { version: "M4", title: "公开内容与真实信源", body: "接通信源、采集、内容处理、公开列表与详情页主链路，并补充中英文权威 AI 信源。" },
  { version: "M3", title: "内容治理基础", body: "完成准入规则、评分、去重、审核状态与公开策略的基础实现。" },
];

export default function ChangelogPage() {
  return (
    <main className="page-shell editorial-page">
      <PageHeader title="更新日志" description="记录 AI Hotspot 的产品能力、数据链路和体验变化。" />
      <section className="release-list">
        {releases.map((release) => <article className="product-panel release-item" key={release.version}><span>{release.version}</span><div><h2>{release.title}</h2><p>{release.body}</p></div></article>)}
      </section>
    </main>
  );
}
