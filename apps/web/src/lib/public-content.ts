export type PublicContent = {
  id: string;
  sourceName: string;
  sourceType: string;
  sourceOfficialLevel: "OFFICIAL" | "FIRST_PARTY" | "THIRD_PARTY";
  contentType: string;
  title: string;
  originalTitle: string;
  summary: string | null;
  recommendationReason: string | null;
  originalUrl: string | null;
  canonicalUrl: string | null;
  factStatus: "CONFIRMED" | "UNCONFIRMED" | "DEBUNKED";
  finalScore: number | null;
  featured: boolean;
  sourcePublishedAt: string | null;
  publishedAt: string;
};

export type PublicContentPage = {
  items: PublicContent[];
  nextCursor: string | null;
  hasMore: boolean;
};

const coreApi = process.env.CORE_API_INTERNAL_URL ?? "http://127.0.0.1:8080";
const INITIAL_DATE_GROUPS = 2;
const MAX_INITIAL_PAGES = 5;
const shanghaiDateKeyFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
});

export async function getPublicContents(featured = false): Promise<PublicContentPage> {
  const suffix = featured ? "/featured" : "";
  const limit = featured ? 12 : 50;
  const items: PublicContent[] = [];
  let cursor: string | null = null;
  let hasMore = true;

  for (let pageIndex = 0; pageIndex < MAX_INITIAL_PAGES && hasMore; pageIndex += 1) {
    const cursorQuery = cursor ? `&cursor=${encodeURIComponent(cursor)}` : "";
    const response = await fetch(`${coreApi}/api/v1/public/contents${suffix}?limit=${limit}${cursorQuery}`, { cache: "no-store" });
    if (!response.ok) throw new Error(`公开内容服务暂时不可用（${response.status}）`);
    const page = await response.json() as PublicContentPage;
    items.push(...page.items);
    cursor = page.nextCursor;
    hasMore = page.hasMore;
    if (countDateGroups(items) >= INITIAL_DATE_GROUPS) break;
  }

  return { items, nextCursor: cursor, hasMore };
}

function countDateGroups(items: PublicContent[]) {
  return new Set(items.map((item) => shanghaiDateKeyFormatter.format(new Date(item.publishedAt)))).size;
}

export async function searchPublicContents(query: string): Promise<PublicContent[]> {
  const response = await fetch(`${coreApi}/api/v1/public/search?query=${encodeURIComponent(query)}&limit=50`, {
    cache: "no-store",
  });
  if (!response.ok) throw new Error(`公开搜索服务暂时不可用（${response.status}）`);
  return response.json() as Promise<PublicContent[]>;
}

export async function getPublicContent(id: string): Promise<PublicContent | null> {
  const response = await fetch(`${coreApi}/api/v1/public/contents/${id}`, { cache: "no-store" });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`公开内容服务暂时不可用（${response.status}）`);
  return response.json() as Promise<PublicContent>;
}
