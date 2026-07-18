import { PageHeader } from "@/components/page-header";
import { PublicFeed } from "@/components/public-feed";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function AllPage({ searchParams }: { searchParams: Promise<{ query?: string }> }) {
  const { query = "" } = await searchParams;
  const page = await getPublicContents(false);
  const today = new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long", timeZone: "Asia/Shanghai" }).format(new Date());
  return (
    <div className="page-shell">
      <PageHeader title="全部 AI 动态" description={`${today} · AI 相关资讯全量信息流`} />
      <PublicFeed items={page.items} initialQuery={query} />
    </div>
  );
}
