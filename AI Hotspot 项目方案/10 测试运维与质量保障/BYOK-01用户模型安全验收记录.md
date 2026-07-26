# BYOK-01 用户模型安全验收记录

> 验收日期：2026-07-26
> 范围：普通用户自带 Generation、Embedding、Rerank API Key；不包含生产密钥轮换和厂商账单对账。

## 1. 交付结论

BYOK-01 已在本地 Docker Compose 环境完成代码、数据库、接口、页面和真实 Provider 闭环。登录用户可在“我的模型”管理自己的三类模型连接；Key 只允许录入或替换，服务端以 AES-GCM 保存，响应、审计和错误不返回原始 Key、密文、nonce 或指纹。

平台 Key 不再被静默使用：用户连接存在但未通过烟测、未启用或已降级时，RAG 在检索前直接停止；只有用户显式开启平台回落且站点策略允许时才会消耗平台凭据。

## 2. 数据库与接口

- Flyway `V046__user_byok_policy_and_quota.sql` 已应用，在 `knowledge.user_provider_assignment` 增加：
  - `platform_fallback_enabled`：平台回落显式同意，默认 `false`；
  - `daily_query_limit`：按 `Asia/Shanghai` 自然日统计，范围 1～500；
  - `monthly_token_limit`：自然月 Token 上限，范围 10,000～100,000,000。
- `knowledge.provider_metric` 已有的 `connection_id`、`actor_user_id`、`query_run_id`、`credential_scope` 用于区分平台与用户用量；V046 增加用户/归属/时间部分索引。
- 新增 `/api/v1/me/ai/connections`、`/usage`、更新、烟测、状态和撤销接口，共 7 个普通用户操作；OpenAPI 当前覆盖 106 个 Controller 操作。
- 撤销连接由外键级联删除私有任务路由；历史用量保留 `USER` 归属，连接编号按 `ON DELETE SET NULL` 处理。

数据库仍为 8 个业务 Schema、59 张业务表；V046 只增加字段、约束和索引，没有新增表。

## 3. 安全与资源门禁

| 门禁 | 实现与验收 |
|---|---|
| 所有权 | 所有用户连接查询、修改、烟测、启停和撤销 SQL 都同时限定 `owner_user_id` 与当前用户 |
| 密钥 | AES-GCM 密文 + 随机 nonce；API 只返回末四位；前端密码输入不写入 localStorage/sessionStorage |
| 启用 | 新建和编辑后均为 DISABLED；必须用该连接真实烟测 PASS 后才能 ACTIVE |
| SSRF | 用户 Base URL 只接受可解析的公网 HTTPS；拒绝凭据 URL、localhost、内网、链路本地、CGNAT、保留/文档地址和 ULA |
| 平台回落 | 默认关闭；连接不可用时 fail-closed；只有用户显式同意才能回落 |
| 配额 | RAG 开始前检查每日查询和每月个人 Token；超限返回 429 |
| 并发 | Redis 使用随机锁令牌和 Lua compare-and-delete，单用户同时只运行一个研究任务 |
| 审计与错误 | 创建、更新、烟测、启停、撤销均留审计；只记录厂商、任务、状态和回落策略，不记录密钥或密文 |
| 动态覆盖 | 只有 Core 携带内部令牌可向 AI API 发送 Provider override，浏览器不能直接注入地址或 Key |

`RAG_REQUIRE_USER_KEY` 默认为 `false`，便于当前站点继续为未配置用户提供平台能力；生产运营可设为 `true` 强制每个用户先配置 Generation Key。无论全局值如何，只要用户已经配置但连接不可用且未允许回落，就不会使用平台 Key。

## 4. 自动化与真实验收证据

- Core：57 项测试通过，包含公网地址分类和 development/production 地址策略；
- Web：24 项测试、TypeScript、ESLint、Next.js 生产构建通过，`/settings/models` 已进入生产路由；
- 契约：OpenAPI 漂移检查通过，共 106 个操作；
- Docker：10 个 Compose 服务运行；Core、Web、AI API 与声明健康检查的基础设施均正常；
- 数据库：Flyway 最新版本 `046` 成功，三项新字段存在，59 张业务表数量未变化；
- fail-closed HTTP：创建默认关闭回落的未烟测连接后，RAG 在检索前返回 `503 USER_PROVIDER_UNAVAILABLE`，响应未泄漏密钥；
- 真实路由：用户 Key 烟测通过并启用后，RAG 返回 `SUCCEEDED`、5 条数据库真实来源引用、引用覆盖率 0.8571；
- 用量：真实 Generation 记录为 `credential_scope=USER`，输入 2,111、输出 864，共 2,975 Token；
- 清理：验收连接和密文已撤销，当前 `scope=USER AND display_name LIKE 'Codex BYOK%'` 为 0；历史指标保留审计。

真实验收首次发现用户 Generation 烟测请求 32 tokens，而 AI API 合同下限是 64，导致 422。已将烟测统一为 64 并通过重建复测，防止页面出现“配置正确但永远不能启用”的业务故障。

## 5. 尚未覆盖的生产条件

1. 目标 Linux 环境的真实多用户并发、Redis 故障和长时间运行压测；
2. 各厂商实际计费单价与账单对账；未配置单价时界面保持“待配置单价”；
3. 生产密钥轮换、HTTPS、真实 SMTP、异机恢复和内容合规，继续由上线 P1 任务负责；
4. 普通用户跨账号攻击应在生产前用独立测试账号再做一次黑盒回归；当前服务端所有权条件和统一 404 已实现，但本轮真实 HTTP 使用管理员账号的普通用户权限完成。
