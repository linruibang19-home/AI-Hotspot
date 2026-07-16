[CmdletBinding()]
param(
    [string]$Service
)

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $arguments = @('compose', 'logs', '--follow', '--tail', '200')
    if ($Service) { $arguments += $Service }
    & docker @arguments
} finally {
    Pop-Location
}
