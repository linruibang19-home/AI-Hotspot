"use client";

import { useEffect, useState } from "react";
import { Icon } from "@/components/icons";

const storageKey = "ai-hotspot-favorites-v1";

function readFavorites() {
  try {
    return new Set<string>(JSON.parse(window.localStorage.getItem(storageKey) ?? "[]"));
  } catch {
    return new Set<string>();
  }
}

export function FavoriteButton({ id }: { id: string }) {
  const [active, setActive] = useState(false);

  useEffect(() => {
    const sync = () => setActive(readFavorites().has(id));
    sync();
    window.addEventListener("ai-hotspot-favorites", sync);
    return () => window.removeEventListener("ai-hotspot-favorites", sync);
  }, [id]);

  function toggle() {
    const favorites = readFavorites();
    if (favorites.has(id)) favorites.delete(id);
    else favorites.add(id);
    window.localStorage.setItem(storageKey, JSON.stringify([...favorites]));
    setActive(favorites.has(id));
    window.dispatchEvent(new Event("ai-hotspot-favorites"));
  }

  return (
    <button className={`bookmark-button ${active ? "active" : ""}`} type="button" onClick={toggle} aria-label={active ? "取消收藏" : "收藏内容"} aria-pressed={active}>
      <Icon name="bookmark" />
    </button>
  );
}

export { storageKey as favoriteStorageKey };
