param(
  [int]$P95TargetMs = 12000,
  [int]$MinimumCitations = 2,
  [double]$MinimumCitationCoverage = 0.8
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envLines = Get-Content (Join-Path $root ".env")

function EnvValue([string]$name) {
  $line = $envLines | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
  if ($line) { return $line.Substring($name.Length + 1) }
  return ""
}

function Assert([bool]$condition, [string]$message) {
  if (-not $condition) { throw $message }
}

$email = EnvValue "BOOTSTRAP_ADMIN_EMAIL"
$password = EnvValue "BOOTSTRAP_ADMIN_PASSWORD"
Assert (-not [string]::IsNullOrWhiteSpace($email)) "缺少 BOOTSTRAP_ADMIN_EMAIL"
Assert (-not [string]::IsNullOrWhiteSpace($password)) "缺少 BOOTSTRAP_ADMIN_PASSWORD"

$base = "http://127.0.0.1:8080/api/v1"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function CsrfHeaders {
  $csrf = Invoke-RestMethod -Uri "$base/auth/csrf" -WebSession $session -TimeoutSec 10
  return @{ $csrf.headerName = $csrf.token }
}

function PostJson([string]$path, $body) {
  return Invoke-RestMethod -Uri "$base$path" -Method Post -ContentType "application/json" `
    -Headers (CsrfHeaders) -WebSession $session -Body ($body | ConvertTo-Json -Depth 12) `
    -TimeoutSec 120
}

$login = PostJson "/auth/login" @{ email = $email; password = $password }
Assert ($login.roles -contains "ADMIN") "管理员登录或权限校验失败"

$runtime = Invoke-RestMethod -Uri "$base/admin/ai/runtime" -WebSession $session -TimeoutSec 20
$expectedProvider = [string]$runtime.generation.provider
Assert ($expectedProvider -notin @("", "mock", "test", "fixture")) "RAG 延迟回归必须使用真实 Generation Provider"

# 固定问题集同时覆盖时效、官方限定、比较、多信源、中英混合与冲突表达。
$questions = @(
  "过去 7 天 Agent 产品有哪些重要变化？请区分官方发布与媒体观点。",
  "OpenAI 近期发布了哪些产品能力？",
  "OpenAI 与 Anthropic 最近有哪些重要模型动态？",
  "比较官方与媒体对 Gemini 更新的不同观点。",
  "近期有哪些值得关注的开源大模型发布？请优先引用项目官方来源。",
  "RAG 检索与重排技术最近有哪些重要进展？",
  "Model Context Protocol（MCP）生态最近有哪些更新？",
  "AI 编程助手和 coding agent 最近有哪些产品变化？",
  "国产大模型近期有哪些重要发布？",
  "Anthropic 最近发布了哪些重要更新？",
  "最近模型安全与治理有哪些动态？",
  "过去 30 天多模态模型有哪些新进展？"
)

$results = foreach ($question in $questions) {
  $result = PostJson "/research/query" @{
    sessionId = $null
    question = $question
    filters = @{}
  }
  $coverage = [double]($result.diagnostics.citationCoverage ?? 0)
  [pscustomobject]@{
    Question = $question
    Status = [string]$result.answerStatus
    Provider = [string]$result.generationProvider
    LatencyMs = [long]$result.latencyMs
    Citations = @($result.citations).Count
    Coverage = $coverage
    EmbeddingMs = [long]($result.diagnostics.stageTimingsMs.embedding ?? 0)
    RetrievalMs = [long]($result.diagnostics.stageTimingsMs.hybridRetrieval ?? 0)
    RerankMs = [long]($result.diagnostics.stageTimingsMs.rerank ?? 0)
    GenerationMs = [long]($result.diagnostics.stageTimingsMs.generation ?? 0)
  }
}

$ordered = @($results.LatencyMs | Sort-Object)
$p95Index = [Math]::Max(0, [Math]::Ceiling($ordered.Count * 0.95) - 1)
$p95 = [long]$ordered[$p95Index]
$average = [long](($results | Measure-Object -Property LatencyMs -Average).Average)
$failed = @($results | Where-Object {
  $_.Status -ne "SUCCEEDED" -or
  $_.Provider -ne $expectedProvider -or
  $_.Citations -lt $MinimumCitations -or
  $_.Coverage -lt $MinimumCitationCoverage
})

$results | Format-Table Status, LatencyMs, Citations, Coverage, EmbeddingMs, RetrievalMs, RerankMs, GenerationMs -AutoSize
[pscustomobject]@{
  Provider = $expectedProvider
  Samples = $results.Count
  AverageMs = $average
  P95Ms = $p95
  MaximumMs = ($ordered | Select-Object -Last 1)
  QualityFailures = $failed.Count
  TargetMs = $P95TargetMs
} | Format-List

Assert ($failed.Count -eq 0) "RAG 固定问题集存在状态、Provider、引用数量或引用覆盖不达标"
Assert ($p95 -le $P95TargetMs) "RAG P95 ${p95}ms 超过目标 ${P95TargetMs}ms"
