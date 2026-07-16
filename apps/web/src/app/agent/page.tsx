import { WorkspacePage } from "@/components/workspace-page";

export default function AgentPage() {
  return <WorkspacePage title="Agent 工作台" description="使用受控工具完成研究、订阅运营和故障诊断。" actionLabel="创建任务" metrics={[["4", "Agent 模板"], ["0", "运行中"], ["0", "等待审批"], ["Mock", "编排 Provider"]]} cards={[{ title: "研究 Agent", description: "搜索、比较、构建时间线并生成带引用报告。" }, { title: "订阅 Agent", description: "把自然语言需求转成结构化订阅。" }, { title: "采集运维 Agent", description: "分析失败、结构变化与任务积压。" }]} />;
}
