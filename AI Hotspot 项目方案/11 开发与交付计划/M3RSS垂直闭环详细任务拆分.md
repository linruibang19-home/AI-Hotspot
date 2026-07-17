# AI Hotspot M3 RSS/Atom 垂直闭环详细任务拆分

> 文档状态：已完成本地技术验证
> 适用分支：`feat/m3-rss-vertical-slice`  
> 阶段目标：以真实 RSS/Atom 信源完成从调度到公开展示的可运行、可追踪、可恢复纵向闭环。

## 1. 阶段边界

M3 只实现 RSS/Atom Connector，不提前实现 Website/Sitemap、GitHub、Hugging Face API、arXiv API、OpenReview 或 Hacker News Connector。X 与微信公众号仍不在项目范围。

本阶段交付链路：

```text
SourceEndpoint
→ FetchJob + Outbox
→ RabbitMQ q.crawl.worker
→ RSS/Atom Worker
→ FetchArtifact + MinIO 原件
→ RawEntry
→ ContentItem 候选
→ RabbitMQ q.content.worker
→ Mock GenerationProvider 基础加工
→ 配置化准入与发布
→ 精选 / 全部动态 / 内容详情
```

M3 的“AI 加工”只验证 Provider 抽象、处理版本、摘要和基础评分闭环。完整中文标题、分类标签、质量评分、事实状态、近重复和事件聚类仍属于 M5。

## 2. M3 验证信源

以下入口是本地验收注册表，不等同于正式运营 Source Registry，因此不关闭 NQ-004：

| 信源 | 类型 | 入口 | 级别 | 验收用途 |
|---|---|---|---|---|
| NVIDIA Developer Blog | ATOM | `https://developer.nvidia.com/blog/feed/` | OFFICIAL | Atom 解析、ETag/Last-Modified、英文内容 |
| Microsoft Research | RSS | `https://www.microsoft.com/en-us/research/feed/` | OFFICIAL | RSS 2.0、较大响应、官方研究内容 |
| AWS Machine Learning Blog | RSS | `https://aws.amazon.com/blogs/machine-learning/feed/` | OFFICIAL | RSS 2.0、多作者与分类字段 |

入口频率、自动发布和响应大小都通过 Endpoint 配置表达，不硬编码进 Worker。正式运营前仍需由产品负责人确认来源等级、频率和授权策略。

## 3. 数据工作包

### 3.1 SourceEndpoint 增量状态

- `next_fetch_at`：下一次可调度时间；
- `last_etag`、`last_modified`：条件请求游标；
- `last_content_hash`：无缓存头时识别响应未变化；
- `last_fetch_job_id`：运维追踪；
- `last_fetch_item_count`：最近一次条目数。

### 3.2 FetchJob

保存入口、触发类型、调度窗口、状态、尝试次数、HTTP 结果、条目计数、错误分类、trace/correlation、重放来源和时间。相同 Endpoint 与调度窗口只能创建一个任务。

状态：`QUEUED → RUNNING → SUCCEEDED`；失败时进入 `WAITING_RETRY → RUNNING`，超过上限进入 `DEAD_LETTERED`。取消只为后续运营界面保留状态，本阶段不实现运行中强杀。

### 3.3 FetchArtifact

每次产生响应正文的请求保存一条元数据：请求 URL、最终 URL、HTTP 状态、MIME、字节数、SHA-256、ETag、Last-Modified、MinIO bucket/object key 和抓取时间。HTTP 304 只更新 Job 和 Endpoint，不复制原件。

### 3.4 RawEntry 与 ContentItem

- RawEntry 以 `endpoint_id + stable_external_id` 唯一；缺少 GUID/ID 时依次使用规范 URL、标题+时间摘要生成稳定 ID；
- `entry_hash` 记录当前规范化条目内容；重复抓取使用原子 UPSERT，不重复创建 RawEntry；
- ContentItem 以 `raw_entry_id` 唯一；只有首次插入的候选发送内容处理事件；
- 公开查询必须同时限制 `publication_status=PUBLISHED`、`visibility=PUBLIC`、`admission_status=PASSED`、`is_duplicate=false`、`fact_status<>DEBUNKED`。

## 4. 调度、消息和幂等

### 4.1 调度

- Core Scheduler 使用 `FOR UPDATE SKIP LOCKED` 原子领取到期 ACTIVE RSS/Atom Endpoint；
- 创建 FetchJob 与 `source.crawl.requested` Outbox 事件在同一事务；
- 支持管理员对单 Endpoint 手工触发；
- 调度窗口由 Endpoint ID 与窗口起点组成稳定唯一键，重复扫描不重复派发。

### 4.2 事件

- `source.crawl.requested`：包含 fetchJobId、endpointId、幂等键、trace/correlation；
- `content.processing.requested`：包含 contentItemId、rawEntryId、处理版本与幂等键；
- 消息正文不放 RSS 原件和文章正文。

### 4.3 重试、死信和回放

- q.crawl.worker 与 q.content.worker 各有 TTL 重试队列；
- 默认最多 4 次，开发验收延迟可配置为 2 秒，生产默认 1 分钟；
- 网络超时、5xx 和临时数据库异常可重试；格式错误、策略拒绝等确定性错误直接死信；
- 最终失败既进入 RabbitMQ DLQ，也写入 `messaging.dead_letter_record`；
- 管理员回放生成新的 eventId，保留原 fetchJobId/idempotencyKey 和 replay 关联，写审计记录；
- Consumer Inbox 对成功事件去重，对 FAILED 记录允许受控重试或回放。

## 5. 准入基线

M3 使用配置化阈值 `relevance>=70`、`quality>=60`。只有满足以下全部条件才自动发布：

1. Endpoint 为 ACTIVE；
2. Endpoint `config.autoPublish=true`；
3. display_policy 不是 HIDDEN；
4. 原始标题和可访问原文 URL 存在；
5. Mock/真实 Provider 处理成功；
6. relevance_score 与 quality_score 达到配置阈值；
7. 不是重复主条目，事实状态不是 DEBUNKED。

`autoPublish` 是 M3 验收入口的显式配置，不代表正式运营自动发布策略已经确认；NQ-006 保持开放。

## 6. API 与界面

### 管理端

- `POST /admin/fetch-jobs/dispatch-due`：立即扫描到期入口；
- `POST /admin/fetch-jobs/endpoints/{id}`：手工触发；
- `GET /admin/fetch-jobs`、`GET /admin/fetch-jobs/{id}`：任务列表与详情；
- `GET /admin/dead-letters`、`POST /admin/dead-letters/{id}/replay`：死信查看与回放。

### 公开端

- `GET /public/contents`：游标分页全部动态；
- `GET /public/contents/featured`：基础精选；
- `GET /public/contents/{id}`：公开内容详情；
- 所有公开端点允许游客访问，并在 SQL 层执行公开准入过滤。

前端继续使用既有左侧导航、浅灰背景、白色时间线内容块和青绿色强调色。M3 不重做产品视觉，只把精选、全部动态与详情从 Mock 数据替换为真实 API，并补齐加载、空、错误状态。

## 7. 测试矩阵

| 编号 | 场景 | 通过条件 |
|---|---|---|
| M3-T01 | 数据迁移 | 全新库与已有 M2 库均可前向迁移 |
| M3-T02 | 调度并发 | 重复/并发扫描同一窗口只产生一个 FetchJob |
| M3-T03 | 真实 RSS/Atom | 3 个入口均产生 SUCCEEDED Job、Artifact 与条目 |
| M3-T04 | MinIO 原件 | 每个 200 响应 Artifact 的 object key 可读取且 hash 一致 |
| M3-T05 | 条件请求 | 再抓取携带 ETag/Last-Modified；304 不复制内容 |
| M3-T06 | 内容幂等 | 重复抓取后 RawEntry/ContentItem 数量不重复增长 |
| M3-T07 | 队列恢复 | RabbitMQ 暂停期间 Outbox/Job 保留，恢复后最终处理 |
| M3-T08 | 失败与 DLQ | 故障达到上限后 Job=DEAD_LETTERED、DB 记录与 Rabbit DLQ 均可见 |
| M3-T09 | 人工回放 | ADMIN 回放有新事件、任务恢复执行且审计可查 |
| M3-T10 | 公开隔离 | 候选、失败、PRIVATE/HIDDEN、DEBUNKED 均不出现在公开 API |
| M3-T11 | 页面 | 精选、全部动态、详情在桌面/移动可访问且无控制台错误 |
| M3-T12 | 全量回归 | Web、Core、Python、Compose 和 M1/M2 Smoke 不回归 |

## 8. 完成定义

只有以下条件全部成立才标记 M3 完成：

- 代码、迁移、OpenAPI/事件契约和中文文档同步；
- 自动化测试、真实 Compose 验收、故障恢复和浏览器验收均通过；
- 至少 3 个真实 RSS/Atom 入口有可重复的成功证据；
- 不存在重复内容、公开越权或未解释的死信；
- 工作树干净，提交历史可追踪；
- 合并到 `main` 并创建 `m3-rss-vertical-slice-v1` 标签。
