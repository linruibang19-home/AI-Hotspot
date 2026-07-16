"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { navigation } from "@/lib/navigation";

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === href;
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="sidebar" aria-label="主导航">
      <Link className="brand" href="/" aria-label="AI Hotspot 精选">
        <span>AI</span>
        <i className="brand-mark" aria-hidden="true" />
        <span className="brand-accent">HOTSPOT</span>
      </Link>
      <nav className="sidebar-nav">
        {navigation.map((group) => (
          <section className="nav-group" key={group.label}>
            <div className="nav-label">{group.label}</div>
            {group.items.map((item) => (
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
        ))}
      </nav>
      <div className="sidebar-footer">
        <strong>M1 工程模式</strong>
        <br />Mock Provider · 本地 Compose
      </div>
    </aside>
  );
}
