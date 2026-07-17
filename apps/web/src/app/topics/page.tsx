import Link from "next/link";
import { topicGroups } from "@/lib/topics";

export default function TopicsPage() {
  const topicCount = topicGroups.reduce((total, group) => total + group.topics.length, 0);
  return (
    <div className="topics-page page-shell">
      <header className="topics-hero panel">
        <span>TOPICS · 主题地图</span>
        <h1>按主题看 AI</h1>
        <p>公司与模型、技术方向、内容形态——{topicCount} 个主题把公开内容聚合成可持续浏览的知识入口。</p>
      </header>
      {topicGroups.map((group) => (
        <section className="topic-section" key={group.title}>
          <header><h2>{group.title}</h2><p>{group.description}</p></header>
          <div className="topic-grid">
            {group.topics.map((topic) => (
              <Link className="topic-card" href={`/all?query=${encodeURIComponent(topic.query)}`} key={topic.name}>
                <h3>{topic.name}</h3>
                <p>{topic.description}</p>
                <span>查看真实内容 <b aria-hidden="true">→</b></span>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
