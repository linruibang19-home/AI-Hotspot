"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { apiFetch, ApiError } from "@/lib/api";

type TaskType = "RAG_GENERATION" | "EMBEDDING" | "RERANK";
type Connection = {
  id: string; displayName: string; vendor: string; apiProtocol: string; baseUrl: string;
  keyLastFour?: string; status: string; lastTestStatus: string; lastTestedAt?: string;
  lastErrorCode?: string; taskType: TaskType; modelName: string; assignmentStatus: string;
  timeoutMs: number; platformFallbackEnabled: boolean; dailyQueryLimit: number;
  monthlyTokenLimit: number;
};
type Usage = {
  todayQueries: number; dailyQueryLimit: number; monthTokens: number; monthlyTokenLimit: number;
  monthEstimatedCost: number; userProviderCalls: number; platformFallbackEnabled: boolean;
  userKeyRequired: boolean;
};
type Draft = {
  displayName: string; vendor: string; apiProtocol: string; baseUrl: string; apiKey: string;
  taskType: TaskType; modelName: string; timeoutMs: number; platformFallbackEnabled: boolean;
  dailyQueryLimit: number; monthlyTokenLimit: number;
};

const PRESETS: Record<string, { baseUrl: string; protocol: string; models: Record<TaskType, string> }> = {
  deepseek: { baseUrl: "https://api.deepseek.com", protocol: "OPENAI_COMPATIBLE", models: { RAG_GENERATION: "deepseek-chat", EMBEDDING: "", RERANK: "" } },
  openai: { baseUrl: "https://api.openai.com/v1", protocol: "OPENAI_COMPATIBLE", models: { RAG_GENERATION: "gpt-4.1-mini", EMBEDDING: "text-embedding-3-small", RERANK: "" } },
  siliconflow: { baseUrl: "https://api.siliconflow.cn/v1", protocol: "SILICONFLOW", models: { RAG_GENERATION: "Qwen/Qwen3-8B", EMBEDDING: "BAAI/bge-m3", RERANK: "BAAI/bge-reranker-v2-m3" } },
  moonshot: { baseUrl: "https://api.moonshot.cn/v1", protocol: "OPENAI_COMPATIBLE", models: { RAG_GENERATION: "moonshot-v1-8k", EMBEDDING: "", RERANK: "" } },
  qwen: { baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", protocol: "OPENAI_COMPATIBLE", models: { RAG_GENERATION: "qwen-plus", EMBEDDING: "text-embedding-v3", RERANK: "" } },
  custom: { baseUrl: "", protocol: "OPENAI_COMPATIBLE", models: { RAG_GENERATION: "", EMBEDDING: "", RERANK: "" } },
};
const EMPTY_DRAFT: Draft = {
  displayName: "我的回答模型", vendor: "deepseek", apiProtocol: "OPENAI_COMPATIBLE",
  baseUrl: PRESETS.deepseek.baseUrl, apiKey: "", taskType: "RAG_GENERATION",
  modelName: PRESETS.deepseek.models.RAG_GENERATION, timeoutMs: 40000,
  platformFallbackEnabled: false, dailyQueryLimit: 50, monthlyTokenLimit: 5000000,
};

export default function UserModelsPage() {
  const { user, loading: authLoading } = useAuth();
  const [connections, setConnections] = useState<Connection[]>([]);
  const [usage, setUsage] = useState<Usage | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [items, nextUsage] = await Promise.all([
        apiFetch<Connection[]>("/me/ai/connections"),
        apiFetch<Usage>("/me/ai/usage"),
      ]);
      setConnections(items);
      setUsage(nextUsage);
    } catch (error) { setNotice(errorMessage(error)); }
  }, []);
  useEffect(() => {
    if (!authLoading && user) {
      const timer = window.setTimeout(() => void load(), 0);
      return () => window.clearTimeout(timer);
    }
  }, [authLoading, load, user]);

  const configuredTasks = useMemo(() => new Set(connections.map(item => item.taskType)), [connections]);
  if (authLoading) return <div className="page-shell"><div className="workspace-empty">正在检查会话…</div></div>;
  if (!user) return <div className="page-shell"><div className="permission-state"><span>需要登录</span><h1>我的模型</h1><p>登录后才能安全管理个人 API Key。</p><Link className="button primary" href="/login?returnTo=/settings/models">前往登录</Link></div></div>;

  function selectVendor(vendor: string) {
    const preset = PRESETS[vendor];
    setDraft(value => ({
      ...value, vendor, apiProtocol: preset.protocol, baseUrl: preset.baseUrl,
      modelName: preset.models[value.taskType],
    }));
  }
  function selectTask(taskType: TaskType) {
    setDraft(value => ({
      ...value,
      taskType,
      modelName: (PRESETS[value.vendor] ?? PRESETS.custom).models[taskType],
    }));
  }
  function edit(connection: Connection) {
    setEditingId(connection.id);
    setDraft({
      displayName: connection.displayName, vendor: connection.vendor,
      apiProtocol: connection.apiProtocol, baseUrl: connection.baseUrl, apiKey: "",
      taskType: connection.taskType, modelName: connection.modelName,
      timeoutMs: connection.timeoutMs, platformFallbackEnabled: connection.platformFallbackEnabled,
      dailyQueryLimit: connection.dailyQueryLimit, monthlyTokenLimit: connection.monthlyTokenLimit,
    });
    setNotice("正在编辑连接。API Key 留空表示保留原 Key；保存后必须重新烟测。");
  }
  function resetForm() {
    setEditingId(null);
    setDraft(EMPTY_DRAFT);
  }
  async function save() {
    if (!editingId && configuredTasks.has(draft.taskType)) {
      setNotice("该任务已经配置，请编辑现有连接或先撤销。");
      return;
    }
    setBusy(true);
    try {
      await apiFetch(editingId ? `/me/ai/connections/${editingId}` : "/me/ai/connections", {
        method: editingId ? "PUT" : "POST", body: JSON.stringify(draft),
      });
      setNotice("连接已安全保存。请执行真实烟测，成功后再启用。");
      resetForm(); await load();
    } catch (error) { setNotice(errorMessage(error)); }
    finally { setBusy(false); }
  }
  async function test(connection: Connection) {
    setBusy(true);
    try {
      const result = await apiFetch<{ passed: boolean; latencyMs: number; errorCode?: string }>(
        `/me/ai/connections/${connection.id}/test`, { method: "POST" });
      setNotice(result.passed ? `烟测通过，耗时 ${result.latencyMs}ms。现在可以启用。` : `烟测失败：${result.errorCode}`);
      await load();
    } catch (error) { setNotice(errorMessage(error)); }
    finally { setBusy(false); }
  }
  async function toggle(connection: Connection) {
    setBusy(true);
    try {
      const status = connection.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
      await apiFetch(`/me/ai/connections/${connection.id}/status`, {
        method: "PUT", body: JSON.stringify({ status }),
      });
      setNotice(status === "ACTIVE" ? "个人连接已启用，下一次对应任务会使用你的 Key。" : "连接已停用。除非你明确开启平台回落，否则不会静默消耗平台 Key。");
      await load();
    } catch (error) { setNotice(errorMessage(error)); }
    finally { setBusy(false); }
  }
  async function revoke(connection: Connection) {
    if (!window.confirm(`确认撤销“${connection.displayName}”？密文和任务路由会被永久删除。`)) return;
    setBusy(true);
    try {
      await apiFetch(`/me/ai/connections/${connection.id}`, { method: "DELETE" });
      setNotice("连接已撤销，服务器不再保存该 Key 的密文。"); await load();
    } catch (error) { setNotice(errorMessage(error)); }
    finally { setBusy(false); }
  }

  return <div className="page-shell byok-page">
    <PageHeader title="我的模型" description="使用自己的 API Key 运行知识库问答；密钥只可替换或撤销，任何页面都无法再次查看。" />
    {notice && <div className="notice" role="status">{notice}</div>}
    <section className="byok-policy-banner">
      <div><span>当前站点策略</span><strong>{usage?.userKeyRequired ? "必须使用个人回答 Key" : "个人 Key 优先，未配置时使用平台能力"}</strong></div>
      <p>个人连接不可用时默认停止任务。只有你主动开启“允许平台回落”，系统才可能消耗站点 Token。</p>
    </section>
    <section className="byok-usage-grid">
      <UsageCard label="今日研究查询" value={`${usage?.todayQueries ?? 0} / ${usage?.dailyQueryLimit ?? 50}`} detail="Asia/Shanghai 自然日" />
      <UsageCard label="本月个人 Tokens" value={`${formatNumber(usage?.monthTokens ?? 0)} / ${formatNumber(usage?.monthlyTokenLimit ?? 5000000)}`} detail="只统计个人 Provider" />
      <UsageCard label="个人 Provider 调用" value={formatNumber(usage?.userProviderCalls ?? 0)} detail="失败调用同样留痕" />
      <UsageCard label="估算费用" value={usage?.monthEstimatedCost ? `$${usage.monthEstimatedCost.toFixed(4)}` : "待配置单价"} detail="实际账单以厂商为准" />
    </section>
    <section className="product-panel byok-connections">
      <header><div><span>PRIVATE CONNECTIONS</span><h2>我的私有连接</h2></div><small>{connections.length}/3 项任务已配置</small></header>
      {connections.length === 0 ? <div className="workspace-empty">尚未保存个人模型连接。下方录入后先烟测，再启用。</div> :
        <div className="byok-connection-list">{connections.map(connection => <article key={connection.id}>
          <div className="byok-connection-main">
            <span className={`status-dot ${connection.status === "ACTIVE" ? "active" : ""}`} />
            <div><strong>{connection.displayName}</strong><small>{taskLabel(connection.taskType)} · {connection.vendor} / {connection.modelName}</small></div>
          </div>
          <dl>
            <div><dt>凭据</dt><dd>•••• •••• {connection.keyLastFour ?? "••••"}</dd></div>
            <div><dt>烟测</dt><dd>{testLabel(connection.lastTestStatus)}</dd></div>
            <div><dt>平台回落</dt><dd>{connection.platformFallbackEnabled ? "已明确允许" : "关闭"}</dd></div>
          </dl>
          <div className="byok-actions">
            <button className="button" disabled={busy} onClick={() => edit(connection)}>编辑</button>
            <button className="button" disabled={busy} onClick={() => void test(connection)}>真实烟测</button>
            <button className="button primary" disabled={busy || (connection.status !== "ACTIVE" && connection.lastTestStatus !== "PASS")} onClick={() => void toggle(connection)}>{connection.status === "ACTIVE" ? "停用" : "启用"}</button>
            <button className="button danger-outline" disabled={busy} onClick={() => void revoke(connection)}>撤销</button>
          </div>
        </article>)}</div>}
    </section>
    <section className="product-panel byok-form">
      <header><div><span>{editingId ? "EDIT CONNECTION" : "NEW CONNECTION"}</span><h2>{editingId ? "编辑个人连接" : "添加个人连接"}</h2></div>{editingId && <button className="button" onClick={resetForm}>取消编辑</button>}</header>
      <div className="byok-form-grid">
        <label>任务类型<select value={draft.taskType} disabled={Boolean(editingId)} onChange={event => selectTask(event.target.value as TaskType)}>
          <option value="RAG_GENERATION">回答生成</option><option value="EMBEDDING">Embedding</option><option value="RERANK">Rerank</option>
        </select></label>
        <label>厂商<select value={draft.vendor} onChange={event => selectVendor(event.target.value)}>
          <option value="deepseek">DeepSeek</option><option value="openai">OpenAI</option><option value="siliconflow">SiliconFlow</option><option value="moonshot">Moonshot</option><option value="qwen">阿里云百炼 / Qwen</option><option value="custom">自定义兼容接口</option>
        </select></label>
        <label>连接名称<input value={draft.displayName} maxLength={80} onChange={event => setDraft(value => ({ ...value, displayName: event.target.value }))} /></label>
        <label>模型名称<input value={draft.modelName} maxLength={120} placeholder="厂商控制台中的准确模型 ID" onChange={event => setDraft(value => ({ ...value, modelName: event.target.value }))} /></label>
        <label className="wide">API Base URL<input value={draft.baseUrl} placeholder="https://..." onChange={event => setDraft(value => ({ ...value, baseUrl: event.target.value }))} /></label>
        <label className="wide">API Key<input type="password" autoComplete="new-password" value={draft.apiKey} placeholder={editingId ? "留空保留原 Key；输入则替换" : "仅在保存时提交，之后不可查看"} onChange={event => setDraft(value => ({ ...value, apiKey: event.target.value }))} /></label>
        <label>每日查询上限<input type="number" min={1} max={500} value={draft.dailyQueryLimit} onChange={event => setDraft(value => ({ ...value, dailyQueryLimit: Number(event.target.value) }))} /></label>
        <label>每月 Token 上限<input type="number" min={10000} max={100000000} step={10000} value={draft.monthlyTokenLimit} onChange={event => setDraft(value => ({ ...value, monthlyTokenLimit: Number(event.target.value) }))} /></label>
      </div>
      <label className="byok-fallback-check"><input type="checkbox" checked={draft.platformFallbackEnabled} onChange={event => setDraft(value => ({ ...value, platformFallbackEnabled: event.target.checked }))} /><span><strong>个人连接不可用时允许平台回落</strong><small>默认关闭。开启后可能消耗站点 Token，仅在你接受该行为时选择。</small></span></label>
      <div className="byok-security-note"><strong>安全边界</strong><span>仅接受可解析的公网 HTTPS 地址；内网、localhost、保留地址和带凭据 URL 会被拒绝。日志与审计不记录原始 Key。</span></div>
      <button className="button primary byok-save" disabled={busy || !draft.modelName || (!editingId && !draft.apiKey)} onClick={() => void save()}>{busy ? "处理中…" : editingId ? "保存并停用待测" : "加密保存连接"}</button>
    </section>
  </div>;
}

function UsageCard({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <article><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>;
}
function taskLabel(value: TaskType) { return value === "RAG_GENERATION" ? "回答生成" : value === "EMBEDDING" ? "Embedding" : "Rerank"; }
function testLabel(value: string) { return value === "PASS" ? "通过" : value === "FAIL" ? "失败" : "未测试"; }
function formatNumber(value: number) { return new Intl.NumberFormat("zh-CN").format(value); }
function errorMessage(error: unknown) { return error instanceof ApiError || error instanceof Error ? error.message : "操作失败"; }
