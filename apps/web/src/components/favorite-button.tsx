"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Icon } from "@/components/icons";
import { useAuth } from "@/components/auth-provider";
import { apiFetch } from "@/lib/api";

export function FavoriteButton({ id }: { id: string }) {
  const router = useRouter();
  const { user, loading } = useAuth();
  const [active, setActive] = useState(false);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    void apiFetch<{ favorite: boolean }>(`/favorites/CONTENT/${id}`)
      .then((state) => { if (!cancelled) setActive(state.favorite); })
      .catch(() => { if (!cancelled) setActive(false); });
    return () => { cancelled = true; };
  }, [id, user]);

  async function toggle() {
    if (loading) return;
    if (!user) { router.push(`/login?returnTo=${encodeURIComponent(location.pathname)}`); return; }
    if (pending) return;
    const next = !active;
    setPending(true); setActive(next);
    try {
      if (next) await apiFetch<void>("/favorites", { method: "POST", body: JSON.stringify({ targetType: "CONTENT", targetId: id }) });
      else await apiFetch<void>(`/favorites/CONTENT/${id}`, { method: "DELETE" });
      window.dispatchEvent(new Event("ai-hotspot-favorites"));
    } catch { setActive(!next); }
    finally { setPending(false); }
  }

  const visibleActive = Boolean(user && active);
  return <button className={`bookmark-button ${visibleActive ? "active" : ""}`} disabled={pending} type="button" onClick={() => void toggle()} aria-label={visibleActive ? "取消收藏" : "收藏内容"} aria-pressed={visibleActive}><Icon name="bookmark" /></button>;
}
