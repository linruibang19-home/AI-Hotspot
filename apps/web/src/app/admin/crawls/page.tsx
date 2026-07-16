import { WorkspacePage } from "@/components/workspace-page";

export default function AdminCrawlsPage() {
  return <WorkspacePage title="采集监控" description="任务、耗时、错误、重试、Outbox 和死信状态。" actionLabel="运行健康检查" metrics={[["99.2%", "成功率"], ["12", "运行中"], ["3", "等待重试"], ["0", "死信新增"]]} cards={[{ title: "采集任务", description: "QUEUED、RUNNING、WAITING_RETRY 与完成状态。" }, { title: "可靠消息", description: "RabbitMQ、Outbox、Inbox 与 DLX。" }, { title: "人工重放", description: "重新校验权限和数据状态后创建新事件。" }]} />;
}
