[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & pnpm check
    if ($LASTEXITCODE -ne 0) { throw 'Web checks failed' }

    & "$root\services\core\mvnw.cmd" test -f "$root\services\core\pom.xml"
    if ($LASTEXITCODE -ne 0) { throw 'Core API checks failed' }

    $python = "$root\services\ai\.venv\Scripts\python.exe"
    if (-not (Test-Path $python)) {
        throw 'Python virtual environment is missing. Create services/ai/.venv and install .[dev].'
    }
    & $python -m ruff check "$root\services\ai"
    if ($LASTEXITCODE -ne 0) { throw 'AI service lint failed' }
    & $python -m ruff format --check "$root\services\ai"
    if ($LASTEXITCODE -ne 0) { throw 'AI service format check failed' }
    & $python -m pytest "$root\services\ai\tests" -q
    if ($LASTEXITCODE -ne 0) { throw 'AI service checks failed' }

    & docker compose --env-file .env.example config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration check failed' }
} finally {
    Pop-Location
}
