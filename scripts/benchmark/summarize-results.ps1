param(
    [Parameter(Mandatory = $true)][string]$InputCsv,
    [string]$OutputMarkdown = "benchmark-summary.md"
)

$rows = Import-Csv -LiteralPath $InputCsv
if (-not $rows) { throw "No benchmark rows found." }

$lines = @(
    "# Fluxio benchmark summary",
    "",
    "> Emulator-only evidence. Battery and physical-device behavior were not measured.",
    "",
    "| Mode | Profile | Runs | Completion | Median Mbps | Peak heap MiB |",
    "|---|---|---:|---:|---:|---:|"
)
$rows | Group-Object mode, profile | ForEach-Object {
    $group = $_.Group
    $throughputs = @($group | ForEach-Object { [double]$_.throughputMbps } | Sort-Object)
    $median = $throughputs[[math]::Floor(($throughputs.Count - 1) / 2)]
    $completed = @($group | Where-Object completed -eq "true").Count
    $peakMiB = (($group | ForEach-Object { [double]$_.peakHeapBytes } | Measure-Object -Maximum).Maximum / 1MB)
    $lines += "| $($group[0].mode) | $($group[0].profile) | $($group.Count) | $completed/$($group.Count) | $([math]::Round($median, 2)) | $([math]::Round($peakMiB, 2)) |"
}
$lines | Set-Content -LiteralPath $OutputMarkdown
