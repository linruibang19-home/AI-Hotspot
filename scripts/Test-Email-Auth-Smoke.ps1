param(
    [string]$WebBaseUrl = "http://127.0.0.1:3000/api/core/api/v1",
    [string]$MailpitBaseUrl = "http://127.0.0.1:8025",
    [switch]$KeepTestUser
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$email = "auth-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 10))@example.test"

function New-AuthSession {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod -Uri "$WebBaseUrl/auth/csrf" -WebSession $session
    return @{ Session = $session; Headers = @{ "X-XSRF-TOKEN" = $csrf.token } }
}

function Request-Code([hashtable]$auth, [string]$purpose) {
    Invoke-RestMethod -Uri "$WebBaseUrl/auth/email-codes" -Method Post `
        -WebSession $auth.Session -Headers $auth.Headers -ContentType "application/json" `
        -Body (@{ email = $email; purpose = $purpose } | ConvertTo-Json) | Out-Null
    Start-Sleep -Seconds 1
    $message = (Invoke-RestMethod "$MailpitBaseUrl/api/v1/messages").messages |
        Where-Object { (($_.To | ForEach-Object Address) -contains $email) } |
        Select-Object -First 1
    if (-not $message) { throw "Mailpit 中未找到 $email 的验证码邮件" }
    $detail = Invoke-RestMethod "$MailpitBaseUrl/api/v1/message/$($message.ID)"
    $match = [regex]::Match($detail.Text, '(?m)^\s*(\d{6})\s*$')
    if (-not $match.Success) { throw "验证码邮件中没有独立的 6 位验证码" }
    return $match.Groups[1].Value
}

try {
    $registration = New-AuthSession
    $registrationCode = Request-Code $registration "REGISTER"
    $registered = Invoke-RestMethod -Uri "$WebBaseUrl/auth/register" -Method Post `
        -WebSession $registration.Session -Headers $registration.Headers -ContentType "application/json" `
        -Body (@{ email = $email; displayName = "邮箱认证 Smoke"; code = $registrationCode } | ConvertTo-Json)
    if ($registered.roles -notcontains "USER") { throw "注册账号没有 USER 角色" }
    $me = Invoke-RestMethod -Uri "$WebBaseUrl/auth/me" -WebSession $registration.Session
    if ($me.email -ne $email) { throw "注册后的 Session 与账号不一致" }
    Invoke-RestMethod -Uri "$WebBaseUrl/auth/logout" -Method Post `
        -WebSession $registration.Session -Headers $registration.Headers | Out-Null

    $login = New-AuthSession
    $loginCode = Request-Code $login "LOGIN"
    $loggedIn = Invoke-RestMethod -Uri "$WebBaseUrl/auth/code-login" -Method Post `
        -WebSession $login.Session -Headers $login.Headers -ContentType "application/json" `
        -Body (@{ email = $email; code = $loginCode } | ConvertTo-Json)
    if ($loggedIn.email -ne $email) { throw "验证码登录账号不一致" }

    [pscustomobject]@{
        Status = "PASS"
        Email = $email
        RegistrationRole = ($registered.roles -join ",")
        CodeLoginRole = ($loggedIn.roles -join ",")
        MailProvider = "Mailpit"
    } | Format-List
}
finally {
    if (-not $KeepTestUser) {
        $sql = @"
begin;
delete from governance.audit_log where actor_id in (select id from iam.user_account where email = '$email')
   or target_id in (select id from iam.user_account where email = '$email');
delete from iam.user_role where user_id in (select id from iam.user_account where email = '$email');
delete from iam.email_verification_challenge where email = '$email';
delete from iam.user_account where email = '$email';
commit;
"@
        $sql | docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U ai_hotspot -d ai_hotspot | Out-Null
    }
}
