[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$checks = @(
    @{ Name = 'Docker'; Command = { docker version --format '{{.Client.Version}}' } },
    @{ Name = 'Docker Compose'; Command = { docker compose version --short } },
    @{ Name = 'WSL2'; Command = { wsl --status } },
    @{ Name = 'Java 17+'; Command = { java -version } },
    @{ Name = 'Node 22+'; Command = { node --version } },
    @{ Name = 'pnpm'; Command = { pnpm --version } },
    @{ Name = 'Python 3.12+'; Command = { python --version } }
)

foreach ($check in $checks) {
    Write-Host "[CHECK] $($check.Name)"
    & $check.Command
    if ($LASTEXITCODE -ne 0) { throw "$($check.Name) check failed" }
}
