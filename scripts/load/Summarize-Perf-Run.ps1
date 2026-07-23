[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$RunDirectory)

$ErrorActionPreference = "Stop"
$runDir = (Resolve-Path $RunDirectory).Path
$meta = Get-Content (Join-Path $runDir "run-meta.json") -Raw | ConvertFrom-Json
$public = Get-Content (Join-Path $runDir "public-summary.json") -Raw | ConvertFrom-Json
$ragPath = Join-Path $runDir "rag-summary.json"
$workerPath = Join-Path $runDir "worker-summary.json"

function Number($value, [int]$digits = 2) {
  if ($null -eq $value) { return "-" }
  return ([double]$value).ToString("F$digits")
}

function To-MiB([string]$value) {
  $number = [double]($value -replace "[^0-9.]", "")
  if ($value -match "GiB") { return $number * 1024 }
  if ($value -match "kB") { return $number / 1024 }
  if ($value -match "B$" -and $value -notmatch "[kMGT]i?B") { return $number / 1MB }
  return $number
}

function MetricLine([string]$label, [string]$name, $source) {
  $metric = $source.metrics.$name
  return "| $label | $($metric.count) | $(Number $metric.med) | $(Number $metric.'p(95)') | $(Number $metric.'p(99)') | $(Number $metric.max) |"
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# PERF-01 运行摘要：$($meta.runId)")
$lines.Add("")
$lines.Add("- 提交：``$($meta.commit)``")
$lines.Add("- 分支：``$($meta.branch)``")
$lines.Add("- 目标：$($meta.target)")
$lines.Add("- 生成时间：$($meta.generatedAt)")
$lines.Add("- Public/RAG 退出码：$($meta.publicExitCode)/$($meta.ragExitCode)")
$lines.Add("")
$lines.Add("## 公开读取")
$lines.Add("")
$lines.Add("| 指标 | 样本 | P50 ms | P95 ms | P99 ms | Max ms |")
$lines.Add("|---|---:|---:|---:|---:|---:|")
$lines.Add((MetricLine "整体 HTTP" "http_req_duration" $public))
$lines.Add((MetricLine "1 VU" "browse_1_latency_ms" $public))
$lines.Add((MetricLine "10 VU" "browse_10_latency_ms" $public))
$lines.Add((MetricLine "25 VU" "browse_25_latency_ms" $public))
$lines.Add((MetricLine "50 VU" "browse_50_latency_ms" $public))
$lines.Add((MetricLine "首页" "home_latency_ms" $public))
$lines.Add((MetricLine "全部页" "all_page_latency_ms" $public))
$lines.Add((MetricLine "列表 API" "list_latency_ms" $public))
$lines.Add((MetricLine "详情 API" "detail_latency_ms" $public))
$lines.Add((MetricLine "搜索 API" "search_latency_ms" $public))
$lines.Add((MetricLine "主题 API" "topics_latency_ms" $public))
$lines.Add("")
$lines.Add("- 请求：$($public.metrics.http_reqs.count)，吞吐：$(Number $public.metrics.http_reqs.rate) req/s")
$lines.Add("- HTTP 失败：$($public.metrics.http_req_failed.value)")
$lines.Add("")

if (Test-Path $ragPath) {
  $rag = Get-Content $ragPath -Raw | ConvertFrom-Json
  $lines.Add("## RAG 小并发")
  $lines.Add("")
  $lines.Add("| 样本 | 平均 ms | P50 ms | P95 ms | P99 ms | Max ms | 传输错误 | 质量失败 |")
  $lines.Add("|---:|---:|---:|---:|---:|---:|---:|---:|")
  $metric = $rag.metrics.rag_latency_ms
  $lines.Add("| $($metric.count) | $(Number $metric.avg) | $(Number $metric.med) | $(Number $metric.'p(95)') | $(Number $metric.'p(99)') | $(Number $metric.max) | $($rag.metrics.rag_errors.value) | $($rag.metrics.rag_quality_failures.value) |")
  $lines.Add("")
}

if (Test-Path $workerPath) {
  $worker = Get-Content $workerPath -Raw | ConvertFrom-Json
  $lines.Add("## Worker")
  $lines.Add("")
  $lines.Add("- Endpoint/成功/失败/未完成：$($worker.endpoints)/$($worker.succeeded)/$($worker.failed)/$($worker.unfinished)")
  $lines.Add("- 入队耗时：$($worker.enqueueMs) ms；完成耗时：$($worker.durationSeconds) s")
  $lines.Add("- 吞吐：$($worker.throughputJobsPerSecond) jobs/s")
  $lines.Add("- 队列峰值 ready/unacknowledged：$($worker.maxQueueReady)/$($worker.maxQueueUnacknowledged)")
  $lines.Add("- PENDING 死信前后：$($worker.pendingDeadLettersBefore)/$($worker.pendingDeadLettersAfter)")
  $lines.Add("")
}

$statsPath = Join-Path $runDir "docker-stats.jsonl"
if (Test-Path $statsPath) {
  $rows = foreach ($line in Get-Content $statsPath) {
    $parts = $line -split [char]9, 2
    if ($parts.Count -ne 2) { continue }
    $item = $parts[1] | ConvertFrom-Json
    if ($item.Name -notlike "ai-hotspot-*") { continue }
    [pscustomobject]@{
      Name = $item.Name
      Cpu = [double]($item.CPUPerc.TrimEnd("%"))
      MemoryMiB = To-MiB (($item.MemUsage -split "/")[0].Trim())
    }
  }
  $lines.Add("## 容器资源")
  $lines.Add("")
  $lines.Add("| 容器 | 样本 | CPU 平均 % | CPU P95 % | CPU 峰值 % | 内存峰值 MiB |")
  $lines.Add("|---|---:|---:|---:|---:|---:|")
  foreach ($group in ($rows | Group-Object Name | Sort-Object Name)) {
    $cpu = @($group.Group.Cpu | Sort-Object)
    $memory = @($group.Group.MemoryMiB | Sort-Object)
    $p95Index = [Math]::Max(0, [Math]::Ceiling($cpu.Count * 0.95) - 1)
    $avg = ($cpu | Measure-Object -Average).Average
    $lines.Add("| $($group.Name) | $($cpu.Count) | $(Number $avg) | $(Number $cpu[$p95Index]) | $(Number $cpu[-1]) | $(Number $memory[-1] 1) |")
  }
  $lines.Add("")
}

$errorsPath = Join-Path $runDir "application-errors.txt"
$errorLines = if (Test-Path $errorsPath) { @(Get-Content $errorsPath).Count } else { 0 }
$lines.Add("## 稳定性")
$lines.Add("")
$lines.Add("- 应用错误日志匹配数：$errorLines")
$lines.Add("- 原始证据目录：``$runDir``")
$lines.Add("")

$summaryPath = Join-Path $runDir "summary.md"
$lines | Set-Content -LiteralPath $summaryPath -Encoding UTF8
Write-Host $summaryPath
