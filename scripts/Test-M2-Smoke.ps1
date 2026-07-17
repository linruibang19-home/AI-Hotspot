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

function New-CsrfSession {
    param([string]$ApiBase)
    $session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $csrf = Invoke-RestMethod -Uri "$ApiBase/auth/csrf" -WebSession $session -TimeoutSec 10
    return [pscustomobject]@{
        Session = $session
        Headers = @{ $csrf.headerName = $csrf.token }
    }
}

function Invoke-JsonPost {
    param(
        [string]$Uri,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [hashtable]$Headers,
        [hashtable]$Body
    )
    return Invoke-RestMethod -Method Post -Uri $Uri -WebSession $Session -Headers $Headers `
        -ContentType 'application/json; charset=utf-8' -Body ($Body | ConvertTo-Json -Depth 8) -TimeoutSec 30
}

Push-Location $root
try {
    $adminEmail = Get-DotEnvValue 'BOOTSTRAP_ADMIN_EMAIL' 'admin@aihotspot.local'
    $adminPassword = Get-DotEnvValue 'BOOTSTRAP_ADMIN_PASSWORD' 'change-me-before-use'
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

    $admin = New-CsrfSession $BaseUrl
    $login = Invoke-JsonPost "$BaseUrl/auth/login" $admin.Session $admin.Headers @{
        email = $adminEmail
        password = $adminPassword
    }
    if ($login.roles -notcontains 'ADMIN') { throw '引导管理员登录后没有 ADMIN 角色' }

    $invite = Invoke-JsonPost "$BaseUrl/admin/invitations" $admin.Session $admin.Headers @{
        defaultRole = 'USER'
        maxUses = 1
        expiresAt = [DateTimeOffset]::UtcNow.AddHours(2).ToString('o')
    }
    if (-not $invite.token) { throw '邀请码创建后没有返回一次性明文 token' }

    $user = New-CsrfSession $BaseUrl
    $userEmail = "m2-smoke-$stamp@aihotspot.local"
    $null = Invoke-JsonPost "$BaseUrl/auth/invitations/$($invite.token)/register" $user.Session $user.Headers @{
        email = $userEmail
        displayName = 'M2 自动验收用户'
        password = 'M2-smoke-password!'
    }
    $userLogin = Invoke-JsonPost "$BaseUrl/auth/login" $user.Session $user.Headers @{
        email = $userEmail
        password = 'M2-smoke-password!'
    }
    if ($userLogin.roles -notcontains 'USER') { throw '邀请注册用户没有 USER 角色' }

    $denied = Invoke-WebRequest -Uri "$BaseUrl/admin/sources" -WebSession $user.Session `
        -SkipHttpErrorCheck -TimeoutSec 10
    if ($denied.StatusCode -ne 403) { throw "普通用户访问管理信源应返回 403，实际为 $($denied.StatusCode)" }

    $blockedBody = @{
        name = "Blocked M2 $stamp"; slug = "blocked-m2-$stamp"; entityType = 'MEDIA'
        countryCode = 'CN'; officialLevel = 'COMMUNITY'; authorityScore = 10
        endpointName = 'Blocked localhost'; endpointUrl = 'http://127.0.0.1/feed'
        endpointType = 'RSS'; language = 'zh-CN'; pollingIntervalSeconds = 3600
        displayPolicy = 'SUMMARY_ONLY'; indexPolicy = 'NO_INDEX'; config = @{ testRun = $true }
    }
    $blocked = Invoke-WebRequest -Method Post -Uri "$BaseUrl/admin/sources" -WebSession $admin.Session `
        -Headers $admin.Headers -ContentType 'application/json; charset=utf-8' `
        -Body ($blockedBody | ConvertTo-Json -Depth 8) -SkipHttpErrorCheck -TimeoutSec 10
    if ($blocked.StatusCode -ne 422) { throw "内网/环回 URL 应返回 422，实际为 $($blocked.StatusCode)" }

    $created = Invoke-JsonPost "$BaseUrl/admin/sources" $admin.Session $admin.Headers @{
        name = "Example M2 $stamp"; slug = "example-m2-$stamp"; entityType = 'MEDIA'
        countryCode = 'US'; officialLevel = 'OFFICIAL'; authorityScore = 80
        websiteUrl = 'https://example.com'; endpointName = 'Example Website'
        endpointUrl = "https://example.com/?m2-smoke=$stamp"; endpointType = 'WEBSITE'; language = 'en'
        pollingIntervalSeconds = 3600; displayPolicy = 'SUMMARY_ONLY'
        indexPolicy = 'PUBLIC_RAG'; config = @{ testRun = $true }
    }
    $probe = Invoke-JsonPost "$BaseUrl/admin/sources/endpoints/$($created.endpointId)/probe" `
        $admin.Session $admin.Headers @{ version = 0 }
    if ($probe.healthStatus -notin @('HEALTHY', 'WARNING')) {
        throw "试抓取未通过，healthStatus=$($probe.healthStatus), message=$($probe.message)"
    }
    $activated = Invoke-JsonPost "$BaseUrl/admin/sources/endpoints/$($created.endpointId)/activate" `
        $admin.Session $admin.Headers @{ version = $probe.version }
    if ($activated.status -ne 'ACTIVE') { throw "入口未成功启用，status=$($activated.status)" }

    $detail = Invoke-RestMethod -Uri "$BaseUrl/admin/sources/$($created.sourceId)" `
        -WebSession $admin.Session -TimeoutSec 10
    if ($detail.source.status -ne 'ACTIVE' -or @($detail.endpoints).Count -ne 1) {
        throw '信源详情未同步返回 ACTIVE 主体和已创建 Endpoint'
    }

    $list = Invoke-RestMethod -Uri "$BaseUrl/admin/sources?limit=200" `
        -WebSession $admin.Session -TimeoutSec 10
    $listed = @($list.items | Where-Object { $_.endpointId -eq $created.endpointId })
    if ($listed.Count -ne 1) { throw '信源列表无法检索到刚创建的入口' }

    $audits = Invoke-RestMethod `
        -Uri "$BaseUrl/admin/audits?targetType=SOURCE_ENDPOINT&targetId=$($created.endpointId)&limit=20" `
        -WebSession $admin.Session -TimeoutSec 10
    if (@($audits).Count -lt 2) { throw '信源创建、试抓取或启用审计记录不完整' }
    $sourceAudits = Invoke-RestMethod `
        -Uri "$BaseUrl/admin/audits?targetType=SOURCE_ENTITY&targetId=$($created.sourceId)&limit=20" `
        -WebSession $admin.Session -TimeoutSec 10
    if (@($sourceAudits).Count -lt 2) { throw '信源主体创建或激活审计记录不完整' }

    [pscustomobject]@{
        AdminRole = 'ADMIN'
        InvitationRegistration = 'PASSED'
        UserRole = 'USER'
        UserAdminAccess = '403 DENIED'
        SsrfProtection = '422 BLOCKED'
        SourceId = $created.sourceId
        EndpointId = $created.endpointId
        ProbeHealth = $probe.healthStatus
        EndpointStatus = $activated.status
        EndpointAuditCount = @($audits).Count
        SourceAuditCount = @($sourceAudits).Count
    } | Format-List
} finally {
    & docker compose exec -T postgres psql -U ai_hotspot -d ai_hotspot -c `
        "update source.source_endpoint set status='ARCHIVED', next_fetch_at=null, updated_at=now() where catalog_kind='TEST' and status<>'ARCHIVED';" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Warning 'M2 测试信源自动清理失败' }
    Pop-Location
}
