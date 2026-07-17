"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { PageHeader } from "@/components/page-header";
import { ApiError, apiFetch } from "@/lib/api";

type SourceView = {
  source: { id: string; name: string; slug: string; entityType: string; countryCode?: string; officialLevel: string; authorityScore: number; websiteUrl?: string; status: string; version: number; createdAt: string; updatedAt: string };
  endpoints: Array<{ endpointId: string; endpointName: string; endpointType: string; endpointUrl: string; endpointStatus: string; healthStatus: string; endpointVersion: number; lastSuccessAt?: string; lastFailureAt?: string }>;
};
type Audit = { id: string; action: string; actorType: string; actorId?: string; beforeData?: string; afterData?: string; correlationId?: string; createdAt: string };

export default function SourceDetailPage() {
  const params = useParams<{ id: string }>();
  const [view, setView] = useState<SourceView | null>(null);
  const [audits, setAudits] = useState<Audit[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    apiFetch<SourceView>(`/admin/sources/${params.id}`).then(async (source) => {
      const history = await Promise.all([
        apiFetch<Audit[]>(`/admin/audits?targetType=SOURCE_ENTITY&targetId=${params.id}`),
        ...source.endpoints.map((endpoint) => apiFetch<Audit[]>(`/admin/audits?targetType=SOURCE_ENDPOINT&targetId=${endpoint.endpointId}`)),
      ]);
      setView(source);
      setAudits(history.flat().sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt)));
    }).catch((reason) => setError(reason instanceof ApiError ? reason.message : "无法加载信源详情"));
  }, [params.id]);

  if (error) return <div className="page-shell"><div className="permission-state"><h1>无法打开信源</h1><p>{error}</p><Link className="button" href="/admin/sources">返回信源列表</Link></div></div>;
  if (!view) return <div className="page-shell"><div className="detail-skeleton skeleton" /></div>;

  return <div className="page-shell"><PageHeader title={view.source.name} description={`SourceEntity · ${view.source.slug}`} action={<Link className="button" href="/admin/sources">← 返回列表</Link>} /><section className="detail-grid"><article className="detail-panel"><h2>主体信息</h2><dl className="definition-list"><div><dt>主体类型</dt><dd>{view.source.entityType}</dd></div><div><dt>官方等级</dt><dd>{view.source.officialLevel}</dd></div><div><dt>权威分</dt><dd>{view.source.authorityScore}</dd></div><div><dt>状态</dt><dd>{view.source.status}</dd></div><div><dt>国家/地区</dt><dd>{view.source.countryCode || "未设置"}</dd></div><div><dt>官网</dt><dd>{view.source.websiteUrl ? <a href={view.source.websiteUrl} target="_blank" rel="noreferrer">{view.source.websiteUrl}</a> : "未设置"}</dd></div></dl></article><article className="detail-panel"><h2>Endpoint</h2>{view.endpoints.map((endpoint) => <div className="endpoint-detail" key={endpoint.endpointId}><div><strong>{endpoint.endpointName}</strong><span className={`status-badge ${endpoint.healthStatus.toLowerCase()}`}>{endpoint.healthStatus}</span></div><p>{endpoint.endpointUrl}</p><small>{endpoint.endpointType} · {endpoint.endpointStatus} · version {endpoint.endpointVersion}</small></div>)}</article></section><section className="audit-panel"><h2>审计记录</h2>{audits.length ? audits.map((entry) => <article className="audit-row" key={entry.id}><div><strong>{entry.action}</strong><span>{new Date(entry.createdAt).toLocaleString("zh-CN")}</span></div><p>操作者：{entry.actorId || entry.actorType} · 关联 ID：{entry.correlationId || "—"}</p></article>) : <p className="muted">暂无可见审计记录。</p>}</section></div>;
}
