# RAG-06 成熟化阶段 A 验收记录

> 执行时间：2026-07-23
> 范围：评测分层与新指标、Prompt 版本化、Provider 超时/重试/错误分类

## 1. 交付内容

### 1.1 评测可信度

- V033 将 `RAG_BASELINE_ZH` 升级为 4.0，57 条用例拆为 DEV 33、TEST 17、CANARY 7。
- 无证据范围集从 3 条扩展为 10 条，单独计算拒答准确率。
- 新增 Hit@5、Precision@8、MRR@10、拒答准确率、结构化输出失败率。
- 每次 ALL 评测同时保存 `bySplit` 与 `byScenario`，单例结果保存 split、scenario 和各项检索指标。
- 管理 API 支持 `split=ALL|DEV|TEST|CANARY`；管理页面展示新增指标和三个分层的通过率。

2026-07-23 真实公开语料 ALL 回归：

| 指标 | 结果 | 门槛 |
|---|---:|---:|
| 用例 | 57 | ≥50 |
| Recall@20 | 1.000 | ≥0.85 |
| nDCG@10 | 0.977 | ≥0.80 |
| Hit@5 | 1.000 | ≥0.90 |
| Precision@8 | 0.890 | ≥0.75 |
| MRR@10 | 0.963 | ≥0.85 |
| 拒答准确率 | 1.000 | ≥0.95 |
| 结构化输出失败率 | 0 | ≤0.01 |
| DEV / TEST / CANARY 通过率 | 1.000 / 1.000 / 1.000 | 全部通过 |
| ACL 泄漏 | 0 | 必须为 0 |

### 1.2 Prompt 版本化

- `knowledge.prompt_version` 增加 System、Task、Evidence 模板和 SHA-256 Hash。
- `research.query_run` 保存 Prompt ID、版本、Hash；查询诊断同步返回版本和 Hash。
- AI API 支持独立的 `system_prompt`、`user_prompt`、`evidence`，OpenAI-compatible 请求不再把所有内容拼成单个 user 消息。
- Evidence 使用显式边界，并声明为不可信数据，不能执行其中的指令。
- V033～V035 记录了 `rag-answer-v1.0.0` 到 `v1.0.2` 的演进，旧版本保留为 RETIRED，可审计、可回滚。

真实验证发现 650 Token 上限会截断复杂 JSON；`v1.0.2` 将复杂回答预算调整为 1000 Token，并限制最多 5 个关键点。最终真实调用为 `SUCCEEDED`，结构化输出有效，4 条引用、覆盖率 1.0，无生成或引用降级，总延迟 9.301s。

### 1.3 Provider 稳定性

| 能力 | 连接超时 | 读取超时 | 总超时 |
|---|---:|---:|---:|
| Generation | 3s | 35s | 40s |
| Embedding | 3s | 15s | 20s |
| Rerank | 3s | 15s | 20s |

- 只对网络错误、超时、HTTP 408/429/5xx 重试一次，并增加短抖动。
- HTTP 4xx、非法 JSON、缺失向量或非法排序结果不重试。
- 错误码按能力和原因分类，例如 `GENERATION_TIMEOUT`、`EMBEDDING_NETWORK`、`RERANK_RATE_LIMITED`、`*_INVALID_RESPONSE`。
- Generation、Embedding、Rerank 的成功和失败均进入 `knowledge.provider_metric`；RAG 降级继续返回确定性证据摘要。

## 2. 验证

| 门禁 | 结果 |
|---|---|
| AI Ruff + pytest | PASS，41/41 |
| Core Maven test | PASS，35/35 |
| Web ESLint + TypeScript + node:test | PASS，18/18 |
| Web 生产构建 | PASS，25 个路由 |
| Compose 配置与服务健康 | PASS |
| Flyway | V033、V034、V035 已应用 |
| M10 Readiness | PASS |

所有测试通过 `scripts/test/compose.test.yaml` 在 Docker Compose 中执行。黄金集评测耗时约 114 秒，Nginx 只对该管理端路径设置 300 秒读写超时，其他 Core API 不放宽。

## 3. 已知限制

1. `rag-answer-v1.0.2` 当前只有 1 条真实结构化输出样本，需继续累计后再判断长期失败率。
2. split 是数据库字段和操作纪律，不是对数据库管理员隐藏答案的安全边界。
3. 提示注入集、困难负样本人工闭环、结构感知 Chunk 和 parent-child retrieval 留在阶段 B。
4. OpenTelemetry/OTLP 仍需结合下一轮 Linux PERF 基线接入，本轮不新增独立 LLMOps 平台。
5. 评测仍为同步管理任务；若真实语料增长后超过 300 秒，应改为异步 evaluation job，而不是继续放大代理超时。
