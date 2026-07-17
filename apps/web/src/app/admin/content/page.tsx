"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { ApiError, apiFetch } from "@/lib/api";

type Metrics = { reviewRequired: number; unconfirmed: number; duplicates: number; openTickets: number };
type Item = {
  id: string; title: string; sourceName: string; originalUrl?: string; categoryCode?: string;
  publicationStatus: string; visibility: string; admissionStatus: string; factStatus: string;
  relevanceScore?: number; qualityScore?: number; finalScore?: number; featured: boolean;
  duplicate: boolean; duplicateOfId?: string; qualityDimensions: string; tags: string;
  events: string; version: number; updatedAt: string;
};
type EventItem = { id: string; title: string; categoryCode?: string; factStatus: string; contentCount: number; lastSeenAt: string };
type Ticket = { id: string; contentItemId?: string; ticketType: string; status: string; priority: string; reason: string; resolution?: string; createdAt: string };
type Response = { metrics: Metrics; items: Item[] };
const EMPTY: Response = { metrics: { reviewRequired: 0, unconfirmed: 0, duplicates: 0, openTickets: 0 }, items: [] };

export default function AdminContentPage() {
  const { user, loading: authLoading } = useAuth();
  const allowed = user?.roles.some((role) => ["EDITOR", "OPERATOR", "ADMIN"].includes(role));
  const canManage = user?.roles.some((role) => ["OPERATOR", "ADMIN"].includes(role)) ?? false;
  const [data, setData] = useState(EMPTY);
  const [events, setEvents] = useState<EventItem[]>([]);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [view, setView] = useState<"CONTENT" | "EVENTS" | "TICKETS">("CONTENT");
  const [filter, setFilter] = useState("ALL");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState<{ item: Item; action: string } | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [content, eventRows, ticketRows] = await Promise.all([
        apiFetch<Response>("/admin/content?limit=100"),
        apiFetch<EventItem[]>("/admin/content/events?limit=100"),
        canManage ? apiFetch<Ticket[]>("/admin/content/tickets?limit=100") : Promise.resolve([]),
      ]);
      setData(content); setEvents(eventRows); setTickets(ticketRows);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法加载内容治理数据");
    } finally { setLoading(false); }
  }, [canManage]);

  useEffect(() => {
    const task = window.setTimeout(() => {
      if (!authLoading && user) void load();
      else if (!authLoading) setLoading(false);
    }, 0);
    return () => window.clearTimeout(task);
  }, [authLoading, user, load]);

  const visible = useMemo(() => data.items.filter((item) => {
    const matches = filter === "ALL" || (filter === "REVIEW" && ["PENDING", "REVIEW_REQUIRED"].includes(item.admissionStatus)) ||
      (filter === "UNCONFIRMED" && item.factStatus === "UNCONFIRMED") ||
      (filter === "DUPLICATE" && item.duplicate) || (filter === "PUBLISHED" && item.publicationStatus === "PUBLISHED");
    const text = `${item.title} ${item.sourceName} ${item.tags} ${item.events}`.toLowerCase();
    return matches && (!query || text.includes(query.toLowerCase()));
  }), [data.items, filter, query]);

  async function govern(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!dialog) return;
    const reason = String(new FormData(event.currentTarget).get("reason") || "");
    try {
      await apiFetch(`/admin/content/${dialog.item.id}/actions`, {
        method: "POST", body: JSON.stringify({ action: dialog.action, reason, version: dialog.item.version }),
      });
      setNotice(`已执行：${actionLabel(dialog.action)}`); setDialog(null); await load();
    } catch (reasonValue) { setError(reasonValue instanceof ApiError ? reasonValue.message : "治理操作失败"); }
  }

  async function resolveTicket(ticket: Ticket, status: "RESOLVED" | "REJECTED") {
    const resolution = window.prompt(status === "RESOLVED" ? "请输入处理结果" : "请输入驳回理由");
    if (!resolution) return;
    try {
      await apiFetch(`/admin/content/tickets/${ticket.id}/resolve`, {
        method: "POST", body: JSON.stringify({ status, resolution }),
      });
      setNotice("工单已处理"); await load();
    } catch (reason) { setError(reason instanceof ApiError ? reason.message : "工单处理失败"); }
  }

  if (!authLoading && (!user || !allowed)) return <div className="permission-state"><h1>没有内容治理权限</h1><p>请使用 EDITOR、OPERATOR 或 ADMIN 账号。</p></div>;

  return <div className="page-shell content-governance">
    <PageHeader title="内容治理" description="审核公开准入、事实状态、重复主条目、跨事件关联与纠错下架工单。" />
    <section className="metric-grid" aria-label="治理概览">
      {[[data.metrics.reviewRequired, "待审核"], [data.metrics.unconfirmed, "未证实"], [data.metrics.duplicates, "重复条目"], [data.metrics.openTickets, "开放工单"]].map(([value, label]) =>
        <div className="metric-card" key={label}><strong>{value}</strong><span>{label}</span></div>)}
    </section>
    <div className="admin-toolbar governance-toolbar">
      <div className="tabs" aria-label="治理视图">
        {[["CONTENT", "内容队列"], ["EVENTS", "事件聚类"], ...(canManage ? [["TICKETS", "反馈工单"]] : [])].map(([key, label]) =>
          <button className={`tab ${view === key ? "active" : ""}`} type="button" key={key} onClick={() => setView(key as typeof view)}>{label}</button>)}
      </div>
      {view === "CONTENT" ? <><select aria-label="内容筛选" value={filter} onChange={(event) => setFilter(event.target.value)}>
        <option value="ALL">全部内容</option><option value="REVIEW">待审核</option><option value="UNCONFIRMED">未证实</option>
        <option value="DUPLICATE">重复内容</option><option value="PUBLISHED">已公开</option>
      </select><div className="search-shell"><input aria-label="搜索内容" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索标题、信源、标签或事件…" /></div></> : null}
    </div>
    {notice ? <div className="notice success" role="status">{notice}</div> : null}
    {error ? <div className="notice error" role="alert">{error}</div> : null}
    {loading ? <div className="panel empty-state">正在加载治理数据…</div> : view === "CONTENT" ?
      <section className="governance-list" aria-label="内容审核队列">{visible.map((item) => <article className="governance-card" key={item.id}>
        <header><div><div className="governance-badges"><Badge value={item.admissionStatus} /><Badge value={item.factStatus} />{item.duplicate ? <Badge value="DUPLICATE" /> : null}</div><h2>{item.title}</h2><p>{item.sourceName} · {item.categoryCode || "未分类"} · {item.tags || "无标签"}</p></div><Score value={item.finalScore} /></header>
        <div className="score-strip"><span>相关度 <b>{score(item.relevanceScore)}</b></span><span>质量 <b>{score(item.qualityScore)}</b></span><span>事件 <b>{item.events || "待聚类"}</b></span></div>
        <footer><span>{item.publicationStatus} / {item.visibility}{item.duplicateOfId ? ` · 主条目 ${item.duplicateOfId.slice(0, 8)}` : ""}</span>{canManage ? <div className="row-actions">
          {!item.duplicate && item.factStatus !== "DEBUNKED" && item.publicationStatus !== "PUBLISHED" ? <Action item={item} action="APPROVE" onClick={setDialog} /> : null}
          {item.factStatus === "CONFIRMED" ? <Action item={item} action="MARK_UNCONFIRMED" onClick={setDialog} /> : null}
          {item.factStatus !== "DEBUNKED" ? <Action item={item} action="DEBUNK" onClick={setDialog} /> : null}
          {item.publicationStatus === "PUBLISHED" ? <Action item={item} action="TAKEDOWN" onClick={setDialog} /> : (!item.duplicate && item.factStatus !== "DEBUNKED" ? <Action item={item} action="RESTORE" onClick={setDialog} /> : null)}
          {item.admissionStatus !== "FAILED" ? <Action item={item} action="REJECT" onClick={setDialog} /> : null}
        </div> : <span>只读权限</span>}</footer>
      </article>)}</section> : view === "EVENTS" ?
      <section className="governance-list">{events.map((item) => <article className="governance-card compact" key={item.id}><header><div><div className="governance-badges"><Badge value={item.factStatus} /></div><h2>{item.title}</h2><p>{item.categoryCode || "未分类"} · 最近更新 {new Date(item.lastSeenAt).toLocaleString("zh-CN")}</p></div><Score value={item.contentCount} suffix="条" /></header><footer><span>支持通过 API 将内容关联到多个事件；主事件关系受保护。</span></footer></article>)}</section> :
      <section className="governance-list">{tickets.map((ticket) => <article className="governance-card compact" key={ticket.id}><header><div><div className="governance-badges"><Badge value={ticket.priority} /><Badge value={ticket.status} /></div><h2>{ticket.ticketType} · {ticket.contentItemId?.slice(0, 8) || "事件反馈"}</h2><p>{ticket.reason}</p></div></header><footer><span>{new Date(ticket.createdAt).toLocaleString("zh-CN")}</span>{["OPEN", "IN_PROGRESS"].includes(ticket.status) ? <div className="row-actions"><button className="button primary" onClick={() => void resolveTicket(ticket, "RESOLVED")}>解决</button><button className="button" onClick={() => void resolveTicket(ticket, "REJECTED")}>驳回</button></div> : <span>{ticket.resolution}</span>}</footer></article>)}</section>}
    {dialog ? <div className="dialog-backdrop"><section className="dialog-card" role="dialog" aria-modal="true"><div className="dialog-header"><div><h2>{actionLabel(dialog.action)}</h2><p>{dialog.item.title}</p></div><button className="icon-button" onClick={() => setDialog(null)} aria-label="关闭">×</button></div><form className="governance-form" onSubmit={govern}><label>操作理由<textarea name="reason" required minLength={4} maxLength={1000} placeholder="理由将写入不可省略的审计记录…" /></label><div className="dialog-actions"><button className="button" type="button" onClick={() => setDialog(null)}>取消</button><button className="button primary" type="submit">确认执行</button></div></form></section></div> : null}
  </div>;
}

function Action({ item, action, onClick }: { item: Item; action: string; onClick: (value: { item: Item; action: string }) => void }) {
  return <button className={`button ${action === "APPROVE" || action === "RESTORE" ? "primary" : ""}`} type="button" onClick={() => onClick({ item, action })}>{actionLabel(action)}</button>;
}
function Badge({ value }: { value: string }) { return <span className={`governance-badge ${value.toLowerCase()}`}>{value}</span>; }
function Score({ value, suffix = "" }: { value?: number; suffix?: string }) { return <div className="governance-score"><strong>{value ?? "—"}</strong><span>{suffix || "最终分"}</span></div>; }
function score(value?: number) { return value == null ? "—" : Number(value).toFixed(1); }
function actionLabel(action: string) { return ({ APPROVE: "批准公开", REJECT: "驳回", MARK_UNCONFIRMED: "标记未证实", DEBUNK: "标记证伪", TAKEDOWN: "下架", RESTORE: "恢复" } as Record<string, string>)[action] || action; }
