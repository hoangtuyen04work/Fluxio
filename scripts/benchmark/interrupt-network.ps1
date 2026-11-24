param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$OutageSeconds = 30,
    [int]$BetweenOutagesSeconds = 30,
    [string]$EventLog = "benchmark-network-events.csv"
)

$ErrorActionPreference = "Stop"
if (-not $Serial.StartsWith("emulator-")) {
    throw "Refusing to alter connectivity for non-emulator serial '$Serial'."
}

"timestampUtc,event" | Set-Content -LiteralPath $EventLog
function Add-NetworkEvent([string]$Name) {
    "$(Get-Date -AsUTC -Format o),$Name" | Add-Content -LiteralPath $EventLog
}

try {
    1..2 | ForEach-Object {
        & adb -s $Serial shell svc wifi disable
        & adb -s $Serial shell svc data disable
        Add-NetworkEvent "outage-$($_)-start"
        Start-Sleep -Seconds $OutageSeconds
        & adb -s $Serial shell svc wifi enable
        & adb -s $Serial shell svc data enable
        Add-NetworkEvent "outage-$($_)-end"
        if ($_ -eq 1) { Start-Sleep -Seconds $BetweenOutagesSeconds }
    }
}
finally {
    & adb -s $Serial shell svc wifi enable
    & adb -s $Serial shell svc data enable
    Add-NetworkEvent "connectivity-restored"
}
