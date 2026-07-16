import { WorkspacePage } from "@/components/workspace-page";

export default function AllPage() {
  return <WorkspacePage title="全部 AI 动态" description="只展示已准入、PUBLISHED 且 PUBLIC 的公开内容。" actionLabel="筛选内容" metrics={[["327", "今日公开"], ["68", "启用信源"], ["13", "独立事件"], ["0", "越权内容"]]} cards={[{ title: "公开时间线", description: "按发布时间展示官方、论文、媒体和社区公开内容。" }, { title: "组合筛选", description: "来源、主题、事实状态和时间范围写入 URL。" }, { title: "游标分页", description: "稳定分页，不暴露连续数据库 ID。" }]} />;
}
