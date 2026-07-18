import { PageHeader } from "@/components/page-header";
import { FavoritesView } from "@/components/favorites-view";
import { getPublicContents } from "@/lib/public-content";

export const dynamic = "force-dynamic";

export default async function FavoritesPage() {
  const page = await getPublicContents(false);
  return <div className="page-shell favorites-page"><PageHeader title="收藏" description="保存稍后阅读的资讯、论文和研究线索。" /><div className="favorites-notice">收藏保存在当前浏览器；登录后将支持跨设备同步。</div><FavoritesView items={page.items} /></div>;
}
