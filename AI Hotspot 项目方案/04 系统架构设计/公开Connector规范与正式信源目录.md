# AI Hotspot 公开 Connector 规范与正式信源目录

## 1. 统一 Connector 边界

每个 Connector 只负责：安全获取公开响应、解析为统一条目、保存原件、更新游标和报告结构化失败。内容评分、中文增强、事实判断、去重与事件关联由后续内容处理阶段负责。

统一输入：Endpoint URL 与类型、Connector 配置、增量游标、FetchJob 与链路 ID，以及超时、响应体、最大条目数、重试和 User-Agent。

统一输出：HTTP 响应与 MinIO FetchArtifact，以及 `RawEntry` 的外部 ID、URL、标题、摘要、发布时间、作者、原始 payload 和 Hash。

## 2. 能力状态

- `AVAILABLE`：已实现、可探测、可启用、可调度；
- `RESERVED`：只保留未来契约，禁止探测、启用和调度；
- `DISABLED`：实现存在但被配置或运营策略关闭；
- `DEPRECATED`：只允许读取历史数据，不允许新建。

X 当前固定为 `RESERVED`，`activationAllowed=false`。

## 3. 安全不变量

- 只允许公开 HTTP/HTTPS 和标准端口；
- 每次重定向重新执行 DNS 与 SSRF 校验；
- 禁止 URL 凭据、私网、本地、链路本地和保留地址；
- XML 拒绝 DTD 和外部实体，HTML 不执行脚本；
- JSON/XML/HTML 都受最大响应体限制；
- 不绕过登录、付费墙、robots、验证码或平台访问限制；
- Token 只能通过 `credential_ref` 引用，不能写入配置或 Git。

## 4. 正式目录与测试数据

正式信源目录用于产品运营；Smoke Source 只用于自动化验收。两者必须有显式标记，指标默认只统计正式目录。自动化测试创建的数据应在结束时删除，无法删除时归档并标记为测试数据。

正式目录初始化必须幂等，不覆盖管理员已经修改的状态、健康信息、游标和策略。

## 5. 当前正式信源目录

截至 2026-07-18，正式目录共 82 个 Endpoint：79 个 `ACTIVE + HEALTHY`，3 个 `PAUSED + UNKNOWN`。运行时健康状态以 PostgreSQL 的 `source.source_endpoint` 为准，下面是按主体类型整理的可读清单；机器可执行定义以 `services/core/src/main/resources/source-catalog.json` 为准。

- 公司与官方产品（33）：OpenAI、Anthropic、NVIDIA Developer、AWS Machine Learning、GitHub AI & ML、Microsoft AI/Azure/Official Blog、Cursor、OpenRouter、Stability AI、Cohere、Google AI/Cloud AI、Mistral AI、xAI（暂停）、阿里云通义、腾讯混元、火山引擎、智谱 AI、MiniMax、月之暗面 Kimi、DeepSeek、美团技术团队、蚂蚁集团、百川智能、零一万物、GitHub Copilot、Cloudflare Engineering、Vercel。
- 开源项目与版本（20）：LangGraph、TensorFlow、vLLM、llama.cpp、Hugging Face Transformers、PyTorch、Qwen-Agent、PaddlePaddle、PaddleNLP、MindSpore、ModelScope、InternLM、OpenCompass、MiniCPM-V、DeepSeek-V3、OpenAI Codex、Claude Code、Gemini CLI、MCP Specification、MCP Servers。
- 研究机构与论文（14）：Google DeepMind、Microsoft Research、arXiv cs.AI、OpenReview（暂停）、Apple Machine Learning Research、Mozilla AI、BAIR、Stanford HAI、Meta Engineering、字节跳动 Seed、百度研究院、华为诺亚方舟、北京智源研究院、Google Research。
- 技术媒体（8）：TechCrunch AI、The Decoder、Ars Technica、VentureBeat AI、MIT Technology Review、IT之家、少数派、阮一峰科技爱好者周刊。
- 社区（7）：Hugging Face Daily Papers（暂停）、Hacker News AI、Hacker News LLM、MLCommons、ModelScope 头条、Hugging Face Blog、V2EX 技术社区。

腾讯混元、火山引擎和蚂蚁集团的官网动态列表因客户端渲染或结构波动，使用同主体官方 GitHub API 作为稳定采集入口；网页仍保留为主体官网链接。Awesome RSSHub Routes 提供了候选发现依据，但公共 `rsshub.app` 在本地返回 403，因此生产目录优先采用官方直连 RSS/Atom/API，不依赖第三方公共 RSSHub 实例。

## 6. X 后续研究门槛

后续研究只接受合规路径：X 官方 API、内容主体明确授权的数据服务，或产品负责人和法务确认的公开嵌入/引用机制。不得通过登录态 Cookie、非公开接口、自动化账号规避、抓包逆向或访问控制绕过实现。

启用前必须评审：API 条款、授权范围、按量费用、速率限制、删除同步、原文展示权、RAG/向量化权利、数据保留、账号风险和供应商锁定。
