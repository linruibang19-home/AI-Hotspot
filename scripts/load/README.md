# PERF-01 本地性能基线

仅通过 Docker Compose 运行，流量从 k6 容器进入 Nginx，再到 Web/Core。默认执行：

1. 公开读取：1、10、25、50 VU 顺序阶梯；
2. RAG：2 VU，每 VU 2 次，共 4 次真实 Provider 调用；
3. Worker：10 个近期健康 RSS/Atom Endpoint 快速入队，记录队列峰值、完成时间和新增死信；
4. 每 2 秒采集容器 CPU/内存，并在测试前后采集 PostgreSQL、RabbitMQ、Redis 和应用错误。

```powershell
.\scripts\load\Run-Perf-Baseline.ps1
```

只跑公开读取：

```powershell
.\scripts\load\Run-Perf-Baseline.ps1 -SkipRag
```

CI 或发布门禁需要阈值失败时返回错误：

```powershell
.\scripts\load\Run-Perf-Baseline.ps1 -EnforceThresholds
```

原始产物写入 `artifacts/load/<run-id>/`，该目录不得提交。对比两个结果前必须固定代码提交、数据快照、Docker 资源、Provider、问题集、预热方式和测试时长。

已有运行可以重新生成摘要：

```powershell
.\scripts\load\Summarize-Perf-Run.ps1 -RunDirectory .\artifacts\load\<run-id>
```
