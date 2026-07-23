[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$RunDirectory,
  [int]$EndpointCount = 10,
  [int]$TimeoutSeconds = 90,
  [switch]$Enforce
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$base = "http://127.0.0.1:8088/api/core/api/v1"

function EnvValue([string]$name) {
  $line = Get-Content (Join-Path $root ".env") |
    Where-Object { $_ -match "^$name=" } |
    Select-Object -First 1
  if ($line) { return $line.Substring($name.Length + 1) }
  return ""
}

function SqlLines([string]$sql) {
  return @(
    docker compose exec -T postgres sh -lc `
      "psql -U `"`$POSTGRES_USER`" -d `"`$POSTGRES_DB`" -P pager=off -At -c `"$sql`""
  )
}

function QueueDepth {
  $vhost = EnvValue "RABBITMQ_DEFAULT_VHOST"
  if ([string]::IsNullOrWhiteSpace($vhost)) { $vhost = "ai_hotspot" }
  $rows = docker compose exec -T rabbitmq rabbitmqctl -q list_queues -p $vhost `
    name messages_ready messages_unacknowledged consumers
  $row = $rows | Where-Object { $_ -match "^q\.crawl\.worker`t" } | Select-Object -First 1
  if (-not $row) {
    return [pscustomobject]@{ Ready = 0; Unacknowledged = 0; Consumers = 0 }
  }
  $parts = $row -split "`t"
  return [pscustomobject]@{
    Ready = [int]$parts[1]
    Unacknowledged = [int]$parts[2]
    Consumers = [int]$parts[3]
  }
}

$email = EnvValue "BOOTSTRAP_ADMIN_EMAIL"
$password = EnvValue "BOOTSTRAP_ADMIN_PASSWORD"
if ([string]::IsNullOrWhiteSpace($email) -or [string]::IsNullOrWhiteSpace($password)) {
  throw "Worker capacity test requires bootstrap admin credentials"
}

$endpointSql = @"
select id from source.source_endpoint
where status='ACTIVE' and health_status='HEALTHY'
  and endpoint_type in ('RSS','ATOM')
  and url not ilike '%github.com%'
  and url not ilike '%api.github.com%'
  and last_success_at >= now() - interval '24 hours'
order by last_success_at desc
limit $EndpointCount
"@
$endpointIds = SqlLines $endpointSql
if ($endpointIds.Count -lt $EndpointCount) {
  throw "Only $($endpointIds.Count) eligible healthy endpoints were found"
}

$pendingBefore = [int](SqlLines "select count(*) from messaging.dead_letter_record where replay_status='PENDING'")
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function CsrfHeaders {
  $csrf = Invoke-RestMethod -Uri "$base/auth/csrf" -WebSession $session -TimeoutSec 10
  return @{ $csrf.headerName = $csrf.token }
}

$loginBody = @{ email = $email; password = $password } | ConvertTo-Json
Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" `
  -Headers (CsrfHeaders) -WebSession $session -Body $loginBody -TimeoutSec 20 | Out-Null

$started = [DateTimeOffset]::Now
$jobs = [ordered]@{}
foreach ($endpointId in $endpointIds) {
  $job = Invoke-RestMethod -Uri "$base/admin/fetch-jobs/endpoints/$endpointId" `
    -Method Post -Headers (CsrfHeaders) -WebSession $session -TimeoutSec 20
  $jobs[[string]$job.id] = [string]$job.status
}
$enqueueCompleted = [DateTimeOffset]::Now

$maxReady = 0
$maxUnacknowledged = 0
$consumers = 0
$deadline = $started.AddSeconds($TimeoutSeconds)
do {
  $queue = QueueDepth
  $maxReady = [Math]::Max($maxReady, $queue.Ready)
  $maxUnacknowledged = [Math]::Max($maxUnacknowledged, $queue.Unacknowledged)
  $consumers = [Math]::Max($consumers, $queue.Consumers)
  foreach ($jobId in @($jobs.Keys)) {
    if ($jobs[$jobId] -in @("SUCCEEDED", "FAILED", "CANCELLED")) { continue }
    $job = Invoke-RestMethod -Uri "$base/admin/fetch-jobs/$jobId" `
      -WebSession $session -TimeoutSec 20
    $jobs[$jobId] = [string]$job.status
  }
  $remaining = @($jobs.Values | Where-Object { $_ -notin @("SUCCEEDED", "FAILED", "CANCELLED") }).Count
  if ($remaining -gt 0) { Start-Sleep -Milliseconds 250 }
} while ($remaining -gt 0 -and [DateTimeOffset]::Now -lt $deadline)

$completed = [DateTimeOffset]::Now
$pendingAfter = [int](SqlLines "select count(*) from messaging.dead_letter_record where replay_status='PENDING'")
$succeeded = @($jobs.Values | Where-Object { $_ -eq "SUCCEEDED" }).Count
$failed = @($jobs.Values | Where-Object { $_ -eq "FAILED" }).Count
$unfinished = @($jobs.Values | Where-Object { $_ -notin @("SUCCEEDED", "FAILED", "CANCELLED") }).Count
$durationSeconds = [Math]::Round(($completed - $started).TotalSeconds, 3)

$summary = [ordered]@{
  endpoints = $EndpointCount
  enqueueMs = [Math]::Round(($enqueueCompleted - $started).TotalMilliseconds)
  durationSeconds = $durationSeconds
  throughputJobsPerSecond = if ($durationSeconds -gt 0) {
    [Math]::Round($succeeded / $durationSeconds, 3)
  } else { 0 }
  succeeded = $succeeded
  failed = $failed
  unfinished = $unfinished
  maxQueueReady = $maxReady
  maxQueueUnacknowledged = $maxUnacknowledged
  consumers = $consumers
  pendingDeadLettersBefore = $pendingBefore
  pendingDeadLettersAfter = $pendingAfter
  jobs = $jobs
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $RunDirectory "worker-summary.json")
$summary | Format-List

if ($Enforce -and (
  $succeeded -ne $EndpointCount -or
  $failed -ne 0 -or
  $unfinished -ne 0 -or
  $pendingAfter -gt $pendingBefore
)) {
  throw "Worker capacity thresholds failed"
}
