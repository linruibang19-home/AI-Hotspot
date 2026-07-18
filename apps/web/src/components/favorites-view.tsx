"use client";

import { useEffect, useMemo, useState } from "react";
import { PublicContentCard } from "@/components/public-content-card";
import { favoriteStorageKey } from "@/components/favorite-button";
import type { PublicContent } from "@/lib/public-content";

export function FavoritesView({ items }: { items: PublicContent[] }) {
  const [ids, setIds] = useState<string[]>([]);
  useEffect(() => {
    const sync = () => {
      try { setIds(JSON.parse(window.localStorage.getItem(favoriteStorageKey) ?? "[]")); }
      catch { setIds([]); }
    };
    sync();
    window.addEventListener("ai-hotspot-favorites", sync);
    return () => window.removeEventListener("ai-hotspot-favorites", sync);
  }, []);
  const favorites = useMemo(() => items.filter((item) => ids.includes(item.id)), [ids, items]);

  if (favorites.length === 0) return <section className="panel empty-state favorites-empty"><h2>还没有收藏内容</h2><p>在精选或全部动态中点击书签，内容会保存在当前浏览器。</p></section>;
  return <section className="favorites-list">{favorites.map((item) => <PublicContentCard item={item} showReason={item.featured} compact key={item.id} />)}</section>;
}
