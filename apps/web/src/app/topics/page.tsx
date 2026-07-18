import { getPublicTopics, type PublicTopic } from "@/lib/public-discovery";

export const dynamic = "force-dynamic";
const groups = [
  ["COMPANY_MODEL", "公司与模型", "按厂商与模型系追踪：谁发布了什么，又赢了哪一局"],
  ["TECHNOLOGY", "技术方向", "按技术领域深挖：Agent、多模态、具身智能与基础设施"],
  ["CONTENT_FORM", "内容形态", "按内容类型浏览：论文、教程、观点、政策与产业信号"],
] as const;

export default async function TopicsPage() {
  const topics = await getPublicTopics();
  return <div className="topics-page page-shell">
    <header className="topics-hero panel"><span>TOPICS · 主题地图</span><h1>按主题看 AI</h1><p>公司与模型、技术方向、内容形态——{topics.length} 个主题由真实公开内容持续聚合，点击任意主题查看近期焦点与精选。</p></header>
    {groups.map(([code, title, description]) => <TopicGroup code={code} title={title} description={description} topics={topics.filter((topic) => topic.groupCode === code)} key={code} />)}
  </div>;
}

function TopicGroup({ title, description, topics }: { code: string; title: string; description: string; topics: PublicTopic[] }) {
  return <section className="topic-section"><header><h2>{title}</h2><p>{description}</p></header><div className="topic-grid">{topics.map((topic) =>
    <a className="topic-card" href={`/topics/${topic.slug}`} key={topic.id}><h3>{topic.name}</h3><p>{topic.description}</p><span>查看 {topic.contentCount} 条内容 · {topic.featuredCount} 条精选 <b aria-hidden="true">→</b></span></a>
  )}</div></section>;
}
