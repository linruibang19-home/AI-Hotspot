"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { PublicContentCard } from "@/components/public-content-card";
import { useAuth } from "@/components/auth-provider";
import { apiFetch } from "@/lib/api";
import type { PublicContent } from "@/lib/public-content";

export function FavoritesView() {
  const { user, loading: authLoading } = useAuth();
  const [items, setItems] = useState<PublicContent[]>([]);
  const [loading, setLoading] = useState(true);
  const load = useCallback(async () => {
    if (!user) { setItems([]); setLoading(false); return; }
    setLoading(true);
    try { setItems(await apiFetch<PublicContent[]>("/favorites")); } finally { setLoading(false); }
  }, [user]);
  useEffect(() => { const timer = window.setTimeout(() => void load(), 0); const sync = () => void load(); window.addEventListener("ai-hotspot-favorites", sync); return () => { window.clearTimeout(timer); window.removeEventListener("ai-hotspot-favorites", sync); }; }, [load]);

  if (authLoading || loading) return <section className="panel empty-state"><h2>正在读取收藏</h2><p>收藏已与当前账号同步。</p></section>;
  if (!user) return <section className="panel empty-state favorites-empty"><h2>登录后同步收藏</h2><p>注册或登录后，收藏会保存到账号并支持跨设备访问。</p><Link className="button primary" href="/login?returnTo=%2Ffavorites">前往登录</Link></section>;
  if (items.length === 0) return <section className="panel empty-state favorites-empty"><h2>还没有收藏内容</h2><p>在精选、全部动态或内容详情中点击书签即可保存。</p></section>;
  return <section className="favorites-list">{items.map((item) => <PublicContentCard item={item} showReason={item.featured} compact key={item.id} />)}</section>;
}
