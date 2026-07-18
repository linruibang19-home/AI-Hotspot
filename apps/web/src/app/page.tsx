import { PageHeader } from "@/components/page-header";
import { PublicFeed } from "@/components/public-feed";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function FeaturedPage() {
  const page = await getPublicContents(true);
  const today = new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long", timeZone: "Asia/Shanghai" }).format(new Date());
  return (
    <div className="page-shell">
      <PageHeader title="精选" description={`${today} · AI 自动挑选的高价值内容`} />
      <PublicFeed items={page.items} featured />
    </div>
  );
}
