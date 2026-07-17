[CmdletBinding()]
param(
    [string]$EnvironmentFile = '.env',
    [string]$BaseUrl = 'http://127.0.0.1:8088/api/core/api/v1'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-DotEnvValue {
    param([string]$Name, [string]$Default)
    if (-not (Test-Path -LiteralPath $EnvironmentFile)) { return $Default }
    $line = Get-Content -LiteralPath $EnvironmentFile |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -Last 1
    if (-not $line) { return $Default }
    return ($line -split '=', 2)[1].Trim()
}

function Invoke-Sql {
    param([string]$Sql)
    $value = & docker compose exec -T postgres psql -U $script:postgresUser -d $script:postgresDb -At -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$Sql" }
    return ($value | Out-String).Trim()
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) { throw "$Message，期望=$Expected，实际=$Actual" }
}

function Assert-AtLeast {
    param([int]$Actual, [int]$Expected, [string]$Message)
    if ($Actual -lt $Expected) { throw "$Message，至少=$Expected，实际=$Actual" }
}

Push-Location $root
try {
    $script:postgresUser = Get-DotEnvValue 'POSTGRES_USER' 'ai_hotspot'
    $script:postgresDb = Get-DotEnvValue 'POSTGRES_DB' 'ai_hotspot'
    $minioUser = Get-DotEnvValue 'MINIO_ROOT_USER' 'ai_hotspot'
    $minioPassword = Get-DotEnvValue 'MINIO_ROOT_PASSWORD' 'ai_hotspot-local-password'

    $services = @(& docker compose ps --format json | ForEach-Object { $_ | ConvertFrom-Json })
    if ($LASTEXITCODE -ne 0) { throw '无法读取 Docker Compose 服务状态' }
    Assert-Equal $services.Count 10 'M4 标准 Compose 服务数量不正确'
    $notRunning = @($services | Where-Object State -ne 'running')
    if ($notRunning.Count -gt 0) { throw "存在未运行服务：$($notRunning.Service -join ', ')" }
    $unhealthy = @($services | Where-Object { $_.Health -and $_.Health -ne 'healthy' })
    if ($unhealthy.Count -gt 0) { throw "存在未健康服务：$($unhealthy.Service -join ', ')" }

    $migration = Invoke-Sql "select max(installed_rank) || '|' || max(version) from flyway_schema_history where success;"
    if ($migration -ne '7|007') { throw "Flyway 未到 V007：$migration" }

    $production = [int](Invoke-Sql "select count(*) from source.source_endpoint where catalog_kind='PRODUCTION' and status<>'ARCHIVED';")
    $active = [int](Invoke-Sql "select count(*) from source.source_endpoint where catalog_kind='PRODUCTION' and status='ACTIVE';")
    $healthy = [int](Invoke-Sql "select count(*) from source.source_endpoint where catalog_kind='PRODUCTION' and status='ACTIVE' and health_status='HEALTHY';")
    $paused = [int](Invoke-Sql "select count(*) from source.source_endpoint where catalog_kind='PRODUCTION' and status='PAUSED';")
    Assert-Equal $production 19 '正式信源目录数量不正确'
    Assert-Equal $active 17 '已启用正式信源数量不正确'
    Assert-AtLeast $healthy 17 '正式信源健康数量不足'
    Assert-Equal $paused 2 '因外部限制暂停的正式信源数量不正确'

    $connectorKinds = [int](Invoke-Sql "select count(distinct endpoint_type) from source.fetch_job j join source.source_endpoint e on e.id=j.endpoint_id where e.catalog_kind='PRODUCTION' and j.status='SUCCEEDED';")
    $artifacts = [int](Invoke-Sql "select count(*) from source.fetch_artifact a join source.source_endpoint e on e.id=a.endpoint_id where e.catalog_kind='PRODUCTION';")
    $rawEntries = [int](Invoke-Sql "select count(*) from source.raw_entry r join source.source_endpoint e on e.id=r.endpoint_id where e.catalog_kind='PRODUCTION';")
    $publicItems = [int](Invoke-Sql "select count(*) from content.content_item c join source.source_endpoint e on e.id=c.endpoint_id where e.catalog_kind='PRODUCTION' and c.publication_status='PUBLISHED' and c.visibility='PUBLIC' and c.admission_status='PASSED' and c.is_duplicate=false and c.fact_status<>'DEBUNKED';")
    Assert-AtLeast $connectorKinds 7 '经过真实成功采集的 Connector 类型不足'
    Assert-AtLeast $artifacts 17 '正式信源原始采集件不足'
    Assert-AtLeast $rawEntries 400 '正式信源原始条目不足'
    Assert-AtLeast $publicItems 400 '正式信源公开内容不足'

    $productionDead = [int](Invoke-Sql "select count(*) from source.fetch_job j join source.source_endpoint e on e.id=j.endpoint_id where e.catalog_kind='PRODUCTION' and j.status='DEAD_LETTERED';")
    Assert-Equal $productionDead 0 '正式信源存在死信任务'
    $scheduledTest = [int](Invoke-Sql "select count(*) from source.source_endpoint where catalog_kind='TEST' and status='ACTIVE' and next_fetch_at is not null;")
    Assert-Equal $scheduledTest 0 '测试信源仍可能进入周期调度'

    $xEndpoints = [int](Invoke-Sql "select count(*) from source.source_endpoint where endpoint_type='X' and status not in ('DRAFT','ARCHIVED');")
    $xJobs = [int](Invoke-Sql "select count(*) from source.fetch_job j join source.source_endpoint e on e.id=j.endpoint_id where e.endpoint_type='X';")
    Assert-Equal $xEndpoints 0 'X 预留入口被错误启用'
    Assert-Equal $xJobs 0 'X 预留入口被错误采集'

    $artifact = Invoke-Sql "select a.object_bucket || '|' || a.object_key from source.fetch_artifact a join source.source_endpoint e on e.id=a.endpoint_id where e.catalog_kind='PRODUCTION' order by a.fetched_at desc limit 1;"
    $artifactParts = $artifact -split '\|', 2
    if ($artifactParts.Count -ne 2) { throw '无法定位正式信源 MinIO 原始件' }
    & docker compose exec -T minio mc alias set m4verify http://127.0.0.1:9000 $minioUser $minioPassword | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MinIO 验收别名配置失败' }
    & docker compose exec -T minio mc stat "m4verify/$($artifactParts[0])/$($artifactParts[1])" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '正式信源 MinIO 原始件不可读取' }

    $queueRows = @(& docker compose exec -T rabbitmq rabbitmqctl list_queues -q -p ai_hotspot name messages_ready messages_unacknowledged)
    if ($LASTEXITCODE -ne 0) { throw '无法读取 RabbitMQ 队列状态' }
    foreach ($queue in @('q.crawl.worker', 'q.content.worker')) {
        $row = @($queueRows | Where-Object { $_ -match "^$([regex]::Escape($queue))\s+" }) | Select-Object -First 1
        if (-not $row -or $row -notmatch '\s+0\s+0$') { throw "主队列未清空：$row" }
    }

    $public = Invoke-RestMethod -Uri "$BaseUrl/public/contents?limit=20" -TimeoutSec 20
    Assert-Equal @($public.items).Count 20 '公开内容 API 未返回预期分页结果'
    $webResponse = Invoke-WebRequest -Uri 'http://127.0.0.1:8088/' -TimeoutSec 20
    Assert-Equal $webResponse.StatusCode 200 'Web 首页无法访问'

    [pscustomobject]@{
        ComposeServices = $services.Count
        Migration = $migration
        ProductionSources = $production
        ActiveHealthy = "$active/$healthy"
        PausedExternal = $paused
        SuccessfulConnectorKinds = $connectorKinds
        FetchArtifacts = $artifacts
        RawEntries = $rawEntries
        PublicItems = $publicItems
        ProductionDeadLetters = $productionDead
        ScheduledTestSources = $scheduledTest
        XActiveOrJobs = "$xEndpoints/$xJobs"
        MinioArtifact = 'READABLE'
        MainQueues = 'EMPTY'
        PublicApi = 'OK'
        Web = 'OK'
    } | Format-List
    Write-Host 'M4 Smoke 通过'
} finally {
    Pop-Location
}
