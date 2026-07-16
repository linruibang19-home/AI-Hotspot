import { WorkspacePage } from "@/components/workspace-page";

export default function AdminModelsPage() {
  return <WorkspacePage title="模型与 Prompt" description="Provider、模型路由、Prompt 版本和评测发布门禁。" actionLabel="运行评测集" metrics={[["Mock", "开发默认"], ["DeepSeek", "Generation"], ["bge-m3", "Embedding"], ["v2-m3", "Rerank"]]} cards={[{ title: "GenerationProvider", description: "远程 DeepSeek OpenAI-compatible；密钥不硬编码。" }, { title: "EmbeddingProvider", description: "BAAI/bge-m3，可按配置切换远程服务。" }, { title: "RerankProvider", description: "BAAI/bge-reranker-v2-m3，可按配置切换。" }]} />;
}
