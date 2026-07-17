[CmdletBinding()]
param(
    [string]$EnvironmentFile = '.env',
    [string]$BaseUrl = 'http://127.0.0.1:8088/api/core/api/v1'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$rabbitStopped = $false

function Get-DotEnvValue {
    param([string]$Name, [string]$Default)
    if (-not (Test-Path -LiteralPath $EnvironmentFile)) { return $Default }
    $line = Get-Content -LiteralPath $EnvironmentFile |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -Last 1
    if (-not $line) { return $Default }
    return ($line -split '=', 2)[1].Trim()
}

function New-CsrfSession {
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Invoke-RestMethod -Uri "$BaseUrl/auth/csrf" -WebSession $session -TimeoutSec 10
    return [pscustomobject]@{ Session = $session; Headers = @{ $csrf.headerName = $csrf.token } }
}

function Invoke-JsonPost {
    param([string]$Uri, $Session, [hashtable]$Headers, [hashtable]$Body = @{})
    Invoke-RestMethod -Method Post -Uri $Uri -WebSession $Session -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' -Body ($Body | ConvertTo-Json -Depth 10) -TimeoutSec 30
}

function Invoke-Sql {
    param([string]$Sql)
    $value = & docker compose exec -T postgres psql -U ai_hotspot -d ai_hotspot -At -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL 执行失败：$Sql" }
    return ($value | Out-String).Trim()
}

function Wait-FetchJob {
    param([string]$JobId, [int]$TimeoutSeconds = 120, [string[]]$Terminal = @('SUCCEEDED', 'DEAD_LETTERED'))
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $job = Invoke-RestMethod -Uri "$BaseUrl/admin/fetch-jobs/$JobId" -WebSession $admin.Session -TimeoutSec 10
        if ($Terminal -contains $job.status) { return $job }
        Start-Sleep -Milliseconds 750
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "采集任务 $JobId 在 $TimeoutSeconds 秒内未结束，最后状态=$($job.status)"
}

function New-ActiveFeed {
    param([hashtable]$Definition, [string]$Stamp)
    $slug = "$($Definition.slug)-m3-$Stamp"
    $separator = if ($Definition.endpointUrl.Contains('?')) { '&' } else { '?' }
    $endpointUrl = "$($Definition.endpointUrl)${separator}m3-run=$Stamp"
    $created = Invoke-JsonPost "$BaseUrl/admin/sources" $admin.Session $admin.Headers @{
        name = "$($Definition.name) M3 $Stamp"; slug = $slug; entityType = $Definition.entityType
        countryCode = $Definition.countryCode; officialLevel = 'OFFICIAL'; authorityScore = 88
        websiteUrl = $Definition.websiteUrl; endpointName = $Definition.endpointName
        endpointUrl = $endpointUrl; endpointType = $Definition.endpointType; language = 'en'
        pollingIntervalSeconds = 3600; displayPolicy = 'SUMMARY_ONLY'; indexPolicy = 'PUBLIC_RAG'
        config = @{ autoPublish = $true; maxItems = 5; maxResponseBytes = 2097152; timeoutSeconds = 25; relevanceScore = 82; qualityScore = 78; testRun = $true }
    }
    $probe = Invoke-JsonPost "$BaseUrl/admin/sources/endpoints/$($created.endpointId)/probe" $admin.Session $admin.Headers @{ version = 0 }
    if ($probe.healthStatus -notin @('HEALTHY', 'WARNING')) { throw "$($Definition.name) 探测失败：$($probe.message)" }
    $active = Invoke-JsonPost "$BaseUrl/admin/sources/endpoints/$($created.endpointId)/activate" $admin.Session $admin.Headers @{ version = $probe.version }
    if ($active.status -ne 'ACTIVE') { throw "$($Definition.name) 未启用" }
    return [pscustomobject]@{ SourceId = $created.sourceId; EndpointId = $created.endpointId; Name = $Definition.name }
}

Push-Location $root
try {
    $adminEmail = Get-DotEnvValue 'BOOTSTRAP_ADMIN_EMAIL' 'admin@aihotspot.local'
    $adminPassword = Get-DotEnvValue 'BOOTSTRAP_ADMIN_PASSWORD' 'change-me-before-use'
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
    $admin = New-CsrfSession
    $login = Invoke-JsonPost "$BaseUrl/auth/login" $admin.Session $admin.Headers @{ email = $adminEmail; password = $adminPassword }
    if ($login.roles -notcontains 'ADMIN') { throw 'M3 Smoke 需要 ADMIN' }

    $definitions = @(
        @{ name = 'NVIDIA Developer Blog'; slug = 'nvidia-developer-blog'; entityType = 'COMPANY'; countryCode = 'US'; websiteUrl = 'https://developer.nvidia.com/blog/'; endpointName = 'NVIDIA Developer Atom'; endpointUrl = 'https://developer.nvidia.com/blog/feed/'; endpointType = 'ATOM' },
        @{ name = 'Microsoft Research'; slug = 'microsoft-research'; entityType = 'RESEARCH'; countryCode = 'US'; websiteUrl = 'https://www.microsoft.com/en-us/research/'; endpointName = 'Microsoft Research RSS'; endpointUrl = 'https://www.microsoft.com/en-us/research/feed/'; endpointType = 'RSS' },
        @{ name = 'AWS Machine Learning Blog'; slug = 'aws-machine-learning'; entityType = 'COMPANY'; countryCode = 'US'; websiteUrl = 'https://aws.amazon.com/blogs/machine-learning/'; endpointName = 'AWS Machine Learning RSS'; endpointUrl = 'https://aws.amazon.com/blogs/machine-learning/feed/'; endpointType = 'RSS' }
    )
    $feeds = @($definitions | ForEach-Object { New-ActiveFeed $_ $stamp })
    $jobs = @()
    foreach ($feed in $feeds) {
        $job = Invoke-JsonPost "$BaseUrl/admin/fetch-jobs/endpoints/$($feed.EndpointId)" $admin.Session $admin.Headers
        $finished = Wait-FetchJob $job.id 180
        if ($finished.status -ne 'SUCCEEDED') {
            throw "$($feed.Name) 未完成真实采集：$($finished | ConvertTo-Json -Compress)"
        }
        $jobs += $finished
    }

    $endpointIds = ($feeds.EndpointId | ForEach-Object { "'$_'" }) -join ','
    $rawBefore = [int](Invoke-Sql "select count(*) from source.raw_entry where endpoint_id in ($endpointIds);")
    $contentBefore = [int](Invoke-Sql "select count(*) from content.content_item where endpoint_id in ($endpointIds);")
    $artifactCount = [int](Invoke-Sql "select count(*) from source.fetch_artifact where endpoint_id in ($endpointIds);")
    if ($rawBefore -lt 15 -or $contentBefore -lt 15) { throw "3 个真实入口未各产生 5 条内容：raw=$rawBefore, content=$contentBefore" }
    if ($artifactCount -lt 3) { throw "3 个真实入口的 FetchArtifact 不完整：$artifactCount" }
    foreach ($feed in $feeds) {
        $job = Invoke-JsonPost "$BaseUrl/admin/fetch-jobs/endpoints/$($feed.EndpointId)" $admin.Session $admin.Headers
        $finished = Wait-FetchJob $job.id 180
        if ($finished.status -ne 'SUCCEEDED') { throw "$($feed.Name) 重复采集失败" }
    }
    $rawAfter = [int](Invoke-Sql "select count(*) from source.raw_entry where endpoint_id in ($endpointIds);")
    $contentAfter = [int](Invoke-Sql "select count(*) from content.content_item where endpoint_id in ($endpointIds);")
    if ($rawAfter -ne $rawBefore -or $contentAfter -ne $contentBefore) { throw "重复采集产生了重复内容：raw $rawBefore->$rawAfter, content $contentBefore->$contentAfter" }
    $conditionalCursorCount = [int](Invoke-Sql "select count(*) from source.source_endpoint where id in ($endpointIds) and (last_etag is not null or last_modified is not null);")
    $notModifiedCount = [int](Invoke-Sql "select count(*) from source.fetch_job where endpoint_id in ($endpointIds) and http_status=304;")
    if ($conditionalCursorCount -lt 1 -or $notModifiedCount -lt 1) {
        throw "条件请求未生效：cursor=$conditionalCursorCount, http304=$notModifiedCount"
    }

    $artifactKey = Invoke-Sql "select object_key from source.fetch_artifact where endpoint_id = '$($feeds[0].EndpointId)' order by fetched_at desc limit 1;"
    $artifactBucket = Invoke-Sql "select object_bucket from source.fetch_artifact where endpoint_id = '$($feeds[0].EndpointId)' order by fetched_at desc limit 1;"
    $minioUser = Get-DotEnvValue 'MINIO_ROOT_USER' 'ai_hotspot'
    $minioPassword = Get-DotEnvValue 'MINIO_ROOT_PASSWORD' 'ai_hotspot-local-password'
    & docker compose exec -T minio mc alias set verify http://127.0.0.1:9000 $minioUser $minioPassword | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MinIO 验收别名配置失败' }
    & docker compose exec -T minio mc stat "verify/$artifactBucket/$artifactKey" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MinIO 原件不可读取' }

    & docker compose stop rabbitmq | Out-Null
    $rabbitStopped = $true
    $pausedJob = Invoke-JsonPost "http://127.0.0.1:8080/api/v1/admin/fetch-jobs/endpoints/$($feeds[0].EndpointId)" $admin.Session $admin.Headers
    Start-Sleep -Seconds 2
    $queuedStatus = Invoke-Sql "select status from source.fetch_job where id = '$($pausedJob.id)';"
    if ($queuedStatus -ne 'QUEUED') { throw "RabbitMQ 暂停时任务应保留为 QUEUED，实际=$queuedStatus" }
    & docker compose start rabbitmq | Out-Null
    $rabbitStopped = $false
    $recovered = Wait-FetchJob $pausedJob.id 180
    if ($recovered.status -ne 'SUCCEEDED') { throw 'RabbitMQ 恢复后任务未最终成功' }

    $bad = New-ActiveFeed @{ name = 'M3 Invalid Feed'; slug = 'm3-invalid-feed'; entityType = 'OTHER'; countryCode = 'US'; websiteUrl = 'https://example.com/'; endpointName = 'HTML As RSS'; endpointUrl = "https://example.com/?m3=$stamp"; endpointType = 'RSS' } $stamp
    $badJob = Invoke-JsonPost "$BaseUrl/admin/fetch-jobs/endpoints/$($bad.EndpointId)" $admin.Session $admin.Headers
    $dead = Wait-FetchJob $badJob.id 60
    if ($dead.status -ne 'DEAD_LETTERED') { throw '确定性格式错误未进入 DEAD_LETTERED' }
    $deadLetters = Invoke-RestMethod -Uri "$BaseUrl/admin/dead-letters?status=PENDING&limit=100" -WebSession $admin.Session -TimeoutSec 10
    $record = @($deadLetters | Where-Object { $_.aggregateId -eq $badJob.id }) | Select-Object -First 1
    if (-not $record) { throw '数据库死信记录缺失' }
    $null = Invoke-JsonPost "$BaseUrl/admin/dead-letters/$($record.id)/replay" $admin.Session $admin.Headers
    Start-Sleep -Seconds 1
    $replayStatus = Invoke-Sql "select replay_status from messaging.dead_letter_record where id = '$($record.id)';"
    if ($replayStatus -ne 'REPLAYED') { throw '死信未标记为 REPLAYED' }
    $replayAuditCount = [int](Invoke-Sql "select count(*) from governance.audit_log where action='DEAD_LETTER_REPLAYED' and target_id='$($record.id)';")
    if ($replayAuditCount -lt 1) { throw '死信回放审计记录缺失' }
    $queueRows = & docker compose exec -T rabbitmq rabbitmqctl list_queues -q -p ai_hotspot name messages
    if ($LASTEXITCODE -ne 0 -or -not ($queueRows -match 'q\.crawl\.worker\.dlq\s+[1-9][0-9]*')) {
        throw 'RabbitMQ 采集死信队列没有保留失败消息'
    }

    $public = Invoke-RestMethod -Uri "$BaseUrl/public/contents?limit=50" -TimeoutSec 20
    $publicDb = [int](Invoke-Sql "select count(*) from content.content_item where publication_status='PUBLISHED' and visibility='PUBLIC' and admission_status='PASSED' and is_duplicate=false and fact_status<>'DEBUNKED';")
    if (@($public.items).Count -ne [Math]::Min($publicDb, 50)) { throw "公开 API 与数据库准入池数量不一致" }
    if (@($public.items).Count -gt 0) {
        $publicIds = (@($public.items).id | ForEach-Object { "'$_'" }) -join ','
        $leakCount = [int](Invoke-Sql "select count(*) from content.content_item where id in ($publicIds) and not (publication_status='PUBLISHED' and visibility='PUBLIC' and admission_status='PASSED' and is_duplicate=false and fact_status<>'DEBUNKED');")
        if ($leakCount -ne 0) { throw "公开 API 泄露了 $leakCount 条不符合准入条件的内容" }
    }

    [pscustomobject]@{
        RealFeeds = $feeds.Count; SuccessfulInitialJobs = @($jobs | Where-Object status -eq 'SUCCEEDED').Count
        RawEntries = $rawAfter; ContentItems = $contentAfter; DuplicateDelta = $contentAfter - $contentBefore
        RabbitRecovery = $recovered.status; DeadLetter = $dead.status; Replay = $replayStatus
        ConditionalCursors = $conditionalCursorCount; Http304 = $notModifiedCount
        PublicItems = @($public.items).Count; PublicLeakCount = 0; MinioArtifact = 'READABLE'
    } | Format-List
} finally {
    if ($rabbitStopped) { & docker compose start rabbitmq | Out-Null }
    & docker compose exec -T postgres psql -U ai_hotspot -d ai_hotspot -c `
        "update source.source_endpoint set status='ARCHIVED', next_fetch_at=null, updated_at=now() where catalog_kind='TEST' and status<>'ARCHIVED';" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning 'M3 测试信源自动清理失败' }
    Pop-Location
}
