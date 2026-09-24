# Starts each service in its own window, so any one of them can be closed to
# see how the rest of the system reacts.
#
#   .\scripts\run-all.ps1

$root = Split-Path -Parent $PSScriptRoot

function Start-Component($title, $jar) {
    Start-Process powershell -ArgumentList @(
        "-NoExit", "-Command",
        "`$Host.UI.RawUI.WindowTitle = '$title'; java -jar '$root\$jar'"
    )
}

Start-Component "ingestion-service :7020"    "ingestion-service\target\ingestion-service.jar"
Start-Sleep -Seconds 2
Start-Component "intersection-service :7021" "intersection-service\target\intersection-service.jar"
Start-Component "congestion-service :7022"   "congestion-service\target\congestion-service.jar"
Start-Component "routing-service :7023"      "routing-service\target\routing-service.jar"
Start-Component "watchdog-service :7024"     "watchdog-service\target\watchdog-service.jar"

Write-Host ""
Write-Host "Started. Give it ~10 seconds, then try:" -ForegroundColor Green
Write-Host '  irm http://localhost:7020/cleaning-report'
Write-Host '  irm "http://localhost:7023/route?from=INT-009&to=INT-010"'
Write-Host '  .\scripts\set-congestion.ps1 6 "Accident on the M1"'
Write-Host '  irm http://localhost:7024/status'
