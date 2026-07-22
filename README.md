# AI Hotspot

AI Hotspot 是面向 AI 从业者的公开资讯、主题追踪、报告订阅、RAG 研究与受控 Agent 产品。当前仓库已经完成 **M0～M10 本地产品闭环**，现阶段重点是信源质量、RAG 质量与公开 Beta 上线准备，不再重新搭建工程骨架。

方案唯一入口见 [AI Hotspot 项目方案](./AI%20Hotspot%20项目方案/README.md)，当前事实以 [项目进度与剩余任务](./AI%20Hotspot%20项目方案/00%20项目总览/项目进度与剩余任务.md) 为准。

## 一、工程结构

```text
apps/web                  Next.js 用户端与管理端
services/core             Spring Boot 核心业务、认证、权限和治理 API
services/ai               FastAPI AI API 与统一异步 Worker
packages/contracts        OpenAPI 与跨服务事件契约
infra                     Nginx 等部署配置
scripts                   启停、测试、备份与恢复脚本
AI Hotspot 项目方案       中文产品、架构、数据和交付方案
archive                    已停用的早期材料，仅供追溯
AI_HOTSPOT_PROTOTYPE.html  视觉和交互验收基线
compose.yaml               唯一标准运行入口
```

详细边界和清理规则见 [项目目录说明](./项目目录说明.md)。

## 二、实际运行架构

- 3 个代码应用：`web`、`core-api`、`ai-api/worker`；
- 10 个 Compose 服务：Web、Core API、AI API、Worker、PostgreSQL+pgvector、Redis、RabbitMQ、MinIO、Mailpit、Nginx；
- 1 个 PostgreSQL 实例，按 8 个业务 Schema 隔离；
- 1 个 Python Worker 进程消费多类队列，不为每种任务复制一套服务；
- Docker Compose 是开发和部署的唯一标准编排方式。

完整说明见 [系统总体架构](./AI%20Hotspot%20项目方案/04%20系统架构设计/系统总体架构.md)。

## 三、首次启动

环境建议：Windows 10/11、Docker Desktop + WSL2、Node.js 22+、pnpm 11.9、Java 17+、Python 3.12+、16GB RAM。

```powershell
Copy-Item .env.example .env
./scripts/Test-Environment.ps1
./scripts/Start-Local.ps1 -Build
```

`.env` 只保存在本机，禁止提交、截图或复制到文档。无远程模型密钥时可以启动基础工程，但真实公开内容处理和 RAG 终验必须使用真实 Provider。

## 四、本地入口

| 服务 | 地址 |
|---|---|
| Nginx 统一入口 | http://127.0.0.1:8088 |
| Web | http://127.0.0.1:3000 |
| Core API | http://127.0.0.1:8080/actuator/health |
| AI API | http://127.0.0.1:18000/health |
| RabbitMQ 管理端 | http://127.0.0.1:15672 |
| MinIO 控制台 | http://127.0.0.1:9001 |
| Mailpit | http://127.0.0.1:8025 |
| PostgreSQL | `127.0.0.1:15432` |
| Redis | `127.0.0.1:16379` |

数据库与 Navicat 连接方法见 [本地中间件与数据库连接指南](./AI%20Hotspot%20项目方案/10%20测试运维与质量保障/本地中间件与数据库连接指南.md)。

## 五、认证与范围

- 游客可浏览公开精选、动态、报告、主题、详情和搜索；
- 普通用户可使用邮箱验证码注册和登录，入口为 `http://127.0.0.1:3000/register` 与 `/login`；开发邮件在 Mailpit 查看；
- 收藏、订阅、RAG 和 Agent 需要登录；管理端只允许 ADMIN/OPERATOR；
- 引导管理员由 `.env` 创建，生产使用前必须轮换凭据；
- 当前不采集 X 或微信公众号；X 只保留默认禁用的扩展契约；
- 不使用 Kubernetes、Kafka、Elasticsearch，也不让用户自带模型密钥。

## 六、验证命令

```powershell
pnpm check
./services/core/mvnw.cmd test -f ./services/core/pom.xml
./services/ai/.venv/Scripts/python.exe -m pytest ./services/ai/tests -q
docker compose --env-file .env.example config --quiet
./scripts/verify-m7-m10.ps1 -RequireBetaReady
```

完整测试、备份恢复和发布门禁请按 [后续任务清单](./AI%20Hotspot%20项目方案/11%20开发与交付计划/后续任务清单.md) 执行。测试浏览器必须无头运行并在结束后关闭 Session，避免弹出闪烁窗口。

## 七、运行管理

```powershell
./scripts/Start-Local.ps1
./scripts/Logs-Local.ps1 -Service core-api
./scripts/Stop-Local.ps1
./scripts/Reset-Local.ps1  # 会确认删除本地命名卷
```

当前分支只在本地提交；没有远程仓库授权时不得擅自 push。
