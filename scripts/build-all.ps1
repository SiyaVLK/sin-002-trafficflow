# Builds every service. Each is an independent Maven project, so this is just
# a loop rather than a parent aggregator - which is what the brief asks for.
#
#   .\scripts\build-all.ps1              build and test
#   .\scripts\build-all.ps1 -SkipTests   faster, for a rebuild during a demo

param([switch]$SkipTests)

$root = Split-Path -Parent $PSScriptRoot
$services = "ingestion-service", "intersection-service", "congestion-service",
            "routing-service", "watchdog-service"

foreach ($service in $services) {
    Write-Host "building $service" -ForegroundColor Cyan
    Push-Location (Join-Path $root $service)
    if ($SkipTests) { mvn -q -B package -DskipTests } else { mvn -q -B package }
    $failed = $LASTEXITCODE -ne 0
    Pop-Location
    if ($failed) { Write-Host "$service failed" -ForegroundColor Red; exit 1 }
}

Write-Host "`nAll five services built." -ForegroundColor Green
Write-Host "Start the broker (see common\README.md), then: .\scripts\run-all.ps1"
