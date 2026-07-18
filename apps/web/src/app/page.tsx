import Link from "next/link";
import { PageHeader } from "@/components/page-header";
import { PublicFeed } from "@/components/public-feed";
import { Icon } from "@/components/icons";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function FeaturedPage() {
  const page = await getPublicContents(true);
  const today = new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long", timeZone: "Asia/Shanghai" }).format(new Date());
  return (
    <div className="page-shell">
      <PageHeader
        title="精选"
        description={`${today} · AI 自动挑选的高价值公开内容`}
        action={<div className="header-actions"><Link className="button" href="/reports?period=DAILY"><Icon name="download" />导出日报</Link><Link className="button primary" href="/research">进入知识库</Link></div>}
      />
      <PublicFeed items={page.items} featured />
    </div>
  );
}
