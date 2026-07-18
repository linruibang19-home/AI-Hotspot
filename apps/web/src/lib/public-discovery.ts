import type { PublicContent } from "@/lib/public-content";

export type PublicTopic = { id: string; slug: string; groupCode: "COMPANY_MODEL" | "TECHNOLOGY" | "CONTENT_FORM"; name: string; description: string; queryText: string; contentCount: number; featuredCount: number; updatedAt: string };
export type PublicTopicDetail = { topic: PublicTopic; items: PublicContent[] };
export type PublicEvent = { id: string; title: string; summary: string | null; categoryCode: string | null; factStatus: "CONFIRMED" | "UNCONFIRMED"; contentCount: number; sourceCount: number; heatScore: number; firstSeenAt: string; lastSeenAt: string };
export type PublicEventDetail = { event: PublicEvent; items: PublicContent[] };

const coreApi = process.env.CORE_API_INTERNAL_URL ?? "http://127.0.0.1:8080";
async function get<T>(path: string): Promise<T> {
  const response = await fetch(`${coreApi}/api/v1/public${path}`, { cache: "no-store" });
  if (!response.ok) throw new Error(`公开发现服务暂时不可用（${response.status}）`);
  return response.json() as Promise<T>;
}
export const getPublicTopics = () => get<PublicTopic[]>("/topics");
export const getPublicTopic = (slug: string) => get<PublicTopicDetail>(`/topics/${encodeURIComponent(slug)}?limit=50`);
export const getPublicEvents = (limit = 10) => get<PublicEvent[]>(`/events?limit=${limit}`);
export const getPublicEvent = (id: string) => get<PublicEventDetail>(`/events/${encodeURIComponent(id)}`);
