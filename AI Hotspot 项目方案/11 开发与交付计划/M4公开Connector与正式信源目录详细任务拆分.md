# AI Hotspot M4 公开 Connector 与正式信源目录详细任务拆分

> 文档状态：执行中  
> 基线日期：2026-07-17  
> 前置里程碑：M0～M3 已完成本地技术验收

## 1. 目标

M4 不是简单增加 URL，而是把“正式信源目录、Connector 能力、真实采集、失败治理和页面状态”形成可持续扩展的闭环。

本阶段完成后，测试用 Source 与正式产品 Source 必须可区分；管理员看到的信源数量必须代表真实可运营入口，不能用 Smoke 重复记录充数。

## 2. 当前基线

- 已跑通的独立真实信源：NVIDIA Developer Blog、Microsoft Research、AWS Machine Learning Blog；
- 已实现 Connector：RSS、Atom；
- 已有 SourceEntity/SourceEndpoint、FetchJob、MinIO 原件、RawEntry、ContentItem、重试、DLQ、审计；
- 当前数据库中的多条 M2/M3 Source 是自动化验收记录，不属于正式信源目录；
- 精选、全部动态、详情、登录、用户邀请、信源管理和采集监控已接真实业务；
- 日报、主题、收藏、RAG、订阅、Agent、内容审核和模型管理尚未进入对应实现阶段。

## 3. Connector 范围

| Connector | M4 状态 | 最低真实验收样本 |
|---|---|---|
| RSS/Atom | 扩充与回归 | OpenAI、Google DeepMind、TechCrunch AI、The Decoder、IT之家等 |
| Website | 实现 | 公开官网新闻列表或文章页 |
| Sitemap | 实现 | Anthropic Sitemap |
| GitHub | 实现 | OpenAI Python、LangGraph Releases |
| Hugging Face | 实现 | Daily Papers 公开 API；不可用时保留失败分类，不绕过访问策略 |
| arXiv | 实现 | cs.AI 最新论文 Atom API |
| OpenReview | 实现 | 公开 Notes API；必须遵守官方访问和限速要求 |
| Hacker News | 实现 | Algolia 公开检索 API |
| 公开技术媒体/社区 | 复用 RSS/API | TechCrunch AI、The Decoder、Ars Technica、VentureBeat AI、MIT Technology Review、IT之家 |
| X | 仅预留 | 不发请求、不调度、不启用，不作为 M4 退出条件 |
| 微信公众号 | 排除 | 不预留采集实现 |

## 4. 首批正式信源目录

### 4.1 官方与一手

- OpenAI News RSS；
- Google DeepMind Blog RSS；
- Anthropic 官方 Sitemap；
- Microsoft Research RSS；
- NVIDIA Developer Blog Atom；
- AWS Machine Learning Blog RSS；
- OpenAI Python GitHub Releases；
- LangGraph GitHub Releases。

### 4.2 论文与研究社区

- arXiv cs.AI；
- Hugging Face Daily Papers；
- OpenReview 公开 Notes。

### 4.3 开发者社区与媒体

- Hacker News AI；
- TechCrunch AI RSS；
- The Decoder RSS；
- Ars Technica Technology Lab RSS；
- VentureBeat AI RSS；
- MIT Technology Review RSS；
- IT之家 RSS。

所有目录项必须带：主体类型、官方等级、国家/地区、权威分、语言、展示策略、索引策略、轮询间隔、限速和最大响应体配置。

## 5. X 预留契约

产品负责人在 2026-07-17 将原“永久排除 X”修订为“当前不采集，但保留未来扩展契约”。本阶段只允许交付下列内容：

1. `X` Connector 能力描述和配置 Schema 占位；
2. `implementationStatus=RESERVED`、`activationAllowed=false`；
3. 数据库约束保证 X Endpoint 只能处于 `DRAFT` 或 `ARCHIVED`；
4. Core API 拒绝 X 的探测、启用和手工采集；
5. Scheduler 和 Worker 不识别、不派发 X；
6. 管理端新增信源表单不展示 X；
7. 仓库不得包含 X 登录态、Cookie、非公开接口、抓包逆向或规避平台限制的实现；
8. 后续启用必须新增决策记录，确认官方/合规数据路径、授权、费用、限速、内容使用权和稳定性。

## 6. 工作包

### WP1：范围、契约和数据治理

- 更新已确认决策、范围、Connector 规范和追踪表；
- 新增 Flyway 迁移，扩展可调度 Connector 索引；
- 增加正式目录标识和 X 强制禁用约束；
- 信源列表支持区分正式目录与测试数据。

### WP2：通用 HTTP 与安全

- 复用 SSRF、重定向、响应体、超时、User-Agent 和条件请求策略；
- 按 Connector 设置 Accept Header；
- 入口级轮询和限速；
- 统一失败代码：DNS、SSRF、超时、限速、鉴权、结构变化、空结果、响应过大、解析错误。

### WP3：结构化 Connector

- Website/Sitemap；
- GitHub Releases；
- Hugging Face Daily Papers；
- arXiv；
- OpenReview；
- Hacker News。

所有 Connector 输出统一 `RawEntry`，原始响应进入 MinIO。

### WP4：正式信源目录

- 使用幂等初始化器导入首批目录；
- 正式目录只创建一次，可由管理员后续暂停；
- 不覆盖管理员已经修改的运行状态和策略；
- Smoke Source 使用显式测试标记并在验收后清理或归档。

### WP5：管理端与页面诚实性

- 信源列表展示 Connector、目录类型、健康度、最近成功和今日内容；
- 采集监控显示任务详情和失败分类；
- 尚未实现业务的页面按钮改为阶段状态，不再呈现无响应的主操作按钮；
- 不改变已确认的 AI HOT 风格、左侧导航、灰色画布、卡片和时间线方向。

### WP6：质量与真实验收

- 每类 Connector 离线 Fixture；
- 至少一个真实成功样本；
- 条件请求或内容 Hash 幂等；
- MinIO 原件可读；
- 失败进入重试/DLQ 并可审计回放；
- 全量 Web/Core/Python 测试和 M1～M3 回归通过；
- 桌面与移动页面交互核验。

## 7. 退出条件

1. 正式目录至少 15 个独立信源，覆盖官方、论文、媒体和社区；
2. M4 每类 Connector 有 Fixture 和真实结果，或记录由上游授权/可用性造成的明确阻塞；
3. 正式 Source 与 Smoke Source 不再混算；
4. 所有已接通按钮有真实状态变化，未实现按钮明确显示阶段状态；
5. X 预留契约存在，但任何路径都不能启用或发起采集；
6. 全量自动化、迁移、Compose、真实 Smoke 和 Git 状态通过；
7. 验收记录列出已知限制和 M5 输入。

