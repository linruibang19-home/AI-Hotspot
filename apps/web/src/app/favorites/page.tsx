import { WorkspacePage } from "@/components/workspace-page";

export default function FavoritesPage() {
  return <WorkspacePage title="收藏" description="登录后跨设备保存内容与事件。" actionLabel="邀请登录" metrics={[["2", "内容收藏"], ["1", "事件收藏"], ["0", "待整理"], ["3", "全部收藏"]]} cards={[{ title: "内容收藏", description: "稍后阅读的公开资讯和论文。" }, { title: "事件收藏", description: "持续追踪同一事件的后续证据。" }, { title: "权限状态", description: "M2 接入邀请登录后启用真实写入。", status: "等待 M2" }]} />;
}
