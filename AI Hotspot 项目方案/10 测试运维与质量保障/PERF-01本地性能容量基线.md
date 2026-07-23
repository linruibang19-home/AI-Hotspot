# PERF-01 本地性能与容量基线

> 执行时间：2026-07-23
> 基线性质：Windows Docker Desktop 本机预热基线，不代表 Linux 生产容量
> 代码基线：`396c919`，压测脚本在本轮提交
> 工具：`grafana/k6:2.0.0`，全部流量经 Compose 网络进入 Nginx

## 1. 场景与目标

| 场景 | 实际负载 | 目标 |
|---|---|---|
| 公开读取 | 1/10/25/50 VU 顺序执行，分别 20/30/30/30 秒，用户思考 0.2～0.8 秒 | 错误率 <1%，50 VU P95 <1s |
| 页面 | `/`、`/all` | P95 <1.5s |
| 列表/详情/搜索/主题 API | 真实公开数据，随机内容与主题 | 列表 <500ms、详情 <700ms、搜索 <1s、主题 <500ms |
| RAG | 2 VU、每 VU 2 次，共 4 次真实 Provider 调用 | P95 <12s，全部成功，引用≥2、覆盖≥0.8 |
| Worker | 10 个近期健康、非 GitHub 的 RSS/Atom Endpoint 快速入队 | 全部完成、无新增 PENDING 死信、记录队列峰值 |
| 基础设施 | PostgreSQL、RabbitMQ、Redis、容器资源、应用错误日志 | 无死锁/临时文件/新增错误/不可恢复积压 |

公开场景使用持续 VU 与用户思考时间，因此“50 VU”不等于 50 RPS。吞吐以实际完成请求数计算。

## 2. 公开读取结果

公开阶段共 4,700 个 HTTP 请求，吞吐 35.315 req/s，0 个 HTTP/业务检查失败；所有设定阈值 PASS。

| 范围 | 样本 | P50 | P95 | P99 | 最大值 | 目标 |
|---|---:|---:|---:|---:|---:|---|
| 整体 HTTP | 4,700 | 15.85ms | 256.69ms | 431.77ms | 1,021.60ms | 记录基线 |
| 1 VU | 34 | 19.86ms | 140.68ms | 305.91ms | 357.16ms | 记录基线 |
| 10 VU | 569 | 17.81ms | 91.27ms | 178.97ms | 771.89ms | 记录基线 |
| 25 VU | 1,435 | 14.00ms | 82.01ms | 187.98ms | 269.30ms | 记录基线 |
| 50 VU | 2,660 | 16.63ms | 320.38ms | 469.64ms | 1,021.60ms | P95 <1s，PASS |
| 首页 | 647 | 92.54ms | 437.68ms | 524.03ms | 1,021.60ms | P95 <1.5s，PASS |
| 全部页 | 648 | 74.93ms | 379.49ms | 447.89ms | 975.81ms | P95 <1.5s，PASS |
| 列表 API | 1,408 | 11.99ms | 23.91ms | 39.58ms | 97.02ms | P95 <500ms，PASS |
| 详情 API | 658 | 4.37ms | 9.73ms | 33.71ms | 86.51ms | P95 <700ms，PASS |
| 搜索 API | 671 | 26.62ms | 50.13ms | 90.91ms | 722.72ms | P95 <1s，PASS |
| 主题 API | 666 | 7.07ms | 23.81ms | 39.62ms | 111.46ms | P95 <500ms，PASS |

10/25 VU P95 低于 1 VU 是预热、随机请求构成和短测试窗口共同影响，不能解释为并发越高越快。50 VU 时页面请求使整体 P95 明显上升，但仍在本地预算内。

## 3. RAG 小并发

| 样本 | 平均 | P50 | P95 | P99 | 最大值 | 传输错误 | 质量失败 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 | 9.148s | 9.416s | 10.944s | 11.025s | 11.045s | 0 | 0 |

4 次均使用真实 Generation/Embedding/Rerank Provider，答案状态为 `SUCCEEDED`，每题至少 2 条引用且引用覆盖率不低于 0.8。此场景只验证小并发稳定性和成本护栏，不代表高并发 RAG 容量。

## 4. Worker 与消息队列

| 指标 | 实测 |
|---|---:|
| Endpoint | 10 |
| 入队耗时 | 398ms |
| 全部完成时间 | 7.601s |
| 成功/失败/未完成 | 10/0/0 |
| 完成吞吐 | 1.316 jobs/s |
| `q.crawl.worker` ready 峰值 | 0 |
| unacknowledged 峰值 | 4 |
| Consumer | 1 |
| 数据库 PENDING 死信前后 | 0 → 0 |

单 Worker 在该批健康 Feed 上没有形成 ready 积压；吞吐包含真实外部网络等待，不能当作纯解析能力。RabbitMQ 的 `q.crawl.worker.dlq` 另有 204 条历史消息，但对应数据库死信均已审计为 IGNORED/REPLAYED，普通工作队列在验收后为 0；后续应单独治理 Rabbit 历史 DLQ 物理消息。

## 5. PostgreSQL、Redis 与稳定性

公开与 RAG 主测试前后 PostgreSQL 快照：

| 指标 | 前 | 后 | 增量 |
|---|---:|---:|---:|
| Backend 连接 | 3 | 9 | +6（非峰值） |
| Commit | 508,855 | 515,949 | +7,094 |
| Rollback | 56 | 56 | 0 |
| Block read | 30,611 | 30,681 | +70 |
| Block hit | 104,444,762 | 115,071,401 | +10,626,639 |
| Temp files/bytes | 0/0 | 0/0 | 0 |
| Deadlocks | 0 | 0 | 0 |

增量缓存命中率约 99.999%，未观察到临时文件、回滚增长或死锁。Redis `total_commands_processed` 从 66,671 增至 66,746，`total_error_replies` 保持 57，没有新增错误。Core、Web、Nginx、AI、Worker 最近日志的 ERROR/Exception/Traceback/FATAL 匹配为 0。

## 6. 容器资源

资源采样使用循环 `docker stats --no-stream`；目标间隔 2 秒，但 Docker Desktop 完成一轮全容器采样约需 3～5 秒，因此本次只有 35 个样本，适合发现明显峰值，不适合精确计费。

| Compose 容器 | CPU 平均 | CPU P95 | CPU 峰值 | 内存峰值 |
|---|---:|---:|---:|---:|
| Core API | 38.95% | 89.24% | 189.69% | 638.2MiB |
| Web | 43.35% | 116.69% | 162.15% | 395.4MiB |
| PostgreSQL | 42.14% | 142.65% | 146.62% | 290.7MiB |
| Nginx | 5.09% | 13.83% | 17.76% | 19.4MiB |
| AI API | 5.61% | 40.22% | 54.08% | 49.9MiB |
| Worker | 0.01% | 0.12% | 0.12% | 92.0MiB |
| Redis | 1.42% | 5.73% | 5.94% | 12.9MiB |
| MinIO | 4.03% | 24.70% | 27.38% | 266.0MiB |
| Mailpit | 1.09% | 6.15% | 6.20% | 37.8MiB |
| RabbitMQ | 62.05% | 535.19% | 611.09% | 207.0MiB |

CPU 百分比可超过 100%，表示使用多个逻辑核心。RabbitMQ 在没有公开流量依赖和普通队列无积压时出现瞬时多核峰值，与业务吞吐不匹配，可能受到 Erlang 调度、管理插件、采样或后台维护影响；不能据此宣称 RabbitMQ 已饱和，也不能忽略。下一次 Linux soak 需要结合 RabbitMQ 自身 process/reduction 指标复核。

## 7. 结论和限制

本地预热环境在 50 VU 公开浏览、2 VU RAG 和 10 Endpoint Worker 批次下达到当前性能预算，错误率、引用质量、数据库稳定性和 PENDING 死信没有退化，可以作为后续版本的第一份可复现比较基线。

这不是生产容量承诺，原因包括：

1. Windows Docker Desktop，不是目标 Linux 主机；
2. 单阶段仅 20～30 秒，没有 30 分钟以上 soak；
3. 数据库和页面缓存已经预热；
4. RAG 只有 4 次真实调用，未压外部 Provider 配额；
5. Worker 批次只有 10 个健康 Feed，未覆盖失败重试、内容分析和索引重建；
6. 未模拟真实公网 TLS、带宽、CDN、生产 SMTP 和多地域延迟；
7. RabbitMQ 瞬时 CPU 峰值需在 Linux 目标环境复核。

公开 Beta 前仍需在目标 Linux 主机重复同一脚本，增加 30 分钟 50 VU soak、独立 Worker/队列恢复测试，并记录宿主机 CPU、磁盘 IOPS、网络、PostgreSQL 连接池峰值和 RabbitMQ 原生指标。

## 8. 复现入口

- `scripts/load/Run-Perf-Baseline.ps1`
- `scripts/load/public-browse.js`
- `scripts/load/rag-small-concurrency.js`
- `scripts/load/Test-Worker-Capacity.ps1`
- `scripts/load/Summarize-Perf-Run.ps1`

原始运行产物位于本机忽略目录 `artifacts/load/20260723-baseline-01/`，不进入 Git。
