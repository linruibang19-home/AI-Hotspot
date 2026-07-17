"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { ApiError, apiFetch } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const search = useSearchParams();
  const { refresh } = useAuth();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    const form = new FormData(event.currentTarget);
    try {
      await apiFetch("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email: form.get("email"), password: form.get("password") }),
      });
      await refresh();
      router.replace(search.get("returnTo") || "/admin/sources");
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
        <p>收藏、订阅、研究、Agent 和管理能力需要邀请制账号。</p>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <form className="form-stack" onSubmit={submit}>
          <label>邮箱<input name="email" type="email" autoComplete="username" required /></label>
          <label>密码<input name="password" type="password" autoComplete="current-password" minLength={12} required /></label>
          <button className="button primary" type="submit" disabled={submitting}>{submitting ? "正在登录…" : "登录"}</button>
        </form>
        <small>不开放公众自由注册。邀请码注册链接由管理员单独提供。</small>
      </section>
    </div>
  );
}
