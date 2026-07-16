# AI Hotspot

AI Hotspot 是面向 AI 从业者的公开情报、研究、订阅与自动化产品。当前仓库已经完成 M0 方案与正式原型、M1 工程与基础设施，下一阶段为 M2 邀请制身份、权限与信源管理。

## 工程结构

```text
apps/web        Next.js 用户端与管理端
services/core   Spring Boot 核心业务 API
services/ai       FastAPI AI API 与 Python Worker
packages/contracts OpenAPI 与异步事件契约
infra           Nginx 等基础设施配置
scripts         Docker Compose 辅助脚本
AI Hotspot 项目方案  中文产品与技术方案
```

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

- 不采集 X 或微信公众号；
- 不开放公众自由注册；
- 不支持用户自带模型密钥；
- 不使用 Kubernetes、Kafka 或 Elasticsearch；
- M1 只建立可靠工程底座；页面中的 Mock 数据不代表 M2～M9 业务已实现。

详细进度见 [项目进度与剩余任务](./AI%20Hotspot%20项目方案/00%20项目总览/项目进度与剩余任务.md)。
