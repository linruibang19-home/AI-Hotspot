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
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -Last 1
    if (-not $line) { return $Default }
    return ($line -split '=', 2)[1].Trim()
}

function Invoke-Sql {
    param([string]$Sql)
    $value = & docker compose exec -T postgres psql -U $script:postgresUser -d $script:postgresDb -At -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $Sql" }
    return ($value | Out-String).Trim()
}

function Assert-AtLeast {
    param([int]$Actual, [int]$Expected, [string]$Message)
    if ($Actual -lt $Expected) { throw "$Message; expected-at-least=$Expected actual=$Actual" }
}

Push-Location $root
try {
    $script:postgresUser = Get-DotEnvValue 'POSTGRES_USER' 'ai_hotspot'
    $script:postgresDb = Get-DotEnvValue 'POSTGRES_DB' 'ai_hotspot'
    $adminEmail = Get-DotEnvValue 'BOOTSTRAP_ADMIN_EMAIL' 'admin@aihotspot.local'
    $adminPassword = Get-DotEnvValue 'BOOTSTRAP_ADMIN_PASSWORD' 'change-me-before-use'

    $services = @(& docker compose ps --format json | ForEach-Object { $_ | ConvertFrom-Json })
    if ($services.Count -ne 10) { throw "Unexpected Compose service count: $($services.Count)" }
    if (@($services | Where-Object State -ne 'running').Count -gt 0) { throw 'A service is not running' }
    if (@($services | Where-Object { $_.Health -and $_.Health -ne 'healthy' }).Count -gt 0) { throw 'A service is unhealthy' }

    $migration = Invoke-Sql "select max(version) from flyway_schema_history where success;"
    if ($migration -ne '008') { throw "Flyway is not at V008: $migration" }
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.model_run where status='SUCCEEDED';")) 2 'Insufficient model runs'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.event_cluster;")) 2 'Insufficient event clusters'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.content_event_relation;")) 3 'Insufficient event relations'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.content_item where processing_version='m5-v1' and fact_status='UNCONFIRMED';")) 1 'Missing unconfirmed sample'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.content_item where processing_version='m5-v1' and fact_status='CONFIRMED';")) 1 'Missing confirmed sample'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from content.content_item where is_duplicate and duplicate_of_id is not null;")) 1 'Missing duplicate sample'
    $leaks = [int](Invoke-Sql "select count(*) from content.content_item where publication_status='PUBLISHED' and visibility='PUBLIC' and (is_duplicate or fact_status='DEBUNKED');")
    if ($leaks -ne 0) { throw "Duplicate or debunked public leakage: $leaks" }
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from governance.audit_log where action in ('CONTENT_TAKEDOWN','CONTENT_RESTORE');")) 2 'Insufficient governance audits'
    Assert-AtLeast ([int](Invoke-Sql "select count(*) from governance.content_ticket where status='RESOLVED';")) 1 'Missing resolved ticket'

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "$BaseUrl/auth/csrf" -WebSession $session
    $headers = @{ 'X-XSRF-TOKEN' = $csrf.token }
    $loginBody = @{ email = $adminEmail; password = $adminPassword } | ConvertTo-Json
    Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -WebSession $session -Headers $headers -ContentType 'application/json' -Body $loginBody | Out-Null
    $contents = Invoke-RestMethod -Uri "$BaseUrl/admin/content?limit=5" -WebSession $session
    $events = @(Invoke-RestMethod -Uri "$BaseUrl/admin/content/events?limit=5" -WebSession $session)
    $tickets = @(Invoke-RestMethod -Uri "$BaseUrl/admin/content/tickets?limit=5" -WebSession $session)
    if (-not $contents.metrics -or $contents.items.Count -lt 1) { throw 'Content API returned no real data' }
    if ($events.Count -lt 1) { throw 'Event API returned no data' }
    if ($tickets.Count -lt 1) { throw 'Ticket API returned no data' }

    Write-Host 'M5 smoke passed'
    Write-Host "- Flyway: $migration"
    $modelRuns = Invoke-Sql "select count(*) from content.model_run;"
    $relationCount = Invoke-Sql "select count(*) from content.content_event_relation;"
    Write-Host "- Model runs: $modelRuns"
    Write-Host "- Events / Relations: $($events.Count) / $relationCount"
    Write-Host "- Admin queue / Tickets: $($contents.items.Count) / $($tickets.Count)"
} finally {
    Pop-Location
}
