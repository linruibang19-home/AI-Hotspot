"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { ApiError, apiFetch } from "@/lib/api";

type FetchJob = {
  id: string; endpointName: string; sourceName: string; status: string;
  pollOutcome: string;
  attemptCount: number; maxAttempts: number; httpStatus: number | null;
  discoveredCount: number; newEntryCount: number; lastError: string | null; createdAt: string;
};

type DeadLetter = {
  id: string; queueName: string; eventType: string; aggregateId: string | null;
  failureCount: number; lastError: string; replayStatus: string;
};

export default function AdminCrawlsPage() {
  const { user, loading: authLoading } = useAuth();
  const [jobs, setJobs] = useState<FetchJob[]>([]);
  const [deadLetters, setDeadLetters] = useState<DeadLetter[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [jobRows, deadRows] = await Promise.all([
        apiFetch<FetchJob[]>("/admin/fetch-jobs?limit=100"),
        apiFetch<DeadLetter[]>("/admin/dead-letters?limit=100"),
      ]);
      setJobs(jobRows); setDeadLetters(deadRows);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "无法加载采集状态");
    } finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const task = window.setTimeout(() => {
      if (!authLoading && user) void load();
      else if (!authLoading) setLoading(false);
    }, 0);
    return () => window.clearTimeout(task);
  }, [authLoading, load, user]);

  const metrics = useMemo(() => ({
    running: jobs.filter((job) => ["QUEUED", "RUNNING"].includes(job.status)).length,
    retrying: jobs.filter((job) => job.status === "WAITING_RETRY").length,
    pendingDead: deadLetters.filter((item) => item.replayStatus === "PENDING").length,
    noChange: jobs.filter((job) => ["NO_NEW_CONTENT", "NOT_MODIFIED"].includes(job.pollOutcome)).length,
  }), [deadLetters, jobs]);

  async function dispatchDue() {
    setBusy("dispatch"); setNotice(""); setError("");
    try {
      const response = await apiFetch<{ dispatched: number }>("/admin/fetch-jobs/dispatch-due", { method: "POST" });
      setNotice(`已派发 ${response.dispatched} 个到期 RSS/Atom 任务。`); await load();
    } catch (reason) { setError(reason instanceof ApiError ? reason.message : "派发失败"); }
    finally { setBusy(""); }
  }

  async function replay(id: string) {
    setBusy(id); setNotice(""); setError("");
    try {
      await apiFetch(`/admin/dead-letters/${id}/replay`, { method: "POST" });
      setNotice("死信已生成受审计的回放事件。"); await load();
    } catch (reason) { setError(reason instanceof ApiError ? reason.message : "回放失败"); }
    finally { setBusy(""); }
  }

  const allowed = user?.roles.some((role) => role === "ADMIN" || role === "OPERATOR");
  if (!authLoading && !user) return <PermissionState title="需要登录" detail="采集监控只对 ADMIN 和 OPERATOR 开放。" login />;
  if (!authLoading && !allowed) return <PermissionState title="没有管理权限" detail="普通用户不能查看采集任务和死信。" />;

  return (
    <div className="page-shell">
      <PageHeader title="采集监控" description="真实 FetchJob、RabbitMQ 重试、DLQ 和受审计回放。" action={<div className="header-actions"><button className="button" type="button" onClick={() => void load()}>刷新</button><button className="button primary" type="button" disabled={busy === "dispatch"} onClick={() => void dispatchDue()}>{busy === "dispatch" ? "正在派发…" : "派发到期任务"}</button></div>} />
      <section className="metric-grid" aria-label="采集概览">
        {[[metrics.noChange, "正常零新增"], [metrics.running, "运行/排队"], [metrics.retrying, "等待重试"], [metrics.pendingDead, "待处理死信"]].map(([value, label]) => <div className="metric-card" key={label}><strong>{value}</strong><span>{label}</span></div>)}
      </section>
      {notice ? <div className="notice success" role="status">{notice}</div> : null}
      {error ? <div className="notice" role="alert">{error}</div> : null}
      <section className="table-panel crawl-panel" aria-labelledby="jobs-title">
        <h2 id="jobs-title">采集任务</h2>
        {loading ? <div className="source-row skeleton" /> : jobs.length ? <div className="data-table">
          <div className="data-row head"><span>信源 / 任务</span><span>状态</span><span>结果</span><span>时间</span></div>
          {jobs.map((job) => <div className="data-row" key={job.id}><span><strong>{job.sourceName}</strong><small>{job.endpointName} · {job.id.slice(0, 8)}</small></span><span><i className={`status-badge ${job.status.toLowerCase()}`}>{job.status}</i><small>{outcomeLabel(job.pollOutcome)} · {job.attemptCount}/{job.maxAttempts} 次</small></span><span>{job.httpStatus ?? "—"}<small>发现 {job.discoveredCount} · 新增 {job.newEntryCount}</small></span><span>{formatDate(job.createdAt)}{job.lastError ? <small title={job.lastError}>{job.lastError}</small> : null}</span></div>)}
        </div> : <div className="empty-state"><h2>暂无采集任务</h2><p>启用 RSS/Atom Endpoint 后派发到期任务。</p></div>}
      </section>
      <section className="table-panel crawl-panel" aria-labelledby="dead-title">
        <h2 id="dead-title">死信与回放</h2>
        {deadLetters.length ? <div className="data-table">
          <div className="data-row head"><span>事件</span><span>队列</span><span>错误</span><span>操作</span></div>
          {deadLetters.map((item) => <div className="data-row" key={item.id}><span><strong>{item.eventType}</strong><small>{item.aggregateId?.slice(0, 8) ?? "无关联对象"}</small></span><span>{item.queueName}<small>失败 {item.failureCount} 次</small></span><span title={item.lastError}>{item.lastError}</span><span>{item.replayStatus === "PENDING" ? <button className="button" type="button" disabled={busy === item.id} onClick={() => void replay(item.id)}>{busy === item.id ? "回放中…" : "人工回放"}</button> : <i className="status-badge active">{item.replayStatus}</i>}</span></div>)}
        </div> : <div className="empty-state compact-empty"><h2>没有待处理死信</h2><p>达到最大重试次数的任务会出现在这里。</p></div>}
      </section>
    </div>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function outcomeLabel(value: string) {
  return ({ PENDING: "等待结果", NEW_CONTENT: "发现新内容", NO_NEW_CONTENT: "正常零新增", NOT_MODIFIED: "上游未变化", UPSTREAM_FAILURE: "上游失败", CONTENT_FAILURE: "内容结构失败", CANCELLED: "已取消" } as Record<string, string>)[value] ?? value;
}

function PermissionState({ title, detail, login = false }: { title: string; detail: string; login?: boolean }) {
  return <div className="page-shell"><div className="permission-state"><span>权限边界</span><h1>{title}</h1><p>{detail}</p>{login ? <Link className="button primary" href="/login?returnTo=/admin/crawls">前往登录</Link> : <Link className="button" href="/">返回公开区</Link>}</div></div>;
}
