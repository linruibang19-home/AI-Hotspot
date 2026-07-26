param(
    [string]$WebBaseUrl = "http://127.0.0.1:3000/api/core/api/v1",
    [switch]$KeepTestUsers
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$stamp = [guid]::NewGuid().ToString("N").Substring(0, 10)
$emailA = "byok-owner-$stamp@example.test"
$emailB = "byok-other-$stamp@example.test"
$password = "BYOK-security-$stamp!"
$fakeKey = "byok-security-not-a-real-key-$stamp"
$connectionId = $null

function Get-DotEnvValue([string]$name) {
    $line = Get-Content -LiteralPath ".env" |
        Where-Object { $_ -match "^\s*$([regex]::Escape($name))\s*=" } |
        Select-Object -Last 1
    if (-not $line) { return $null }
    return (($line -split "=", 2)[1]).Trim().Trim('"').Trim("'")
}

function New-CsrfSession {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "$WebBaseUrl/auth/csrf" -WebSession $session
    return @{ Session = $session; Headers = @{ "X-XSRF-TOKEN" = $csrf.token } }
}

function Login([string]$email, [string]$secret) {
    $auth = New-CsrfSession
    Invoke-RestMethod -Uri "$WebBaseUrl/auth/login" -Method Post `
        -WebSession $auth.Session -Headers $auth.Headers -ContentType "application/json" `
        -Body (@{ email = $email; password = $secret } | ConvertTo-Json) | Out-Null
    return $auth
}

function Invoke-Json([hashtable]$auth, [string]$method, [string]$path, $body = $null) {
    $arguments = @{
        Uri = "$WebBaseUrl$path"
        Method = $method
        WebSession = $auth.Session
        Headers = $auth.Headers
        ContentType = "application/json"
    }
    if ($null -ne $body) { $arguments.Body = $body | ConvertTo-Json -Depth 10 }
    return Invoke-RestMethod @arguments
}

function Get-ErrorResponse($errorRecord) {
    $response = $errorRecord.Exception.Response
    if (-not $response) { throw $errorRecord }
    $status = [int]$response.StatusCode
    $body = $null
    try {
        $body = $errorRecord.ErrorDetails.Message | ConvertFrom-Json
    } catch {
        $body = @{ code = "UNPARSEABLE_ERROR" }
    }
    return @{ Status = $status; Body = $body }
}

function Assert-HttpFailure(
    [hashtable]$auth,
    [string]$method,
    [string]$path,
    $body,
    [int]$expectedStatus,
    [string]$expectedCode
) {
    try {
        Invoke-Json $auth $method $path $body | Out-Null
        throw "请求本应失败却成功：$method $path"
    } catch {
        if ($_.Exception.Message -like "请求本应失败却成功*") { throw }
        $failure = Get-ErrorResponse $_
        if ($failure.Status -ne $expectedStatus) {
            throw "$method $path 状态码应为 $expectedStatus，实际为 $($failure.Status)"
        }
        if ($expectedCode -and $failure.Body.code -ne $expectedCode) {
            throw "$method $path 错误码应为 $expectedCode，实际为 $($failure.Body.code)"
        }
        return $failure.Body.code
    }
}

function New-ConnectionBody([string]$baseUrl, [string]$key) {
    return @{
        displayName = "BYOK 安全黑盒"
        vendor = "OPENAI_COMPATIBLE"
        apiProtocol = "OPENAI_COMPATIBLE"
        baseUrl = $baseUrl
        apiKey = $key
        taskType = "RAG_GENERATION"
        modelName = "security-test-model"
        timeoutMs = 5000
        platformFallbackEnabled = $false
        dailyQueryLimit = 2
        monthlyTokenLimit = 1000
    }
}

$adminEmail = Get-DotEnvValue "BOOTSTRAP_ADMIN_EMAIL"
$adminPassword = Get-DotEnvValue "BOOTSTRAP_ADMIN_PASSWORD"
if ([string]::IsNullOrWhiteSpace($adminEmail) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
    throw ".env 缺少本地引导管理员凭据"
}

try {
    $admin = Login $adminEmail $adminPassword
    foreach ($account in @(
        @{ email = $emailA; displayName = "BYOK Owner" },
        @{ email = $emailB; displayName = "BYOK Other" }
    )) {
        Invoke-Json $admin Post "/admin/users" @{
            email = $account.email
            displayName = $account.displayName
            password = $password
            role = "USER"
        } | Out-Null
    }

    $owner = Login $emailA $password
    $other = Login $emailB $password
    $created = Invoke-Json $owner Post "/me/ai/connections" (New-ConnectionBody "https://8.8.8.8/v1" $fakeKey)
    $connectionId = [string]$created.id
    if ($created.keyLastFour -ne $fakeKey.Substring($fakeKey.Length - 4)) {
        throw "创建响应未返回正确的掩码后四位"
    }
    $createdJson = $created | ConvertTo-Json -Depth 10 -Compress
    if ($createdJson.Contains($fakeKey) -or $createdJson -match "credential|nonce|fingerprint|apiKey") {
        throw "创建响应泄露了凭据或内部加密字段"
    }

    $otherConnections = @(Invoke-Json $other Get "/me/ai/connections")
    if ($otherConnections.id -contains $connectionId) { throw "其他用户看到了所有者连接" }

    $foreignBody = New-ConnectionBody "https://8.8.8.8/v1" $fakeKey
    $updateCode = Assert-HttpFailure $other Put "/me/ai/connections/$connectionId" $foreignBody 404 "BYOK_CONNECTION_NOT_FOUND"
    $testCode = Assert-HttpFailure $other Post "/me/ai/connections/$connectionId/test" $null 404 "BYOK_CONNECTION_NOT_FOUND"
    $statusCode = Assert-HttpFailure $other Put "/me/ai/connections/$connectionId/status" @{ status = "ACTIVE" } 404 "BYOK_CONNECTION_NOT_FOUND"
    $deleteCode = Assert-HttpFailure $other Delete "/me/ai/connections/$connectionId" $null 404 "BYOK_CONNECTION_NOT_FOUND"

    $fallbackCode = Assert-HttpFailure $owner Post "/research/query" @{
        question = "这是一条不应消耗平台 Token 的安全边界测试"
        filters = @{}
    } 503 "USER_PROVIDER_UNAVAILABLE"

    $blockedEndpointCode = Assert-HttpFailure $owner Post "/me/ai/connections" `
        (New-ConnectionBody "https://api.example.com/v1?api_key=must-not-be-in-url" $fakeKey) `
        422 "BYOK_ENDPOINT_BLOCKED"

    $audits = @(Invoke-Json $admin Get "/admin/audits?targetType=PROVIDER_CONNECTION&targetId=$connectionId&limit=20")
    if ($audits.Count -lt 1) { throw "未记录 BYOK 创建审计" }
    $auditJson = $audits | ConvertTo-Json -Depth 10 -Compress
    if ($auditJson.Contains($fakeKey) -or $auditJson -match "credentialCiphertext|credentialNonce|keyFingerprint|apiKey") {
        throw "审计响应泄露了凭据或内部加密字段"
    }

    Invoke-Json $owner Delete "/me/ai/connections/$connectionId" | Out-Null
    $remaining = @(Invoke-Json $owner Get "/me/ai/connections")
    if ($remaining.id -contains $connectionId) { throw "撤销后连接仍对所有者可见" }

    $connectionCount = (
        "select count(*) from knowledge.provider_connection where id='$connectionId';" |
            docker compose exec -T postgres psql -At -v ON_ERROR_STOP=1 -U ai_hotspot -d ai_hotspot
    ).Trim()
    if ($connectionCount -ne "0") { throw "撤销后数据库仍保留凭据连接" }

    [pscustomobject]@{
        Status = "PASS"
        OwnerIsolation = "$updateCode/$testCode/$statusCode/$deleteCode"
        DisabledRoute = $fallbackCode
        UrlSecretGuard = $blockedEndpointCode
        AuditRedaction = "PASS"
        Revocation = "HARD_DELETE"
        TestUsersRetained = [bool]$KeepTestUsers
    } | Format-List
}
finally {
    if (-not $KeepTestUsers) {
        $sql = @"
begin;
delete from governance.audit_log
 where actor_id in (select id from iam.user_account where email in ('$emailA','$emailB'))
    or target_id in (select id from iam.user_account where email in ('$emailA','$emailB'));
delete from iam.user_role
 where user_id in (select id from iam.user_account where email in ('$emailA','$emailB'));
delete from iam.email_verification_challenge where email in ('$emailA','$emailB');
delete from iam.user_account where email in ('$emailA','$emailB');
commit;
"@
        $sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U ai_hotspot -d ai_hotspot | Out-Null
    }
}
