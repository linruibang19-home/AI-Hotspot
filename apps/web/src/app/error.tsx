"use client";

export default function GlobalError({ reset }: { reset: () => void }) {
  return (
    <section className="empty-state" role="alert">
      <strong>页面暂时无法加载</strong>
      <p>请稍后重试；若问题持续存在，请携带页面地址提交反馈。</p>
      <button className="button primary" type="button" onClick={reset}>
        重新加载
      </button>
    </section>
  );
}
