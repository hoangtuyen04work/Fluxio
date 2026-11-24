param([string]$EnvFile = ".env.e2e.example")

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $repoRoot "docker-compose.e2e.yml"
$resolvedEnv = (Resolve-Path (Join-Path $repoRoot $EnvFile)).Path

docker version | Out-Null
docker compose --env-file $resolvedEnv -f $composeFile config --quiet
docker compose --env-file $resolvedEnv -f $composeFile up --build -d --wait

$initId = docker compose --env-file $resolvedEnv -f $composeFile ps -a -q minio-init
if (-not $initId) { throw "MinIO bucket initializer container was not created." }
$initExit = docker inspect --format "{{.State.ExitCode}}" $initId
if ($initExit -ne "0") {
    docker compose --env-file $resolvedEnv -f $composeFile logs minio-init
    throw "MinIO bucket initialization failed with exit code $initExit."
}

Write-Host "Fluxio E2E infrastructure is healthy."
Write-Host "PostgreSQL: 127.0.0.1:5432"
Write-Host "MinIO API: http://127.0.0.1:9000"
Write-Host "MinIO Console: http://127.0.0.1:9001"
Write-Host 'Next: cd fluxio; $env:SPRING_PROFILES_ACTIVE="e2e"; mvn spring-boot:run'
