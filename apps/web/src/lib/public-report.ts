export type ReportPeriod = "DAILY" | "WEEKLY" | "MONTHLY";

export type ReportItem = {
  id: string;
  title: string;
  summary: string | null;
  sourceName: string;
  sourceOfficialLevel: "OFFICIAL" | "FIRST_PARTY" | "THIRD_PARTY";
  contentType: string;
  finalScore: number | null;
  publishedAt: string;
};

export type ReportSection = {
  code: string;
  label: string;
  items: ReportItem[];
};

export type PublicReportResponse = {
  report: {
    period: ReportPeriod;
    periodLabel: string;
    volume: string;
    startDate: string;
    endDate: string;
    headline: string;
    lead: string;
    storyCount: number;
    eventCount: number;
    sourceCount: number;
    officialSourceCount: number;
    featuredCount: number;
    estimatedMinutes: number;
    highlights: Array<{ code: string; label: string; displayedCount: number; leadTitle: string | null }>;
    sections: ReportSection[];
  };
  archive: Array<{ anchorDate: string; storyCount: number; leadTitle: string | null }>;
};

const coreApi = process.env.CORE_API_INTERNAL_URL ?? "http://127.0.0.1:8080";

export async function getPublicReport(period: ReportPeriod, anchor?: string): Promise<PublicReportResponse> {
  const params = new URLSearchParams({ period });
  if (anchor) params.set("anchor", anchor);
  const response = await fetch(`${coreApi}/api/v1/public/reports?${params}`, { cache: "no-store" });
  if (!response.ok) throw new Error(`公开报告服务暂时不可用（${response.status}）`);
  return response.json() as Promise<PublicReportResponse>;
}
