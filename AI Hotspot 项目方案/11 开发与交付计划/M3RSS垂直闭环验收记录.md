# AI Hotspot M3 RSS/Atom 垂直闭环验收记录

> 验收日期：2026-07-17  
> 验收结论：本地技术验证通过，待产品负责人审核  
> 实施分支：`feat/m3-rss-vertical-slice`

## 1. 交付结果

M3 已完成以下真实纵向链路：

```text
SourceEndpoint → FetchJob/Outbox → RabbitMQ → RSS/Atom Worker
→ FetchArtifact/MinIO → RawEntry → ContentItem → 内容处理事件
→ 配置化准入/发布 → 公开 API → 精选/全部动态/详情
```

同时交付采集任务列表、运行概览、死信列表和管理员回放入口。M3 未提前实现 Website/Sitemap 等 M4 Connector，也未把 X 或微信公众号带回范围。

## 2. 数据库与契约

- V004：Endpoint 条件请求游标、FetchJob、FetchArtifact、RawEntry、死信回放字段及索引；
- V005：ContentItem、准入/发布/可见性/事实状态/评分与公开部分索引；
- 已有 M2 数据库从 V003 前向迁移到 V005；
- 临时空数据库一次执行 V001～V005，5 条 Flyway 记录全部成功，`source.fetch_job` 与 `content.content_item` 可用；
- OpenAPI 更新为 0.3.0，覆盖采集、死信和公开内容接口；
- Event Envelope 要求 aggregate、correlation、trace、occurredAt 和 producer 字段；YAML/JSON 解析通过。

## 3. 自动化结果

| 验收项 | 结果 |
|---|---|
| Core 单元/上下文测试 | 15/15 通过 |
| Python pytest | 12/12 通过 |
| Python Ruff | 通过 |
| Web 路由测试 | 5/5 通过 |
| Web ESLint/TypeScript/生产构建 | 通过 |
| Compose 构建与 10 服务运行 | 通过 |
| M1 Smoke 回归 | 通过 |
| M2 Smoke 回归 | 通过 |
| OpenAPI YAML/Event JSON 解析 | 通过 |

## 4. 真实 RSS/Atom 验收

验收入口：NVIDIA Developer Blog（Atom）、Microsoft Research（RSS）、AWS Machine Learning Blog（RSS）。

| 指标 | 结果 |
|---|---:|
| 真实入口 | 3 |
| 首次成功任务 | 3 |
| RawEntry | 15 |
| ContentItem | 15 |
| 重复采集新增 | 0 |
| 有 ETag/Last-Modified 的入口 | 3 |
| HTTP 304 | 3（最新一轮；不同 Feed 缓存头可能变化） |
| MinIO 原件 | 可读取 |
| RabbitMQ 恢复后任务 | SUCCEEDED |
| 确定性坏源 | DEAD_LETTERED |
| 管理员回放 | REPLAYED，审计存在 |
| 公开 API 越权内容 | 0 |

`Test-M3-Smoke.ps1` 会创建独立验收 Source、真实抓取、二次抓取、短暂停止/恢复项目 RabbitMQ、注入 HTML-as-RSS 坏源，并核验数据库、Rabbit DLQ、MinIO 和公开 API。

## 5. 浏览器验收

- 桌面 2048×1125：全部动态加载真实内容，筛选与搜索区域、时间线、卡片和分数正常；
- 搜索 `Agentic vision` 后列表从 50 条过滤为 4 条；
- 点击内容卡片进入 `/content/{id}`，来源、最终分、推荐理由、公开状态和原文链接正常；
- 390×844：导航收窄、筛选换行、卡片单列，无横向溢出；
- 游客访问采集监控得到登录边界；管理员登录后可见真实成功/304/死信任务与回放入口；
- 页面正文非空，无 Next.js 错误覆盖层，浏览器错误列表为空。

视觉对照继续继承已确认参考：固定左侧导航、浅灰背景、白色卡片、时间轨和青绿色强调。M3 只用真实数据替换原型数据，没有重做版式。

## 6. 验收中发现并解决的问题

1. AWS Probe 在默认 8 秒内偶发超时：将连接/请求超时调整到 15/25 秒，仍保持无重定向和 SSRF 校验；
2. MinIO Smoke 写死 bucket：改为从 FetchArtifact 读取实际 bucket/object key；
3. RabbitMQ DLQ 检查误查默认 `/` vhost：改为项目 `ai_hotspot` vhost；
4. 临时空库验证误用默认数据库密码：改为从 `.env` 安全读取，不输出密钥；
5. 应用内浏览器运行时资产路径不可用：记录失败后切换独立浏览器自动化完成同等 QA；
6. PowerShell 未引用 `@eN` 导致第一次交互命令被语法解析：引用 selector 后搜索和详情跳转通过；
7. 手工任务幂等键按毫秒窗口可能碰撞：改为包含 jobId；定时任务仍以 Endpoint+调度窗口幂等。
8. M1 的 `content.processing.smoke` 不含真实 `contentItemId`，M3 Worker 曾将其误送入真实内容处理并形成 2 条长期 unacked：增加显式兼容分支、补齐 Smoke 事件信封和 2 项回归测试；重建后主队列 ready=0、unacked=0，随后 M1/M3 Smoke 再次通过。

## 7. 已知边界

- 中文标题/摘要的真实模型生成、分类标签、近重复、事实核验和事件聚类属于 M5；M3 使用确定性 Mock Provider 验证处理和发布链路；
- 三个 Feed 是技术验收注册表，不是正式运营 Source Registry，NQ-004 仍需产品负责人确认；
- M3 验收数据会保留在本地开发数据库，名称/slug 带验收标识，生产环境不得复用；
- 浏览器截图保存在被 Git 忽略的 `artifacts/m3-browser`，不进入提交。

## 8. Git 收口

完成文档后应执行：代码/迁移提交、Web 提交、验收文档提交、`git diff --check`、干净工作树、`--no-ff` 合并 `main`、创建 `m3-rss-vertical-slice-v1`。仓库尚无 Remote，因此不 push、不部署生产。
