import Link from "next/link";
import { ContentCard } from "@/components/content-card";
import { PageHeader } from "@/components/page-header";
import { featuredItems } from "@/lib/mock-data";

export default function FeaturedPage() {
  return (
    <div className="page-shell">
      <PageHeader
        title="精选"
        description="AI 自动挑选的高价值公开内容 · M1 工程骨架"
        action={<Link className="button primary" href="/research">进入知识库</Link>}
      />
      <div className="toolbar">
        <div className="tabs" aria-label="精选分类">
          {['全部', '官方', '论文', '社区', '媒体'].map((tab, index) => (
            <span className={`tab ${index === 0 ? 'active' : ''}`} key={tab}>{tab}</span>
          ))}
        </div>
        <label className="search-shell">
          <input aria-label="搜索公开内容" placeholder="搜索标题 / 摘要 / 正文..." />
          <span aria-hidden="true">⌕</span>
        </label>
      </div>
      <section className="panel hot-panel" aria-labelledby="hot-title">
        <div className="panel-title" id="hot-title">
          当前热点 <small>基于独立信源数量与时效计算</small>
        </div>
        {[
          ['1', 'Agent 产品开始从 Demo 进入可交付工作流阶段', '5 个独立信源'],
          ['2', 'Coding Agent 文件系统权限边界受到关注', '4 个独立信源'],
          ['3', '端侧多模态与长上下文模型持续密集发布', '4 个独立信源'],
        ].map(([rank, title, count]) => (
          <div className="hot-row" key={rank}>
            <span className="hot-rank">{rank}</span>
            <strong>{title}</strong>
            <small>{count}</small>
          </div>
        ))}
      </section>
      <div className="date-heading">7月16日 <small>星期四 · {featuredItems.length} 条</small></div>
      <div className="timeline">
        {featuredItems.map((item) => (
          <div className="timeline-row" key={item.title}>
            <time className="timeline-time">{item.time}</time>
            <ContentCard item={item} />
          </div>
        ))}
      </div>
    </div>
  );
}
