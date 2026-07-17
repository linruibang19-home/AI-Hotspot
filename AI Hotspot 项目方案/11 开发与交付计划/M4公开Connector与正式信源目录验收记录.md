# AI Hotspot M4 公开 Connector 与正式信源目录验收记录

> 验收日期：2026-07-17
> 验收结论：本地技术验收通过，等待产品负责人审核
> 分支：`feat/m4-public-connectors`

## 1. 交付结论

M4 已把“少量 RSS 技术样本”升级为可运营的正式目录：19 个正式 Endpoint、17 个启用且健康、2 个因上游可用性保持暂停。正式目录和 Smoke 数据已分层，测试入口不再参与正式统计或周期调度。

真实采集已覆盖 7 类 Connector：RSS、Atom、Website、Sitemap、GitHub、arXiv、Hacker News。Hugging Face 与 OpenReview Connector 已实现并有离线 Fixture，但本地真实访问分别遇到连接重置/不可用和 HTTP 403，因此没有绕过限制，目录保持 PAUSED。

## 2. 首批正式信源

| 类别 | 信源 | Connector | 状态 |
|---|---|---|---|
| 官方 | OpenAI News、Google DeepMind、Microsoft Research、NVIDIA Developer、AWS Machine Learning | RSS/Atom | ACTIVE / HEALTHY |
| 官方 | Anthropic Sitemap、Anthropic News | Sitemap/Website | ACTIVE / HEALTHY |
| 开源 | OpenAI Python Releases、LangGraph Releases | GitHub | ACTIVE / HEALTHY |
| 论文 | arXiv cs.AI | arXiv | ACTIVE / HEALTHY |
| 研究平台 | Hugging Face Daily Papers、OpenReview Public Notes | HF/OpenReview | PAUSED / UNKNOWN |
| 社区 | Hacker News AI | Hacker News | ACTIVE / HEALTHY |
| 媒体 | TechCrunch AI、The Decoder、Ars Technica、VentureBeat AI、MIT Technology Review、IT之家 | RSS | ACTIVE / HEALTHY |

## 3. 自动化与运行态证据

| 验收项 | 结果 |
|---|---|
| Web | 5/5 测试通过；TypeScript、ESLint、Next.js 生产构建通过 |
| Core | 16/16 测试通过；Spring Boot 启动通过 |
| Python | Ruff 通过；18/18 测试通过 |
| Flyway | V001～V007 校验通过，当前版本 `007` |
| Compose | 10 个标准服务运行；有 Healthcheck 的服务均 healthy |
| 正式目录 | 19 个，幂等重启新增 0 个 |
| 真实采集 | 17/17 ACTIVE Endpoint 健康，7 类 Connector 有成功任务 |
| 内容与原件 | 最终闭环复核时 20 个 FetchArtifact、420 个 RawEntry、420 个公开 ContentItem |
| 正式死信 | 0 |
| RabbitMQ | crawl/content 主队列 ready=0、unacked=0 |
| MinIO | 正式信源原件可读取 |
| M1 回归 | Outbox/Inbox 幂等、RabbitMQ、SMTP→Mailpit 通过 |
| M2 回归 | 邀请、RBAC、SSRF、探测、启用、审计通过 |
| M3 回归 | 3/3 真实 Feed、重复增量 0、3 次 304、恢复、DLQ 回放、公开泄漏 0 |
| M4 Smoke | `Test-M4-Smoke.ps1` 通过 |

## 4. X 边界验证

- `X` 只作为未来 Connector 类型契约存在，状态为 `RESERVED`；
- 管理端新建表单不提供 X；
- 数据库约束仅允许 X Endpoint 为 DRAFT 或 ARCHIVED；
- 探测、启用、手工采集、调度器和 Worker 均不能执行 X；
- 本次验收 X ACTIVE 数量为 0、FetchJob 数量为 0；
- 仓库不包含登录态、Cookie、非公开接口、抓包逆向或规避平台限制实现。

未来研究 X 时必须先形成新的合规决策，优先评估官方 API、授权数据供应商、费用、限速、内容展示权和稳定性；不得仅根据参考产品页面推断或复制其未公开实现。

## 5. 页面与按钮真实状态

| 页面/操作 | 当前状态 |
|---|---|
| 精选、全部动态 | 真实 API；筛选、搜索、打开详情可用 |
| 内容详情、原文链接 | 真实 API/真实来源 URL |
| 登录、邀请注册 | 可用 |
| 管理用户与邀请码 | 可用 |
| 管理信源 | 正式目录、搜索/筛选、新增、探测、启用、暂停可用 |
| 采集监控 | 刷新、派发到期任务、查看任务、死信回放可用 |
| 日报/周报、主题详情、收藏、订阅 | 尚未实现，对应操作禁用并显示“尚未接通” |
| RAG/研究、Agent | 尚未实现，对应操作禁用并显示阶段状态 |
| 内容审核、模型管理 | 尚未实现，对应操作禁用并显示阶段状态 |

## 6. 浏览器验证说明

Codex 内置浏览器控制组件在本轮启动时报告 `failed to write kernel assets: 系统找不到指定的路径 (os error 3)`，会产生空白窗口。为避免继续干扰用户，本轮没有再次调用该组件。

替代证据包括：Next.js 路由测试与生产构建、Nginx HTTP、公开 API、登录/RBAC Smoke，以及系统 Chrome 无头渲染。`/`、`/all`、`/topics`、`/reports` 均成功生成 DOM；`/reports` 的未实现状态可见。M4 未宣称完成一次新的人工可视化走查，后续工具修复后可补充，但不影响当前业务链路技术验收。

## 7. 已解决问题

1. MyBatis 直接映射 PostgreSQL UUID 标量时尝试错误的字节构造：改为 SQL 返回文本并显式 `UUID.fromString`；
2. 历史 M2/M3 Smoke 数据冒充正式信源数量：新增 `catalog_kind`、正式目录键和 V007 隔离；
3. 测试信源可能被调度器重复抓取：到期查询和部分索引排除 TEST，Smoke 结束自动归档；
4. 页面存在无响应主按钮：未实现功能统一改为禁用并标明“尚未接通”；
5. M4 验收脚本的 Flyway 版本格式与 PowerShell `$HOME` 变量冲突：均已修复并重跑通过。

## 8. M5 输入

- 中文标题/摘要的真实生成与质量评测；
- relevance、quality、final score 的完整评分与管理端维度展示；
- 精确/近重复、事件聚类与跨事件关联；
- UNCONFIRMED/DEBUNKED 工作流；
- 内容候选池、审核、纠错、下架、恢复和审计；
- 继续观察 Hugging Face/OpenReview 官方公开访问能力，条件满足后单独启用。
