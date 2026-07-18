"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { ApiError, apiFetch } from "@/lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const search = useSearchParams();
  const { refresh } = useAuth();
  const [email, setEmail] = useState("");
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
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
        body: JSON.stringify({ email, purpose: "REGISTER" }),
      });
      setCountdown(60);
      setMessage("验证码已发送；本地开发可打开 Mailpit 查看邮件。");
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
      await apiFetch("/auth/register", {
        method: "POST",
        body: JSON.stringify({ email, displayName: form.get("displayName"), code: form.get("code") }),
      });
      await refresh();
      router.replace(search.get("returnTo") || "/");
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "注册失败，请稍后再试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="auth-page">
      <section className="auth-panel" aria-labelledby="register-title">
        <Link className="auth-back" href="/">← 返回公开内容</Link>
        <h1 id="register-title">注册 AI Hotspot</h1>
        <p>任何用户都可以使用邮箱验证码注册。注册成功后会直接登录普通用户账号。</p>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        {message ? <div className="notice success" role="status">{message}</div> : null}
        <form className="form-stack" onSubmit={submit}>
          <label>显示名称<input name="displayName" autoComplete="name" minLength={2} maxLength={80} required /></label>
          <label>邮箱
            <input name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
          </label>
          <label>验证码
            <span className="code-field">
              <input name="code" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" maxLength={6} required />
              <button type="button" disabled={sending || countdown > 0} onClick={() => void sendCode()}>
                {countdown > 0 ? `${countdown}s 后重发` : sending ? "发送中…" : "发送验证码"}
              </button>
            </span>
          </label>
          <button className="button primary" type="submit" disabled={submitting}>{submitting ? "正在注册…" : "注册并登录"}</button>
        </form>
        <small>已有账号？<Link className="auth-inline-link" href={`/login${search.get("returnTo") ? `?returnTo=${encodeURIComponent(search.get("returnTo")!)}` : ""}`}>返回登录</Link></small>
      </section>
    </div>
  );
}
