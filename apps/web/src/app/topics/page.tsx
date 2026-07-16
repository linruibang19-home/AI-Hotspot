import { WorkspacePage } from "@/components/workspace-page";

export default function TopicsPage() {
  return <WorkspacePage title="主题" description="由标签、实体和事件形成的长期知识入口。" actionLabel="浏览主题" metrics={[["38", "主题"], ["12", "公司与模型"], ["14", "技术方向"], ["12", "内容形态"]]} cards={[{ title: "Agent 智能体", description: "框架、工具调用、MCP、记忆、评测与安全。" }, { title: "AI 编码", description: "Coding Agent、IDE、代码模型与工程工作流。" }, { title: "RAG / 检索", description: "混合检索、Rerank、引用验证和权限过滤。" }]} />;
}
