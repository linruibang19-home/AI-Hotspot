# AI Hotspot

AI Hotspot 是面向 AI 从业者的公开情报、研究、订阅与自动化产品。当前仓库已经完成 M0～M5：方案与正式原型、工程基础设施、身份权限与信源管理、RSS/Atom 垂直闭环、公开 Connector 与正式信源目录，以及内容质量、事件关联和审核治理。正式 Web 界面已按冻结原型完成一轮高保真实现；下一阶段为 M6 完整资讯产品与报告业务闭环。

## 工程结构

```text
apps/web        Next.js 用户端与管理端
services/core   Spring Boot 核心业务 API
services/ai       FastAPI AI API 与 Python Worker
packages/contracts OpenAPI 与异步事件契约
infra           Nginx 等基础设施配置
scripts         Docker Compose 辅助脚本
AI Hotspot 项目方案  中文产品与技术方案
archive         已停用早期资料归档（不作为现行需求）
```

完整职责、生成目录和归档规则见 [项目目录说明](./项目目录说明.md)。

## 本地要求

- Windows 10/11；
- Docker Desktop + WSL2；
- Node.js 22+ 与 pnpm 11.9；
- Java 17+；
- Python 3.12+；
- 16GB RAM、40GB 可用空间建议值。

Docker Compose 是唯一标准运行方式。PowerShell 脚本只包装 Compose，不维护另一套原生部署。

## 首次启动

```powershell
Copy-Item .env.example .env
./scripts/Test-Environment.ps1
./scripts/Start-Local.ps1 -Build
```

默认入口：

| 服务 | 地址 |
|---|---|
| Nginx 统一入口 | http://localhost:8088 |
| Web | http://localhost:3000 |
| Core API | http://localhost:8080/api/v1/health |
| AI API | http://localhost:8000/health |
| RabbitMQ 管理 | http://localhost:15672 |
| MinIO 控制台 | http://localhost:9001 |
| Mailpit | http://localhost:8025 |

PostgreSQL 和 Redis 的宿主机调试端口默认为 15432 和 16379，避免与本机已有服务冲突；容器网络内仍使用 5432 和 6379。

开发环境默认使用 Mock Generation、Embedding 和 Rerank Provider，不需要任何远程 API Key。

## 本地质量检查

```powershell
./scripts/Test-All.ps1
```

Compose 已启动时，可执行 M1 端到端 Smoke：

```powershell
./scripts/Test-M1-Smoke.ps1
```

该脚本验证 Nginx、Core Outbox、RabbitMQ、Worker Inbox 幂等和 SMTP→Mailpit 链路。

执行 M2 身份、权限、SSRF、信源探测和审计 Smoke：

```powershell
./scripts/Test-M2-Smoke.ps1
```

执行 M3 真实 RSS/Atom、增量游标、MinIO、幂等、RabbitMQ 恢复、死信回放和公开隔离 Smoke：

```powershell
./scripts/Test-M3-Smoke.ps1
```

该脚本访问 NVIDIA Developer Blog、Microsoft Research 和 AWS Machine Learning Blog 的公开 Feed，并会短暂停止后恢复本项目 RabbitMQ 容器；仅在本地验收环境执行。

执行 M4 正式目录、七类 Connector、MinIO、队列、公开 API、测试数据隔离与 X 禁用边界 Smoke：

```powershell
./scripts/Test-M4-Smoke.ps1
```

执行 M5 内容加工、评分、去重、事件、审核、纠错和下架恢复 Smoke：

```powershell
./scripts/Test-M5-Smoke.ps1
```

普通用户可在 `http://localhost:3000/register` 使用邮箱验证码注册，并在登录页使用邮箱验证码免密登录。本地验证码邮件在 Mailpit（`http://localhost:8025`）查看。引导管理员仍使用 `.env` 中的 `BOOTSTRAP_ADMIN_EMAIL` 和 `BOOTSTRAP_ADMIN_PASSWORD`；首次启动后必须修改生产环境凭据。

执行公开邮箱注册、Session 与验证码登录 Smoke：

```powershell
./scripts/Test-Email-Auth-Smoke.ps1
```

单独执行：

```powershell
pnpm check
./services/core/mvnw.cmd test -f ./services/core/pom.xml
./services/ai/.venv/Scripts/python.exe -m pytest ./services/ai/tests -q
docker compose --env-file .env.example config --quiet
```

## 运行管理

```powershell
./scripts/Start-Local.ps1
./scripts/Logs-Local.ps1 -Service core-api
./scripts/Stop-Local.ps1
./scripts/Reset-Local.ps1  # 会确认删除本地命名卷
```

## 当前范围

- 当前不采集 X 或微信公众号；X 仅保留不可启用、不可调度的 Connector 契约，微信公众号仍不进入范围；
- 普通用户开放邮箱验证码注册与登录；管理端仍只允许 ADMIN/OPERATOR 角色进入；
- 不支持用户自带模型密钥；
- 不使用 Kubernetes、Kafka 或 Elasticsearch；
- 已实现 RSS/Atom、Website、Sitemap、GitHub、arXiv、Hacker News 等公开 Connector；正式目录现有 82 个 Endpoint，79 个启用，3 个因上游接口限制或已有稳定替代源而明确暂停。M0～M10 的本地功能代码和验收脚本均已交付；真实 Provider、生产 SMTP、Linux/HTTPS 和合规仍由发布门禁阻止提前宣称 Beta 就绪。

数据库、中间件端口和 Navicat 连接方式见 [本地中间件与数据库连接指南](./AI%20Hotspot%20项目方案/10%20测试运维与质量保障/本地中间件与数据库连接指南.md)。

详细进度见 [项目进度与剩余任务](./AI%20Hotspot%20项目方案/00%20项目总览/项目进度与剩余任务.md)。
