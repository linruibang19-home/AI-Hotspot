[CmdletBinding()]
param(
    [string]$EnvironmentFile = '.env.example'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Get-DotEnvValue {
    param([string]$Name, [string]$Default)
    $line = Get-Content -LiteralPath $EnvironmentFile |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -Last 1
    if (-not $line) { return $Default }
    return ($line -split '=', 2)[1].Trim()
}

Push-Location $root
try {
    $services = docker compose --env-file $EnvironmentFile ps --format json | ConvertFrom-Json
    $notRunning = @($services | Where-Object { $_.State -ne 'running' })
    if ($notRunning.Count -gt 0) {
        throw "存在未运行服务: $($notRunning.Service -join ', ')"
    }
    $unhealthy = @($services | Where-Object { $_.Health -and $_.Health -ne 'healthy' })
    if ($unhealthy.Count -gt 0) {
        throw "存在不健康服务: $($unhealthy.Service -join ', ')"
    }
    $postgresUser = Get-DotEnvValue 'POSTGRES_USER' 'ai_hotspot'
    $postgresDb = Get-DotEnvValue 'POSTGRES_DB' 'ai_hotspot'

    $outboxResponse = Invoke-RestMethod -Method Post `
        -Uri 'http://127.0.0.1:8088/api/core/api/v1/system/smoke/outbox' -TimeoutSec 10
    $eventId = $outboxResponse.eventId
    $status = ''
    $inboxCount = 0
    for ($attempt = 0; $attempt -lt 30 -and ($status -ne 'PUBLISHED' -or $inboxCount -ne 1); $attempt++) {
        Start-Sleep -Milliseconds 500
        $status = docker compose --env-file $EnvironmentFile exec -T postgres `
            psql -U $postgresUser -d $postgresDb -Atc `
            "SELECT status FROM messaging.outbox_event WHERE event_id = '$eventId';"
        $inboxCount = [int](docker compose --env-file $EnvironmentFile exec -T postgres `
            psql -U $postgresUser -d $postgresDb -Atc `
            "SELECT count(*) FROM messaging.consumer_inbox WHERE event_id = '$eventId';")
    }
    if ($status -ne 'PUBLISHED') { throw "Outbox 未发布，当前状态=$status" }
    if ($inboxCount -ne 1) { throw "Inbox 首次消费异常，count=$inboxCount" }

    $payload = docker compose --env-file $EnvironmentFile exec -T postgres `
        psql -U $postgresUser -d $postgresDb -Atc `
        "SELECT payload::text FROM messaging.outbox_event WHERE event_id = '$eventId';"
    $rabbitUser = Get-DotEnvValue 'RABBITMQ_DEFAULT_USER' 'ai_hotspot'
    $rabbitPassword = Get-DotEnvValue 'RABBITMQ_DEFAULT_PASS' 'ai_hotspot'
    $rabbitVhost = Get-DotEnvValue 'RABBITMQ_DEFAULT_VHOST' 'ai_hotspot'
    $credential = [Convert]::ToBase64String(
        [Text.Encoding]::ASCII.GetBytes("${rabbitUser}:${rabbitPassword}"))
    $headers = @{ Authorization = "Basic $credential"; 'Content-Type' = 'application/json' }
    $publishBody = @{
        properties = @{ delivery_mode = 2; content_type = 'application/json' }
        routing_key = 'content.processing.smoke'
        payload = $payload
        payload_encoding = 'string'
    } | ConvertTo-Json -Compress
    $duplicate = Invoke-RestMethod -Method Post `
        -Uri "http://127.0.0.1:15672/api/exchanges/$rabbitVhost/aihot.events/publish" `
        -Headers $headers -Body $publishBody
    if (-not $duplicate.routed) { throw '重复测试事件未进入 RabbitMQ' }
    Start-Sleep -Seconds 1
    $duplicateCount = [int](docker compose --env-file $EnvironmentFile exec -T postgres `
        psql -U $postgresUser -d $postgresDb -Atc `
        "SELECT count(*) FROM messaging.consumer_inbox WHERE event_id = '$eventId';")
    if ($duplicateCount -ne 1) { throw "Inbox 幂等失败，count=$duplicateCount" }

    $subject = "AI Hotspot M1 Smoke $eventId"
    $mailBody = @{
        recipient = 'm1-smoke@ai-hotspot.local'
        subject = $subject
    } | ConvertTo-Json -Compress
    $mailResponse = Invoke-RestMethod -Method Post `
        -Uri 'http://127.0.0.1:8088/api/core/api/v1/system/smoke/mail' `
        -ContentType 'application/json' -Body $mailBody -TimeoutSec 10
    Start-Sleep -Milliseconds 500
    $mailpit = Invoke-RestMethod -Uri 'http://127.0.0.1:8025/api/v1/messages' -TimeoutSec 10
    $matches = @($mailpit.messages | Where-Object { $_.Subject -eq $subject })
    if ($matches.Count -ne 1) { throw "Mailpit 未找到测试邮件，matches=$($matches.Count)" }

    [pscustomobject]@{
        EventId = $eventId
        OutboxStatus = $status
        InboxCountAfterDuplicate = $duplicateCount
        MailProvider = $mailResponse.provider
        MailpitSubject = $matches[0].Subject
    } | Format-List
} finally {
    Pop-Location
}
