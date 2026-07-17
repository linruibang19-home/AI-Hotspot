import { PageHeader } from "@/components/page-header";
import { PublicFeed } from "@/components/public-feed";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function FeaturedPage() {
  const page = await getPublicContents(true);
  return (
    <div className="page-shell">
      <PageHeader title="精选" description="AI 自动挑选的高价值公开内容" />
      <PublicFeed items={page.items} featured />
    </div>
  );
}
