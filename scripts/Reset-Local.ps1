[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($PSCmdlet.ShouldProcess($root, '删除 AI Hotspot 本地 Compose 容器与命名卷')) {
    Push-Location $root
    try {
        & docker compose down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { throw "docker compose reset failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}
