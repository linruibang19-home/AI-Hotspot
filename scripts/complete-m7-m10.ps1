param(
  [int]$BatchSize = 250,
  [int]$ProcessingTimeoutMinutes = 90
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$envPath = Join-Path $root ".env"

function Read-Env {
  $script:envLines = Get-Content -LiteralPath $envPath
}
function EnvValue([string]$name) {
  $line = $script:envLines | Where-Object { $_ -match "^$([regex]::Escape($name))=" } | Select-Object -First 1
  if ($line) { return $line.Substring($name.Length + 1).Trim() }
  return ""
}
function Assert([bool]$condition, [string]$message) {
  if (-not $condition) { throw $message }
}

Read-Env
$generationProvider = EnvValue "GENERATION_PROVIDER"
$embeddingProvider = EnvValue "EMBEDDING_PROVIDER"
$rerankProvider = EnvValue "RERANK_PROVIDER"
Assert ($generationProvider -eq "openai-compatible") "GENERATION_PROVIDER 必须为 openai-compatible"
Assert ($embeddingProvider -eq "remote") "EMBEDDING_PROVIDER 必须为 remote"
Assert ($rerankProvider -eq "remote") "RERANK_PROVIDER 必须为 remote"
Assert (-not [string]::IsNullOrWhiteSpace((EnvValue "GENERATION_API_KEY"))) "GENERATION_API_KEY 未配置"
Assert (-not [string]::IsNullOrWhiteSpace((EnvValue "EMBEDDING_API_KEY"))) "EMBEDDING_API_KEY 未配置"
Assert (-not [string]::IsNullOrWhiteSpace((EnvValue "RERANK_API_KEY"))) "RERANK_API_KEY 未配置"

Push-Location $root
try {
  docker compose up -d --no-build --force-recreate ai-api worker | Out-Host
} finally {
  Pop-Location
}

$providersReady = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
  try {
    $providers = Invoke-RestMethod -Uri "http://127.0.0.1:8000/api/v1/providers" -TimeoutSec 5
    if ($providers.generation.provider -notin @("mock","test","fixture") -and
        $providers.embedding.provider -notin @("mock","test","fixture") -and
        $providers.rerank.provider -notin @("mock","test","fixture")) {
      $providersReady = $true
      break
    }
  } catch { }
  Start-Sleep -Seconds 2
}
Assert $providersReady "AI API 未在两分钟内以三个真实 Provider 就绪"

$base = "http://127.0.0.1:8080/api/v1"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function CsrfHeaders {
  $csrf = Invoke-RestMethod -Uri "$base/auth/csrf" -WebSession $session -TimeoutSec 10
  return @{ $csrf.headerName = $csrf.token }
}
function PostJson([string]$path, $body) {
  Invoke-RestMethod -Uri "$base$path" -Method Post -ContentType "application/json" `
    -Headers (CsrfHeaders) -WebSession $session -Body ($body | ConvertTo-Json -Depth 12) -TimeoutSec 180
}
function PutJson([string]$path, $body) {
  Invoke-RestMethod -Uri "$base$path" -Method Put -ContentType "application/json" `
    -Headers (CsrfHeaders) -WebSession $session -Body ($body | ConvertTo-Json -Depth 12) -TimeoutSec 180
}

$email = EnvValue "BOOTSTRAP_ADMIN_EMAIL"
$password = EnvValue "BOOTSTRAP_ADMIN_PASSWORD"
$login = PostJson "/auth/login" @{ email = $email; password = $password }
Assert ($login.roles -contains "ADMIN") "管理员登录失败"

$smoke = PostJson "/admin/ai/smoke" @{}
Assert ($smoke.passed -eq $true) "真实 Provider 烟测失败；未修改治理配置，也未启动历史回填"

$generationModel = EnvValue "GENERATION_MODEL"
$generationBase = EnvValue "GENERATION_BASE_URL"
$embeddingModel = EnvValue "EMBEDDING_MODEL"
$embeddingBase = EnvValue "EMBEDDING_BASE_URL"
$rerankModel = EnvValue "RERANK_MODEL"
$rerankBase = EnvValue "RERANK_BASE_URL"
$generationTasks = @("CONTENT_ANALYSIS", "RAG_GENERATION", "AGENT")
foreach ($task in $generationTasks) {
  PutJson "/admin/ai/configs" @{ taskType=$task; providerName="deepseek"; modelName=$generationModel; baseUrl=$generationBase; credentialRef="GENERATION_API_KEY"; status="ACTIVE"; timeoutMs=120000; parametersJson="{}" } | Out-Null
}
PutJson "/admin/ai/configs" @{ taskType="EMBEDDING"; providerName="siliconflow"; modelName=$embeddingModel; baseUrl=$embeddingBase; credentialRef="EMBEDDING_API_KEY"; status="ACTIVE"; timeoutMs=120000; parametersJson="{}" } | Out-Null
PutJson "/admin/ai/configs" @{ taskType="RERANK"; providerName="siliconflow"; modelName=$rerankModel; baseUrl=$rerankBase; credentialRef="RERANK_API_KEY"; status="ACTIVE"; timeoutMs=120000; parametersJson="{}" } | Out-Null

$queuedTotal = 0
do {
  $batch = PostJson "/admin/ai/reprocess?limit=$BatchSize" @{}
  $queuedTotal += [int]$batch.queued
} while ([int]$batch.queued -gt 0)

$deadline = (Get-Date).AddMinutes($ProcessingTimeoutMinutes)
do {
  $status = Invoke-RestMethod -Uri "$base/admin/ai/reprocess/status" -WebSession $session -TimeoutSec 20
  if ([int]$status.remaining_mock -eq 0 -and [int]$status.processing -eq 0) { break }
  if ((Get-Date) -ge $deadline) { throw "真实内容处理等待超时：remaining_mock=$($status.remaining_mock), processing=$($status.processing)" }
  Start-Sleep -Seconds 10
} while ($true)

$indexedTotal = 0
do {
  $index = PostJson "/admin/ai/reindex" @{}
  $indexedTotal += [int]$index.indexedDocuments
} while ([int]$index.discoveredDocuments -gt 0)

$research = PostJson "/research/query" @{ sessionId=$null; question="最近有哪些重要的 AI 模型发布、智能体和 RAG 技术动态？"; filters=@{} }
Assert ($research.status -eq "SUCCEEDED") "真实 RAG 未产生有引用的答案"
$evaluation = PostJson "/admin/ai/evaluations/run" @{}
Assert ($evaluation.passed -eq $true) "RAG 黄金集评测未通过"
$readiness = Invoke-RestMethod -Uri "$base/admin/readiness" -WebSession $session -TimeoutSec 30
Assert ($readiness.status -eq "PASS") "M10 发布就绪门禁未通过"

[pscustomobject]@{
  ProviderSmoke = "PASS"
  ReprocessQueued = $queuedTotal
  RealAdmitted = $status.real_admitted
  PublicContent = $status.public_content
  IndexedDocuments = $indexedTotal
  RagResearch = $research.status
  RagEvaluation = "PASS"
  M10Readiness = "PASS"
} | Format-List
