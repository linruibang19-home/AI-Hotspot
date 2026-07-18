"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { ApiError, apiFetch } from "@/lib/api";

type LoginMode = "code" | "password";

export default function LoginPage() {
  const router = useRouter();
  const search = useSearchParams();
  const { refresh } = useAuth();
  const [mode, setMode] = useState<LoginMode>("code");
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [sending, setSending] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setInterval(() => setCountdown((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearInterval(timer);
  }, [countdown]);

  async function sendCode() {
    if (!email) {
      setError("请先填写邮箱");
      return;
    }
    setSending(true);
    setError("");
    setMessage("");
    try {
      await apiFetch("/auth/email-codes", {
        method: "POST",
        body: JSON.stringify({ email, purpose: "LOGIN" }),
      });
      setCountdown(60);
      setMessage("验证码已发送；本地开发可在 Mailpit 中查看邮件。");
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "验证码发送失败，请稍后再试");
    } finally {
      setSending(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      const path = mode === "code" ? "/auth/code-login" : "/auth/login";
      const body = mode === "code"
        ? { email, code: form.get("code") }
        : { email, password: form.get("password") };
      await apiFetch(path, { method: "POST", body: JSON.stringify(body) });
      await refresh();
      router.replace(search.get("returnTo") || "/");
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "登录失败，请稍后再试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-page">
      <section className="auth-panel" aria-labelledby="login-title">
        <Link className="auth-back" href="/">← 返回公开内容</Link>
        <h1 id="login-title">登录 AI Hotspot</h1>
        <p>登录后可使用收藏、订阅、知识库问答和 Agent。普通用户推荐邮箱验证码登录。</p>
        <div className="auth-tabs" role="tablist" aria-label="登录方式">
          <button className={mode === "code" ? "active" : ""} type="button" onClick={() => setMode("code")}>邮箱验证码</button>
          <button className={mode === "password" ? "active" : ""} type="button" onClick={() => setMode("password")}>管理员密码</button>
        </div>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        {message ? <div className="notice success" role="status">{message}</div> : null}
        <form className="form-stack" onSubmit={submit}>
          <label>邮箱
            <input name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
          </label>
          {mode === "code" ? (
            <label>验证码
              <span className="code-field">
                <input name="code" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required />
                <button type="button" disabled={sending || countdown > 0} onClick={() => void sendCode()}>
                  {countdown > 0 ? `${countdown}s 后重发` : sending ? "发送中…" : "发送验证码"}
                </button>
              </span>
            </label>
          ) : (
            <label>密码<input name="password" type="password" autoComplete="current-password" minLength={12} required /></label>
          )}
          <button className="button primary" type="submit" disabled={submitting}>{submitting ? "正在登录…" : "登录"}</button>
        </form>
        <small>还没有账号？<Link className="auth-inline-link" href={`/register${search.get("returnTo") ? `?returnTo=${encodeURIComponent(search.get("returnTo")!)}` : ""}`}>免费注册</Link></small>
      </section>
    </div>
  );
}
