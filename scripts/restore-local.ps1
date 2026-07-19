param(
  [Parameter(Mandatory = $true)][string]$DatabaseDump,
  [string]$MinioArchive,
  [switch]$Force
)

$ErrorActionPreference = "Stop"
if (-not $Force) { throw "恢复会覆盖当前数据；确认后请显式添加 -Force。" }
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$dump = (Resolve-Path $DatabaseDump).Path
if (-not $dump.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "只允许恢复项目目录内的备份文件。"
}
$postgresContainer = "ai-hotspot-postgres-1"
docker cp $dump "${postgresContainer}:/tmp/ai-hotspot-restore.dump"
docker exec $postgresContainer pg_restore -U ai_hotspot -d ai_hotspot --clean --if-exists --no-owner "/tmp/ai-hotspot-restore.dump"
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 恢复失败" }
docker exec $postgresContainer rm -f "/tmp/ai-hotspot-restore.dump"

if ($MinioArchive) {
  $archive = (Resolve-Path $MinioArchive).Path
  if (-not $archive.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) { throw "只允许恢复项目目录内的 MinIO 备份。" }
  $minioContainer = "ai-hotspot-minio-1"
  $restoreTemp = [System.IO.Path]::GetFullPath((Join-Path $root ("artifacts\backups\restore-" + [guid]::NewGuid())))
  $allowedRestoreRoot = [System.IO.Path]::GetFullPath((Join-Path $root "artifacts\backups"))
  if (-not $restoreTemp.StartsWith($allowedRestoreRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw "MinIO 恢复临时目录越界" }
  New-Item -ItemType Directory -Force -Path $restoreTemp | Out-Null
  & tar.exe -xf $archive -C $restoreTemp
  if ($LASTEXITCODE -ne 0) { throw "MinIO 备份解压失败" }
  docker exec $minioContainer sh -c "rm -rf /data/*"
  if ($LASTEXITCODE -ne 0) { throw "MinIO 旧数据清理失败" }
  docker cp "${restoreTemp}\." "${minioContainer}:/data/"
  if ($LASTEXITCODE -ne 0) { throw "MinIO 数据回灌失败" }
  Remove-Item -LiteralPath $restoreTemp -Recurse -Force
  docker restart $minioContainer | Out-Null
}
Write-Host "恢复完成；请执行 scripts/verify-stack.ps1 验证。"
