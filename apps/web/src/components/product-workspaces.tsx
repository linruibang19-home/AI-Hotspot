"use client";

import { useState } from "react";
import { Icon } from "@/components/icons";
import { PageHeader } from "@/components/page-header";

export function SubscriptionWorkspace() {
  const [frequency, setFrequency] = useState("每日");
  return <div className="page-shell"><PageHeader title="订阅与邮件" description="每日与每周简报，按主题、来源和时间送达。" action={<button className="button primary" type="button">新建订阅</button>} />
    <div className="subscription-layout"><section className="product-panel subscription-list"><header><h2>我的订阅</h2><span>2 个启用</span></header><button className="subscription-item active"><strong>每日 AI 精选</strong><span>每天 09:00 · Asia/Shanghai</span><small>全部主题 · 最多 12 条</small></button><button className="subscription-item"><strong>Agent 与 AI 编码周报</strong><span>每周一 09:00</span><small>2 个主题 · 最多 20 条</small></button></section>
      <section className="product-panel subscription-editor"><header><div><span className="overline">SUBSCRIPTION</span><h2>每日 AI 精选</h2></div><span className="status-badge active">已启用</span></header><div className="form-grid"><label>频率<select value={frequency} onChange={(event) => setFrequency(event.target.value)}><option>每日</option><option>每周</option></select></label><label>发送时间<input value="09:00" readOnly /></label><label className="span-2">订阅主题<input value="全部主题" readOnly /></label></div><div className="email-preview"><span>AI HOTSPOT · {frequency.toUpperCase()}</span><h3>今天值得关注的 AI 进展</h3><p>从公开信源中筛选高价值动态，并保留原文和来源追踪。</p><div><b>01</b><strong>模型发布与更新</strong><small>3 篇</small></div><div><b>02</b><strong>Agent 与工具调用</strong><small>5 篇</small></div></div><footer><button className="button" type="button">暂停订阅</button><button className="button primary" type="button">保存设置</button></footer></section></div>
  </div>;
}

export function ResearchWorkspace() {
  const [question, setQuestion] = useState("过去 7 天 Agent 产品有哪些值得关注的变化？");
  return <div className="page-shell research-page"><PageHeader title="知识库问答" description="先过滤权限，再检索、重排并生成带引用的答案。" action={<button className="button" type="button">新建研究</button>} />
    <div className="research-layout"><aside className="product-panel research-scope"><h2>检索范围</h2><label>时间范围<select><option>最近 7 天</option><option>最近 30 天</option></select></label><label>主题<select><option>Agent 智能体</option><option>全部主题</option></select></label><label>来源等级<select><option>官方与公开来源</option></select></label><div className="scope-note"><Icon name="research" /><span>检索前执行权限过滤，私有内容不会越权进入上下文。</span></div></aside>
      <main className="product-panel research-chat"><header><span className="status-badge active">RAG READY</span><small>8 个证据片段</small></header><div className="question-bubble">{question}</div><div className="answer-block"><h2>Agent 正从演示能力进入可交付工作流</h2><p>近期变化集中在三个方向：更长时间的自主执行、面向企业流程的工具接入，以及对执行轨迹和权限边界的强化。多个官方更新都把“可靠完成任务”放在单次生成效果之前。</p><p>同时，Coding Agent 已成为最活跃的落地场景，产品开始提供审批、回滚、沙箱和可观测性能力。</p><div className="citation-row"><button>[1] Anthropic</button><button>[2] GitHub</button><button>[3] OpenAI</button></div></div><label className="composer"><textarea value={question} onChange={(event) => setQuestion(event.target.value)} /><button type="button" aria-label="发送问题"><Icon name="arrow" /></button></label></main>
      <aside className="product-panel evidence-panel"><header><h2>引用证据</h2><span>8</span></header>{["Anthropic Newsroom", "GitHub Changelog", "OpenAI News"].map((source, index) => <article key={source}><span>{String(index + 1).padStart(2, "0")}</span><h3>{source}</h3><p>公开内容片段 · 已通过权限与来源策略校验</p></article>)}</aside></div>
  </div>;
}

export function AgentWorkspace() {
  const [tab, setTab] = useState("模板");
  return <div className="page-shell agent-page"><PageHeader title="Agent 工作台" description="使用受控工具完成研究、订阅运营和采集诊断。" action={<button className="button primary" type="button">创建任务</button>} />
    <div className="agent-toolbar"><div className="tabs">{["模板", "运行记录", "等待审批"].map((item) => <button className={`tab tab-button ${tab === item ? "active" : ""}`} onClick={() => setTab(item)} type="button" key={item}>{item}</button>)}</div><span>Mock Provider · 开发模式</span></div>
    {tab === "模板" ? <section className="agent-grid">{[{name:"研究 Agent",desc:"搜索、比较、构建时间线并生成带引用报告。",icon:"research" as const},{name:"订阅 Agent",desc:"把自然语言需求转换成可确认的结构化订阅。",icon:"mail" as const},{name:"采集运维 Agent",desc:"分析信源失败、结构变化、队列积压并给出处理建议。",icon:"activity" as const}].map((item) => <article className="agent-template" key={item.name}><Icon name={item.icon} /><h2>{item.name}</h2><p>{item.desc}</p><button className="button" type="button">使用模板 <Icon name="arrow" /></button></article>)}</section> : <section className="product-panel agent-empty"><Icon name={tab === "等待审批" ? "review" : "activity"} /><h2>{tab === "等待审批" ? "当前没有待审批工具调用" : "还没有 Agent 运行记录"}</h2><p>创建任务后，可在这里查看计划、步骤、工具、引用和运行状态。</p></section>}
  </div>;
}
