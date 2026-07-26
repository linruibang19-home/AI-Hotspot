"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AdminPermissionState, AdminSessionLoading } from "@/components/admin-permission-state";
import { useAuth } from "@/components/auth-provider";
import { Icon } from "@/components/icons";
import { PageHeader } from "@/components/page-header";
import { apiFetch, ApiError } from "@/lib/api";

type Config = {
  task_type: string; provider_name: string; model_name: string; base_url: string;
  credential_ref: string; connection_id?: string; status: string; timeout_ms: number; parameters: unknown;
};
type Connection = {
  id: string; scope: "PLATFORM" | "USER"; displayName: string; vendor: string;
  apiProtocol: string; baseUrl: string; credentialKind: "ENVIRONMENT" | "ENCRYPTED";
  credentialRef?: string; keyLastFour?: string; capabilities: string[]; status: string;
  lastTestStatus: "NOT_TESTED" | "PASS" | "FAIL"; lastTestedAt?: string; lastErrorCode?: string;
};
type ProviderBreakdown = {
  capability: string; provider_name: string; model_name: string; credential_scope: string;
  connection_id?: string; calls: number; failures: number; error_rate: number;
  avg_latency_ms: number; input_tokens: number; output_tokens: number; estimated_cost: number;
};
type UsageOwnership = {
  credential_scope: string; calls: number; input_tokens: number; output_tokens: number;
  estimated_cost: number; users: number;
};
type SlowQuery = {
  id: string; question: string; answer_status: string; latency_ms: number; citation_count: number;
  source_count: number; embedding_ms: number; retrieval_ms: number; rerank_ms: number;
  generation_ms: number; created_at: string;
};
type FeedbackSample = {
  id: string; rating: "HELPFUL" | "UNHELPFUL"; issue_categories: string[]; comment?: string;
  triage_status: string; updated_at: string; question: string; citation_coverage?: number;
  source_count: number; prompt_version?: string;
};
type Metrics = {
  generatedAt: string; costConfigured: boolean;
  summary: { calls: number; failures: number; failure_rate: number; input_tokens: number; output_tokens: number; estimated_cost: number };
  rag: Record<string, number> & { rag_queries: number; succeeded: number; no_evidence: number; p95_latency_ms: number; avg_citation_coverage: number; avg_source_count: number; conflict_runs: number; assessed_evidence: number };
  index: { chunks: number; vectorized_chunks: number; indexed_sources: number; missing_source_time: number };
  providerBreakdown: ProviderBreakdown[]; usageOwnership: UsageOwnership[];
  latencyTrend: Array<{ bucket: string; queries: number; avg_latency_ms: number; p95_latency_ms: number; no_evidence: number }>;
  noEvidenceReasons: Array<{ reason: string; occurrences: number }>; slowQueries: SlowQuery[];
  feedbackSummary: { total: number; unhelpful: number; awaiting_triage: number; eval_candidates: number; helpful_rate: number };
  feedbackSamples: FeedbackSample[];
};
type Evaluation = { id: string; name: string; metrics: string | Record<string, unknown>; passed: boolean; started_at: string };
type EvaluationFilter = "ALL" | "PASS" | "FAIL";
type WorkspaceTab = "CONNECTIONS" | "MONITORING" | "QUALITY";

const CAPABILITIES = [
  ["RAG_GENERATION", "RAG 回答生成"],
  ["EMBEDDING", "向量检索"],
  ["RERANK", "结果重排"],
  ["CONTENT_ANALYSIS", "内容分析"],
  ["AGENT", "Agent 生成"],
] as const;
const STAGES = [
  ["Embedding", "avg_embedding_ms", "p95_embedding_ms"],
  ["混合召回", "avg_retrieval_ms", "p95_retrieval_ms"],
  ["Rerank", "avg_rerank_ms", "p95_rerank_ms"],
  ["Generation", "avg_generation_ms", "p95_generation_ms"],
] as const;
const VENDOR_PRESETS: Record<string, { baseUrl: string; protocol: string }> = {
  deepseek: { baseUrl: "https://api.deepseek.com", protocol: "OPENAI_COMPATIBLE" },
  openai: { baseUrl: "https://api.openai.com/v1", protocol: "OPENAI_COMPATIBLE" },
  siliconflow: { baseUrl: "https://api.siliconflow.cn/v1", protocol: "SILICONFLOW" },
  moonshot: { baseUrl: "https://api.moonshot.cn/v1", protocol: "OPENAI_COMPATIBLE" },
  qwen: { baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1", protocol: "OPENAI_COMPATIBLE" },
  custom: { baseUrl: "", protocol: "OPENAI_COMPATIBLE" },
};

export default function AdminModelsPage() {
  const { user, loading: authLoading } = useAuth();
  const [tab, setTab] = useState<WorkspaceTab>("CONNECTIONS");
  const [configs, setConfigs] = useState<Config[]>([]);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [evaluations, setEvaluations] = useState<Evaluation[]>([]);
  const [windowHours, setWindowHours] = useState(24);
  const [evaluationFilter, setEvaluationFilter] = useState<EvaluationFilter>("ALL");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState({
    displayName: "新的模型连接", vendor: "deepseek", apiProtocol: "OPENAI_COMPATIBLE",
    baseUrl: VENDOR_PRESETS.deepseek.baseUrl, apiKey: "", capabilities: ["RAG_GENERATION"] as string[],
  });
  const [routeConnections, setRouteConnections] = useState<Record<string, string>>({});

  const loadMetrics = useCallback(async (hours: number) => setMetrics(await apiFetch<Metrics>(`/admin/ai/metrics?hours=${hours}`)), []);
  const loadEvaluations = useCallback(async (filter: EvaluationFilter) => {
    const query = filter === "ALL" ? "" : `&passed=${filter === "PASS"}`;
    setEvaluations(await apiFetch<Evaluation[]>(`/admin/ai/evaluations?limit=30${query}`));
  }, []);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextConfigs, nextConnections] = await Promise.all([
        apiFetch<Config[]>("/admin/ai/configs"),
        apiFetch<Connection[]>("/admin/ai/connections"),
        loadMetrics(windowHours),
        loadEvaluations(evaluationFilter),
      ]);
      setConfigs(nextConfigs);
      setConnections(nextConnections);
      setRouteConnections(Object.fromEntries(nextConfigs.map(config => [config.task_type, config.connection_id ?? ""])));
    } catch (error) {
      setNotice(message(error));
    } finally {
      setLoading(false);
    }
  }, [evaluationFilter, loadEvaluations, loadMetrics, windowHours]);

  const allowed = user?.permissions.includes("ai-config:manage") ?? false;
  useEffect(() => {
    if (authLoading || !allowed) return;
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [allowed, authLoading, load]);

  async function operation(path: string, label: string) {
    setBusy(true);
    try {
      await apiFetch(path, { method: "POST" });
      setNotice(`${label}完成，数据已刷新。`);
      await load();
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  async function createConnection() {
    setBusy(true);
    try {
      await apiFetch("/admin/ai/connections", { method: "POST", body: JSON.stringify(draft) });
      setDraft(value => ({ ...value, apiKey: "", displayName: "新的模型连接" }));
      setNotice("连接已加密保存。请先执行真实测试，通过后再绑定任务。");
      await load();
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  async function testConnection(connection: Connection) {
    setBusy(true);
    try {
      const result = await apiFetch<{ passed: boolean; capability: string; latencyMs: number; errorCode?: string }>(
        `/admin/ai/connections/${connection.id}/test`, { method: "POST" });
      setNotice(result.passed ? `${connection.displayName} 测试通过，耗时 ${result.latencyMs}ms。` : `测试失败：${result.errorCode}`);
      await load();
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  async function toggleConnection(connection: Connection) {
    setBusy(true);
    try {
      const status = connection.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
      await apiFetch(`/admin/ai/connections/${connection.id}/status`, {
        method: "POST", body: JSON.stringify({ status }),
      });
      setNotice(`${connection.displayName} 已${status === "ACTIVE" ? "启用" : "停用"}。`);
      await load();
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  async function assign(config: Config) {
    const connectionId = routeConnections[config.task_type];
    if (!connectionId) { setNotice("请先选择一个 Provider 连接。"); return; }
    const pricing = configParameters(config.parameters);
    setBusy(true);
    try {
      await apiFetch(`/admin/ai/connections/${connectionId}/assign`, {
        method: "POST",
        body: JSON.stringify({
          taskType: config.task_type, modelName: config.model_name, timeoutMs: config.timeout_ms,
          inputCostPerMillion: pricing.inputCostPerMillion,
          outputCostPerMillion: pricing.outputCostPerMillion,
        }),
      });
      setNotice(`${capabilityLabel(config.task_type)} 已切换并立即生效。`);
      await load();
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  async function triageFeedback(id: string, status: "TRIAGED" | "EVAL_CANDIDATE" | "RESOLVED") {
    setBusy(true);
    try {
      await apiFetch(`/admin/ai/feedback/${id}`, { method: "PUT", body: JSON.stringify({ status }) });
      setNotice("反馈样本状态已更新。"); await loadMetrics(windowHours);
    } catch (error) { setNotice(message(error)); }
    finally { setBusy(false); }
  }
  function updateConfig(index: number, key: keyof Config, value: unknown) {
    setConfigs(values => values.map((item, itemIndex) => itemIndex === index ? { ...item, [key]: value } : item));
  }
  function updatePricing(index: number, key: "inputCostPerMillion" | "outputCostPerMillion", value: string) {
    setConfigs(values => values.map((item, itemIndex) => {
      if (itemIndex !== index) return item;
      const parameters = configParameters(item.parameters);
      if (value.trim() === "") delete parameters[key]; else parameters[key] = Number(value);
      return { ...item, parameters: JSON.stringify(parameters) };
    }));
  }

  const maxStage = useMemo(() => Math.max(1, ...STAGES.map(([, , p95]) => Number(metrics?.rag[p95] ?? 0))), [metrics]);
  const maxTrend = useMemo(() => Math.max(1, ...(metrics?.latencyTrend ?? []).map(point => Number(point.p95_latency_ms))), [metrics]);
  if (authLoading) return <AdminSessionLoading />;
  if (!user) return <AdminPermissionState title="需要登录" detail="模型接入与 RAG 监控只对管理员开放。" login />;
  if (!allowed) return <AdminPermissionState title="没有模型治理权限" detail="当前账号不能管理 Provider 或查看 RAG 运行数据。" />;

  return <div className="page-shell rag-admin-page">
    <PageHeader title="模型接入与 RAG 运行中心" description="安全接入不同模型厂商，明确每项 RAG 能力使用哪个连接，并持续监测延迟、Token、费用和答案质量。"
      action={<div className="header-actions">
        <button className="button" disabled={busy} onClick={() => void operation("/admin/ai/reindex", "索引更新")}>更新索引</button>
        <button className="button primary" disabled={busy} onClick={() => { setTab("QUALITY"); void operation("/admin/ai/evaluations/run", "黄金集评测"); }}>运行评测</button>
      </div>} />

    <nav className="model-workspace-tabs" aria-label="模型与 RAG 工作区">
      <TabButton active={tab === "CONNECTIONS"} title="模型接入" detail="厂商、API Key 与任务路由" onClick={() => setTab("CONNECTIONS")} />
      <TabButton active={tab === "MONITORING"} title="RAG 监控" detail="延迟、Token、费用与异常" onClick={() => setTab("MONITORING")} />
      <TabButton active={tab === "QUALITY"} title="质量评测" detail="黄金集与真实失败样本" onClick={() => setTab("QUALITY")} />
    </nav>

    <div className="observability-toolbar">
      <div><span className={`live-dot ${loading ? "loading" : ""}`} />{loading ? "正在同步" : "运行数据已同步"}<small>{metrics?.generatedAt ? new Date(metrics.generatedAt).toLocaleString("zh-CN") : "—"}</small></div>
      {tab !== "CONNECTIONS" && <label>观察窗口<select aria-label="观察窗口" value={windowHours} onChange={event => setWindowHours(Number(event.target.value))}><option value={24}>最近 24 小时</option><option value={72}>最近 3 天</option><option value={168}>最近 7 天</option></select></label>}
      <button className="icon-refresh" aria-label="刷新数据" onClick={() => void load()} disabled={loading}><Icon name="history" /></button>
    </div>
    {notice && <div className="notice" role="status">{notice}<button aria-label="关闭提示" onClick={() => setNotice("")}>×</button></div>}

    {tab === "CONNECTIONS" && <>
      <section className="provider-flow" aria-label="模型接入流程">
        <FlowStep number="1" title="添加连接" detail="Key 仅加密写入，页面和日志永不回显" />
        <FlowStep number="2" title="真实测试" detail="验证厂商接口、模型响应与超时" />
        <FlowStep number="3" title="绑定能力" detail="分别选择生成、Embedding 与 Rerank" />
        <FlowStep number="4" title="监测用量" detail="按连接、用户和查询记录 Token" />
      </section>

      <div className="connection-layout">
        <section className="product-panel provider-connections-panel">
          <PanelHeader eyebrow="PROVIDER CONNECTIONS" title="已接入的模型厂商" note={`${connections.length} 个连接`} />
          <div className="connection-list">{connections.map(connection =>
            <article className="connection-card" key={connection.id}>
              <header><div className="provider-mark">{connection.vendor.slice(0, 2).toUpperCase()}</div><div><h3>{connection.displayName}</h3><p>{connection.vendor} · {connection.apiProtocol.replaceAll("_", " ")}</p></div><span className={`status-badge ${connection.status === "ACTIVE" ? "active" : connection.status === "DEGRADED" ? "failed" : ""}`}>{statusLabel(connection.status)}</span></header>
              <dl><div><dt>服务地址</dt><dd title={connection.baseUrl}>{connection.baseUrl}</dd></div><div><dt>凭据</dt><dd>{connection.credentialKind === "ENCRYPTED" ? `•••• •••• ${connection.keyLastFour ?? "••••"}` : `环境变量 · ${connection.credentialRef}`}</dd></div><div><dt>能力</dt><dd>{connection.capabilities.map(capabilityLabel).join("、")}</dd></div><div><dt>最近测试</dt><dd className={connection.lastTestStatus === "FAIL" ? "danger" : ""}>{testStatusLabel(connection)}{connection.lastTestedAt ? ` · ${new Date(connection.lastTestedAt).toLocaleString("zh-CN")}` : ""}</dd></div></dl>
              <footer><button className="button" disabled={busy} onClick={() => void testConnection(connection)}>测试连接</button><button className={`button ${connection.status === "ACTIVE" ? "" : "primary"}`} disabled={busy || (connection.status !== "ACTIVE" && connection.lastTestStatus !== "PASS")} onClick={() => void toggleConnection(connection)}>{connection.status === "ACTIVE" ? "停用" : "启用"}</button></footer>
            </article>)}</div>
        </section>

        <section className="product-panel connection-form">
          <PanelHeader eyebrow="NEW CONNECTION" title="添加模型连接" note="平台凭据" />
          <p className="form-security-note">API Key 通过 AES-GCM 加密保存。保存后只能替换，不能查看或复制。</p>
          <label>连接名称<input value={draft.displayName} onChange={event => setDraft(value => ({ ...value, displayName: event.target.value }))} /></label>
          <label>模型厂商<select value={draft.vendor} onChange={event => { const vendor = event.target.value; const preset = VENDOR_PRESETS[vendor]; setDraft(value => ({ ...value, vendor, apiProtocol: preset.protocol, baseUrl: preset.baseUrl })); }}>{Object.keys(VENDOR_PRESETS).map(vendor => <option value={vendor} key={vendor}>{vendorLabel(vendor)}</option>)}</select></label>
          <label>API 协议<select value={draft.apiProtocol} onChange={event => setDraft(value => ({ ...value, apiProtocol: event.target.value }))}><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option><option value="SILICONFLOW">SiliconFlow</option></select></label>
          <label>服务地址<input value={draft.baseUrl} placeholder="https://api.example.com/v1" onChange={event => setDraft(value => ({ ...value, baseUrl: event.target.value }))} /></label>
          <label>API Key<input type="password" autoComplete="new-password" value={draft.apiKey} placeholder="输入后将加密保存" onChange={event => setDraft(value => ({ ...value, apiKey: event.target.value }))} /></label>
          <fieldset><legend>支持的能力</legend>{CAPABILITIES.map(([value, label]) => <label className="capability-check" key={value}><input type="checkbox" checked={draft.capabilities.includes(value)} onChange={event => setDraft(current => ({ ...current, capabilities: event.target.checked ? [...current.capabilities, value] : current.capabilities.filter(item => item !== value) }))} />{label}</label>)}</fieldset>
          <button className="button primary wide" disabled={busy || !draft.apiKey || !draft.baseUrl} onClick={() => void createConnection()}>加密保存连接</button>
        </section>
      </div>

      <section className="product-panel task-routing-panel">
        <PanelHeader eyebrow="RUNTIME ROUTING" title="RAG 与 AI 任务路由" note="保存后立即生效，无需重启容器" />
        <div className="routing-grid">{configs.map((config, index) => {
          const pricing = configParameters(config.parameters);
          const candidates = connections.filter(connection => connection.capabilities.includes(config.task_type));
          return <article key={config.task_type}>
            <header><div><span className="overline">{config.task_type}</span><h3>{capabilityLabel(config.task_type)}</h3></div><span className={`status-badge ${config.status === "ACTIVE" ? "active" : ""}`}>{statusLabel(config.status)}</span></header>
            <label>Provider 连接<select value={routeConnections[config.task_type] ?? ""} onChange={event => setRouteConnections(value => ({ ...value, [config.task_type]: event.target.value }))}><option value="">请选择已测试连接</option>{candidates.map(connection => <option value={connection.id} key={connection.id}>{connection.displayName} · {testStatusLabel(connection)}</option>)}</select></label>
            <label>模型名称<input value={config.model_name ?? ""} onChange={event => updateConfig(index, "model_name", event.target.value)} /></label>
            <label>超时（毫秒）<input type="number" min="1000" max="180000" value={config.timeout_ms} onChange={event => updateConfig(index, "timeout_ms", Number(event.target.value))} /></label>
            {config.task_type === "RAG_GENERATION" && <div className="pricing-grid"><label>输入单价 / 百万 Token<input type="number" min="0" step="0.000001" value={String(pricing.inputCostPerMillion ?? "")} onChange={event => updatePricing(index, "inputCostPerMillion", event.target.value)} placeholder="未配置" /></label><label>输出单价 / 百万 Token<input type="number" min="0" step="0.000001" value={String(pricing.outputCostPerMillion ?? "")} onChange={event => updatePricing(index, "outputCostPerMillion", event.target.value)} placeholder="未配置" /></label></div>}
            <button className="button primary" disabled={busy || !routeConnections[config.task_type]} onClick={() => void assign(config)}>保存并应用</button>
          </article>;
        })}</div>
      </section>
      <aside className="byok-roadmap"><strong>用户自带 Key 已预留正式边界</strong><p>连接表已经区分平台与用户归属，用量也会记录触发用户和凭据来源。下一阶段开放用户设置页时，可复用同一加密、测试与路由链路，不需要重写 RAG。</p></aside>
    </>}

    {tab === "MONITORING" && <>
      <section className="rag-kpi-strip" aria-label="RAG 核心指标">
        <Kpi value={metrics?.rag.rag_queries ?? 0} label="研究查询" meta={`${metrics?.rag.succeeded ?? 0} 次成功`} />
        <Kpi value={`${formatNumber(metrics?.rag.comparable_p95_latency_ms ?? metrics?.rag.p95_latency_ms)} ms`} label="P95 可比延迟" meta={`${formatNumber(metrics?.rag.comparable_queries)} 个当前配置样本`} state={(metrics?.rag.comparable_p95_latency_ms ?? metrics?.rag.p95_latency_ms ?? 0) <= 12000 ? "good" : "warn"} />
        <Kpi value={`${Math.round((metrics?.rag.avg_citation_coverage ?? 0) * 100)}%`} label="引用覆盖" meta={`${formatNumber(metrics?.rag.avg_source_count, 1)} 个平均信源`} state={(metrics?.rag.avg_citation_coverage ?? 0) >= .8 ? "good" : "warn"} />
        <Kpi value={`${formatNumber(metrics?.summary.failure_rate, 2)}%`} label="Provider 错误率" meta={`${metrics?.summary.failures ?? 0} / ${metrics?.summary.calls ?? 0} 次`} state={(metrics?.summary.failures ?? 0) === 0 ? "good" : "warn"} />
        <Kpi value={formatNumber((metrics?.summary.input_tokens ?? 0) + (metrics?.summary.output_tokens ?? 0))} label="Token 消耗" meta={metrics?.costConfigured ? `估算费用 ${formatCost(metrics?.summary.estimated_cost)}` : "配置单价后显示费用"} />
      </section>
      <section className="usage-ownership-strip">{(metrics?.usageOwnership ?? []).map(item => <article key={item.credential_scope}><span>{scopeLabel(item.credential_scope)}</span><strong>{formatNumber(item.input_tokens + item.output_tokens)} tokens</strong><small>{item.calls} 次调用 · {item.users} 位用户 · {formatCost(item.estimated_cost)}</small></article>)}</section>
      <div className="rag-dashboard-grid">
        <section className="product-panel stage-latency-panel"><PanelHeader eyebrow="LATENCY BREAKDOWN" title="RAG 各阶段延迟" note="均值 / P95" /><div className="stage-bars">{STAGES.map(([label, averageKey, p95Key]) => { const average = Number(metrics?.rag[averageKey] ?? 0); const p95 = Number(metrics?.rag[p95Key] ?? 0); return <div className="stage-row" key={label}><div><strong>{label}</strong><span>{formatNumber(average)} / {formatNumber(p95)} ms</span></div><div className="stage-track"><i style={{ width: `${Math.max(2, p95 / maxStage * 100)}%` }} /><b style={{ width: `${Math.max(1, average / maxStage * 100)}%` }} /></div></div>; })}</div><div className="stage-legend"><span><i />P95</span><span><i />均值</span></div></section>
        <section className="product-panel latency-trend-panel"><PanelHeader eyebrow={`TREND · ${windowHours}H`} title="查询延迟趋势" note={`${metrics?.rag.no_evidence ?? 0} 次无证据`} /><div className="latency-chart">{(metrics?.latencyTrend ?? []).map(point => <div className="trend-column" title={`${point.bucket} · P95 ${point.p95_latency_ms}ms`} key={point.bucket}><div><i style={{ height: `${Math.max(3, point.p95_latency_ms / maxTrend * 100)}%` }} /><b style={{ height: `${Math.max(2, point.avg_latency_ms / maxTrend * 100)}%` }} /></div><span>{point.bucket.slice(-5)}</span></div>)}</div></section>
      </div>
      <div className="rag-dashboard-grid lower">
        <section className="product-panel provider-health"><PanelHeader eyebrow="PROVIDER HEALTH" title="调用质量与用量" note={`${formatNumber((metrics?.summary.input_tokens ?? 0) + (metrics?.summary.output_tokens ?? 0))} tokens`} /><div className="provider-list">{(metrics?.providerBreakdown ?? []).map(provider => <article key={`${provider.capability}-${provider.provider_name}-${provider.model_name}-${provider.credential_scope}`}><div><strong>{capabilityLabel(provider.capability)}</strong><span>{provider.provider_name} · {provider.model_name} · {scopeLabel(provider.credential_scope)}</span></div><dl><div><dt>调用</dt><dd>{provider.calls}</dd></div><div><dt>错误率</dt><dd className={provider.failures ? "danger" : ""}>{formatNumber(provider.error_rate, 2)}%</dd></div><div><dt>均值</dt><dd>{formatNumber(provider.avg_latency_ms)}ms</dd></div><div><dt>Tokens</dt><dd>{formatNumber(provider.input_tokens + provider.output_tokens)}</dd></div></dl></article>)}</div></section>
        <section className="product-panel evidence-health"><PanelHeader eyebrow="EVIDENCE HEALTH" title="证据与索引护栏" note={`${metrics?.rag.assessed_evidence ?? 0} 条已裁决证据`} /><div className="index-guardrails"><div><strong>{formatNumber(metrics?.index.vectorized_chunks)}</strong><span>向量 Chunk / {formatNumber(metrics?.index.chunks)}</span></div><div><strong>{formatNumber(metrics?.index.indexed_sources)}</strong><span>索引信源</span></div><div><strong>{formatNumber(metrics?.index.missing_source_time)}</strong><span>缺原始时间</span></div><div><strong>{formatNumber(metrics?.rag.conflict_runs)}</strong><span>冲突查询</span></div></div><div className="reason-list">{(metrics?.noEvidenceReasons ?? []).length ? metrics?.noEvidenceReasons.map(item => <div key={item.reason}><span>{reasonLabel(item.reason)}</span><strong>{item.occurrences}</strong></div>) : <div className="empty-compact">当前窗口没有无证据查询</div>}</div></section>
      </div>
      <section className="product-panel slow-query-panel"><PanelHeader eyebrow="SLOW QUERY SAMPLES" title="慢查询诊断" note="优先展示 ≥12 秒" /><div className="slow-query-table"><div className="slow-query-row head"><span>问题 / 时间</span><span>总延迟</span><span>主要瓶颈</span><span>证据</span><span>状态</span></div>{(metrics?.slowQueries ?? []).map(query => <div className={`slow-query-row ${query.latency_ms >= 12000 ? "is-slow" : ""}`} key={query.id}><span><strong title={query.question}>{query.question}</strong><small>{new Date(query.created_at).toLocaleString("zh-CN")}</small></span><span>{formatNumber(query.latency_ms)} ms</span><span>{bottleneck(query)}</span><span>{query.source_count} 信源 · {query.citation_count} 引用</span><span className={`status-badge ${query.answer_status === "SUCCEEDED" ? "active" : "failed"}`}>{query.answer_status}</span></div>)}</div></section>
    </>}

    {tab === "QUALITY" && <>
      <section className="quality-intro"><div><span>真实失败样本</span><strong>{metrics?.feedbackSummary.unhelpful ?? 0} / 30</strong><small>达到 30 条后再执行 parent-child 单变量实验</small></div><div><span>待人工归类</span><strong>{metrics?.feedbackSummary.awaiting_triage ?? 0}</strong><small>禁止用伪造反馈补足样本</small></div><div><span>评测候选</span><strong>{metrics?.feedbackSummary.eval_candidates ?? 0}</strong><small>归类后进入回归集</small></div><div><span>有帮助率</span><strong>{formatNumber(metrics?.feedbackSummary.helpful_rate, 1)}%</strong><small>来自真实回答反馈</small></div></section>
      <section className="product-panel feedback-sample-panel"><PanelHeader eyebrow="REAL FAILURE DATASET" title="回答反馈与评测候选" note={`${metrics?.feedbackSummary.awaiting_triage ?? 0} 条待分析`} /><div className="feedback-sample-list">{(metrics?.feedbackSamples ?? []).length ? metrics?.feedbackSamples.map(sample => <article className={sample.rating === "UNHELPFUL" ? "negative" : ""} key={sample.id}><div className="feedback-sample-main"><div><span className={`status-badge ${sample.rating === "HELPFUL" ? "active" : "failed"}`}>{sample.rating === "HELPFUL" ? "有帮助" : "需改进"}</span><span className="status-badge">{triageStatusLabel(sample.triage_status)}</span></div><h3>{sample.question}</h3><p>{sample.comment || "用户未补充说明"}</p><div className="feedback-sample-tags">{sample.issue_categories.map(category => <span key={category}>{feedbackReasonLabel(category)}</span>)}</div><small>{new Date(sample.updated_at).toLocaleString("zh-CN")} · {sample.source_count} 信源 · 覆盖 {Math.round(Number(sample.citation_coverage ?? 0) * 100)}% · {sample.prompt_version || "未记录 Prompt"}</small></div><div className="feedback-sample-actions"><button className="button" disabled={busy} onClick={() => void triageFeedback(sample.id, "TRIAGED")}>已归类</button>{sample.rating === "UNHELPFUL" && <button className="button primary" disabled={busy} onClick={() => void triageFeedback(sample.id, "EVAL_CANDIDATE")}>设为评测候选</button>}<button className="button" disabled={busy} onClick={() => void triageFeedback(sample.id, "RESOLVED")}>已解决</button></div></article>) : <div className="empty-compact">当前窗口还没有回答反馈；用户评价后会自动出现在这里。</div>}</div></section>
      <section className="product-panel evaluation-panel"><header><div><span className="overline">EVALUATION HISTORY</span><h2>黄金集评测历史</h2></div><div className="evaluation-filters">{(["ALL", "PASS", "FAIL"] as EvaluationFilter[]).map(filter => <button className={evaluationFilter === filter ? "active" : ""} onClick={() => setEvaluationFilter(filter)} key={filter}>{filter === "ALL" ? "全部" : filter}</button>)}</div></header><div className="evaluation-list">{evaluations.map(evaluation => { const values = evaluationMetrics(evaluation.metrics); return <article key={evaluation.id}><div><span className={`status-badge ${evaluation.passed ? "active" : "failed"}`}>{evaluation.passed ? "PASS" : "FAIL"}</span><strong>{evaluation.name}</strong><small>{new Date(evaluation.started_at).toLocaleString("zh-CN")} · {splitSummary(values)}</small></div><dl><MetricTerm label="Hit@5" value={percent(values.hitAt5)} /><MetricTerm label="Precision@8" value={percent(values.precisionAt8)} /><MetricTerm label="MRR@10" value={percent(values.mrrAt10)} /><MetricTerm label="拒答准确率" value={percent(values.refusalAccuracy)} /><MetricTerm label="Recall@20" value={percent(values.recallAt20)} /><MetricTerm label="nDCG@10" value={percent(values.ndcgAt10)} /><MetricTerm label="蕴含通过" value={percent(values.claimEvidenceEntailmentRate)} /><MetricTerm label="来源异常" value={String(values.citationProvenanceFailures ?? 0)} /></dl></article>; })}</div></section>
    </>}
  </div>;
}

function TabButton({ active, title, detail, onClick }: { active: boolean; title: string; detail: string; onClick: () => void }) {
  return <button className={active ? "active" : ""} onClick={onClick}><strong>{title}</strong><span>{detail}</span></button>;
}
function FlowStep({ number, title, detail }: { number: string; title: string; detail: string }) {
  return <article><span>{number}</span><div><strong>{title}</strong><small>{detail}</small></div></article>;
}
function Kpi({ value, label, meta, state }: { value: string | number; label: string; meta: string; state?: "good" | "warn" }) { return <article className={state ?? ""}><span>{label}</span><strong>{value}</strong><small>{meta}</small></article>; }
function PanelHeader({ eyebrow, title, note }: { eyebrow: string; title: string; note: string }) { return <header><div><span className="overline">{eyebrow}</span><h2>{title}</h2></div><span>{note}</span></header>; }
function MetricTerm({ label, value }: { label: string; value: string }) { return <div><dt>{label}</dt><dd>{value}</dd></div>; }
function formatNumber(value: unknown, digits = 0) { const numeric = Number(value ?? 0); return Number.isFinite(numeric) ? new Intl.NumberFormat("zh-CN", { maximumFractionDigits: digits }).format(numeric) : "0"; }
function formatCost(value: unknown) { return `¥/$ ${new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 4, maximumFractionDigits: 6 }).format(Number(value ?? 0))}`; }
function percent(value: unknown) { return `${formatNumber(Number(value ?? 0) * 100, 1)}%`; }
function evaluationMetrics(value: Evaluation["metrics"]) { if (typeof value !== "string") return value; try { return JSON.parse(value) as Record<string, unknown>; } catch { return {}; } }
function splitSummary(values: Record<string, unknown>) { const groups = values.bySplit as Record<string, Record<string, unknown>> | undefined; if (!groups) return String(values.split ?? "ALL"); return ["DEV", "TEST", "CANARY"].filter(key => groups[key]).map(key => `${key} ${percent(groups[key].passRate)}`).join(" · "); }
function configParameters(value: unknown): Record<string, number> { if (typeof value === "string") { try { return JSON.parse(value) as Record<string, number>; } catch { return {}; } } return value && typeof value === "object" ? { ...(value as Record<string, number>) } : {}; }
function bottleneck(query: SlowQuery) { const values: [string, number][] = [["Embedding", query.embedding_ms], ["召回", query.retrieval_ms], ["Rerank", query.rerank_ms], ["Generation", query.generation_ms]]; return values.sort((left, right) => right[1] - left[1])[0][0]; }
function capabilityLabel(value: string) { return Object.fromEntries(CAPABILITIES)[value] ?? value; }
function vendorLabel(value: string) { return ({ deepseek: "DeepSeek", openai: "OpenAI", siliconflow: "SiliconFlow", moonshot: "Moonshot / Kimi", qwen: "阿里云百炼 / Qwen", custom: "自定义兼容接口" } as Record<string, string>)[value] ?? value; }
function scopeLabel(value: string) { return ({ PLATFORM: "平台凭据", USER: "用户自带 Key", ENVIRONMENT: "环境变量", FALLBACK: "本地降级" } as Record<string, string>)[value] ?? value; }
function statusLabel(value: string) { return ({ ACTIVE: "已启用", DISABLED: "已停用", DEGRADED: "异常降级" } as Record<string, string>)[value] ?? value; }
function testStatusLabel(connection: Connection) { return connection.lastTestStatus === "PASS" ? "测试通过" : connection.lastTestStatus === "FAIL" ? `测试失败${connection.lastErrorCode ? ` · ${connection.lastErrorCode}` : ""}` : "尚未测试"; }
function reasonLabel(reason: string) { return ({ OFFICIAL_SCOPE_NO_MATCH: "官方范围无匹配", TIME_RANGE_NO_MATCH: "时间窗口无匹配", SOURCE_TYPE_NO_MATCH: "来源类型无匹配", NO_RELEVANT_CHUNK: "没有相关 Chunk", RERANK_NO_RELEVANT: "重排后无相关证据", DIVERSITY_FILTERED: "多样化后无证据", LEGACY_UNCLASSIFIED: "旧记录未分类" } as Record<string, string>)[reason] ?? reason; }
function feedbackReasonLabel(reason: string) { return ({ IRRELEVANT: "答非所问", MISSING_EVIDENCE: "证据不足", OUTDATED: "信息过时", INCORRECT: "事实错误", INCOMPLETE: "回答不完整", CITATION_MISMATCH: "引用不匹配", TOO_VERBOSE: "过于冗长", OTHER: "其他" } as Record<string, string>)[reason] ?? reason; }
function triageStatusLabel(status: string) { return ({ NEW: "待分析", TRIAGED: "已归类", EVAL_CANDIDATE: "评测候选", RESOLVED: "已解决", DISMISSED: "已忽略" } as Record<string, string>)[status] ?? status; }
function message(error: unknown) { return error instanceof ApiError ? error.message : error instanceof Error ? error.message : "请求失败"; }
