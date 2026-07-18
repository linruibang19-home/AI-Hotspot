"use client";

import { FormEvent, useDeferredValue, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { PublicContentCard } from "@/components/public-content-card";
import { Icon } from "@/components/icons";
import type { PublicContent, PublicContentPage } from "@/lib/public-content";
import type { PublicEvent } from "@/lib/public-discovery";

type SourceFilter = "ALL" | "OFFICIAL" | "MEDIA" | "RESEARCH" | "COMMUNITY";

const featuredFilters: Array<[SourceFilter, string]> = [
  ["ALL", "全部"], ["OFFICIAL", "官方"], ["RESEARCH", "论文"], ["COMMUNITY", "社区"], ["MEDIA", "媒体"],
];

const sourceFilters: Array<[SourceFilter, string]> = [
  ["ALL", "全部"], ["OFFICIAL", "一手信源"], ["MEDIA", "资讯"], ["RESEARCH", "论文"], ["COMMUNITY", "社区"],
];

export function PublicFeed({
  items,
  nextCursor: initialNextCursor = null,
  hasMore: initialHasMore = false,
  featured = false,
  initialQuery = "",
  hotEvents = [],
}: {
  items: PublicContent[];
  nextCursor?: string | null;
  hasMore?: boolean;
  featured?: boolean;
  initialQuery?: string;
  hotEvents?: PublicEvent[];
}) {
  const router = useRouter();
  const [loadedItems, setLoadedItems] = useState(items);
  const [nextCursor, setNextCursor] = useState(initialNextCursor);
  const [hasMore, setHasMore] = useState(initialHasMore);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [featuredFilter, setFeaturedFilter] = useState<SourceFilter>("ALL");
  const [sourceFilter, setSourceFilter] = useState<SourceFilter>("ALL");
  const [contentType, setContentType] = useState("ALL");
  const [query, setQuery] = useState(initialQuery);
  const deferredQuery = useDeferredValue(query.trim().toLocaleLowerCase("zh-CN"));

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalized = query.trim();
    router.push(normalized ? `/all?query=${encodeURIComponent(normalized)}` : "/all");
  }

  const visible = useMemo(() => loadedItems.filter((item) => {
    const haystack = `${item.title} ${item.originalTitle} ${item.summary ?? ""} ${item.sourceName}`.toLocaleLowerCase("zh-CN");
    if (deferredQuery && !haystack.includes(deferredQuery)) return false;
    if (featured) return matchesSource(item, featuredFilter);
    if (!matchesSource(item, sourceFilter)) return false;
    return contentType === "ALL" || item.contentType === contentType;
  }), [contentType, deferredQuery, featured, featuredFilter, loadedItems, sourceFilter]);

  const groups = useMemo(() => groupByDate(visible), [visible]);
  const types = useMemo(() => [...new Set(loadedItems.map((item) => item.contentType))], [loadedItems]);

  async function loadMore() {
    if (!hasMore || !nextCursor || loadingMore) return;
    setLoadingMore(true);
    setLoadError("");
    try {
      const suffix = featured ? "/featured" : "";
      const response = await fetch(`/api/core/api/v1/public/contents${suffix}?limit=20&cursor=${encodeURIComponent(nextCursor)}`, { cache: "no-store" });
      if (!response.ok) throw new Error(`加载失败（${response.status}）`);
      const page = await response.json() as PublicContentPage;
      setLoadedItems((current) => {
        const existingIds = new Set(current.map((item) => item.id));
        return [...current, ...page.items.filter((item) => !existingIds.has(item.id))];
      });
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (reason) {
      setLoadError(reason instanceof Error ? reason.message : "加载更多失败");
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <>
      {featured ? (
        <div className="toolbar featured-toolbar">
          <FilterTabs items={featuredFilters} value={featuredFilter} onChange={setFeaturedFilter} label="精选分类" />
          <SearchBox value={query} onChange={setQuery} onSubmit={submitSearch} placeholder="搜索标题 / 摘要 / 正文..." />
        </div>
      ) : (
        <div className="all-filterbar">
          <span className="filter-caption">来源</span>
          <FilterTabs items={sourceFilters} value={sourceFilter} onChange={setSourceFilter} label="来源筛选" />
          <label className="type-filter"><span>类型</span><select value={contentType} onChange={(event) => setContentType(event.target.value)} aria-label="内容类型">
            <option value="ALL">全部</option>
            {types.map((type) => <option value={type} key={type}>{contentTypeLabel(type)}</option>)}
          </select></label>
          <SearchBox value={query} onChange={setQuery} onSubmit={submitSearch} placeholder="搜索标题/摘要/正文..." withButton />
        </div>
      )}

      {featured && hotEvents.length > 0 ? <HotList events={hotEvents} /> : null}

      {groups.length === 0 ? (
        <section className="panel empty-state"><h2>暂无匹配内容</h2><p>清除筛选条件，或等待下一轮公开信源更新。</p><button className="button" type="button" onClick={() => { setQuery(""); setFeaturedFilter("ALL"); setSourceFilter("ALL"); setContentType("ALL"); }}>清除筛选</button></section>
      ) : groups.map(([date, dateItems]) => (
        <section className="date-section" key={date.key} aria-label={`${date.label}公开内容`}>
          <div className="date-heading"><strong>{date.label}</strong><span>⌄</span><small>{date.weekday} · {dateItems.length} 条</small></div>
          <div className="timeline">
            {dateItems.map((item) => (
              <div className="timeline-row" key={item.id}>
                <time className="timeline-time" dateTime={item.publishedAt}>{formatTime(item.publishedAt)}</time>
                <PublicContentCard item={item} showReason={featured} compact={!featured} />
              </div>
            ))}
          </div>
        </section>
      ))}
      {loadError ? <div className="load-more-error" role="alert">{loadError}</div> : null}
      {hasMore ? <div className="load-more"><button className="button" type="button" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? "正在加载…" : "加载更多内容"}</button></div> : null}
    </>
  );
}

function FilterTabs<T extends string>({ items, value, onChange, label }: { items: Array<[T, string]>; value: T; onChange: (value: T) => void; label: string }) {
  return <div className="tabs" aria-label={label}>{items.map(([key, text]) => <button className={`tab tab-button ${value === key ? "active" : ""}`} type="button" aria-pressed={value === key} onClick={() => onChange(key)} key={key}>{text}</button>)}</div>;
}

function SearchBox({ value, onChange, onSubmit, placeholder, withButton = false }: { value: string; onChange: (value: string) => void; onSubmit: (event: FormEvent<HTMLFormElement>) => void; placeholder: string; withButton?: boolean }) {
  return <form className={`search-shell ${withButton ? "with-button" : ""}`} onSubmit={onSubmit} role="search"><input aria-label="搜索公开内容" placeholder={placeholder} value={value} onChange={(event) => onChange(event.target.value)} />{withButton ? <button type="submit">搜索</button> : <button className="search-icon-button" type="submit" aria-label="提交搜索"><Icon name="search" /></button>}</form>;
}

function HotList({ events }: { events: PublicEvent[] }) {
  return <section className="panel hot-panel" aria-labelledby="hot-title"><div className="panel-title" id="hot-title"><span>当前热点</span><small>基于独立信源数量与时效计算</small></div>{events.map((event, index) => <Link className="hot-row" href={`/events/${event.id}`} key={event.id}><span className="hot-rank">{index + 1}</span><strong>{event.title}</strong><small>{event.sourceCount} 个独立信源</small></Link>)}</section>;
}

function matchesSource(item: PublicContent, filter: SourceFilter) {
  if (filter === "ALL") return true;
  if (filter === "OFFICIAL") return item.sourceOfficialLevel !== "THIRD_PARTY";
  if (filter === "MEDIA") return item.sourceType === "MEDIA";
  if (filter === "RESEARCH") return item.sourceType === "RESEARCH" || item.contentType === "RESEARCH";
  return item.sourceType === "COMMUNITY" || item.sourceType === "GITHUB";
}

function groupByDate(items: PublicContent[]): Array<[{ key: string; label: string; weekday: string }, PublicContent[]]> {
  const groups = new Map<string, PublicContent[]>();
  for (const item of items) {
    const key = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(item.publishedAt));
    groups.set(key, [...(groups.get(key) ?? []), item]);
  }
  return [...groups.entries()].map(([key, dateItems]) => {
    const date = new Date(dateItems[0].publishedAt);
    return [{ key, label: new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", month: "long", day: "numeric" }).format(date), weekday: new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", weekday: "long" }).format(date) }, dateItems];
  });
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

function contentTypeLabel(value: string) {
  return ({ ARTICLE: "资讯", RESEARCH: "论文/研究", RELEASE: "模型发布", PRODUCT: "产品更新", TUTORIAL: "教程实践", OPINION: "观点" } as Record<string, string>)[value] ?? value;
}
