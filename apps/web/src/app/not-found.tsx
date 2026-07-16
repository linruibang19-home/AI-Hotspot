import Link from "next/link";

export default function NotFound() {
  return (
    <div className="page-shell">
      <div className="empty-state"><h2>页面不存在</h2><p>该路由尚未进入当前里程碑。</p><Link className="button primary" href="/">返回精选</Link></div>
    </div>
  );
}
