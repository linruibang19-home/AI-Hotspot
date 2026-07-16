import { WorkspacePage } from "@/components/workspace-page";

export default function ResearchPage() {
  return <WorkspacePage title="知识库问答" description="检索前权限过滤，混合召回、重排并生成带引用答案。" actionLabel="新建研究" metrics={[["Mock", "Generation"], ["16", "Mock 向量维度"], ["Top 8", "Rerank"], ["0", "越权召回"]]} cards={[{ title: "问题解析", description: "识别时间、主题、实体和来源限制。" }, { title: "混合检索", description: "PostgreSQL FTS 与向量召回后执行 Rerank。" }, { title: "引用验证", description: "无证据时拒答，引用必须可定位。" }]} />;
}
