import { WorkspacePage } from "@/components/workspace-page";

export default function AdminSourcesPage() {
  return <WorkspacePage title="信源管理" description="只有 ADMIN/OPERATOR 可以维护 SourceEntity 与 Endpoint。" actionLabel="新增信源" metrics={[["68", "启用 Endpoint"], ["61", "健康运行"], ["4", "需要关注"], ["327", "今日内容"]]} cards={[{ title: "RSS / Atom", description: "标准订阅入口、ETag 和游标。" }, { title: "Website / Sitemap", description: "公开官网与站点地图。" }, { title: "公开平台", description: "GitHub、Hugging Face、arXiv、OpenReview 和 HN。" }]} />;
}
