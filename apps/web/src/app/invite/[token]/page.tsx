"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { ApiError, apiFetch } from "@/lib/api";

export default function InvitationRegistrationPage() {
  const params = useParams<{ token: string }>();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [completed, setCompleted] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    const form = new FormData(event.currentTarget);
    const password = String(form.get("password"));
    if (password !== form.get("passwordConfirm")) {
      setError("两次输入的密码不一致");
      setSubmitting(false);
      return;
    }
    try {
      await apiFetch(`/auth/invitations/${encodeURIComponent(params.token)}/register`, {
        method: "POST",
        body: JSON.stringify({ email: form.get("email"), displayName: form.get("displayName"), password }),
      });
      setCompleted(true);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : "注册失败，请稍后再试");
    } finally {
      setSubmitting(false);
    }
  }

  if (completed) {
    return <div className="auth-page"><section className="auth-panel"><h1>账号已创建</h1><p>邀请码已安全核销，现在可以登录。</p><button className="button primary" onClick={() => router.replace("/login")}>前往登录</button></section></div>;
  }

  return (
    <div className="auth-page">
      <section className="auth-panel" aria-labelledby="invite-title">
        <Link className="auth-back" href="/">← 返回公开内容</Link>
        <h1 id="invite-title">使用邀请码创建账号</h1>
        <p>邀请码只会在有效期和剩余次数内生效，注册后无法再次查看邀请码内容。</p>
        {error ? <div className="form-error" role="alert">{error}</div> : null}
        <form className="form-stack" onSubmit={submit}>
          <label>显示名称<input name="displayName" autoComplete="name" minLength={2} maxLength={80} required /></label>
          <label>邮箱<input name="email" type="email" autoComplete="email" required /></label>
          <label>密码<input name="password" type="password" autoComplete="new-password" minLength={12} maxLength={72} required /></label>
          <label>确认密码<input name="passwordConfirm" type="password" autoComplete="new-password" minLength={12} maxLength={72} required /></label>
          <button className="button primary" type="submit" disabled={submitting}>{submitting ? "正在创建…" : "创建账号"}</button>
        </form>
      </section>
    </div>
  );
}
