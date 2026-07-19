param(
  [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\artifacts\backups")
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not $output.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "备份目录必须位于项目目录内：$root"
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$postgresContainer = "ai-hotspot-postgres-1"
$minioContainer = "ai-hotspot-minio-1"

docker exec $postgresContainer pg_dump -U ai_hotspot -d ai_hotspot -Fc -f "/tmp/ai-hotspot-$stamp.dump"
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 备份失败" }
docker cp "${postgresContainer}:/tmp/ai-hotspot-$stamp.dump" (Join-Path $output "postgres-$stamp.dump")
docker exec $postgresContainer rm -f "/tmp/ai-hotspot-$stamp.dump"

$minioTemp = [System.IO.Path]::GetFullPath((Join-Path $output "minio-$stamp"))
if (-not $minioTemp.StartsWith($output, [System.StringComparison]::OrdinalIgnoreCase)) { throw "MinIO 临时目录越界" }
New-Item -ItemType Directory -Force -Path $minioTemp | Out-Null
docker cp "${minioContainer}:/data/." $minioTemp
if ($LASTEXITCODE -ne 0) { throw "MinIO 数据导出失败" }
$minioArchive = Join-Path $output "minio-$stamp.tar"
& tar.exe -cf $minioArchive -C $minioTemp .
if ($LASTEXITCODE -ne 0) { throw "MinIO 数据压缩失败" }
Remove-Item -LiteralPath $minioTemp -Recurse -Force

Write-Host "备份完成：$output"
