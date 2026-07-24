param(
    [string]$EnvironmentFile = ".env",
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-EnvironmentFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file not found: $Path"
    }
    $values = @{}
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Get-Value([hashtable]$Values, [string]$Key) {
    if ($Values.ContainsKey($Key)) {
        return [string]$Values[$Key]
    }
    return ""
}

function Test-StrongSecret(
    [hashtable]$Values,
    [System.Collections.Generic.List[string]]$Issues,
    [string]$Key,
    [int]$MinimumLength
) {
    $value = Get-Value $Values $Key
    $weak = @(
        "ai_hotspot",
        "ai-hotspot-local-password",
        "change-me-before-use",
        "change-me-in-local-env",
        "local-development-unsubscribe-secret",
        "ai-hotspot-local-email-code-pepper",
        "replace-with-a-long-random-secret-in-production"
    )
    if ($value.Length -lt $MinimumLength -or $weak -contains $value.ToLowerInvariant()) {
        $Issues.Add("$Key must be rotated to a strong value")
    }
}

function Test-HttpsUrl(
    [hashtable]$Values,
    [System.Collections.Generic.List[string]]$Issues,
    [string]$Key
) {
    $uri = $null
    if (-not [Uri]::TryCreate((Get-Value $Values $Key), [UriKind]::Absolute, [ref]$uri) `
            -or $uri.Scheme -ne "https" `
            -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        $Issues.Add("$Key must be an absolute HTTPS URL")
    }
}

function Get-SecurityViolations([hashtable]$Values) {
    $issues = [System.Collections.Generic.List[string]]::new()

    if ((Get-Value $Values "AI_HOTSPOT_ENV").ToLowerInvariant() -notin @("prod", "production")) {
        $issues.Add("AI_HOTSPOT_ENV must be production")
    }
    foreach ($rule in @(
        @("SESSION_COOKIE_SECURE", "true"),
        @("AI_HOTSPOT_SMOKE_ENABLED", "false"),
        @("BOOTSTRAP_ADMIN_ENABLED", "false"),
        @("SMTP_AUTH", "true"),
        @("SMTP_STARTTLS", "true"),
        @("SMTP_STARTTLS_REQUIRED", "true")
    )) {
        if ((Get-Value $Values $rule[0]).ToLowerInvariant() -ne $rule[1]) {
            $issues.Add("$($rule[0]) must be $($rule[1])")
        }
    }
    if (-not [string]::IsNullOrWhiteSpace((Get-Value $Values "BOOTSTRAP_ADMIN_PASSWORD"))) {
        $issues.Add("BOOTSTRAP_ADMIN_PASSWORD must be empty in production")
    }
    if ((Get-Value $Values "MAIL_PROVIDER").ToLowerInvariant() -ne "smtp") {
        $issues.Add("MAIL_PROVIDER must be smtp")
    }

    Test-HttpsUrl $Values $issues "PUBLIC_BASE_URL"
    foreach ($key in @("GENERATION_BASE_URL", "EMBEDDING_BASE_URL", "RERANK_BASE_URL")) {
        Test-HttpsUrl $Values $issues $key
    }

    foreach ($rule in @(
        @("POSTGRES_PASSWORD", 16),
        @("REDIS_PASSWORD", 16),
        @("RABBITMQ_DEFAULT_PASS", 16),
        @("MINIO_ROOT_PASSWORD", 16),
        @("EMAIL_CODE_PEPPER", 32),
        @("UNSUBSCRIBE_SECRET", 32),
        @("SMTP_PASSWORD", 16),
        @("GENERATION_API_KEY", 16),
        @("EMBEDDING_API_KEY", 16),
        @("RERANK_API_KEY", 16)
    )) {
        Test-StrongSecret $Values $issues $rule[0] ([int]$rule[1])
    }

    foreach ($provider in @(
        @("GENERATION_PROVIDER", "openai-compatible"),
        @("EMBEDDING_PROVIDER", "remote"),
        @("RERANK_PROVIDER", "remote")
    )) {
        if ((Get-Value $Values $provider[0]).ToLowerInvariant() -ne $provider[1]) {
            $issues.Add("$($provider[0]) must be $($provider[1])")
        }
    }

    $smtpHost = (Get-Value $Values "SMTP_HOST").ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($smtpHost) `
            -or $smtpHost -in @("localhost", "127.0.0.1", "mailpit")) {
        $issues.Add("SMTP_HOST must reference the production SMTP service")
    }
    if ([string]::IsNullOrWhiteSpace((Get-Value $Values "SMTP_USERNAME"))) {
        $issues.Add("SMTP_USERNAME is required")
    }
    if ((Get-Value $Values "MAIL_FROM") -match '(?i)(@aihotspot\.local|@localhost)') {
        $issues.Add("MAIL_FROM must use the verified production domain")
    }
    if ((Get-Value $Values "MINIO_ROOT_USER") -eq "ai_hotspot") {
        $issues.Add("MINIO_ROOT_USER must be rotated from the local default")
    }

    $passwords = @(@(
        Get-Value $Values "POSTGRES_PASSWORD"
        Get-Value $Values "REDIS_PASSWORD"
        Get-Value $Values "RABBITMQ_DEFAULT_PASS"
        Get-Value $Values "MINIO_ROOT_PASSWORD"
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if (@($passwords | Select-Object -Unique).Count -ne $passwords.Count) {
        $issues.Add("Infrastructure passwords must not be reused")
    }
    return $issues
}

if ($SelfTest) {
    $good = @{
        AI_HOTSPOT_ENV = "production"
        SESSION_COOKIE_SECURE = "true"
        AI_HOTSPOT_SMOKE_ENABLED = "false"
        BOOTSTRAP_ADMIN_ENABLED = "false"
        BOOTSTRAP_ADMIN_PASSWORD = ""
        PUBLIC_BASE_URL = "https://ai.example.com"
        POSTGRES_PASSWORD = "postgres-credential-2026"
        REDIS_PASSWORD = "redis-credential-2026"
        RABBITMQ_DEFAULT_PASS = "rabbit-credential-2026"
        MINIO_ROOT_USER = "production-minio"
        MINIO_ROOT_PASSWORD = "minio-credential-2026"
        EMAIL_CODE_PEPPER = "email-code-pepper-with-32-characters"
        UNSUBSCRIBE_SECRET = "unsubscribe-secret-with-32-characters"
        MAIL_PROVIDER = "smtp"
        MAIL_FROM = "AI Hotspot <noreply@example.com>"
        SMTP_HOST = "smtp.example.com"
        SMTP_USERNAME = "mailer@example.com"
        SMTP_PASSWORD = "smtp-credential-2026"
        SMTP_AUTH = "true"
        SMTP_STARTTLS = "true"
        SMTP_STARTTLS_REQUIRED = "true"
        GENERATION_PROVIDER = "openai-compatible"
        GENERATION_BASE_URL = "https://generation.example.com/v1"
        GENERATION_API_KEY = "generation-credential-2026"
        EMBEDDING_PROVIDER = "remote"
        EMBEDDING_BASE_URL = "https://embedding.example.com/v1"
        EMBEDDING_API_KEY = "embedding-credential-2026"
        RERANK_PROVIDER = "remote"
        RERANK_BASE_URL = "https://rerank.example.com/v1"
        RERANK_API_KEY = "rerank-credential-2026"
    }
    if (@(Get-SecurityViolations $good).Count -ne 0) {
        throw "Production security preflight rejected its hardened self-test fixture"
    }
    $weak = $good.Clone()
    $weak["POSTGRES_PASSWORD"] = "change-me-in-local-env"
    $weak["GENERATION_PROVIDER"] = "mock"
    if (@(Get-SecurityViolations $weak).Count -lt 2) {
        throw "Production security preflight did not reject its weak self-test fixture"
    }
    "SECURITY_PREFLIGHT_SELF_TEST=PASS"
    exit 0
}

$configuration = Read-EnvironmentFile $EnvironmentFile
$violations = @(Get-SecurityViolations $configuration)
if ($violations.Count -gt 0) {
    "SECURITY_PREFLIGHT=FAIL"
    $violations | Sort-Object -Unique | ForEach-Object { " - $_" }
    exit 1
}

"SECURITY_PREFLIGHT=PASS"
