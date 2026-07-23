[CmdletBinding()]
param(
  [string]$RunId = (Get-Date -Format "yyyyMMdd-HHmmss"),
  [switch]$SkipRag,
  [switch]$SkipWorker,
  [switch]$EnforceThresholds
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$artifactRoot = Join-Path $root "artifacts/load"
$runDir = Join-Path $artifactRoot $RunId
$composeFiles = @("-f", (Join-Path $root "compose.yaml"), "-f", (Join-Path $PSScriptRoot "compose.load.yaml"))

New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Invoke-Compose {
  param([string[]]$ComposeArguments)
  & docker compose @composeFiles @ComposeArguments | Out-Host
  $exitCode = $LASTEXITCODE
  return $exitCode
}

function EnvValue([string]$name) {
  $line = Get-Content (Join-Path $root ".env") |
    Where-Object { $_ -match "^$name=" } |
    Select-Object -First 1
  if ($line) { return $line.Substring($name.Length + 1) }
  return ""
}

function Write-InfrastructureSnapshot([string]$name) {
  $snapshotDir = Join-Path $runDir $name
  New-Item -ItemType Directory -Force -Path $snapshotDir | Out-Null
  Push-Location $root
  try {
    docker compose ps --format json | Set-Content (Join-Path $snapshotDir "compose-ps.jsonl")
    docker compose exec -T postgres sh -lc @'
psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -At -F "|" -c "
select datname,numbackends,xact_commit,xact_rollback,blks_read,blks_hit,
       temp_files,temp_bytes,deadlocks
from pg_stat_database where datname=current_database();
select status,count(*) from source.fetch_job
where created_at>=now()-interval '15 minutes' group by status order by status;
select poll_outcome,count(*) from source.fetch_job
where created_at>=now()-interval '15 minutes' group by poll_outcome order by poll_outcome;
select replay_status,count(*) from messaging.dead_letter_record
group by replay_status order by replay_status;"
'@ | Set-Content (Join-Path $snapshotDir "postgres.txt")
    $vhost = EnvValue "RABBITMQ_DEFAULT_VHOST"
    if ([string]::IsNullOrWhiteSpace($vhost)) { $vhost = "ai_hotspot" }
    docker compose exec -T rabbitmq rabbitmqctl -q list_queues -p $vhost `
      name messages_ready messages_unacknowledged consumers |
      Set-Content (Join-Path $snapshotDir "rabbitmq.txt")
    $redisPassword = EnvValue "REDIS_PASSWORD"
    if ([string]::IsNullOrWhiteSpace($redisPassword)) {
      throw "REDIS_PASSWORD is required in .env for the performance snapshot"
    }
    docker compose exec -T -e "REDISCLI_AUTH=$redisPassword" redis redis-cli INFO stats |
      Select-String -Pattern "total_commands_processed|instantaneous_ops_per_sec|keyspace_hits|keyspace_misses|total_error_replies" |
      Set-Content (Join-Path $snapshotDir "redis-stats.txt")
    docker compose exec -T -e "REDISCLI_AUTH=$redisPassword" redis redis-cli INFO memory |
      Select-String -Pattern "used_memory_human|maxmemory_human|mem_fragmentation_ratio" |
      Set-Content (Join-Path $snapshotDir "redis-memory.txt")
  } finally {
    Pop-Location
  }
}

Push-Location $root
try {
  docker compose ps | Set-Content (Join-Path $runDir "compose-before.txt")
  Write-InfrastructureSnapshot "before"

  $resourceJob = Start-Job -ScriptBlock {
    param($workspace)
    Set-Location $workspace
    while ($true) {
      $capturedAt = [DateTimeOffset]::Now.ToString("o")
      $containerIds = @(
        docker ps -q --filter "label=com.docker.compose.project=ai-hotspot"
      )
      if ($containerIds.Count -gt 0) {
        docker stats --no-stream --format "{{json .}}" @containerIds |
          ForEach-Object { "$capturedAt`t$_" }
      }
      Start-Sleep -Seconds 2
    }
  } -ArgumentList $root

  $publicExit = Invoke-Compose -ComposeArguments @(
    "run", "--rm", "--no-deps", "-e", "RUN_ID=$RunId", "k6", "run",
    "--summary-export=/artifacts/$RunId/public-summary.json",
    "--out=json=/artifacts/$RunId/public-metrics.jsonl",
    "/work/public-browse.js"
  )

  $ragExit = 0
  if (-not $SkipRag) {
    $ragExit = Invoke-Compose -ComposeArguments @(
      "run", "--rm", "--no-deps", "-e", "RUN_ID=$RunId", "k6", "run",
      "--summary-export=/artifacts/$RunId/rag-summary.json",
      "--out=json=/artifacts/$RunId/rag-metrics.jsonl",
      "/work/rag-small-concurrency.js"
    )
  }

  $workerExit = 0
  if (-not $SkipWorker) {
    & (Join-Path $PSScriptRoot "Test-Worker-Capacity.ps1") `
      -RunDirectory $runDir -Enforce:$EnforceThresholds
    $workerExit = $LASTEXITCODE
  }

  Stop-Job $resourceJob
  Receive-Job $resourceJob | Set-Content (Join-Path $runDir "docker-stats.jsonl")
  Remove-Job $resourceJob

  Write-InfrastructureSnapshot "after"
  docker compose logs --since 10m core-api web nginx ai-api worker |
    Select-String -Pattern "ERROR|Exception|Traceback|FATAL" |
    Set-Content (Join-Path $runDir "application-errors.txt")

  $meta = [ordered]@{
    runId = $RunId
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    commit = (git rev-parse HEAD)
    branch = (git branch --show-current)
    publicExitCode = $publicExit
    ragExitCode = $ragExit
    ragSkipped = [bool]$SkipRag
    workerExitCode = $workerExit
    workerSkipped = [bool]$SkipWorker
    target = "local Docker Compose through nginx"
  }
  $meta | ConvertTo-Json | Set-Content (Join-Path $runDir "run-meta.json")
  & (Join-Path $PSScriptRoot "Summarize-Perf-Run.ps1") -RunDirectory $runDir
  $meta | Format-List
  Write-Host "Artifacts: $runDir"

  if ($EnforceThresholds -and ($publicExit -ne 0 -or $ragExit -ne 0 -or $workerExit -ne 0)) {
    throw "One or more performance thresholds failed"
  }
} finally {
  if ($resourceJob -and $resourceJob.State -eq "Running") {
    Stop-Job $resourceJob
    Receive-Job $resourceJob | Set-Content (Join-Path $runDir "docker-stats.jsonl")
    Remove-Job $resourceJob
  }
  Pop-Location
}
