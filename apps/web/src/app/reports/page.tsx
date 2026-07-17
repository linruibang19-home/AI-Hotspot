import { ReportView } from "@/components/report-view";
import { getPublicReport, type ReportPeriod } from "@/lib/public-report";

export const dynamic = "force-dynamic";

export default async function ReportsPage({ searchParams }: { searchParams: Promise<{ period?: string; anchor?: string }> }) {
  const params = await searchParams;
  const period = normalizePeriod(params.period);
  const data = await getPublicReport(period, params.anchor);
  return <ReportView data={data} />;
}

function normalizePeriod(value?: string): ReportPeriod {
  const normalized = value?.toUpperCase();
  return normalized === "WEEKLY" || normalized === "MONTHLY" ? normalized : "DAILY";
}
