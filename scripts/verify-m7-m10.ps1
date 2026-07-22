param([switch]$RequireBetaReady)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envLines = Get-Content (Join-Path $root ".env")
function EnvValue([string]$name) {
  $line = $envLines | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
  if ($line) { return $line.Substring($name.Length + 1) }
  return ""
}
function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }

$email = EnvValue "BOOTSTRAP_ADMIN_EMAIL"
$password = EnvValue "BOOTSTRAP_ADMIN_PASSWORD"
Assert (-not [string]::IsNullOrWhiteSpace($email)) "缺少 BOOTSTRAP_ADMIN_EMAIL"
Assert (-not [string]::IsNullOrWhiteSpace($password)) "缺少 BOOTSTRAP_ADMIN_PASSWORD"

$base = "http://127.0.0.1:8080/api/v1"
$coreReady = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
  try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:8080/actuator/health/readiness" -TimeoutSec 3
    if ($health.status -eq "UP") { $coreReady = $true; break }
  } catch { }
  Start-Sleep -Seconds 1
}
Assert $coreReady "Core API 未在 30 秒内就绪"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function CsrfHeaders {
  $csrf = Invoke-RestMethod -Uri "$base/auth/csrf" -WebSession $session -TimeoutSec 10
  return @{ $csrf.headerName = $csrf.token }
}
function PostJson([string]$path, $body) {
  return Invoke-RestMethod -Uri "$base$path" -Method Post -ContentType "application/json" `
    -Headers (CsrfHeaders) -WebSession $session -Body ($body | ConvertTo-Json -Depth 12) -TimeoutSec 120
}
function PutJson([string]$path, $body) {
  return Invoke-RestMethod -Uri "$base$path" -Method Put -ContentType "application/json" `
    -Headers (CsrfHeaders) -WebSession $session -Body ($body | ConvertTo-Json -Depth 12) -TimeoutSec 120
}

$login = PostJson "/auth/login" @{ email = $email; password = $password }
Assert ($login.roles -contains "ADMIN") "管理员登录或权限校验失败"

$configs = Invoke-RestMethod -Uri "$base/admin/ai/configs" -WebSession $session -TimeoutSec 20
Assert ($configs.Count -eq 5) "Provider 配置数量不是 5"
$smoke = PostJson "/admin/ai/smoke" @{}
$reindex = PostJson "/admin/ai/reindex" @{}
$evaluation = PostJson "/admin/ai/evaluations/run" @{}

$research = PostJson "/research/query" @{ sessionId = $null; question = "OpenAI 与 Anthropic 最近有哪些重要模型动态？"; filters = @{} }
Assert ($research.answerStatus -in @("SUCCEEDED", "NO_EVIDENCE")) "M7 研究查询状态异常"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$subscription = PostJson "/subscriptions" @{ name = "M8 自动验收 $stamp"; frequency = "DAILY"; timezone = "Asia/Shanghai"; sendTime = "09:00:00"; weekday = $null; topicSlugs = @(); maxItems = 5 }
$delivery = PostJson "/subscriptions/$($subscription.id)/send-now" @{}
Assert ($delivery.status -in @("SENT", "SKIPPED")) "M8 即时投递状态异常"
PutJson "/subscriptions/$($subscription.id)/status" @{ status = "PAUSED" } | Out-Null

$agent = PostJson "/agents/runs" @{ agentCode = "RESEARCH"; objective = "M9 自动验收：总结当前有权知识库中的 Agent 动态" }
$agentDetail = Invoke-RestMethod -Uri "$base/agents/runs/$($agent.id)" -WebSession $session -TimeoutSec 30
Assert ($agentDetail.status -in @("SUCCEEDED", "FAILED")) "M9 L0 Agent 未结束"

$controlled = PostJson "/agents/runs" @{ agentCode = "CRAWL_OPS"; objective = "M9 自动验收：只读诊断采集队列" }
$approvals = Invoke-RestMethod -Uri "$base/agents/approvals" -WebSession $session -TimeoutSec 20
$approval = $approvals | Where-Object { $_.agent_run_id -eq $controlled.id } | Select-Object -First 1
Assert ($null -ne $approval) "M9 L2 Agent 未产生审批"
PostJson "/agents/approvals/$($approval.id)" @{ approve = $true; note = "自动验收批准只读诊断" } | Out-Null
$controlledDetail = Invoke-RestMethod -Uri "$base/agents/runs/$($controlled.id)" -WebSession $session -TimeoutSec 30
Assert ($controlledDetail.status -eq "SUCCEEDED") "M9 L2 Agent 审批后未成功"

$readiness = Invoke-RestMethod -Uri "$base/admin/readiness" -WebSession $session -TimeoutSec 30
if ($RequireBetaReady) {
  Assert ($smoke.passed -eq $true) "真实 Provider 烟测未通过"
  Assert ($evaluation.passed -eq $true) "RAG 黄金集未通过"
  Assert ($readiness.status -eq "PASS") "M10 发布就绪门禁未通过"
}

[pscustomobject]@{
  AdminLogin = "PASS"
  ProviderSmoke = if ($smoke.passed) { "PASS" } else { "BLOCKED_MOCK" }
  IndexedDocuments = $reindex.indexedDocuments
  RagEvaluation = if ($evaluation.passed) { "PASS" } else { "BLOCKED_NO_REAL_INDEX" }
  Research = $research.answerStatus
  SubscriptionDelivery = $delivery.status
  AgentL0 = $agentDetail.status
  AgentL2Approval = $controlledDetail.status
  Readiness = $readiness.status
} | Format-List
