"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { ApiError, apiFetch } from "@/lib/api";

type User = { id: string; email: string; displayName: string; status: string; roles: string; createdAt: string; lastLoginAt?: string };
type Invitation = { id: string; defaultRole: string; maxUses: number; usedCount: number; expiresAt: string; status: string; createdAt: string };

export default function AdminUsersPage() {
  const { user, loading: authLoading } = useAuth();
  const [users, setUsers] = useState<User[]>([]);
  const [invitations, setInvitations] = useState<Invitation[]>([]);
  const [error, setError] = useState("");
  const [token, setToken] = useState("");
  const [mode, setMode] = useState<"user" | "invitation" | null>(null);

  const load = useCallback(async () => {
    try {
      const [accounts, codes] = await Promise.all([apiFetch<User[]>("/admin/users"), apiFetch<Invitation[]>("/admin/invitations")]);
      setUsers(accounts); setInvitations(codes);
    } catch (reason) { setError(reason instanceof ApiError ? reason.message : "无法加载账号数据"); }
  }, []);
  useEffect(() => {
    const task = window.setTimeout(() => { if (user?.roles.includes("ADMIN")) void load(); }, 0);
    return () => window.clearTimeout(task);
  }, [user, load]);

  async function createUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); setError("");
    try { await apiFetch("/admin/users", { method: "POST", body: JSON.stringify({ email: form.get("email"), displayName: form.get("displayName"), password: form.get("password"), role: form.get("role") }) }); setMode(null); await load(); }
    catch (reason) { setError(reason instanceof ApiError ? reason.message : "账号创建失败"); }
  }
  async function createInvitation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); setError("");
    try { const created = await apiFetch<{ token: string }>("/admin/invitations", { method: "POST", body: JSON.stringify({ defaultRole: form.get("role"), maxUses: Number(form.get("maxUses")), expiresAt: new Date(String(form.get("expiresAt"))).toISOString() }) }); setToken(`${location.origin}/invite/${created.token}`); setMode(null); await load(); }
    catch (reason) { setError(reason instanceof ApiError ? reason.message : "邀请码创建失败"); }
  }

  if (!authLoading && !user?.roles.includes("ADMIN")) return <div className="page-shell"><div className="permission-state"><span>ADMIN</span><h1>没有用户管理权限</h1><p>账号、邀请和角色仅允许管理员操作。</p></div></div>;
  return <div className="page-shell"><PageHeader title="用户与邀请" description="管理员创建账号或一次性邀请码；公众自由注册保持关闭。" action={<div className="header-actions"><button className="button" onClick={() => setMode("invitation")}>创建邀请码</button><button className="button primary" onClick={() => setMode("user")}>创建账号</button></div>} />{error ? <div className="notice error" role="alert">{error}</div> : null}{token ? <div className="token-box" role="status"><strong>邀请码只显示这一次</strong><code>{token}</code><button className="button" onClick={() => void navigator.clipboard.writeText(token)}>复制链接</button></div> : null}<section className="admin-columns"><article className="table-panel"><h2>账号</h2><div className="data-table"><div className="data-row head"><span>用户</span><span>角色</span><span>状态</span><span>最近登录</span></div>{users.map((account) => <div className="data-row" key={account.id}><span><strong>{account.displayName}</strong><small>{account.email}</small></span><span>{account.roles}</span><span>{account.status}</span><span>{account.lastLoginAt ? new Date(account.lastLoginAt).toLocaleString("zh-CN") : "从未登录"}</span></div>)}</div></article><article className="table-panel"><h2>邀请码</h2>{invitations.map((invitation) => <div className="invite-row" key={invitation.id}><div><strong>{invitation.defaultRole}</strong><span className={`status-badge ${invitation.status.toLowerCase()}`}>{invitation.status}</span></div><p>已使用 {invitation.usedCount}/{invitation.maxUses} · {new Date(invitation.expiresAt).toLocaleString("zh-CN")} 到期</p></div>)}</article></section>{mode ? <div className="dialog-backdrop"><section className="dialog-card compact" role="dialog" aria-modal="true"><div className="dialog-header"><h2>{mode === "user" ? "创建账号" : "创建邀请码"}</h2><button className="icon-button" onClick={() => setMode(null)}>×</button></div>{mode === "user" ? <form className="form-stack" onSubmit={createUser}><label>显示名称<input name="displayName" required minLength={2} /></label><label>邮箱<input name="email" type="email" required /></label><label>初始密码<input name="password" type="password" required minLength={12} maxLength={72} /></label><label>角色<select name="role"><option>USER</option><option>EDITOR</option><option>OPERATOR</option><option>ADMIN</option></select></label><button className="button primary">创建账号</button></form> : <form className="form-stack" onSubmit={createInvitation}><label>默认角色<select name="role"><option>USER</option><option>EDITOR</option><option>OPERATOR</option></select></label><label>最大使用次数<input name="maxUses" type="number" min="1" max="100" defaultValue="1" required /></label><label>到期时间<input name="expiresAt" type="datetime-local" required /></label><button className="button primary">生成邀请码</button></form>}</section></div> : null}</div>;
}
