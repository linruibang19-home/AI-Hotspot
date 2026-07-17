import { PageHeader } from "@/components/page-header";
import { PublicFeed } from "@/components/public-feed";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function AllPage() {
  const page = await getPublicContents(false);
  return (
    <div className="page-shell">
      <PageHeader title="全部 AI 动态" description="通过准入规则、已发布且公开的 RSS/Atom 内容流。" />
      <PublicFeed items={page.items} />
    </div>
  );
}
