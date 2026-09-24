# Sets the city-wide congestion level.
#
#   .\scripts\set-congestion.ps1 6 "Accident on the M1"
#   .\scripts\set-congestion.ps1 0 "Cleared"

param(
    [Parameter(Mandatory, Position = 0)][ValidateRange(0, 8)][int]$Level,
    [Parameter(Position = 1)][string]$Reason = ""
)

$body = @{ level = $Level; reason = $Reason } | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "http://localhost:7022/congestion" `
        -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 5
} catch {
    Write-Host "Request failed: $($_.Exception.Message)" -ForegroundColor Yellow
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
}
