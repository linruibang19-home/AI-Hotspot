import { WorkspacePage } from "@/components/workspace-page";

export default function ReportsPage() {
  return <WorkspacePage title="AI 日报" description="已发布日报和周报归档，邮件与网页共享修订快照。" actionLabel="查看今日日报" metrics={[["13", "独立事件"], ["8", "一手信源"], ["31", "原始内容"], ["8 min", "预计阅读"]]} cards={[{ title: "今日日报", description: "Agent 产品化、安全权限和长程任务稳定性。" }, { title: "本周周报", description: "按主题聚合一周重要变化与证据。" }, { title: "报告修订", description: "发布后使用不可变 revision 支持审计。" }]} />;
}
