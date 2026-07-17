"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { navigation } from "@/lib/navigation";
import { useAuth } from "@/components/auth-provider";

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === href;
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function Sidebar() {
  const pathname = usePathname();
  const { user, loading, logout } = useAuth();
  const isOperator = user?.roles.some((role) => role === "ADMIN" || role === "OPERATOR") ?? false;
  const isAdmin = user?.roles.includes("ADMIN") ?? false;

  const canSee = (access?: "authenticated" | "operator" | "admin") => {
    if (!access) return true;
    if (access === "authenticated") return Boolean(user);
    if (access === "operator") return isOperator;
    return isAdmin;
  };

  return (
    <aside className="sidebar" aria-label="主导航">
      <Link className="brand" href="/" aria-label="AI Hotspot 精选">
        <span>AI</span>
        <i className="brand-mark" aria-hidden="true" />
        <span className="brand-accent">HOTSPOT</span>
      </Link>
      <nav className="sidebar-nav">
        {navigation.map((group) => {
          const items = group.items.filter((item) => canSee(item.access));
          if (items.length === 0) return null;
          return (
          <section className="nav-group" key={group.label}>
            <div className="nav-label">{group.label}</div>
            {items.map((item) => (
              <Link
                className={`nav-link ${isActive(pathname, item.href) ? "active" : ""}`}
                href={item.href}
                key={item.href}
                aria-current={isActive(pathname, item.href) ? "page" : undefined}
                title={item.label}
              >
                <span className="nav-icon" aria-hidden="true">{item.icon}</span>
                <span className="nav-text">{item.label}</span>
              </Link>
            ))}
          </section>
        );})}
      </nav>
      <div className="sidebar-footer">
        {loading ? <span>正在检查会话…</span> : user ? (
          <>
            <strong>{user.displayName}</strong>
            <br /><span>{user.roles.join(" / ")}</span>
            <button className="sidebar-account" type="button" onClick={() => void logout()}>退出登录</button>
          </>
        ) : (
          <Link className="sidebar-account" href="/login">内部员工登录</Link>
        )}
      </div>
    </aside>
  );
}
