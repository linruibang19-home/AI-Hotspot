"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { navigation } from "@/lib/navigation";
import { useAuth } from "@/components/auth-provider";
import { Icon } from "@/components/icons";

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === href;
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function Sidebar() {
  const pathname = usePathname();
  const { user, loading, logout } = useAuth();
  const isOperator = user?.roles.some((role) => role === "ADMIN" || role === "OPERATOR") ?? false;
  const isAdmin = user?.roles.includes("ADMIN") ?? false;
  const [theme, setTheme] = useState<"light" | "dark">("light");

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  function toggleTheme() {
    const next = theme === "light" ? "dark" : "light";
    setTheme(next);
  }

  const canSee = (access?: "authenticated" | "operator" | "admin") => {
    if (!access) return true;
    if (access === "authenticated") return Boolean(user);
    if (access === "operator") return isOperator;
    return isAdmin;
  };

  return (
    <aside className="sidebar" aria-label="主导航">
      <Link className="brand" href="/" aria-label="AI Hotspot 精选">
        <span className="brand-ai">AI</span>
        <i className="brand-mark" aria-hidden="true"><i /></i>
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
                <span className="nav-icon" aria-hidden="true"><Icon name={item.icon} /></span>
                <span className="nav-text">{item.label}</span>
              </Link>
            ))}
          </section>
        );})}
      </nav>
      <div className="sidebar-footer">
        <button className="theme-toggle" type="button" onClick={toggleTheme} aria-label={theme === "light" ? "切换深色模式" : "切换浅色模式"}>
          <Icon name="moon" />
          <Icon name="desktop" />
          <span className={theme === "light" ? "active" : ""}><Icon name="sun" /></span>
        </button>
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
