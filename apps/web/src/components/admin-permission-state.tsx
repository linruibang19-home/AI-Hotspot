import Link from "next/link";

export function AdminPermissionState({
  title,
  detail,
  login = false,
}: {
  title: string;
  detail: string;
  login?: boolean;
}) {
  return (
    <div className="page-shell">
      <div className="permission-state">
        <span>{login ? "AUTHENTICATION" : "ACCESS CONTROL"}</span>
        <h1>{title}</h1>
        <p>{detail}</p>
        {login ? (
          <Link className="button primary" href={`/login?returnTo=${encodeURIComponent(location.pathname)}`}>
            登录或注册
          </Link>
        ) : null}
      </div>
    </div>
  );
}

export function AdminSessionLoading() {
  return (
    <div className="page-shell">
      <div className="permission-state" aria-live="polite">
        <span>SESSION</span>
        <h1>正在检查会话…</h1>
      </div>
    </div>
  );
}
