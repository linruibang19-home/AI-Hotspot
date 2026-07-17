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

type PublicContentPage = {
  items: PublicContent[];
  nextCursor: string | null;
  hasMore: boolean;
};

const coreApi = process.env.CORE_API_INTERNAL_URL ?? "http://127.0.0.1:8080";

export async function getPublicContents(featured = false): Promise<PublicContentPage> {
  const suffix = featured ? "/featured" : "";
  const response = await fetch(`${coreApi}/api/v1/public/contents${suffix}?limit=50`, {
    cache: "no-store",
  });
  if (!response.ok) throw new Error(`公开内容服务暂时不可用（${response.status}）`);
  return response.json() as Promise<PublicContentPage>;
}

export async function getPublicContent(id: string): Promise<PublicContent | null> {
  const response = await fetch(`${coreApi}/api/v1/public/contents/${id}`, { cache: "no-store" });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`公开内容服务暂时不可用（${response.status}）`);
  return response.json() as Promise<PublicContent>;
}

