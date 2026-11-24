param(
    [string]$EnvFile = ".env.e2e.example",
    [switch]$RemoveVolumes
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $repoRoot "docker-compose.e2e.yml"
$resolvedEnv = (Resolve-Path (Join-Path $repoRoot $EnvFile)).Path

$arguments = @("compose", "--env-file", $resolvedEnv, "-f", $composeFile, "down")
if ($RemoveVolumes) { $arguments += "--volumes" }
docker @arguments
