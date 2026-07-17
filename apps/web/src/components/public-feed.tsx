"use client";

import { useMemo, useState } from "react";
import { PublicContentCard } from "@/components/public-content-card";
import type { PublicContent } from "@/lib/public-content";

type Filter = "ALL" | "OFFICIAL" | "RESEARCH" | "ARTICLE";

const filters: Array<[Filter, string]> = [
  ["ALL", "全部"],
  ["OFFICIAL", "官方/一手"],
  ["RESEARCH", "研究"],
  ["ARTICLE", "资讯"],
];

export function PublicFeed({ items, featured = false }: { items: PublicContent[]; featured?: boolean }) {
  const [filter, setFilter] = useState<Filter>("ALL");
  const [query, setQuery] = useState("");
  const visible = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase("zh-CN");
    return items.filter((item) => {
      const filterMatches =
        filter === "ALL" ||
        (filter === "OFFICIAL" && item.sourceOfficialLevel !== "THIRD_PARTY") ||
        (filter === "RESEARCH" && (item.sourceType === "RESEARCH" || item.contentType === "RESEARCH")) ||
        (filter === "ARTICLE" && item.contentType === "ARTICLE");
      const queryMatches = !normalized || `${item.title} ${item.summary ?? ""} ${item.sourceName}`.toLocaleLowerCase("zh-CN").includes(normalized);
      return filterMatches && queryMatches;
    });
  }, [filter, items, query]);
  const groups = useMemo(() => groupByDate(visible), [visible]);

  return (
    <>
      <div className="toolbar">
        <div className="tabs" aria-label="内容筛选">
          {filters.map(([value, label]) => (
            <button
              className={`tab tab-button ${filter === value ? "active" : ""}`}
              key={value}
              type="button"
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >{label}</button>
          ))}
        </div>
        <label className="search-shell">
          <input
            aria-label="搜索公开内容"
            placeholder="搜索标题 / 摘要 / 来源..."
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <span aria-hidden="true">⌕</span>
        </label>
      </div>
      {featured && visible.length > 0 ? (
        <section className="panel hot-panel" aria-labelledby="hot-title">
          <div className="panel-title" id="hot-title">当前热点 <small>按基础准入评分与时效排序</small></div>
          {visible.slice(0, 3).map((item, index) => (
            <div className="hot-row" key={item.id}>
              <span className="hot-rank">{index + 1}</span>
              <strong>{item.title}</strong>
              <small>{item.sourceName}</small>
            </div>
          ))}
        </section>
      ) : null}
      {groups.length === 0 ? (
        <section className="panel empty-state"><h2>暂无匹配内容</h2><p>调整筛选或等待下一次 RSS/Atom 采集。</p></section>
      ) : groups.map(([date, dateItems]) => (
        <section key={date} aria-label={`${date}公开内容`}>
          <div className="date-heading">{date} <small>{dateItems.length} 条已准入公开内容</small></div>
          <div className="timeline">
            {dateItems.map((item) => (
              <div className="timeline-row" key={item.id}>
                <time className="timeline-time" dateTime={item.publishedAt}>{formatTime(item.publishedAt)}</time>
                <PublicContentCard item={item} showReason={featured} />
              </div>
            ))}
          </div>
        </section>
      ))}
    </>
  );
}

function groupByDate(items: PublicContent[]): Array<[string, PublicContent[]]> {
  const groups = new Map<string, PublicContent[]>();
  for (const item of items) {
    const date = new Intl.DateTimeFormat("zh-CN", {
      timeZone: "Asia/Shanghai",
      month: "long",
      day: "numeric",
    }).format(new Date(item.publishedAt));
    groups.set(date, [...(groups.get(date) ?? []), item]);
  }
  return [...groups.entries()];
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
}
