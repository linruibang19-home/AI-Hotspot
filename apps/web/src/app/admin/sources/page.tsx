"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { ApiError, apiFetch } from "@/lib/api";

type SourceItem = {
  id: string; name: string; slug: string; entityType: string; officialLevel: string;
  authorityScore: number; sourceStatus: string; endpointId: string; endpointName: string;
  endpointType: string; connectorType: string; endpointUrl: string; endpointStatus: string;
  catalogKind: "PRODUCTION" | "TEST" | "USER_MANAGED"; healthStatus: string;
  endpointVersion: number; lastFetchItemCount: number; todayContentCount: number; updatedAt: string;
};
type SourceResponse = {
  metrics: { totalEndpoints: number; activeEndpoints: number; healthyEndpoints: number; attentionEndpoints: number; productionEndpoints: number; testEndpoints: number };
  items: SourceItem[];
};
type QualityEndpoint = {
  endpoint_id: string; source_name: string; endpoint_name: string; official_level: string;
  item_count: number; published_count: number; duplicate_rate: number;
  source_time_completeness: number; published_source_share: number;
  quality_score?: number; assessment: string; quality_weight: number; quality_state: string;
};
type QualityReport = {
  summary: { endpoint_count: number; healthy_count: number; watch_count: number; underperforming_count: number;
    insufficient_data_count: number; duplicate_rate: number; source_time_completeness: number; largest_source_share: number };
  endpoints: QualityEndpoint[];
};

const EMPTY: SourceResponse = { metrics: { totalEndpoints: 0, activeEndpoints: 0, healthyEndpoints: 0, attentionEndpoints: 0, productionEndpoints: 0, testEndpoints: 0 }, items: [] };

export default function AdminSourcesPage() {
  const { user, loading: authLoading } = useAuth();
  const [data, setData] = useState(EMPTY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [catalogKind, setCatalogKind] = useState<"PRODUCTION" | "USER_MANAGED" | "TEST" | "ALL">("PRODUCTION");
  const [connectorType, setConnectorType] = useState("ALL");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState("");
  const [quality, setQuality] = useState<QualityReport | null>(null);
  const [qualityBusy, setQualityBusy] = useState(false);

  const load = useCallback(async (search = "") => {
    setLoading(true);
    setError("");
    try {
      const [sources, report] = await Promise.all([
        apiFetch<SourceResponse>(`/admin/sources?limit=100${search ? `&query=${encodeURIComponent(search)}` : ""}`),
        apiFetch<QualityReport>("/admin/sources/quality"),
      ]);
      setData(sources);
      setQuality(report);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法加载信源");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const task = window.setTimeout(() => {
      if (!authLoading && user) void load();
      else if (!authLoading) setLoading(false);
    }, 0);
    return () => window.clearTimeout(task);
  }, [authLoading, user, load]);

  async function createSource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      await apiFetch("/admin/sources", { method: "POST", body: JSON.stringify({
        name: form.get("name"), slug: form.get("slug"), entityType: form.get("entityType"),
        countryCode: form.get("countryCode"), officialLevel: form.get("officialLevel"),
        authorityScore: Number(form.get("authorityScore")), websiteUrl: form.get("websiteUrl"),
        endpointName: form.get("endpointName"), endpointUrl: form.get("endpointUrl"),
        endpointType: form.get("endpointType"), language: form.get("language"),
        pollingIntervalSeconds: Number(form.get("pollingIntervalSeconds")),
        displayPolicy: form.get("displayPolicy"), indexPolicy: form.get("indexPolicy"), config: {
          autoPublish: false,
          maxItems: 50,
          maxResponseBytes: 2097152,
        },
      }) });
      setDialogOpen(false);
      setNotice("信源已创建，请完成试抓取后再启用。");
      await load();
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "创建信源失败");
    } finally {
      setSubmitting(false);
    }
  }

  async function endpointAction(item: SourceItem, action: "probe" | "activate" | "pause") {
    setNotice(""); setError("");
    try {
      const result = await apiFetch<{ healthStatus?: string; message?: string }>(`/admin/sources/endpoints/${item.endpointId}/${action}`, {
        method: "POST", body: JSON.stringify({ version: item.endpointVersion }),
      });
      setNotice(action === "probe" ? `试抓取完成：${result.healthStatus} · ${result.message}` : action === "activate" ? "Endpoint 已启用" : "Endpoint 已暂停");
      await load(query);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "操作失败");
    }
  }

  async function captureQualityReport() {
    setQualityBusy(true); setError(""); setNotice("");
    try {
      const result = await apiFetch<{ snapshots: number; downranked: number; paused: number }>("/admin/sources/quality/run", { method: "POST" });
      setNotice(`周度质量快照已更新：${result.snapshots} 个 Endpoint，降权 ${result.downranked}，暂停 ${result.paused}`);
      await load(query);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "生成质量周报失败");
    } finally {
      setQualityBusy(false);
    }
  }

  const allowed = user?.roles.some((role) => role === "ADMIN" || role === "OPERATOR");
  const visibleItems = useMemo(() => data.items.filter((item) => {
    const catalogMatches = catalogKind === "ALL" || item.catalogKind === catalogKind;
    const connectorMatches = connectorType === "ALL" || item.endpointType === connectorType ||
      (connectorType === "FEED" && ["RSS", "ATOM"].includes(item.endpointType));
    return catalogMatches && connectorMatches;
  }), [catalogKind, connectorType, data.items]);
  if (!authLoading && !user) return <PermissionState title="需要登录" detail="信源管理只对 ADMIN 和 OPERATOR 开放。" login />;
  if (!authLoading && !allowed) return <PermissionState title="没有管理权限" detail="普通用户不能查看或修改信源。" />;

  return (
    <div className="page-shell">
      <PageHeader title="信源管理" description="统一维护 SourceEntity、Endpoint、采集策略、权威分与正文展示政策。" action={<div className="header-actions"><button className="button" disabled={qualityBusy} type="button" onClick={() => void captureQualityReport()}>{qualityBusy ? "统计中…" : "生成质量周报"}</button><button className="button primary" type="button" onClick={() => setDialogOpen(true)}>＋ 新增信源</button></div>} />
      <section className="metric-grid" aria-label="信源概览">
        {[[data.metrics.productionEndpoints, "正式目录"], [data.metrics.activeEndpoints, "已启用"], [data.metrics.healthyEndpoints, "健康运行"], [data.metrics.testEndpoints, "测试记录"]].map(([value, label]) => <div className="metric-card" key={label}><strong>{value}</strong><span>{label}</span></div>)}
      </section>
      {quality ? <section className="product-panel" aria-label="最近七天信源质量">
        <header><div><span className="overline">SOURCE QUALITY · 7D</span><h2>质量、多样性与时间完整率</h2></div><span className={`status-badge ${quality.summary.underperforming_count ? "warning" : "active"}`}>{quality.summary.underperforming_count} 个低质 Endpoint</span></header>
        <div className="metric-grid"><article><strong>{Math.round(quality.summary.source_time_completeness * 100)}%</strong><span>发布时间完整率</span></article><article><strong>{Math.round(quality.summary.duplicate_rate * 100)}%</strong><span>重复率</span></article><article><strong>{Math.round(quality.summary.largest_source_share * 100)}%</strong><span>最大单源占比</span></article><article><strong>{quality.summary.healthy_count}/{quality.summary.endpoint_count}</strong><span>健康 / 全部 Endpoint</span></article></div>
        <div className="data-table"><div className="data-row head"><span>信源</span><span>质量与状态</span><span>完整率 / 重复率</span><span>公开与份额</span></div>{quality.endpoints.slice(0, 20).map((item) => <div className="data-row" key={item.endpoint_id}><span><strong>{item.source_name}</strong><small>{item.endpoint_name} · {item.official_level}</small></span><span><i className={`status-badge ${item.assessment === "HEALTHY" ? "active" : item.assessment === "UNDERPERFORMING" ? "failed" : "warning"}`}>{item.assessment}</i><small>质量 {item.quality_score?.toFixed(1) ?? "样本不足"} · 权重 {item.quality_weight}</small></span><span>{Math.round(item.source_time_completeness * 100)}%<small>重复 {Math.round(item.duplicate_rate * 100)}%</small></span><span>{item.published_count}/{item.item_count}<small>公开份额 {Math.round(item.published_source_share * 100)}%</small></span></div>)}</div>
      </section> : null}
      <form className="admin-toolbar" onSubmit={(event) => { event.preventDefault(); void load(query); }}>
        <div className="tabs" aria-label="信源目录筛选">{[["PRODUCTION", "正式目录"], ["USER_MANAGED", "用户添加"], ["TEST", "测试数据"], ["ALL", "全部"]].map(([value, label]) => <button className={`tab ${catalogKind === value ? "active" : ""}`} type="button" key={value} onClick={() => setCatalogKind(value as typeof catalogKind)}>{label}</button>)}</div>
        <select aria-label="Connector 类型" value={connectorType} onChange={(event) => setConnectorType(event.target.value)}><option value="ALL">全部 Connector</option><option value="FEED">RSS / Atom</option><option value="WEBSITE">Website</option><option value="SITEMAP">Sitemap</option><option value="GITHUB">GitHub</option><option value="HUGGING_FACE">Hugging Face</option><option value="ARXIV">arXiv</option><option value="OPENREVIEW">OpenReview</option><option value="HACKER_NEWS">Hacker News</option></select>
        <div className="search-shell"><input aria-label="搜索信源" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索主体或 Endpoint..." /><button className="search-button" type="submit">搜索</button></div>
      </form>
      {notice ? <div className="notice success" role="status">{notice}</div> : null}
      {error ? <div className="notice error" role="alert">{error}</div> : null}
      {loading ? <div className="source-list"><div className="source-row skeleton" /><div className="source-row skeleton" /></div> : visibleItems.length ? (
        <section className="source-list" aria-label="信源列表">
          {visibleItems.map((item) => <article className="source-row" key={item.endpointId}>
            <div className="source-main"><div className="source-title"><Link href={`/admin/sources/${item.id}`}>{item.name}</Link><span className={`status-badge ${item.healthStatus.toLowerCase()}`}>{item.healthStatus}</span></div><p>{item.endpointName} · {item.endpointUrl}</p><div className="source-meta"><span>{item.connectorType}</span><span>{item.catalogKind === "PRODUCTION" ? "正式目录" : item.catalogKind === "TEST" ? "测试数据" : "用户添加"}</span><span>{item.officialLevel}</span><span>权威分 {item.authorityScore}</span><span>状态 {item.endpointStatus}</span><span>今日 {item.todayContentCount} 条</span></div></div>
            <div className="row-actions">{item.endpointType === "X" ? <span className="status-badge warning">仅预留</span> : <><button className="button" type="button" onClick={() => void endpointAction(item, "probe")}>试抓取</button>{item.endpointStatus === "ACTIVE" ? <button className="button" type="button" onClick={() => void endpointAction(item, "pause")}>暂停</button> : <button className="button primary" type="button" onClick={() => void endpointAction(item, "activate")}>启用</button>}</>}</div>
          </article>)}
        </section>
      ) : <div className="empty-state panel"><h2>还没有信源</h2><p>先新增一个公开 Endpoint，完成试抓取后再启用。</p><button className="button primary" type="button" onClick={() => setDialogOpen(true)}>新增第一个信源</button></div>}
      {dialogOpen ? <div className="dialog-backdrop" role="presentation"><section className="dialog-card" role="dialog" aria-modal="true" aria-labelledby="source-dialog-title"><div className="dialog-header"><div><h2 id="source-dialog-title">新增信源</h2><p>创建 SourceEntity 与首个 Endpoint；敏感凭据不允许写入配置。</p></div><button className="icon-button" onClick={() => setDialogOpen(false)} aria-label="关闭">×</button></div><form className="source-form" onSubmit={createSource}><label>主体名称<input name="name" required maxLength={120} placeholder="例如：OpenAI News" /></label><label>Slug<input name="slug" required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" placeholder="openai-news" /></label><label>主体类型<select name="entityType" defaultValue="COMPANY"><option>COMPANY</option><option>RESEARCH</option><option>MEDIA</option><option>COMMUNITY</option><option>PROJECT</option><option>OTHER</option></select></label><label>官方等级<select name="officialLevel" defaultValue="OFFICIAL"><option>OFFICIAL</option><option>FIRST_PARTY</option><option>THIRD_PARTY</option></select></label><label>国家/地区<input name="countryCode" maxLength={2} placeholder="US" /></label><label>权威分<input name="authorityScore" type="number" min="0" max="100" defaultValue="80" required /></label><label className="span-2">官网 URL<input name="websiteUrl" type="url" placeholder="https://example.com" /></label><label>Endpoint 名称<input name="endpointName" required placeholder="官方 RSS" /></label><label>Endpoint 类型<select name="endpointType" defaultValue="RSS"><option>RSS</option><option>ATOM</option><option>WEBSITE</option><option>SITEMAP</option><option>GITHUB</option><option>HUGGING_FACE</option><option>ARXIV</option><option>OPENREVIEW</option><option>HACKER_NEWS</option><option>PUBLIC_MEDIA</option><option>PUBLIC_COMMUNITY</option></select></label><label className="span-2">Endpoint URL<input name="endpointUrl" type="url" required placeholder="https://example.com/feed.xml" /></label><label>语言<input name="language" defaultValue="en" /></label><label>轮询间隔（秒）<input name="pollingIntervalSeconds" type="number" min="300" max="2592000" defaultValue="3600" /></label><label>展示策略<select name="displayPolicy" defaultValue="SUMMARY_ONLY"><option>FULLTEXT_ALLOWED</option><option>SUMMARY_ONLY</option><option>LINK_ONLY</option><option>HIDDEN</option></select></label><label>索引策略<select name="indexPolicy" defaultValue="PUBLIC_RAG"><option>PUBLIC_RAG</option><option>PRIVATE_RAG</option><option>METADATA_ONLY</option><option>NO_INDEX</option></select></label><div className="dialog-actions span-2"><button className="button" type="button" onClick={() => setDialogOpen(false)}>取消</button><button className="button primary" type="submit" disabled={submitting}>{submitting ? "正在创建…" : "创建信源"}</button></div></form></section></div> : null}
    </div>
  );
}

function PermissionState({ title, detail, login = false }: { title: string; detail: string; login?: boolean }) {
  return <div className="page-shell"><div className="permission-state"><span>权限边界</span><h1>{title}</h1><p>{detail}</p>{login ? <Link className="button primary" href="/login?returnTo=/admin/sources">前往登录</Link> : <Link className="button" href="/">返回公开区</Link>}</div></div>;
}
