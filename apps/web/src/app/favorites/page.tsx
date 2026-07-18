import { PageHeader } from "@/components/page-header";
import { FavoritesView } from "@/components/favorites-view";
export default function FavoritesPage() {
  return <div className="page-shell favorites-page"><PageHeader title="收藏" description="保存稍后阅读的资讯、论文和研究线索。" /><div className="favorites-notice">收藏与登录账号同步；下架或失去访问权限的内容不会继续显示。</div><FavoritesView /></div>;
}
