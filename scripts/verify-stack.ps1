$ErrorActionPreference = "Stop"
$required = @(
  "ai-hotspot-postgres-1", "ai-hotspot-redis-1", "ai-hotspot-rabbitmq-1",
  "ai-hotspot-minio-1", "ai-hotspot-mailpit-1", "ai-hotspot-ai-api-1",
  "ai-hotspot-worker-1", "ai-hotspot-core-api-1"
)
$failed = @()
foreach ($name in $required) {
  $state = docker inspect --format "{{.State.Status}}" $name 2>$null
  if ($LASTEXITCODE -ne 0 -or $state -ne "running") { $failed += $name }
}
if ($failed.Count -gt 0) { throw "未运行容器：$($failed -join ', ')" }
$health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/actuator/health" -TimeoutSec 10
if ($health.status -ne "UP") { throw "Core API 健康检查失败" }
$providers = Invoke-RestMethod -Uri "http://127.0.0.1:18000/api/v1/providers" -TimeoutSec 10
Write-Host "基础栈运行正常。Generation=$($providers.generation.provider)，Embedding=$($providers.embedding.provider)，Rerank=$($providers.rerank.provider)"
if ($providers.generation.provider -eq "mock") {
  Write-Warning "真实 Generation Provider 尚未启用；Mock 结果会被发布门禁隔离。"
}
