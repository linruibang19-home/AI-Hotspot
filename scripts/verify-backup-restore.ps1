param(
  [string]$DatabaseDump,
  [string]$MinioArchive
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$backupRoot = [System.IO.Path]::GetFullPath((Join-Path $root "artifacts\backups"))

if (-not $DatabaseDump) {
  $DatabaseDump = (Get-ChildItem -LiteralPath $backupRoot -Filter "postgres-*.dump" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $MinioArchive) {
  $MinioArchive = (Get-ChildItem -LiteralPath $backupRoot -Filter "minio-*.tar" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

$dump = (Resolve-Path $DatabaseDump).Path
$archive = (Resolve-Path $MinioArchive).Path
foreach ($path in @($dump, $archive)) {
  if (-not $path.StartsWith($backupRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "恢复演练只允许使用项目 artifacts/backups 下的文件：$path"
  }
}

$suffix = ([guid]::NewGuid().ToString("N")).Substring(0, 10)
$database = "ai_hotspot_restore_$suffix"
$dumpInContainer = "/tmp/$database.dump"
$minioContainer = "ai-hotspot-minio-restore-$suffix"
$minioVolume = "ai-hotspot-minio-restore-$suffix"
$restoreTemp = [System.IO.Path]::GetFullPath((Join-Path $backupRoot "restore-check-$suffix"))

try {
  docker exec ai-hotspot-postgres-1 createdb -U ai_hotspot $database
  if ($LASTEXITCODE -ne 0) { throw "无法创建隔离恢复数据库" }
  docker cp $dump "ai-hotspot-postgres-1:$dumpInContainer" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "无法复制 PostgreSQL 备份" }
  docker exec ai-hotspot-postgres-1 pg_restore -U ai_hotspot -d $database --no-owner $dumpInContainer
  if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 隔离恢复失败" }
  $tableCount = docker exec ai-hotspot-postgres-1 psql -U ai_hotspot -d $database -Atc "select count(*) from information_schema.tables where table_schema not in ('pg_catalog','information_schema');"
  $contentCount = docker exec ai-hotspot-postgres-1 psql -U ai_hotspot -d $database -Atc "select count(*) from content.content_item;"
  if ([int]$tableCount -lt 40) { throw "恢复后的业务表数量异常：$tableCount" }

  New-Item -ItemType Directory -Force -Path $restoreTemp | Out-Null
  & tar.exe -xf $archive -C $restoreTemp
  if ($LASTEXITCODE -ne 0) { throw "MinIO 归档解压失败" }
  docker volume create $minioVolume | Out-Null
  docker create --name $minioContainer -p "127.0.0.1::9000" `
    -e MINIO_ROOT_USER=restore_check `
    -e MINIO_ROOT_PASSWORD=restore-check-password `
    -v "${minioVolume}:/data" `
    minio/minio:RELEASE.2025-09-07T16-13-09Z-cpuv1 server /data --console-address ":9001" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "无法创建 MinIO 隔离恢复容器" }
  docker cp "${restoreTemp}\." "${minioContainer}:/data/" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "无法回灌 MinIO 归档" }
  docker start $minioContainer | Out-Null

  $healthy = $false
  for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Seconds 1
    $mapping = docker port $minioContainer 9000/tcp 2>$null
    if ($mapping -match ':(\d+)$') {
      try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Matches[1])/minio/health/live" -TimeoutSec 3
        if ($response.StatusCode -eq 200) { $healthy = $true; break }
      } catch { }
    }
  }
  if (-not $healthy) { throw "MinIO 隔离恢复容器健康检查失败" }

  Write-Host "隔离恢复验证通过：业务表 $tableCount 张，内容 $contentCount 条，MinIO 健康。"
} finally {
  docker exec ai-hotspot-postgres-1 rm -f $dumpInContainer 2>$null | Out-Null
  docker exec ai-hotspot-postgres-1 dropdb -U ai_hotspot --if-exists --force $database 2>$null | Out-Null
  docker rm -f $minioContainer 2>$null | Out-Null
  docker volume rm $minioVolume 2>$null | Out-Null
  if (Test-Path -LiteralPath $restoreTemp) {
    if (-not $restoreTemp.StartsWith($backupRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "拒绝清理越界的恢复临时目录"
    }
    Remove-Item -LiteralPath $restoreTemp -Recurse -Force
  }
}
