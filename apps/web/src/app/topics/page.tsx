import Link from "next/link";
import { topicGroups } from "@/lib/topics";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function TopicsPage() {
  const topicCount = topicGroups.reduce((total, group) => total + group.topics.length, 0);
  const contents = (await getPublicContents(false)).items;
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
                <span>查看 {countMatches(contents, topic.query)} 条近期内容 <b aria-hidden="true">→</b></span>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

function countMatches(items: Array<{ title: string; summary: string | null; sourceName: string }>, query: string) {
  const terms = query.toLocaleLowerCase("zh-CN").split(/\s+/).filter(Boolean);
  return items.filter((item) => {
    const text = `${item.title} ${item.summary ?? ""} ${item.sourceName}`.toLocaleLowerCase("zh-CN");
    return terms.some((term) => text.includes(term));
  }).length;
}
