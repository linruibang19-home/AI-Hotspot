import { PageHeader } from "@/components/page-header";

export default function FeedbackPage() {
  return (
    <main className="page-shell editorial-page">
      <PageHeader title="反馈" description="提交内容纠错、下架请求、产品建议或信源问题。" />
      <section className="feedback-layout">
        <form className="product-panel feedback-form">
          <label>反馈类型<select name="type" defaultValue="correction"><option value="correction">内容纠错</option><option value="takedown">下架请求</option><option value="source">信源问题</option><option value="product">产品建议</option></select></label>
          <label>相关内容链接<input name="url" type="url" placeholder="https://..." /></label>
          <label>具体说明<textarea name="detail" rows={8} placeholder="请描述问题，并尽量附上可核验的来源。" /></label>
          <label>联系邮箱（可选）<input name="email" type="email" placeholder="name@example.com" /></label>
          <button className="button primary" type="button" title="工单 API 将在后续阶段接入">提交反馈</button>
        </form>
        <aside className="product-panel feedback-note"><span className="overline">PROCESS</span><h2>我们如何处理</h2><ol><li>创建反馈工单并保留提交记录</li><li>核验来源、版权与内容状态</li><li>必要时先下架，再完成复核</li><li>记录恢复、修订或永久下架结果</li></ol><p>当前页面已完成产品交互外观；工单提交 API 将在内容治理后续阶段接入。</p></aside>
      </section>
    </main>
  );
}
