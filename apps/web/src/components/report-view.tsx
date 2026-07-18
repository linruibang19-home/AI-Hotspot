import type { PublicReportResponse, ReportPeriod } from "@/lib/public-report";

const periods: Array<[ReportPeriod, string]> = [["DAILY", "日报"], ["WEEKLY", "周报"], ["MONTHLY", "月报"]];
const REPORT_SECTION_LIMIT = 6;

export function ReportView({ data }: { data: PublicReportResponse }) {
  const { report, archive } = data;
  const dateLabel = report.startDate === report.endDate
    ? formatDate(report.startDate)
    : `${formatDate(report.startDate)} ～ ${formatDate(report.endDate)}`;
  return (
    <div className="report-layout">
      <aside className="report-archive" aria-label="报告归档">
        <nav className="report-period-tabs" aria-label="报告周期">
          {periods.map(([period, label]) => (
            <a
              className={report.period === period ? "active" : ""}
              href={`/reports?period=${period}`}
              key={period}
              aria-current={report.period === period ? "page" : undefined}
            >{label}</a>
          ))}
        </nav>
        <div className="report-archive-list">
          <h2>{archiveTitle(report.period, report.startDate)} <span>{archive.length}</span></h2>
          {archive.map((item) => (
            <a
              className={item.anchorDate === report.startDate ? "active" : ""}
              href={`/reports?period=${report.period}&anchor=${item.anchorDate}`}
              key={item.anchorDate}
            >
              <strong>{archiveLabel(report.period, item.anchorDate)}</strong>
              <span>{item.leadTitle ?? "本期公开内容"}</span>
              <small>{item.storyCount} 条</small>
            </a>
          ))}
        </div>
      </aside>

      <main className="report-paper">
        <header className="report-masthead">
          <span>{report.volume} · {report.storyCount} STORIES · AI HOTSPOT {report.period}</span>
          <h1>AI HOTSPOT {report.periodLabel}</h1>
          <p>{dateLabel} · {report.period} · 编辑系统自动综合</p>
        </header>

        {report.period === "DAILY" ? <DailyLead data={data} /> : <LongPeriodLead data={data} />}

        {report.sections.length === 0 ? (
          <section className="panel empty-state"><h2>{report.headline}</h2><p>{report.lead}</p></section>
        ) : report.sections.map((section, sectionIndex) => (
          <section className="report-section" id={`report-${section.code}`} key={section.code}>
            <header>
              <span>{String(sectionIndex + 1).padStart(2, "0")}</span>
              <h2>{section.label}</h2>
              <small>{Math.min(section.items.length, REPORT_SECTION_LIMIT)} 篇展示</small>
            </header>
            {section.items.slice(0, REPORT_SECTION_LIMIT).map((item) => (
              <article className="report-story" key={item.id}>
                <a href={`/content/${item.id}`}><h3>{item.title}</h3></a>
                <div className="report-story-meta">
                  <b>{item.sourceOfficialLevel === "THIRD_PARTY" ? "公开来源" : "官方/一手"}</b>
                  <span>{item.sourceName}</span>
                  {item.finalScore === null ? null : <span>评分 {item.finalScore}</span>}
                </div>
                {item.summary ? <p>{truncateSummary(item.summary)}</p> : null}
              </article>
            ))}
          </section>
        ))}
      </main>
    </div>
  );
}

function DailyLead({ data }: { data: PublicReportResponse }) {
  const { report } = data;
  return (
    <section className="report-highlights panel">
      <div className="panel-title">今日看点 <small>{report.storyCount} 篇报道 · 约 {report.estimatedMinutes} 分钟</small></div>
      {report.highlights.map((item, index) => (
        <div className="report-highlight-row" key={item.code}>
          <span>{String(index + 1).padStart(2, "0")}</span>
          <div><strong>{item.label}</strong><p>{item.leadTitle ?? "暂无条目"}</p></div>
          <small>{item.displayedCount}</small>
        </div>
      ))}
    </section>
  );
}

function LongPeriodLead({ data }: { data: PublicReportResponse }) {
  const { report } = data;
  return (
    <>
      <section className="report-lead-card panel">
        <span>本期主线</span>
        <h2>{report.headline}</h2>
        <p>{report.lead}</p>
      </section>
      <section className="report-metrics" aria-label="报告统计">
        <div><strong>{report.eventCount}</strong><span>独立事件</span></div>
        <div><strong>{report.sourceCount}</strong><span>独立信源</span></div>
        <div><strong>{report.officialSourceCount}</strong><span>官方/一手信源</span></div>
        <div><strong>≈{report.estimatedMinutes} min</strong><span>读完本页</span></div>
      </section>
      <section className="report-highlights compact panel">
        <div className="panel-title">本期看点 <small>{report.highlights.length} 个主题</small></div>
        {report.highlights.map((item, index) => (
          <div className="report-highlight-row" key={item.code}>
            <span>{String(index + 1).padStart(2, "0")}</span>
            <div><strong>{item.label}</strong><p>{item.leadTitle ?? "暂无条目"}</p></div>
            <small>{item.displayedCount}</small>
          </div>
        ))}
      </section>
    </>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric", timeZone: "Asia/Shanghai" })
    .format(new Date(`${value}T00:00:00+08:00`));
}

function archiveTitle(period: ReportPeriod, value: string) {
  const { year, month } = dateParts(value);
  if (period === "MONTHLY") return `${year} 年`;
  return `${year} 年 ${month} 月`;
}

function archiveLabel(period: ReportPeriod, value: string) {
  const { month, day } = dateParts(value);
  if (period === "DAILY") return `${day} 日`;
  if (period === "WEEKLY") return `${month}月${day}日起`;
  return `${month} 月`;
}

function dateParts(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  return { year, month, day };
}

function truncateSummary(value: string) {
  const normalized = value.replace(/\s+/g, " ").trim();
  return normalized.length > 420 ? `${normalized.slice(0, 420)}…` : normalized;
}
