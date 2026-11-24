param([string]$EnvFile = ".env.e2e.example")

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $repoRoot "docker-compose.e2e.yml"
$resolvedEnv = (Resolve-Path (Join-Path $repoRoot $EnvFile)).Path

$services = @("postgres", "minio")
foreach ($service in $services) {
    $containerId = docker compose --env-file $resolvedEnv -f $composeFile ps -q $service
    if (-not $containerId) { throw "$service container is not running." }
    $health = docker inspect --format "{{.State.Health.Status}}" $containerId
    if ($health -ne "healthy") { throw "$service is $health, expected healthy." }
}

$initId = docker compose --env-file $resolvedEnv -f $composeFile ps -a -q minio-init
$initExit = docker inspect --format "{{.State.ExitCode}}" $initId
if ($initExit -ne "0") { throw "minio-init exit code is $initExit, expected 0." }

$minioStatus = (Invoke-WebRequest -UseBasicParsing http://127.0.0.1:9000/minio/health/live).StatusCode
if ($minioStatus -ne 200) { throw "MinIO health endpoint returned $minioStatus." }

docker compose --env-file $resolvedEnv -f $composeFile ps
Write-Host "PostgreSQL, MinIO, and the private fluxio bucket are ready."
