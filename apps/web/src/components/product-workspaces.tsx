"use client";

import { useState } from "react";
import Link from "next/link";
import { Icon } from "@/components/icons";
import { PageHeader } from "@/components/page-header";
import { useAuth } from "@/components/auth-provider";

const agentTemplates = [
  { name: "研究 Agent", code: "R", description: "搜索、比较、构建时间线并生成带引用的专题报告。" },
  { name: "订阅 Agent", code: "S", description: "把自然语言需求转成结构化订阅，并按计划推送。" },
  { name: "信源运营 Agent", code: "S", description: "检测信源类型、试抓取、查重并推荐配置。" },
  { name: "采集运维 Agent", code: "O", description: "分析采集失败、结构变化与任务积压，给出修复建议。" },
];

const recentAgentRuns = [
  { task: "生成 Agent 框架近 30 天对比报告", agent: "研究 Agent", status: "已完成", duration: "1m 48s", tokens: "12.4k", tone: "success" },
  { task: "每天 09:00 推送 AI 编码精选", agent: "订阅 Agent", status: "等待确认", duration: "18s", tokens: "1.2k", tone: "warning" },
  { task: "试抓取 OpenAI News", agent: "信源运营", status: "已完成", duration: "9s", tokens: "0.8k", tone: "success" },
  { task: "诊断机器之心列表解析异常", agent: "采集运维", status: "运行中", duration: "42s", tokens: "2.6k", tone: "success" },
];

export function SubscriptionWorkspace() {
  const [frequency, setFrequency] = useState("每日");
  const [activeSubscription, setActiveSubscription] = useState("daily");
  const [notice, setNotice] = useState("");
  return <ProtectedWorkspace title="订阅与邮件"><div className="page-shell"><PageHeader title="订阅与邮件" description="每日与每周简报，按主题、来源和时间送达。" action={<button className="button primary" onClick={() => setNotice("已打开新建订阅流程（前端原型演示）")} type="button">新建订阅</button>} />
    <div className="subscription-layout"><section className="product-panel subscription-list"><header><h2>我的订阅</h2><span>2 个启用</span></header><button className={`subscription-item ${activeSubscription === "daily" ? "active" : ""}`} onClick={() => setActiveSubscription("daily")} type="button"><strong>每日 AI 精选</strong><span>每天 09:00 · Asia/Shanghai</span><small>全部主题 · 最多 12 条</small></button><button className={`subscription-item ${activeSubscription === "weekly" ? "active" : ""}`} onClick={() => setActiveSubscription("weekly")} type="button"><strong>Agent 与 AI 编码周报</strong><span>每周一 09:00</span><small>2 个主题 · 最多 20 条</small></button></section>
      <section className="product-panel subscription-editor"><header><div><span className="overline">SUBSCRIPTION</span><h2>{activeSubscription === "daily" ? "每日 AI 精选" : "Agent 与 AI 编码周报"}</h2></div><span className="status-badge active">已启用</span></header><div className="form-grid"><label>频率<select value={frequency} onChange={(event) => setFrequency(event.target.value)}><option>每日</option><option>每周</option></select></label><label>发送时间<input value="09:00" readOnly /></label><label className="span-2">订阅主题<input value="全部主题" readOnly /></label></div><div className="email-preview"><span>AI HOTSPOT · {frequency.toUpperCase()}</span><h3>今天值得关注的 AI 进展</h3><p>从公开信源中筛选高价值动态，并保留原文和来源追踪。</p><div><b>01</b><strong>模型发布与更新</strong><small>3 篇</small></div><div><b>02</b><strong>Agent 与工具调用</strong><small>5 篇</small></div></div><footer><button className="button" onClick={() => setNotice("暂停订阅将在邮件业务接入后生效")} type="button">暂停订阅</button><button className="button primary" onClick={() => setNotice("订阅设置已在前端原型中保存")} type="button">保存设置</button></footer></section></div>
    {notice ? <WorkspaceNotice notice={notice} onClose={() => setNotice("")} /> : null}
  </div></ProtectedWorkspace>;
}

export function ResearchWorkspace() {
  const [question, setQuestion] = useState("过去 7 天 Agent 产品有哪些值得关注的变化？");
  const [notice, setNotice] = useState("");
  return <ProtectedWorkspace title="知识库问答"><div className="page-shell research-page"><PageHeader title="知识库问答" description="先过滤权限，再检索、重排并生成带引用的答案。" action={<button className="button" onClick={() => { setQuestion(""); setNotice("已创建新的研究会话（前端原型演示）"); }} type="button">新建研究</button>} />
    <div className="research-layout"><aside className="product-panel research-scope"><h2>检索范围</h2><label>时间范围<select><option>最近 7 天</option><option>最近 30 天</option></select></label><label>主题<select><option>Agent 智能体</option><option>全部主题</option></select></label><label>来源等级<select><option>官方与公开来源</option></select></label><div className="scope-note"><Icon name="research" /><span>检索前执行权限过滤，私有内容不会越权进入上下文。</span></div></aside>
      <main className="product-panel research-chat"><header><span className="status-badge active">RAG READY</span><small>8 个证据片段</small></header><div className="question-bubble">{question || "请输入研究问题"}</div><div className="answer-block"><h2>Agent 正从演示能力进入可交付工作流</h2><p>近期变化集中在三个方向：更长时间的自主执行、面向企业流程的工具接入，以及对执行轨迹和权限边界的强化。多个官方更新都把“可靠完成任务”放在单次生成效果之前。</p><p>同时，Coding Agent 已成为最活跃的落地场景，产品开始提供审批、回滚、沙箱和可观测性能力。</p><div className="citation-row">{["Anthropic", "GitHub", "OpenAI"].map((source, index) => <button key={source} onClick={() => setNotice(`已定位引用 [${index + 1}] ${source}`)} type="button">[{index + 1}] {source}</button>)}</div></div><label className="composer"><textarea value={question} onChange={(event) => setQuestion(event.target.value)} /><button type="button" aria-label="发送问题" onClick={() => setNotice(question.trim() ? "问题已提交到前端研究流程，RAG 后端将在 M8 接入" : "请先输入研究问题")}><Icon name="arrow" /></button></label></main>
      <aside className="product-panel evidence-panel"><header><h2>引用证据</h2><span>8</span></header>{["Anthropic Newsroom", "GitHub Changelog", "OpenAI News"].map((source, index) => <article key={source}><span>{String(index + 1).padStart(2, "0")}</span><h3>{source}</h3><p>公开内容片段 · 已通过权限与来源策略校验</p></article>)}</aside></div>
    {notice ? <WorkspaceNotice notice={notice} onClose={() => setNotice("")} /> : null}
  </div></ProtectedWorkspace>;
}

export function AgentWorkspace() {
  const [notice, setNotice] = useState("");
  return <ProtectedWorkspace title="Agent 工作台"><div className="page-shell agent-page"><PageHeader title="Agent 工作台" description="使用受控工具完成研究、订阅、信源运营和采集故障诊断。" action={<button className="button primary" onClick={() => setNotice("已打开任务创建流程（前端原型演示）")} type="button">＋ 创建任务</button>} />
    <section className="agent-grid">{agentTemplates.map((item) => <article className="agent-template" key={item.name}><span className="agent-avatar">{item.code}</span><h2>{item.name}</h2><p>{item.description}</p><button className="button" onClick={() => setNotice(`已选择${item.name}，等待后续业务流程接入`)} type="button">启动 Agent</button></article>)}</section>
    <section className="product-panel agent-run-table"><header><h2>最近运行</h2></header><div className="agent-run-head"><span>任务</span><span>Agent</span><span>状态</span><span>耗时</span><span>Token</span><span /></div>{recentAgentRuns.map((run) => <div className="agent-run-row" key={run.task}><strong>{run.task}</strong><span>{run.agent}</span><span className={`agent-run-status ${run.tone}`}>{run.status}</span><span>{run.duration}</span><span>{run.tokens}</span><button className="agent-run-open" aria-label={`查看 ${run.task}`} onClick={() => setNotice(`已打开“${run.task}”运行详情（前端原型演示）`)} type="button"><Icon name="arrow" /></button></div>)}</section>
    {notice ? <div className="agent-toast" role="status"><span>{notice}</span><button aria-label="关闭提示" onClick={() => setNotice("")} type="button">×</button></div> : null}
  </div></ProtectedWorkspace>;
}

function ProtectedWorkspace({ title, children }: { title: string; children: React.ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <div className="page-shell"><div className="permission-state"><span>正在检查会话</span><h1>{title}</h1><p>正在确认当前账号权限…</p></div></div>;
  if (!user) return <div className="page-shell"><div className="permission-state"><span>需要登录</span><h1>{title}</h1><p>收藏、订阅、研究和 Agent 能力仅对邀请制账号开放。</p><Link className="button primary" href={`/login?returnTo=${encodeURIComponent(location.pathname)}`}>前往登录</Link></div></div>;
  return children;
}

function WorkspaceNotice({ notice, onClose }: { notice: string; onClose: () => void }) {
  return <div className="agent-toast" role="status"><span>{notice}</span><button aria-label="关闭提示" onClick={onClose} type="button">×</button></div>;
}
