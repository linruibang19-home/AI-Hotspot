"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { AdminPermissionState, AdminSessionLoading } from "@/components/admin-permission-state";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { apiFetch } from "@/lib/api";

type Issue = { id: string; period: string; startDate: string; endDate: string; volume: string; headline: string; lead: string; status: string; storyCount: number; eventCount: number; sourceCount: number; officialSourceCount: number; featuredCount: number; estimatedMinutes: number; version: number; updatedAt: string };

export default function ReportEditorialPage() {
  const { user, loading: authLoading } = useAuth();
  const [issues, setIssues] = useState<Issue[]>([]);
  const [selected, setSelected] = useState<Issue | null>(null);
  const [period, setPeriod] = useState("DAILY");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const load = useCallback(async () => { const rows = await apiFetch<Issue[]>("/admin/reports?limit=50"); setIssues(rows); setSelected((current) => current ? rows.find((row) => row.id === current.id) ?? rows[0] ?? null : rows[0] ?? null); }, []);
  const allowed = user?.permissions.includes("report:read") ?? false;
  useEffect(() => {
    if (authLoading || !allowed) return;
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [allowed, authLoading, load]);
  async function generate() { setBusy(true); setNotice(""); try { const issue = await apiFetch<Issue>(`/admin/reports/generate?period=${period}`, { method: "POST" }); await load(); setSelected(issue); setNotice("报告草稿已根据当前公开内容生成，可编辑后发布。"); } catch (error) { setNotice(error instanceof Error ? error.message : "生成失败"); } finally { setBusy(false); } }
  async function save() { if (!selected) return; setBusy(true); try { const issue = await apiFetch<Issue>(`/admin/reports/${selected.id}`, { method: "PUT", body: JSON.stringify({ headline: selected.headline, lead: selected.lead, version: selected.version }) }); setSelected(issue); await load(); setNotice("报告编辑已保存。"); } finally { setBusy(false); } }
  async function publish() { if (!selected) return; setBusy(true); try { const issue = await apiFetch<Issue>(`/admin/reports/${selected.id}/publish`, { method: "POST", body: JSON.stringify({ version: selected.version }) }); setSelected(issue); await load(); setNotice("报告已发布，公开报告页面现在读取该版本。"); } finally { setBusy(false); } }
  if (authLoading) return <AdminSessionLoading />;
  if (!user) return <AdminPermissionState title="需要登录" detail="报告编辑只对运营和管理员开放。" login />;
  if (!allowed) return <AdminPermissionState title="没有报告编辑权限" detail="当前账号不能查看或修改报告草稿。" />;
  return <div className="page-shell report-editor-page"><PageHeader title="报告编辑" description="生成、校订、预览并发布日报、周报和月报。" action={<div className="header-actions"><select className="editor-period" value={period} onChange={(event) => setPeriod(event.target.value)}><option value="DAILY">日报</option><option value="WEEKLY">周报</option><option value="MONTHLY">月报</option></select><button className="button primary" disabled={busy} onClick={() => void generate()} type="button">生成草稿</button></div>} />
    {notice ? <div className="notice success">{notice}</div> : null}
    <div className="report-editor-layout"><aside className="product-panel report-editor-list"><h2>报告版本</h2>{issues.map((issue) => <button className={selected?.id === issue.id ? "active" : ""} onClick={() => setSelected(issue)} type="button" key={issue.id}><strong>{issue.period} · {issue.startDate}</strong><span>{issue.headline}</span><small>{issue.status} · v{issue.version}</small></button>)}</aside>
      <main className="product-panel report-editor-form">{selected ? <><header><div><span className={`status-badge ${selected.status.toLowerCase()}`}>{selected.status}</span><h2>{selected.volume}</h2></div><Link className="button" href={`/reports?period=${selected.period}&anchor=${selected.startDate}`}>预览公开页</Link></header><label>本期标题<input value={selected.headline} onChange={(event) => setSelected({ ...selected, headline: event.target.value })} /></label><label>导语<textarea value={selected.lead} onChange={(event) => setSelected({ ...selected, lead: event.target.value })} /></label><div className="report-editor-metrics"><span>{selected.storyCount} 条内容</span><span>{selected.eventCount} 个事件</span><span>{selected.sourceCount} 个信源</span><span>约 {selected.estimatedMinutes} 分钟</span></div><footer><button className="button" disabled={busy || selected.status !== "DRAFT"} onClick={() => void save()} type="button">保存编辑</button><button className="button primary" disabled={busy || selected.status !== "DRAFT"} onClick={() => void publish()} type="button">发布报告</button></footer></> : <div className="empty-state"><h2>尚无报告草稿</h2><p>选择周期后生成第一份报告。</p></div>}</main></div>
  </div>;
}
