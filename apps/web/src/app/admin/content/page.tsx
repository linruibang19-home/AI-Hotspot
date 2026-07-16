import { WorkspacePage } from "@/components/workspace-page";

export default function AdminContentPage() {
  return <WorkspacePage title="内容审核" description="处理准入、事实状态、重复、事件聚类和策略异常。" actionLabel="查看审核队列" metrics={[["7", "待审核"], ["3", "未证实"], ["2", "聚类冲突"], ["0", "版权紧急"]]} cards={[{ title: "发布准入", description: "相关度、质量、重复和 display_policy 硬规则。" }, { title: "事实状态", description: "UNCONFIRMED 默认不精选；DEBUNKED 默认移除。" }, { title: "事件治理", description: "主条目、合并、拆分与跨事件关联。" }]} />;
}
